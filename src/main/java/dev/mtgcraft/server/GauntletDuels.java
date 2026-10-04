package dev.mtgcraft.server;

import dev.mtgcraft.MtgConfig;
import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.engine.DeckChoice;
import dev.mtgcraft.engine.ForgeEngine;
import dev.mtgcraft.engine.MatchSetup;
import dev.mtgcraft.engine.Matches;
import dev.mtgcraft.item.DeckBoxItem;
import dev.mtgcraft.item.GauntletItem;
import dev.mtgcraft.net.Net;
import dev.mtgcraft.net.Packets;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Duel Gauntlet challenges. The challenged mob plus nearby mobs sit down at an invisible table: mobs of the same kind
 * team up and every team fights every other team. Friends nearby holding a Duel Gauntlet (with a ready deck) join
 * the challenger's team. Duels are casual Commander by default; a boss fights alone as the Archenemy.
 * Everyone in the duel is frozen and can't be hurt until it ends. Win: the mobs fall and drop cards. Lose: the
 * configured penalty (death by default).
 */
@Mod.EventBusSubscriber(modid = MtgCraft.MODID)
public final class GauntletDuels {
    private static final int MAX_SEATS = MatchSetup.MAX_SEATS;
    private static final double FRIEND_RANGE = 8;

    private static final class Duel {
        ServerLevel level;
        Vec3 center;
        float radius;
        boolean boss;
        /** Human players in seat order, with their seat index. */
        final Map<UUID, Integer> players = new LinkedHashMap<>();
        final Set<UUID> forfeited = new HashSet<>();
        /** One mob per mob seat. A jockey (a mob riding another) sits as one seat: this is its bottom mount. */
        final List<Mob> mobs = new ArrayList<>();
        /** Every mob at the table: the seat mobs plus whoever rides them. */
        final List<Mob> everyone = new ArrayList<>();
        final Map<UUID, Boolean> hadNoAi = new HashMap<>();
        MatchSetup setup;
        DuelHosting.Handle live;
        boolean over;
        long allForfeitedAt;

        /** One player forfeits (once): walked away, logged out or died. */
        void forfeit(UUID player) {
            Integer seat = players.get(player);
            if (seat == null || !forfeited.add(player) || live == null) return;
            live.concede(seat);
            if (forfeited.size() == players.size()) allForfeitedAt = System.currentTimeMillis();
        }

        int playerTeam() {
            return setup.effectiveTeam(players.values().iterator().next());
        }
    }

    private static final Map<UUID, Duel> BY_PLAYER = new HashMap<>();
    private static final Map<UUID, Duel> BY_MOB = new HashMap<>();
    /** Mobs that lost a duel; their death drops duel loot (see DuelLoot). */
    static final Set<UUID> DEFEATED = new HashSet<>();
    private static final List<Runnable> LATER = new ArrayList<>();
    private static long tick;

    private GauntletDuels() {}

    public static boolean inDuel(UUID entity) {
        return BY_PLAYER.containsKey(entity) || BY_MOB.containsKey(entity);
    }

    /** The player's ready deck, or null (after telling them why). */
    private static DeckChoice deckOf(ServerPlayer p, boolean quiet) {
        if (!TableSessions.isReady(p.getUUID())) {
            if (!quiet) tell(p, "Your cards are still loading...");
            return null;
        }
        ItemStack box = DeckBoxItem.find(p);
        if (box.isEmpty()) {
            if (!quiet) tell(p, "You need a Deck Box with at least " + DeckBoxItem.MIN_CARDS + " cards, or a filled Universal Deck Box.");
            return null;
        }
        try {
            return DeckBoxItem.choiceOf(box, p.getGameProfile().getName() + "'s deck");
        } catch (Exception e) {
            if (!quiet) tell(p, "Couldn't read your deck: " + e.getMessage());
            return null;
        }
    }

    private static boolean holdsGauntlet(ServerPlayer p) {
        for (ItemStack s : p.getInventory().items) if (s.getItem() instanceof GauntletItem) return true;
        return p.getOffhandItem().getItem() instanceof GauntletItem;
    }

    public static void challenge(ServerPlayer player, Mob target) {
        if (BY_PLAYER.containsKey(player.getUUID())) {
            tell(player, "You're already in a duel. Right-click the air with the gauntlet to return to it.");
            return;
        }
        if (BY_MOB.containsKey(target.getUUID())) {
            tell(player, "That mob is already in a duel.");
            return;
        }
        if (ForgeEngine.state() != ForgeEngine.State.READY) {
            tell(player, "The card engine is still loading on the server...");
            return;
        }
        DeckChoice myDeck = deckOf(player, false);
        if (myDeck == null) return;

        Duel d = new Duel();
        d.level = player.serverLevel();
        d.boss = MobThemes.isBoss(target);
        d.radius = d.boss ? 10 : 5;

        // Friends nearby with a gauntlet and a ready deck join the challenger's side.
        Map<ServerPlayer, DeckChoice> humans = new LinkedHashMap<>();
        humans.put(player, myDeck);
        for (ServerPlayer other : d.level.getEntitiesOfClass(ServerPlayer.class, player.getBoundingBox().inflate(FRIEND_RANGE))) {
            if (humans.size() >= MAX_SEATS - 1) break;
            if (other == player || inDuel(other.getUUID()) || !holdsGauntlet(other)) continue;
            DeckChoice deck = deckOf(other, true);
            if (deck != null) humans.put(other, deck);
        }

        // A jockey (skeleton on a spider, zombie on a chicken...) is one opponent, not two.
        Mob seat = mount(target);
        d.mobs.add(seat);
        if (!d.boss) {
            // Mobs of the same kind join first, then other hostile mobs nearby.
            String kind = teamKey(seat);
            Set<Mob> seen = new HashSet<>(group(seat));
            List<Mob> near = new ArrayList<>();
            for (Mob m : d.level.getEntitiesOfClass(Mob.class, seat.getBoundingBox().inflate(10), Mob::isAlive)) {
                Mob root = mount(m);
                if (!seen.add(root) || !root.isAlive() || MobThemes.isBoss(root)) continue;
                boolean free = true;
                for (Mob g : group(root)) {
                    seen.add(g);
                    if (inDuel(g.getUUID()) || MobThemes.isBoss(g)) free = false;
                }
                if (free && (teamKey(root).equals(kind) || isHostile(root))) near.add(root);
            }
            near.sort(Comparator.<Mob>comparingInt(m -> teamKey(m).equals(kind) ? 0 : 1)
                    .thenComparingDouble(m -> m.distanceToSqr(seat)));
            for (Mob m : near) {
                if (humans.size() + d.mobs.size() >= MAX_SEATS) break;
                d.mobs.add(m);
            }
        }
        for (Mob m : d.mobs) d.everyone.addAll(group(m));
        d.center = player.position().add(seat.position()).scale(0.5);

        // Commander needs everyone at the table to bring a real 100-card deck with a commander;
        // otherwise this duel is played with classic rules.
        boolean commander = MtgConfig.DUEL_MODE.get() == MtgConfig.DuelMode.COMMANDER;
        if (commander && !d.boss) {
            for (DeckChoice c : humans.values()) {
                if (!commanderReady(c)) {
                    commander = false;
                    for (ServerPlayer p : humans.keySet()) tell(p, "Classic duel: Commander needs a 100-card deck with a commander for everyone.");
                    break;
                }
            }
        }
        MatchSetup setup = new MatchSetup();
        setup.mode = d.boss ? MatchSetup.Mode.ARCHENEMY : commander ? MatchSetup.Mode.COMMANDER : MatchSetup.Mode.TEAMS;
        List<Entity> seatEntities = new ArrayList<>();
        // The Archenemy sits in seat 1 (index 0); otherwise the players come first.
        if (d.boss) addMobSeats(d, setup, seatEntities, false);
        for (Map.Entry<ServerPlayer, DeckChoice> h : humans.entrySet()) {
            ServerPlayer p = h.getKey();
            d.players.put(p.getUUID(), setup.seats.size());
            setup.seats.add(new MatchSetup.Seat(false, p.getGameProfile().getName(), p.getUUID(), h.getValue(), 1));
            seatEntities.add(p);
        }
        if (!d.boss) addMobSeats(d, setup, seatEntities, commander);
        d.setup = setup;

        // Freeze everyone at the table.
        for (Mob m : d.everyone) {
            d.hadNoAi.put(m.getUUID(), m.isNoAi());
            m.setNoAi(true);
            m.setTarget(null);
            m.getNavigation().stop();
            m.setPersistenceRequired();
            m.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition());
            BY_MOB.put(m.getUUID(), d);
        }
        for (UUID id : d.players.keySet()) BY_PLAYER.put(id, d);

        MinecraftServer server = player.getServer();
        BlockPos key = BlockPos.containing(d.center);
        d.live = DuelHosting.launch(server, setup, key, true,
                err -> {
                    tell(player, "The duel couldn't start: " + err);
                    end(d, null);
                },
                result -> end(d, result));
        if (d.live == null) return;

        // Tell each player's client where the arena is and who sits where, for the 3D battlefield.
        List<Packets.ArenaSeat> seats = new ArrayList<>();
        for (int i = 0; i < setup.seats.size(); i++) {
            seats.add(new Packets.ArenaSeat(setup.seats.get(i).name, seatEntities.get(i).getId()));
        }
        Packets.Arena arena = new Packets.Arena(key, d.center.x, d.center.y, d.center.z, d.radius, d.boss, seats);
        for (ServerPlayer p : humans.keySet()) Net.toPlayer(p, arena);

        StringBuilder who = new StringBuilder();
        for (MatchSetup.Seat s : setup.seats) {
            if (!s.ai) continue;
            if (who.length() > 0) who.append(", ");
            who.append(s.name);
        }
        String vs = (humans.size() > 1 ? "Your team" : "You") + " vs " + who
                + (d.boss ? " (Archenemy)" : commander ? " (Commander)" : "");
        for (ServerPlayer p : humans.keySet()) {
            p.displayClientMessage(Component.literal("Duel! " + vs).withStyle(ChatFormatting.GOLD), true);
        }
    }

    private static boolean commanderReady(DeckChoice c) {
        if (c == null) return false;
        if (c.kind() == DeckChoice.Kind.CMD_PRECON) return true;
        if (c.kind() != DeckChoice.Kind.INLINE) return false;
        try {
            return dev.mtgcraft.engine.Decks.isCommanderReady(dev.mtgcraft.engine.Decks.fromText(c.name()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Seats for the mobs: same kind = same team; themed decks (with a commander in Commander duels). */
    private static void addMobSeats(Duel d, MatchSetup setup, List<Entity> seatEntities, boolean commander) {
        Map<String, Integer> teams = new LinkedHashMap<>();
        Map<String, Integer> names = new HashMap<>();
        for (Mob m : d.mobs) {
            int team = teams.computeIfAbsent(teamKey(m), k -> teams.size() + 2);
            Mob face = rider(m);
            String name = face == m ? m.getDisplayName().getString() : m.getDisplayName().getString() + " Jockey";
            int dup = names.merge(name, 1, Integer::sum);
            if (dup > 1) name += " " + dup;
            setup.seats.add(new MatchSetup.Seat(true, name, null,
                    DeckChoice.theme(MobThemes.theme(face), MobThemes.packs(face), commander), team));
            seatEntities.add(m);
        }
    }

    /** The bottom mount a mob is riding (itself if it isn't riding a mob). */
    private static Mob mount(Mob m) {
        Mob at = m;
        while (at.getVehicle() instanceof Mob v) at = v;
        return at;
    }

    /** A seat mob and everyone riding it. */
    private static List<Mob> group(Mob root) {
        List<Mob> out = new ArrayList<>();
        out.add(root);
        for (Entity e : root.getIndirectPassengers()) if (e instanceof Mob m) out.add(m);
        return out;
    }

    /** The mob that gives a jockey its deck theme: the hostile rider on top (the skeleton, not the spider). */
    private static Mob rider(Mob root) {
        List<Mob> g = group(root);
        for (int i = g.size() - 1; i > 0; i--) if (g.get(i) instanceof Enemy) return g.get(i);
        return g.size() > 1 ? g.get(g.size() - 1) : root;
    }

    /** Mobs with the same key team up: same kind, and for jockeys the same mount and rider. */
    private static String teamKey(Mob root) {
        StringBuilder key = new StringBuilder();
        for (Mob m : group(root)) key.append(EntityType.getKey(m.getType())).append('+');
        return key.toString();
    }

    private static boolean isHostile(Mob root) {
        for (Mob m : group(root)) if (m instanceof Enemy) return true;
        return false;
    }

    /** Applies the stakes and unfreezes everyone. {@code result} null means the duel never really happened. */
    private static void end(Duel d, Matches.Result result) {
        if (d.over) return;
        d.over = true;
        for (UUID id : d.players.keySet()) BY_PLAYER.remove(id);
        for (Mob m : d.everyone) {
            BY_MOB.remove(m.getUUID());
            if (m.isAlive()) m.setNoAi(d.hadNoAi.getOrDefault(m.getUUID(), false));
        }
        DuelHosting.Handle live = d.live;
        LATER.add(() -> { if (live != null) live.close(); });
        if (result == null) return;

        List<ServerPlayer> online = new ArrayList<>();
        for (UUID id : d.players.keySet()) {
            ServerPlayer p = DuelHosting.online(d.level.getServer(), id);
            if (p != null) online.add(p);
        }
        if (result.draw()) {
            for (ServerPlayer p : online) tell(p, "The duel ended in a draw. The mobs wander off.");
            return;
        }
        if (result.winningTeam() == d.playerTeam()) {
            ServerPlayer credit = online.isEmpty() ? null : online.get(0);
            for (ServerPlayer p : online) {
                p.displayClientMessage(Component.literal("Victory!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), true);
            }
            // Every mob at the table falls. A jockey drops one set of loot (from its rider).
            for (Mob seat : d.mobs) {
                DEFEATED.add(rider(seat).getUUID());
            }
            for (Mob m : d.everyone) {
                if (!m.isAlive()) continue;
                m.hurt(credit != null ? credit.damageSources().playerAttack(credit) : m.damageSources().magic(), Float.MAX_VALUE);
                if (m.isAlive()) m.kill();
            }
            return;
        }
        Mob victor = d.mobs.isEmpty() ? null : d.mobs.get(0);
        for (ServerPlayer p : online) {
            switch (MtgConfig.LOSS_PENALTY.get()) {
                case DEATH -> {
                    tell(p, "You lost the duel...");
                    p.hurt(victor != null ? p.damageSources().mobAttack(victor) : p.damageSources().magic(), Float.MAX_VALUE);
                }
                case DAMAGE -> {
                    tell(p, "You lost the duel and take a beating.");
                    p.hurt(victor != null ? p.damageSources().mobAttack(victor) : p.damageSources().magic(), 10);
                }
                case NONE -> tell(p, "You lost the duel.");
            }
        }
    }

    private static void tell(ServerPlayer p, String msg) {
        p.displayClientMessage(Component.literal(msg), false);
    }

    // ------------------------------------------------------------------ events

    /** Nobody at the table can be hurt while the duel runs. */
    @SubscribeEvent
    public static void noHarm(LivingAttackEvent event) {
        if (inDuel(event.getEntity().getUUID())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        tick++;
        if (tick % 100 == 0 && !LATER.isEmpty()) {
            List<Runnable> run = new ArrayList<>(LATER);
            LATER.clear();
            run.forEach(Runnable::run);
        }
        if (tick % 4 != 0) return;
        Set<Duel> duels = new HashSet<>(BY_PLAYER.values());
        for (Duel d : duels) {
            if (d.allForfeitedAt > 0 && System.currentTimeMillis() - d.allForfeitedAt > 60_000) {
                // Watchdog: the game never reported its end (it crashed or hung). Call it a draw (no penalty)
                // and let everyone go, so mobs can't stay frozen.
                end(d, new Matches.Result(true, -1, ""));
                continue;
            }
            ServerPlayer looker = null;
            for (UUID id : d.players.keySet()) {
                if (d.forfeited.contains(id)) continue;
                ServerPlayer p = DuelHosting.online(d.level.getServer(), id);
                if (p == null || !p.isAlive() || p.level() != d.level || p.position().distanceTo(d.center) > d.radius * 4) {
                    // Walked away, died or left: that's a forfeit.
                    d.forfeit(id);
                } else if (looker == null) {
                    looker = p;
                }
            }
            if (d.over) continue;
            runeCircle(d);
            if (looker != null) {
                for (Mob m : d.everyone) if (m.isAlive()) m.lookAt(EntityAnchorArgument.Anchor.EYES, looker.getEyePosition());
            }
        }
    }

    /** A slowly turning ring of light on the ground marks the duel. */
    private static void runeCircle(Duel d) {
        double spin = tick / 40.0;
        int points = d.boss ? 40 : 24;
        double y = Math.floor(d.center.y) + 0.1;
        for (int i = 0; i < points; i++) {
            double a = spin + i * Math.PI * 2 / points;
            double x = d.center.x + Math.cos(a) * d.radius, z = d.center.z + Math.sin(a) * d.radius;
            d.level.sendParticles(i % 6 == 0 ? ParticleTypes.END_ROD : ParticleTypes.ENCHANT, x, y, z, 1, 0, 0.05, 0, 0.01);
        }
        if (tick % 20 == 0) {
            d.level.sendParticles(ParticleTypes.ENCHANT, d.center.x, y + 1.5, d.center.z, 30, d.radius / 2, 0.6, d.radius / 2, 0.4);
        }
    }

    @SubscribeEvent
    public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        Duel d = BY_PLAYER.get(event.getEntity().getUUID());
        if (d != null) d.forfeit(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void died(LivingDeathEvent event) {
        Duel d = BY_PLAYER.get(event.getEntity().getUUID());
        if (d != null) d.forfeit(event.getEntity().getUUID());
    }
}

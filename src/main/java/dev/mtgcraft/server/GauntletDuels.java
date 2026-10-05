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
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
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
import net.minecraftforge.event.RegisterCommandsEvent;
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
    /** Friends must stay this close to the challenger to be pulled into the duel (and onto the stage). */
    private static final double JOIN_RANGE = 16;
    /** How long nearby friends get to click [Join] before a mob duel starts. */
    private static final int JOIN_TICKS = 10 * 20;
    /** How long a player-vs-player challenge stays open. */
    private static final int INVITE_TICKS = 60 * 20;

    private static final class Duel {
        ServerLevel level;
        Vec3 center;
        float radius;
        boolean boss;
        /** A friendly player-vs-player duel: nobody dies, the winner is announced. */
        boolean pvp;
        /** Human players in seat order, with their seat index. */
        final Map<UUID, Integer> players = new LinkedHashMap<>();
        final Set<UUID> forfeited = new HashSet<>();
        /** One mob per mob seat. A jockey (a mob riding another) sits as one seat: this is its bottom mount. */
        final List<Mob> mobs = new ArrayList<>();
        /** Every mob at the table: the seat mobs plus whoever rides them. */
        final List<Mob> everyone = new ArrayList<>();
        final Map<UUID, Boolean> hadNoAi = new HashMap<>();
        /** Mobs' gravity, and players' flight abilities, from before they were put on the floating stage. */
        final Map<UUID, Boolean> hadNoGravity = new HashMap<>();
        final Map<UUID, boolean[]> hadFlight = new HashMap<>();
        /** Each duelist's podium spot (players and mobs); they're held there for the whole duel. */
        final Map<UUID, Vec3> spots = new HashMap<>();
        /** The challengers' team name, when friends fight together. */
        String teamName = "";
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

    /** A mob challenge waiting a few seconds for nearby friends to join. Keyed by the challenger. */
    private static final class Lobby {
        UUID host;
        Mob target;
        boolean targetHadNoAi;
        long deadline;
        final Set<UUID> invited = new HashSet<>();
        final List<UUID> joined = new ArrayList<>();
        String teamName;
    }

    private static final String[] TEAM_A = {"Emerald", "Obsidian", "Crimson", "Golden", "Azure", "Ender", "Nether",
            "Iron", "Mossy", "Glowing", "Diamond", "Copper", "Amethyst", "Redstone", "Frost"};
    private static final String[] TEAM_B = {"Wolves", "Wizards", "Dragons", "Phoenixes", "Golems", "Planeswalkers",
            "Guardians", "Foxes", "Axolotls", "Blazes", "Llamas", "Mages", "Knights", "Ravens", "Bees"};

    private static String teamName(UUID host) {
        java.util.Random r = new java.util.Random(host.getMostSignificantBits() ^ tick);
        return "Team " + TEAM_A[r.nextInt(TEAM_A.length)] + " " + TEAM_B[r.nextInt(TEAM_B.length)];
    }

    /** Players just out of a duel, with the tick their safe time ends: mobs leave them alone until then. */
    private static final Map<UUID, Long> GRACE = new HashMap<>();

    private static final Map<UUID, Lobby> LOBBIES = new LinkedHashMap<>();
    /** Player duel challenges: challenged player -> (challenger -> expiry tick). */
    private static final Map<UUID, Map<UUID, Long>> INVITES = new HashMap<>();
    private static long tick;

    private GauntletDuels() {}

    public static boolean inDuel(UUID entity) {
        return BY_PLAYER.containsKey(entity) || BY_MOB.containsKey(entity);
    }

    /** Hosting or waiting in a mob-duel lobby. */
    private static boolean waiting(UUID id) {
        for (Lobby l : LOBBIES.values()) {
            if (l.host.equals(id) || l.joined.contains(id) || l.target.getUUID().equals(id)) return true;
        }
        return false;
    }

    private static MutableComponent button(String label, String command, ChatFormatting color, String hover) {
        return Component.literal(label).withStyle(s -> s.withColor(color).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
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
        if (waiting(player.getUUID())) {
            tell(player, "You're waiting for a duel to start.");
            return;
        }
        if (BY_MOB.containsKey(target.getUUID()) || waiting(mount(target).getUUID())) {
            tell(player, "That mob is already in a duel.");
            return;
        }
        if (ForgeEngine.state() != ForgeEngine.State.READY) {
            tell(player, "The card engine is still loading on the server...");
            return;
        }
        if (deckOf(player, false) == null) return;

        // Friends close by with a gauntlet and a ready deck get a few seconds to click [Join].
        List<ServerPlayer> nearby = new ArrayList<>();
        for (ServerPlayer other : player.serverLevel().getEntitiesOfClass(ServerPlayer.class, player.getBoundingBox().inflate(FRIEND_RANGE))) {
            if (other == player || inDuel(other.getUUID()) || waiting(other.getUUID()) || !holdsGauntlet(other)) continue;
            if (deckOf(other, true) != null) nearby.add(other);
        }
        if (nearby.isEmpty()) {
            startMobDuel(player, target, List.of());
            return;
        }
        Lobby l = new Lobby();
        l.host = player.getUUID();
        l.teamName = teamName(player.getUUID());
        l.target = mount(target);
        l.targetHadNoAi = l.target.isNoAi();
        l.target.setNoAi(true);
        l.target.setTarget(null);
        l.deadline = tick + JOIN_TICKS;
        for (ServerPlayer o : nearby) l.invited.add(o.getUUID());
        LOBBIES.put(l.host, l);
        String host = player.getGameProfile().getName();
        for (ServerPlayer o : nearby) {
            o.sendSystemMessage(Component.literal(host + " is dueling " + target.getDisplayName().getString() + "! ")
                    .withStyle(ChatFormatting.GOLD)
                    .append(button("[Join]", "/mtgduel join " + host, ChatFormatting.GREEN, "Fight on " + host + "'s side")));
            Net.toPlayer(o, new Packets.Prompt("Join the duel?",
                    host + " is dueling " + target.getDisplayName().getString() + ". Join " + l.teamName + " and fight on their side!",
                    List.of("Join", "No thanks"), List.of("/mtgduel join " + host, ""), JOIN_TICKS / 20));
        }
        player.displayClientMessage(Component.literal("Friends nearby have 10 seconds to join " + l.teamName + "...").withStyle(ChatFormatting.GOLD), true);
    }

    /** A friend joins a mob duel that's about to start (from [Join] or right-clicking the challenger). */
    public static void join(ServerPlayer friend, ServerPlayer host) {
        Lobby l = LOBBIES.get(host.getUUID());
        if (l == null) {
            if (BY_PLAYER.containsKey(host.getUUID())) tell(friend, "That duel already started. Be next to them when it starts to join.");
            else tell(friend, host.getGameProfile().getName() + " isn't starting a duel.");
            return;
        }
        if (l.joined.contains(friend.getUUID())) return;
        if (inDuel(friend.getUUID()) || waiting(friend.getUUID())) {
            tell(friend, "You're already in a duel.");
            return;
        }
        if (friend.level() != host.level() || friend.distanceTo(host) > JOIN_RANGE) {
            tell(friend, "Get closer to " + host.getGameProfile().getName() + " to join.");
            return;
        }
        if (deckOf(friend, false) == null) return;
        if (l.joined.size() + 2 >= MAX_SEATS) {
            tell(friend, "That duel is full.");
            return;
        }
        l.joined.add(friend.getUUID());
        l.invited.remove(friend.getUUID());
        String fname = friend.getGameProfile().getName();
        friend.displayClientMessage(Component.literal("You joined " + l.teamName + " with " + host.getGameProfile().getName() + ". Stay close!")
                .withStyle(ChatFormatting.GREEN), false);
        host.displayClientMessage(Component.literal(fname + " joined " + l.teamName + "!").withStyle(ChatFormatting.GREEN), false);
        for (UUID other : l.joined) {
            ServerPlayer o = DuelHosting.online(host.getServer(), other);
            if (o != null && o != friend) o.displayClientMessage(Component.literal(fname + " joined " + l.teamName + "!").withStyle(ChatFormatting.GREEN), false);
        }
        if (l.invited.isEmpty()) l.deadline = tick;
    }

    private static void startLobby(Lobby l, MinecraftServer server) {
        if (l.target.isAlive()) l.target.setNoAi(l.targetHadNoAi);
        ServerPlayer host = DuelHosting.online(server, l.host);
        if (host == null || !host.isAlive()) return;
        if (!l.target.isAlive() || host.level() != l.target.level()) {
            tell(host, "Your opponent is gone.");
            return;
        }
        // Only friends who are still nearby are pulled in.
        List<ServerPlayer> friends = new ArrayList<>();
        for (UUID id : l.joined) {
            ServerPlayer f = DuelHosting.online(server, id);
            if (f == null || !f.isAlive() || f.level() != host.level() || inDuel(id)) continue;
            if (f.distanceTo(host) > JOIN_RANGE) {
                tell(f, "You were too far away, so the duel started without you.");
                continue;
            }
            friends.add(f);
        }
        startMobDuel(host, l.target, friends, l.teamName);
    }

    private static void startMobDuel(ServerPlayer player, Mob target, List<ServerPlayer> friends) {
        startMobDuel(player, target, friends, teamName(player.getUUID()));
    }

    private static void startMobDuel(ServerPlayer player, Mob target, List<ServerPlayer> friends, String teamName) {
        if (BY_PLAYER.containsKey(player.getUUID()) || BY_MOB.containsKey(target.getUUID()) || !target.isAlive()) return;
        DeckChoice myDeck = deckOf(player, false);
        if (myDeck == null) return;

        Duel d = new Duel();
        d.level = player.serverLevel();
        d.boss = MobThemes.isBoss(target) || MobThemes.isBoss(mount(target));
        d.radius = d.boss ? 7.5f : 5f;
        d.teamName = teamName;

        // Just the one mob, unless the challenger's gauntlet is set to group fights (shift + right-click in the air).
        boolean group = GauntletItem.groupFights(player.getMainHandItem()) || GauntletItem.groupFights(player.getOffhandItem());
        // Friends who joined fight on the challenger's side.
        Map<ServerPlayer, DeckChoice> humans = new LinkedHashMap<>();
        humans.put(player, myDeck);
        for (ServerPlayer f : friends) {
            if (humans.size() >= MAX_SEATS - 1) break;
            DeckChoice deck = deckOf(f, true);
            if (deck != null) humans.put(f, deck);
        }

        // A jockey (skeleton on a spider, zombie on a chicken...) is one opponent, not two.
        Mob seat = mount(target);
        d.mobs.add(seat);
        if (!d.boss && group) {
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
        // Room for everyone: big mobs and more duelists make a bigger circle.
        double widest = player.getBbWidth();
        for (Mob m : d.mobs) for (Mob g : group(m)) widest = Math.max(widest, g.getBbWidth());
        int duelists = humans.size() + d.mobs.size();
        d.radius += (float) (Math.max(0, widest - 1) * 1.3 + (duelists > 2 ? 1.5 : 0));
        Vec3 mid = d.boss && target instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon
                ? player.position().add(seat.position().subtract(player.position()).normalize().scale(d.radius)) // the dragon comes to you
                : player.position().add(seat.position()).scale(0.5);
        d.center = new Vec3(mid.x, stageHeight(d.level, mid, d.radius + 1.5f), mid.z);
        // Underground (or in a forest), shrink the arena to the open space so nobody ends up inside a wall.
        d.radius = fitRadius(d.level, d.center, d.radius);

        // How tough the opponents are: critters and common mobs play a quick classic duel at low life.
        MobThemes.Tier tier = MobThemes.Tier.CRITTER;
        for (Mob m : d.mobs) {
            MobThemes.Tier t = MobThemes.tier(rider(m));
            if (t.ordinal() > tier.ordinal()) tier = t;
        }
        boolean quick = !d.boss && MtgConfig.QUICK_DUELS.get() && MobThemes.quickLife(tier) > 0;

        // Commander needs everyone at the table to bring a real 100-card deck with a commander;
        // otherwise this duel is played with classic rules.
        boolean commander = MtgConfig.DUEL_MODE.get() == MtgConfig.DuelMode.COMMANDER || d.boss;
        if (commander && !quick) {
            for (DeckChoice c : humans.values()) {
                if (!commanderReady(c)) {
                    commander = false;
                    if (!d.boss) for (ServerPlayer p : humans.keySet()) tell(p, "Classic duel: Commander needs a 100-card deck with a commander for everyone.");
                    break;
                }
            }
        }
        if (quick) commander = false;
        MatchSetup setup = new MatchSetup();
        // Bosses fight as the Archenemy; with commander decks all round, Archenemy Commander.
        setup.mode = d.boss ? (commander ? MatchSetup.Mode.ARCHENEMY_COMMANDER : MatchSetup.Mode.ARCHENEMY)
                : commander ? MatchSetup.Mode.COMMANDER : MatchSetup.Mode.TEAMS;
        List<Entity> seatEntities = new ArrayList<>();
        // The Archenemy sits in seat 1 (index 0); otherwise the players come first.
        if (d.boss) addMobSeats(d, setup, seatEntities, commander);
        for (Map.Entry<ServerPlayer, DeckChoice> h : humans.entrySet()) {
            ServerPlayer p = h.getKey();
            d.players.put(p.getUUID(), setup.seats.size());
            setup.seats.add(new MatchSetup.Seat(false, p.getGameProfile().getName(), p.getUUID(), h.getValue(), 1));
            seatEntities.add(p);
        }
        if (!d.boss) addMobSeats(d, setup, seatEntities, commander);
        if (quick) {
            int life = MobThemes.quickLife(tier);
            for (MatchSetup.Seat st : setup.seats) if (st.ai) st.startingLife = life;
        }
        d.setup = setup;
        takeSeats(d, player, seatEntities);
        stopTargeting(d.level, d.center, d.radius + 24, d.players.keySet());

        // Freeze everyone at the table.
        for (Mob m : d.everyone) {
            d.hadNoAi.put(m.getUUID(), m.isNoAi());
            m.setNoAi(true);
            m.setTarget(null);
            m.getNavigation().stop();
            m.setPersistenceRequired();
            m.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition());
            if (m instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon) {
                // The dragon ignores NoAI; make it hover on its spot instead.
                dragon.getPhaseManager().setPhase(net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.HOVERING);
            }
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
        String kind = d.boss ? (commander ? "Archenemy Commander" : "Archenemy") : quick ? "Quick duel" : commander ? "Commander" : "Duel";
        StringBuilder names = new StringBuilder();
        for (ServerPlayer p : humans.keySet()) names.append(names.length() > 0 ? ", " : "").append(p.getGameProfile().getName());
        String vs = (humans.size() > 1 ? d.teamName + " (" + names + ")" : "You") + " vs " + who + " · " + kind;
        for (ServerPlayer p : humans.keySet()) {
            p.displayClientMessage(Component.literal(vs).withStyle(ChatFormatting.GOLD), false);
            title(p, humans.size() > 1 ? d.teamName : "Duel!", (humans.size() > 1 ? names + " vs " : "vs ") + who);
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

    /**
     * Puts every duelist on a spot around the arena circle, facing the centre, like duelists squaring off. The
     * challenger's direction sets where the circle starts; the rest spread evenly. A spot that isn't clear
     * (a wall, a tree) is skipped and that duelist stays where they are.
     */
    private static void takeSeats(Duel d, ServerPlayer challenger, List<Entity> seats) {
        Vec3 c = d.center;
        double a0 = Math.atan2(challenger.getZ() - c.z, challenger.getX() - c.x);
        int challengerIndex = Math.max(0, seats.indexOf(challenger));
        float ring = d.radius * 0.9f;
        for (int i = 0; i < seats.size(); i++) {
            Entity e = seats.get(i);
            double a = a0 + (i - challengerIndex) * Math.PI * 2 / seats.size();
            double x = c.x + Math.cos(a) * ring, z = c.z + Math.sin(a) * ring;
            if (!clear(d.level, x, c.y, z, e.getBbHeight())) {
                // No room there (a cave wall, a tree): stay put and duel from where you stand.
                x = e.getX();
                z = e.getZ();
            }
            float yaw = (float) (Math.toDegrees(Math.atan2(c.z - z, c.x - x)) - 90);
            if (e instanceof ServerPlayer p) {
                // Hover on the stage: flight for the duel, given back afterwards.
                d.hadFlight.put(p.getUUID(), new boolean[]{p.getAbilities().mayfly, p.getAbilities().flying});
                p.getAbilities().mayfly = true;
                p.getAbilities().flying = true;
                p.onUpdateAbilities();
                // Looking down at the field.
                p.teleportTo(d.level, x, c.y, z, yaw, 32);
                d.spots.put(p.getUUID(), new Vec3(x, c.y, z));
            } else {
                d.hadNoGravity.put(e.getUUID(), e.isNoGravity());
                e.setNoGravity(true);
                for (Entity rider : e.getIndirectPassengers()) {
                    d.hadNoGravity.put(rider.getUUID(), rider.isNoGravity());
                    rider.setNoGravity(true);
                }
                e.moveTo(x, c.y, z, yaw, 0);
                e.setYHeadRot(yaw);
                e.setYBodyRot(yaw);
                d.spots.put(e.getUUID(), new Vec3(x, c.y, z));
            }
        }
    }

    /** Whether an entity this tall fits at this spot (nothing solid at its feet or head). */
    private static boolean clear(ServerLevel level, double x, double y, double z, float height) {
        BlockPos feet = BlockPos.containing(x, y + 0.05, z);
        for (int dy = 0; dy < Math.max(2, (int) Math.ceil(height)); dy++) {
            BlockPos p = feet.above(dy);
            if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) return false;
        }
        return true;
    }

    /**
     * The biggest arena (up to {@code wanted}) whose floor is open: in a cave or a cramped room the arena shrinks
     * to the space there is, down to a 3-block circle.
     */
    private static float fitRadius(ServerLevel level, Vec3 c, float wanted) {
        for (float r = wanted; r > 3; r -= 0.5f) {
            int ok = 0, total = 0;
            for (float ring : new float[]{r * 0.9f, r * 0.55f}) {
                int pts = ring > 4 ? 24 : 12;
                for (int i = 0; i < pts; i++) {
                    double a = i * Math.PI * 2 / pts;
                    total++;
                    if (clear(level, c.x + Math.cos(a) * ring, c.y, c.z + Math.sin(a) * ring, 2)) ok++;
                }
            }
            if (ok >= total * 0.85) return r;
        }
        return 3f;
    }

    /** Big text in the middle of a player's screen. */
    private static void title(ServerPlayer p, String title, String subtitle) {
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(5, 50, 15));
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(Component.literal(subtitle).withStyle(ChatFormatting.GRAY)));
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal(title).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
    }

    /** Mobs around the duel stop hunting the duelists (they're on the stage, and then on their safe time). */
    private static void stopTargeting(ServerLevel level, Vec3 center, double range, Set<UUID> players) {
        for (Mob m : level.getEntitiesOfClass(Mob.class, new net.minecraft.world.phys.AABB(center, center).inflate(range))) {
            if (m.getTarget() != null && players.contains(m.getTarget().getUUID())) {
                m.setTarget(null);
                m.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET);
                m.getNavigation().stop();
            }
        }
    }

    private static boolean safe(UUID id) {
        if (BY_PLAYER.containsKey(id)) return true;
        Long until = GRACE.get(id);
        return until != null && until > tick;
    }

    /**
     * The duel stage floats just above the highest block inside the circle, so trees, roofs and hills never cut
     * through the field and every duelist can stand on their spot.
     */
    private static double stageHeight(ServerLevel level, Vec3 mid, float radius) {
        int top = (int) Math.floor(mid.y) - 1;
        for (int dx = (int) -radius; dx <= radius; dx++) {
            for (int dz = (int) -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                int x = (int) Math.floor(mid.x) + dx, z = (int) Math.floor(mid.z) + dz;
                int h = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
                // Ignore the sky limit in caves/the nether: only count blocks near the duel.
                if (h <= mid.y + 12) top = Math.max(top, h);
            }
        }
        return Math.max(mid.y, top + 1.2);
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
            Boolean grav = d.hadNoGravity.get(m.getUUID());
            if (grav != null && m.isAlive()) m.setNoGravity(grav);
        }
        for (Map.Entry<UUID, boolean[]> f : d.hadFlight.entrySet()) {
            ServerPlayer p = DuelHosting.online(d.level.getServer(), f.getKey());
            if (p == null) continue;
            p.getAbilities().mayfly = f.getValue()[0] || p.isCreative() || p.isSpectator();
            p.getAbilities().flying = f.getValue()[1] && p.getAbilities().mayfly;
            p.onUpdateAbilities();
            // Drift down off the stage instead of taking fall damage.
            p.fallDistance = 0;
            p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.SLOW_FALLING, 200, 0, false, false));
        }
        for (Mob m : d.everyone) {
            BY_MOB.remove(m.getUUID());
            if (m.isAlive()) m.setNoAi(d.hadNoAi.getOrDefault(m.getUUID(), false));
        }
        DuelHosting.Handle live = d.live;
        LATER.add(() -> { if (live != null) live.close(); });
        // Safe time: mobs nearby leave everyone alone for a bit after the duel.
        long graceEnd = tick + MtgConfig.GRACE_SECONDS.get() * 20L;
        for (UUID id : d.players.keySet()) GRACE.put(id, graceEnd);
        stopTargeting(d.level, d.center, d.radius + 24, d.players.keySet());
        for (Mob m : d.everyone) {
            if (m instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon && dragon.isAlive()) {
                dragon.getPhaseManager().setPhase(net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.HOLDING_PATTERN);
            }
        }
        if (result == null) return;

        List<ServerPlayer> online = new ArrayList<>();
        for (UUID id : d.players.keySet()) {
            ServerPlayer p = DuelHosting.online(d.level.getServer(), id);
            if (p != null) online.add(p);
        }
        if (result.draw()) {
            for (ServerPlayer p : online) tell(p, d.pvp ? "The duel ended in a draw." : "The duel ended in a draw. The mobs wander off.");
            return;
        }
        if (d.pvp) {
            // A friendly duel: no stakes, just bragging rights.
            String winner = result.winnerName() == null || result.winnerName().isEmpty() ? "Someone" : result.winnerName();
            for (ServerPlayer p : online) {
                p.sendSystemMessage(Component.literal(winner + " wins the duel!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            }
            return;
        }
        if (result.winningTeam() == d.playerTeam()) {
            ServerPlayer credit = online.isEmpty() ? null : online.get(0);
            for (ServerPlayer p : online) {
                p.displayClientMessage(Component.literal("Victory!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), true);
            }
            // Everyone who fought gets their own rewards, straight into their inventory, and sees what they got.
            for (ServerPlayer p : online) {
                if (d.forfeited.contains(p.getUUID())) continue;
                List<ItemStack> loot = DuelLoot.duelRewards(d.mobs.stream().map(GauntletDuels::rider).toList(), d.boss);
                for (ItemStack it : loot) {
                    ItemStack give = it.copy();
                    if (!p.getInventory().add(give)) p.drop(give, false);
                }
                String sub = d.players.size() > 1 ? d.teamName + " wins!" : "You beat " + String.join(", ", seatNames(d)) + ".";
                Net.toPlayer(p, new Packets.Rewards("Victory!", sub, loot));
            }
            // Every mob at the table falls. Rewards were handed out above, so their deaths drop no extra packs.
            for (Mob m : d.everyone) DEFEATED.add(m.getUUID());
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

    private static List<String> seatNames(Duel d) {
        List<String> out = new ArrayList<>();
        for (MatchSetup.Seat st : d.setup.seats) if (st.ai) out.add(st.name);
        return out;
    }

    private static void tell(ServerPlayer p, String msg) {
        p.displayClientMessage(Component.literal(msg), false);
    }

    // ------------------------------------------------------------------ player vs player

    /**
     * Right-clicking a player with the gauntlet: join their mob duel if they're starting one, otherwise challenge
     * them to a friendly duel. They get clickable [Accept] / [Decline] in chat.
     */
    public static void invitePlayer(ServerPlayer from, ServerPlayer to) {
        if (LOBBIES.containsKey(to.getUUID())) {
            join(from, to);
            return;
        }
        String toName = to.getGameProfile().getName(), fromName = from.getGameProfile().getName();
        if (inDuel(from.getUUID()) || waiting(from.getUUID())) {
            tell(from, "You're already in a duel.");
            return;
        }
        if (inDuel(to.getUUID()) || waiting(to.getUUID())) {
            tell(from, toName + " is busy with another duel.");
            return;
        }
        if (ForgeEngine.state() != ForgeEngine.State.READY) {
            tell(from, "The card engine is still loading on the server...");
            return;
        }
        if (deckOf(from, false) == null) return;
        INVITES.computeIfAbsent(to.getUUID(), k -> new HashMap<>()).put(from.getUUID(), tick + INVITE_TICKS);
        to.sendSystemMessage(Component.literal(fromName + " challenges you to a friendly duel! ").withStyle(ChatFormatting.GOLD)
                .append(button("[Accept]", "/mtgduel accept " + fromName, ChatFormatting.GREEN, "Duel " + fromName + " (nobody dies)"))
                .append(Component.literal(" "))
                .append(button("[Decline]", "/mtgduel decline " + fromName, ChatFormatting.RED, "No thanks")));
        Net.toPlayer(to, new Packets.Prompt("Duel challenge!", fromName + " challenges you to a friendly duel. Nobody dies; the winner gets bragging rights.",
                List.of("Accept", "Decline"), List.of("/mtgduel accept " + fromName, "/mtgduel decline " + fromName), INVITE_TICKS / 20));
        tell(from, "Challenge sent to " + toName + ". They have 60 seconds to accept.");
    }

    private static boolean takeInvite(ServerPlayer to, ServerPlayer from) {
        Map<UUID, Long> mine = INVITES.get(to.getUUID());
        Long exp = mine == null ? null : mine.remove(from.getUUID());
        return exp != null && exp >= tick;
    }

    public static void accept(ServerPlayer to, ServerPlayer from) {
        String fromName = from.getGameProfile().getName();
        if (!takeInvite(to, from)) {
            tell(to, "That challenge has expired. Right-click " + fromName + " with your gauntlet to challenge them.");
            return;
        }
        if (inDuel(from.getUUID()) || waiting(from.getUUID()) || inDuel(to.getUUID()) || waiting(to.getUUID())) {
            tell(to, "One of you is already in a duel.");
            return;
        }
        // Only nearby players get pulled onto the stage.
        if (from.level() != to.level() || from.distanceTo(to) > JOIN_RANGE) {
            INVITES.computeIfAbsent(to.getUUID(), k -> new HashMap<>()).put(from.getUUID(), tick + INVITE_TICKS);
            tell(to, "Get closer to " + fromName + " (within " + (int) JOIN_RANGE + " blocks), then accept again.");
            return;
        }
        DeckChoice mine = deckOf(to, false);
        if (mine == null) return;
        DeckChoice theirs = deckOf(from, true);
        if (theirs == null) {
            tell(to, fromName + " doesn't have a ready deck right now.");
            tell(from, "You need a ready Deck Box to duel " + to.getGameProfile().getName() + ".");
            return;
        }
        startPvp(from, theirs, to, mine);
    }

    public static void decline(ServerPlayer to, ServerPlayer from) {
        if (takeInvite(to, from)) tell(from, to.getGameProfile().getName() + " declined your duel.");
        tell(to, "Declined.");
    }

    private static void startPvp(ServerPlayer a, DeckChoice da, ServerPlayer b, DeckChoice db) {
        Duel d = new Duel();
        d.pvp = true;
        d.level = a.serverLevel();
        d.radius = 5f + (float) Math.max(0, Math.max(a.getBbWidth(), b.getBbWidth()) - 1) * 1.3f;
        Vec3 mid = a.position().add(b.position()).scale(0.5);
        d.center = new Vec3(mid.x, stageHeight(d.level, mid, d.radius + 1.5f), mid.z);

        boolean commander = MtgConfig.DUEL_MODE.get() == MtgConfig.DuelMode.COMMANDER;
        if (commander && !(commanderReady(da) && commanderReady(db))) {
            commander = false;
            for (ServerPlayer p : List.of(a, b)) tell(p, "Classic duel: Commander needs a 100-card deck with a commander for both of you.");
        }
        MatchSetup setup = new MatchSetup();
        setup.mode = commander ? MatchSetup.Mode.COMMANDER : MatchSetup.Mode.TEAMS;
        List<Entity> seatEntities = new ArrayList<>();
        int team = 1;
        for (ServerPlayer p : List.of(a, b)) {
            d.players.put(p.getUUID(), setup.seats.size());
            setup.seats.add(new MatchSetup.Seat(false, p.getGameProfile().getName(), p.getUUID(), p == a ? da : db, team++));
            seatEntities.add(p);
        }
        d.setup = setup;
        takeSeats(d, a, seatEntities);
        for (UUID id : d.players.keySet()) BY_PLAYER.put(id, d);

        BlockPos key = BlockPos.containing(d.center);
        d.live = DuelHosting.launch(a.getServer(), setup, key, true,
                err -> {
                    for (ServerPlayer p : List.of(a, b)) tell(p, "The duel couldn't start: " + err);
                    end(d, null);
                },
                result -> end(d, result));
        if (d.live == null) return;
        List<Packets.ArenaSeat> seats = new ArrayList<>();
        for (int i = 0; i < setup.seats.size(); i++) {
            seats.add(new Packets.ArenaSeat(setup.seats.get(i).name, seatEntities.get(i).getId()));
        }
        Packets.Arena arena = new Packets.Arena(key, d.center.x, d.center.y, d.center.z, d.radius, false, seats);
        for (ServerPlayer p : List.of(a, b)) {
            Net.toPlayer(p, arena);
            p.displayClientMessage(Component.literal("Duel! " + a.getGameProfile().getName() + " vs " + b.getGameProfile().getName()
                    + (commander ? " (Commander)" : "")).withStyle(ChatFormatting.GOLD), true);
        }
    }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mtgduel")
                .then(Commands.literal("accept").then(Commands.argument("player", StringArgumentType.word())
                        .executes(c -> withPlayer(c.getSource(), StringArgumentType.getString(c, "player"), GauntletDuels::accept))))
                .then(Commands.literal("decline").then(Commands.argument("player", StringArgumentType.word())
                        .executes(c -> withPlayer(c.getSource(), StringArgumentType.getString(c, "player"), GauntletDuels::decline))))
                .then(Commands.literal("join").then(Commands.argument("player", StringArgumentType.word())
                        .executes(c -> withPlayer(c.getSource(), StringArgumentType.getString(c, "player"), GauntletDuels::join)))));
    }

    private static int withPlayer(net.minecraft.commands.CommandSourceStack src, String name,
                                  java.util.function.BiConsumer<ServerPlayer, ServerPlayer> action) {
        ServerPlayer me = src.getPlayer();
        if (me == null) return 0;
        ServerPlayer other = src.getServer().getPlayerList().getPlayerByName(name);
        if (other == null || other == me) {
            tell(me, name + " isn't online.");
            return 0;
        }
        action.accept(me, other);
        return 1;
    }

    // ------------------------------------------------------------------ events

    /** Nobody at the table can be hurt while the duel runs; afterwards, mobs can't hurt the duelists for a while. */
    @SubscribeEvent
    public static void noHarm(LivingAttackEvent event) {
        UUID id = event.getEntity().getUUID();
        if (inDuel(id)) {
            event.setCanceled(true);
        } else if (safe(id) && !(event.getSource().getEntity() instanceof net.minecraft.world.entity.player.Player)
                && event.getSource().getEntity() != null) {
            event.setCanceled(true);
        }
    }

    /** Mobs don't pick duelists (or players on their safe time) as targets. */
    @SubscribeEvent
    public static void noTargeting(net.minecraftforge.event.entity.living.LivingChangeTargetEvent event) {
        if (event.getNewTarget() instanceof ServerPlayer p && safe(p.getUUID()) && event.getEntity() instanceof Mob) {
            event.setCanceled(true);
        }
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
        if (!LOBBIES.isEmpty()) {
            List<Lobby> ready = new ArrayList<>();
            for (Lobby l : LOBBIES.values()) if (tick >= l.deadline) ready.add(l);
            for (Lobby l : ready) {
                LOBBIES.remove(l.host);
                startLobby(l, event.getServer());
            }
        }
        if (tick % 200 == 0) {
            INVITES.values().forEach(m -> m.values().removeIf(exp -> exp < tick));
            INVITES.values().removeIf(Map::isEmpty);
            GRACE.values().removeIf(until -> until < tick);
        }
        if (tick % 2 != 0) return;
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
                Vec3 spot = d.spots.get(id);
                if (p != null && p.isAlive() && p.level() == d.level && spot != null && p.position().distanceToSqr(spot) > 0.36) {
                    // No flying off: back onto the podium (looking where they were looking).
                    p.teleportTo(d.level, spot.x, spot.y, spot.z, p.getYRot(), p.getXRot());
                    p.setDeltaMovement(Vec3.ZERO);
                }
                if (p == null || !p.isAlive() || p.level() != d.level || p.position().distanceTo(d.center) > d.radius * 4) {
                    // Walked away, died or left: that's a forfeit.
                    d.forfeit(id);
                } else if (looker == null) {
                    looker = p;
                }
            }
            if (d.over) continue;
            // Mobs (the dragon especially) stay on their spots.
            for (Mob m : d.mobs) {
                Vec3 spot = d.spots.get(m.getUUID());
                if (spot != null && m.isAlive() && m.position().distanceToSqr(spot) > 0.5) {
                    m.moveTo(spot.x, spot.y, spot.z, m.getYRot(), m.getXRot());
                    m.setDeltaMovement(Vec3.ZERO);
                }
            }
            if (tick % 20 == 0) stopTargeting(d.level, d.center, d.radius + 24, d.players.keySet());
            runeCircle(d);
            if (looker != null) {
                for (Mob m : d.everyone) if (m.isAlive()) m.lookAt(EntityAnchorArgument.Anchor.EYES, looker.getEyePosition());
            }
            // Undead don't burn in the sun while they're sitting at the table.
            for (Mob m : d.everyone) {
                if (m.isAlive() && m.isOnFire()) m.clearFire();
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

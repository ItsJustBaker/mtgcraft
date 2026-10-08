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
    private static final boolean DEV = Boolean.getBoolean("mtgcraft.devDuel");
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
        /** Player duels: what they play for (FRIENDLY, DEATH, ANTE, REWARD). */
        String stake = "FRIENDLY";
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
        /** Which way each seated mob faces (yaw), held for the whole duel. */
        final Map<UUID, Float> facing = new HashMap<>();
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

        /** A player conceded from their duel screen: the game already has it, just keep count for the watchdog. */
        void conceded(UUID player) {
            if (!players.containsKey(player) || !forfeited.add(player)) return;
            if (forfeited.size() == players.size()) allForfeitedAt = System.currentTimeMillis();
        }

        /** Hears in-screen concessions from each player's seat. */
        void watchConcessions(MinecraftServer server) {
            if (live == null) return;
            for (Map.Entry<UUID, Integer> e : players.entrySet()) {
                dev.mtgcraft.engine.net.RemoteSeat seat = live.seat(e.getValue());
                UUID id = e.getKey();
                if (seat != null) seat.onConcede = () -> server.execute(() -> conceded(id));
            }
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

    /** Mobs being killed at the end of a won duel, and the list their drops are caught into. */
    private static final Map<UUID, List<ItemStack>> CATCH = new HashMap<>();
    /** Mobs whose XP is handed out by the duel instead of dropped as orbs (the dragon, while it's being killed). */
    private static final Set<UUID> NO_XP = new HashSet<>();

    /** Catches the normal drops of mobs that lost a duel, after every mod has added its loot. */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void catchDrops(net.minecraftforge.event.entity.living.LivingDropsEvent event) {
        List<ItemStack> into = CATCH.get(event.getEntity().getUUID());
        if (into == null) return;
        for (net.minecraft.world.entity.item.ItemEntity drop : event.getDrops()) {
            if (!drop.getItem().isEmpty()) into.add(drop.getItem().copy());
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void catchXp(net.minecraftforge.event.entity.living.LivingExperienceDropEvent event) {
        if (NO_XP.contains(event.getEntity().getUUID())) event.setDroppedExperience(0);
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

    /** Players who just picked their deck from the menu: the next challenge goes ahead without asking again. */
    private static final Set<UUID> DECK_PICKED = new HashSet<>();

    /**
     * Carrying two or more decks (and none in the off hand, which is always a deliberate pick): ask which one to
     * fight with. The last one picked is listed first. Returns true when the menu was sent.
     */
    private static boolean askDeck(ServerPlayer p, Mob target) {
        if (DeckBoxItem.usableBox(p.getOffhandItem())) return false;
        List<Integer> slots = new ArrayList<>();
        var items = p.getInventory().items;
        for (int i = 0; i < items.size(); i++) if (DeckBoxItem.usableBox(items.get(i))) slots.add(i);
        if (slots.size() < 2) return false;
        slots.sort(java.util.Comparator.comparing(i -> !DeckBoxItem.isActive(items.get(i))));
        List<String> labels = new ArrayList<>(), cmds = new ArrayList<>();
        for (int slot : slots.subList(0, Math.min(6, slots.size()))) {
            ItemStack box = items.get(slot);
            String cmd = box.getItem() instanceof DeckBoxItem ? DeckBoxItem.commander(box) : null;
            labels.add(box.getHoverName().getString() + (cmd != null ? " - " + dev.mtgcraft.engine.Cards.name(cmd) : ""));
            cmds.add("/mtgduel deck " + slot + " " + target.getId());
        }
        labels.add("Cancel");
        cmds.add("");
        Net.toPlayer(p, new Packets.Prompt("Which deck?",
                "Pick the deck to fight " + target.getDisplayName().getString() + " with.", labels, cmds, 30));
        return true;
    }

    /** From the deck menu: use the deck in this inventory slot and challenge the mob. */
    private static int pickDeck(ServerPlayer p, int slot, int mobId) {
        var items = p.getInventory().items;
        if (slot < 0 || slot >= items.size() || !DeckBoxItem.usableBox(items.get(slot))) {
            tell(p, "That deck isn't in your inventory any more.");
            return 0;
        }
        if (!(p.level().getEntity(mobId) instanceof Mob mob) || !mob.isAlive() || mob.distanceTo(p) > GauntletItem.RANGE + 8) {
            tell(p, "That mob is gone.");
            return 0;
        }
        DeckBoxItem.setActive(p, items.get(slot));
        DECK_PICKED.add(p.getUUID());
        challenge(p, mob);
        DECK_PICKED.remove(p.getUUID());
        return 1;
    }

    /** When each player last pressed Fix (for the second press that ends the duel). */
    private static final Map<UUID, Long> UNSTICK_AT = new HashMap<>();

    /**
     * The duel screen's Fix button: answer anything the game is stuck waiting on from this player with "no answer"
     * and resend the whole game state. A second press within 20 seconds ends the duel as a draw (no penalty).
     */
    private static int unstick(ServerPlayer p) {
        Duel d = BY_PLAYER.get(p.getUUID());
        if (d == null || d.live == null) {
            tell(p, "You're not in a duel.");
            return 0;
        }
        long now = System.currentTimeMillis();
        Long last = UNSTICK_AT.put(p.getUUID(), now);
        if (last != null && now - last < 20_000) {
            UNSTICK_AT.remove(p.getUUID());
            for (UUID id : d.players.keySet()) {
                ServerPlayer o = DuelHosting.online(p.getServer(), id);
                if (o != null) tell(o, p.getGameProfile().getName() + " ended the stuck duel. It's a draw.");
            }
            end(d, new Matches.Result(true, -1, ""));
            return 1;
        }
        Integer seatIdx = d.players.get(p.getUUID());
        dev.mtgcraft.engine.net.RemoteSeat seat = seatIdx == null ? null : d.live.seat(seatIdx);
        if (seat != null) {
            ForgeEngine.runOnUi(() -> {
                seat.cancelPending();
                try {
                    seat.gui.sendFullState();
                } catch (RuntimeException ignored) {
                    // the game is shutting down
                }
            });
        }
        return 1;
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
        if (!DECK_PICKED.remove(player.getUUID()) && askDeck(player, target)) return;

        // Friends close by with a gauntlet and a ready deck get a few seconds to click [Join].
        List<ServerPlayer> nearby = new ArrayList<>();
        for (ServerPlayer other : player.serverLevel().getEntitiesOfClass(ServerPlayer.class, player.getBoundingBox().inflate(FRIEND_RANGE))) {
            if (other == player || inDuel(other.getUUID()) || waiting(other.getUUID()) || !holdsGauntlet(other)) continue;
            if (deckOf(other, true) != null) nearby.add(other);
        }
        List<ServerPlayer> holo = Parties.holoCandidates(player, nearby);
        if (nearby.isEmpty() && holo.isEmpty()) {
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
        for (ServerPlayer o : holo) {
            l.invited.add(o.getUUID());
            Parties.offerHolo(o, player, target);
        }
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
        // A boss anywhere in the ride stack (a boss rider on its mount) makes it a boss duel, whichever was clicked.
        d.boss = group(mount(target)).stream().anyMatch(MobThemes::isBoss);
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
        // Mobs draw from a library as big as the biggest one a player brings (classic rules; Commander is 100 each).
        int library = 0;
        if (!commander) for (DeckChoice c : humans.values()) library = Math.max(library, librarySize(c));
        // Bosses scale with how many players take them on, so one player can win alone.
        int bossPacks = humans.size() > 1 ? 0 : MtgConfig.BOSS_SOLO_PACKS.get();
        if (d.boss) addMobSeats(d, setup, seatEntities, commander, library, bossPacks);
        for (Map.Entry<ServerPlayer, DeckChoice> h : humans.entrySet()) {
            ServerPlayer p = h.getKey();
            d.players.put(p.getUUID(), setup.seats.size());
            setup.seats.add(new MatchSetup.Seat(false, p.getGameProfile().getName(), p.getUUID(), h.getValue(), 1));
            seatEntities.add(p);
        }
        // A Duel Familiar (one per duel) joins the players' side with the deck it learned.
        for (ServerPlayer p : humans.keySet()) {
            ItemStack orb = dev.mtgcraft.item.FamiliarOrbItem.find(p);
            DeckChoice fd = orb.isEmpty() ? null : dev.mtgcraft.item.FamiliarOrbItem.deck(orb);
            if (fd == null || (commander && fd.kind() != DeckChoice.Kind.CMD_PRECON && !commanderReady(fd))) continue;
            setup.seats.add(new MatchSetup.Seat(true, p.getGameProfile().getName() + "'s Familiar", null, fd, 1));
            seatEntities.add(p);
            break;
        }
        if (!d.boss) addMobSeats(d, setup, seatEntities, commander, library, 0);
        if (d.boss) {
            int life = MtgConfig.BOSS_LIFE.get() + MtgConfig.BOSS_LIFE_PER_EXTRA_PLAYER.get() * (humans.size() - 1);
            for (MatchSetup.Seat st : setup.seats) if (st.ai && !st.name.endsWith("'s Familiar")) st.startingLife = life;
        }
        if (quick) {
            int life = MobThemes.quickLife(tier);
            for (MatchSetup.Seat st : setup.seats) if (st.ai && !st.name.endsWith("'s Familiar")) st.startingLife = life;
        }
        // Difficulty: -2 halves the mobs' life, +2 gives them half again as much (their decks change in addMobSeats).
        int diff = MtgConfig.DIFFICULTY.get();
        if (diff != 0) {
            for (MatchSetup.Seat st : setup.seats) {
                if (!st.ai || st.name.endsWith("'s Familiar")) continue;
                int base = st.startingLife > 0 ? st.startingLife : commander ? 40 : 20;
                st.startingLife = Math.max(1, Math.round(base * (1 + 0.25f * diff)));
            }
        }
        d.setup = setup;
        takeSeats(d, player, seatEntities);
        stopTargeting(d.level, d.center, d.radius + 24, d.players.keySet());

        // Freeze everyone at the table.
        for (Mob m : d.everyone) {
            d.hadNoAi.put(m.getUUID(), m.isNoAi());
            m.setTarget(null);
            m.getNavigation().stop();
            m.setPersistenceRequired();
            if (m instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon) {
                // Not NoAI for the dragon: with NoAI the game stops updating its body and tail, so after the move to
                // its seat they'd be drawn where it used to be (twisted, or off somewhere out of sight). It hovers on
                // its spot instead, and the duel tick keeps it there, facing the field.
                dragon.setNoAi(false);
                dragon.getPhaseManager().setPhase(net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.HOVERING);
            } else {
                m.setNoAi(true);
                m.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition());
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
        d.watchConcessions(server);

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
        if (c.kind() == DeckChoice.Kind.CMD_PRECON || c.kind() == DeckChoice.Kind.TRIBE) return true;
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
                // Flyers hover above the floor instead of sinking into it: the dragon well above, others a little.
                double lift = hoverHeight(e);
                d.hadNoGravity.put(e.getUUID(), e.isNoGravity());
                e.setNoGravity(true);
                for (Entity rider : e.getIndirectPassengers()) {
                    d.hadNoGravity.put(rider.getUUID(), rider.isNoGravity());
                    rider.setNoGravity(true);
                }
                // The Ender Dragon's head is on the opposite side to other mobs' faces (its yaw points backwards).
                float face = e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon ? yaw + 180 : yaw;
                e.moveTo(x, c.y + lift, z, face, 0);
                e.setXRot(0);
                e.setYHeadRot(face);
                e.setYBodyRot(face);
                d.spots.put(e.getUUID(), new Vec3(x, c.y + lift, z));
                d.facing.put(e.getUUID(), face);
            }
        }
    }

    /** How high above the stage floor a mob floats: flyers hover, the Ender Dragon (whose body hangs low) most. */
    private static double hoverHeight(Entity e) {
        if (e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon) return 5;
        if (e instanceof net.minecraft.world.entity.boss.wither.WitherBoss) return 1.5;
        if (e instanceof net.minecraft.world.entity.FlyingMob || e instanceof net.minecraft.world.entity.animal.FlyingAnimal
                || e instanceof net.minecraft.world.entity.ambient.AmbientCreature || e.isNoGravity()) {
            return 1;
        }
        return 0;
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
        // Under the open sky the stage rises over hills and trees up to 24 blocks above the duelists (only very tall
        // things, like the End's obsidian towers, poke through). In caves or the Nether the "height" is the roof,
        // so only blocks close to the duel count there.
        boolean outdoors = level.canSeeSky(BlockPos.containing(mid.x, mid.y + 1, mid.z));
        int reach = outdoors ? 24 : 12;
        for (int dx = (int) -radius; dx <= radius; dx++) {
            for (int dz = (int) -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                int x = (int) Math.floor(mid.x) + dx, z = (int) Math.floor(mid.z) + dz;
                int h = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
                if (h <= mid.y + reach) top = Math.max(top, h);
            }
        }
        return Math.max(mid.y, top + 1.2);
    }

    /** How many cards a player's deck holds in a classic game (the size mobs match). */
    private static int librarySize(DeckChoice c) {
        if (c == null) return 60;
        return switch (c.kind()) {
            case CMD_PRECON -> 100;
            case INLINE -> {
                try {
                    yield dev.mtgcraft.engine.Decks.fromText(c.name()).getMain().countAll();
                } catch (RuntimeException e) {
                    yield 60;
                }
            }
            default -> 60;
        };
    }

    /**
     * Seats for the mobs: same kind = same team; themed decks (with a commander in Commander duels).
     * {@code library}: classic decks grow to this many cards (0 = the usual 40). {@code packs}: pool size override (0 = by tier).
     */
    private static void addMobSeats(Duel d, MatchSetup setup, List<Entity> seatEntities, boolean commander, int library, int packs) {
        Map<String, Integer> teams = new LinkedHashMap<>();
        Map<String, Integer> names = new HashMap<>();
        for (Mob m : d.mobs) {
            int team = teams.computeIfAbsent(teamKey(m), k -> teams.size() + 2);
            Mob face = rider(m);
            String name = face == m ? m.getDisplayName().getString() : m.getDisplayName().getString() + " Jockey";
            int dup = names.merge(name, 1, Integer::sum);
            if (dup > 1) name += " " + dup;
            setup.seats.add(new MatchSetup.Seat(true, name, null,
                    DeckChoice.theme(MobThemes.theme(face), Math.max(3, (packs > 0 ? packs : MobThemes.packs(face)) + MtgConfig.DIFFICULTY.get()), commander, library), team));
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
        // Holograms go home first, so loot, penalties and graves all happen where they really are.
        for (UUID id : d.players.keySet()) {
            ServerPlayer hp = DuelHosting.online(d.level.getServer(), id);
            if (hp != null) Parties.sendHome(hp);
        }
        for (Mob m : d.everyone) {
            Boolean grav = d.hadNoGravity.get(m.getUUID());
            if (grav != null && m.isAlive()) {
                m.setNoGravity(grav);
                // The stage can float well above the ground: drift down instead of taking fall damage.
                m.fallDistance = 0;
                if (!grav) m.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.SLOW_FALLING, 200, 0, false, false));
            }
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
            // Player duels: the winner is announced, then the stakes (if any) are paid.
            String winner = result.winnerName() == null || result.winnerName().isEmpty() ? "Someone" : result.winnerName();
            for (ServerPlayer p : online) {
                p.sendSystemMessage(Component.literal(winner + " wins the duel!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            }
            applyStakes(d, result.winnerName(), online);
            return;
        }
        if (result.winningTeam() == d.playerTeam()) {
            ServerPlayer credit = online.isEmpty() ? null : online.get(0);
            for (ServerPlayer p : online) {
                p.displayClientMessage(Component.literal("Victory!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), true);
            }
            // Every mob at the table falls. Their normal drops and XP are caught (not dropped off the stage) and
            // shared out below with the duel's own rewards. DEFEATED: their deaths drop no extra packs.
            List<ItemStack> drops = new ArrayList<>();
            int xp = 0;
            for (Mob m : d.everyone) DEFEATED.add(m.getUUID());
            for (Mob m : d.everyone) {
                if (!m.isAlive()) continue;
                // A Champion is never killed: they hand over a prize instead (below).
                if (Champions.spared(m)) continue;
                if (m instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon) {
                    // The dragon pays out its XP during its death animation: work it out now and catch those orbs.
                    var fight = dragon.getDragonFight();
                    xp += fight != null && !fight.hasPreviouslyKilledDragon() ? 12000 : 500;
                    NO_XP.add(m.getUUID());
                } else {
                    xp += Math.max(0, m.getExperienceReward());
                    m.skipDropExperience();
                }
                CATCH.put(m.getUUID(), drops);
                net.minecraft.world.damagesource.DamageSource src = credit != null ? credit.damageSources().playerAttack(credit) : m.damageSources().magic();
                m.hurt(src, Float.MAX_VALUE);
                if (m.isAlive()) {
                    // Modded mobs (ATM9) often cap damage per hit, and the first hit's cooldown then blocks a second
                    // one, so they'd die seconds later or not at all. Clear the cooldown and finish them for sure.
                    m.invulnerableTime = 0;
                    m.hurtTime = 0;
                    m.kill();
                }
                if (m.isAlive() && !m.isDeadOrDying()) {
                    m.setHealth(0);
                    m.die(src);
                }
                CATCH.remove(m.getUUID());
                NO_XP.remove(m.getUUID());
            }
            // Everyone who fought gets their own pack rewards; the mobs' drops are dealt out between them and the XP
            // split evenly. It all goes straight into the inventory, and the rewards screen shows what they got.
            List<ServerPlayer> winners = new ArrayList<>();
            for (ServerPlayer p : online) if (!d.forfeited.contains(p.getUUID())) winners.add(p);
            List<List<ItemStack>> shares = new ArrayList<>();
            for (int i = 0; i < winners.size(); i++) shares.add(new ArrayList<>());
            for (int i = 0; i < drops.size() && !winners.isEmpty(); i++) shares.get(i % winners.size()).add(drops.get(i));
            for (int i = 0; i < winners.size(); i++) {
                ServerPlayer p = winners.get(i);
                List<ItemStack> loot = new ArrayList<>(DuelLoot.duelRewards(d.mobs.stream().map(GauntletDuels::rider).toList(), d.boss));
                loot.addAll(shares.get(i));
                for (ItemStack it : loot) {
                    ItemStack give = it.copy();
                    if (!p.getInventory().add(give)) p.drop(give, false);
                }
                int myXp = xp / winners.size() + (i < xp % winners.size() ? 1 : 0);
                if (myXp > 0) p.giveExperiencePoints(myXp);
                String sub = (d.players.size() > 1 ? d.teamName + " wins!" : "You beat " + String.join(", ", seatNames(d)) + ".")
                        + (myXp > 0 ? "  +" + myXp + " XP" : "");
                Net.toPlayer(p, new Packets.Rewards("Victory!", sub, loot));
                if (d.mobs.stream().anyMatch(Champions::is)) Champions.reward(p);
                Quests.won(p, d.boss);
            }
            return;
        }
        Mob victor = d.mobs.isEmpty() ? null : d.mobs.get(0);
        if (d.mobs.stream().anyMatch(Champions::spared)) {
            for (ServerPlayer p : online) tell(p, "Your opponent wins, and helps you back up. Try again any time!");
            return;
        }
        for (ServerPlayer p : online) {
            // The safe time would cancel the penalty itself (the hit comes from the mob): lift it for the hit, and
            // give it back to anyone who survives it.
            Long safeUntil = GRACE.remove(p.getUUID());
            switch (MtgConfig.LOSS_PENALTY.get()) {
                case DEATH -> {
                    tell(p, "You lost the duel...");
                    p.hurt(victor != null ? p.damageSources().mobAttack(victor) : p.damageSources().magic(), Float.MAX_VALUE);
                    if (p.isAlive()) {
                        // Modded armour and damage caps (ATM9) can soak or cap that hit, leaving the player to die
                        // seconds later or not at all. Finish it with a kill that ignores armour and hit cooldowns.
                        p.invulnerableTime = 0;
                        p.kill();
                    }
                }
                case DAMAGE -> {
                    tell(p, "You lost the duel and take a beating.");
                    p.hurt(victor != null ? p.damageSources().mobAttack(victor) : p.damageSources().magic(), 10);
                }
                case NONE -> tell(p, "You lost the duel.");
            }
            if (p.isAlive() && safeUntil != null) GRACE.put(p.getUUID(), safeUntil);
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
        // First: what kind of duel? (format and stakes)
        String n = to.getGameProfile().getName();
        Net.toPlayer(from, new Packets.Prompt("Duel " + n, "Pick the format and what you play for. " + n + " sees it before accepting.",
                List.of("Friendly (Commander)", "Friendly (Classic)", "To the death", "Ante: 3 cards each", "Reward duel (packs)",
                        Parties.together(from, to) ? "Leave party" : "Invite to party", "Cancel"),
                List.of("/mtgduel challenge " + n + " FRIENDLY C", "/mtgduel challenge " + n + " FRIENDLY K", "/mtgduel challenge " + n + " DEATH C",
                        "/mtgduel challenge " + n + " ANTE C", "/mtgduel challenge " + n + " REWARD C",
                        Parties.together(from, to) ? "/mtgparty leave" : "/mtgparty invite " + n, ""), 30));
    }

    /** Pays out a player duel's stakes once the game has a winner. */
    private static void applyStakes(Duel d, String winnerName, List<ServerPlayer> online) {
        if ("FRIENDLY".equals(d.stake) || winnerName == null) return;
        ServerPlayer w = null;
        for (ServerPlayer p : online) if (p.getGameProfile().getName().equals(winnerName)) w = p;
        if (w == null) return;
        String wn = w.getGameProfile().getName();
        for (ServerPlayer l : online) {
            if (l == w) continue;
            switch (d.stake) {
                case "DEATH" -> {
                    tell(l, "You lost a duel to the death...");
                    l.hurt(l.damageSources().playerAttack(w), Float.MAX_VALUE);
                    if (l.isAlive()) {
                        l.invulnerableTime = 0;
                        l.kill();
                    }
                }
                case "ANTE" -> {
                    ItemStack box = DeckBoxItem.find(l);
                    List<dev.mtgcraft.item.CardBag.Entry> pool = new ArrayList<>();
                    if (box.getItem() instanceof DeckBoxItem) {
                        for (var e : dev.mtgcraft.item.CardBag.entries(box)) if (!DeckBoxItem.isBasicKey(e.key())) pool.add(e);
                    }
                    java.util.Collections.shuffle(pool);
                    int taken = 0;
                    for (var e : pool) {
                        if (taken == 3) break;
                        var pc = dev.mtgcraft.engine.Cards.card(e.key());
                        if (pc == null || dev.mtgcraft.item.CardBag.remove(box, e.key(), e.foil(), 1) == 0) continue;
                        ItemStack card = dev.mtgcraft.item.CardItem.of(pc, e.foil(), 1);
                        if (!w.getInventory().add(card)) w.drop(card, false);
                        taken++;
                    }
                    tell(l, wn + " won " + taken + " of your cards (the ante).");
                    tell(w, "You won " + taken + " cards from " + l.getGameProfile().getName() + " (the ante).");
                }
                case "REWARD" -> {
                    long day = w.level().getDayTime() / 24000L;
                    String key = w.getUUID() + "|" + l.getUUID();
                    if (Long.valueOf(day).equals(REWARDED.get(key))) {
                        tell(w, "You already won a reward from " + l.getGameProfile().getName() + " today.");
                        continue;
                    }
                    REWARDED.put(key, day);
                    var themes = dev.mtgcraft.engine.Packs.Theme.values();
                    for (int i = 0; i < 2; i++) {
                        ItemStack pack = dev.mtgcraft.item.PackItem.themed(themes[w.getRandom().nextInt(themes.length)], 1);
                        if (!w.getInventory().add(pack)) w.drop(pack, false);
                    }
                    tell(w, "Reward: 2 booster packs for beating " + l.getGameProfile().getName() + ".");
                }
                default -> { }
            }
        }
    }

    /** Whether {@code host} has a duel lobby open that {@code p} was invited to. */
    static boolean lobbyOpenFor(ServerPlayer host, ServerPlayer p) {
        Lobby l = LOBBIES.get(host.getUUID());
        return l != null && l.invited.contains(p.getUUID()) && tick < l.deadline;
    }

    static boolean joinedLobby(ServerPlayer host, ServerPlayer p) {
        Lobby l = LOBBIES.get(host.getUUID());
        return l != null && l.joined.contains(p.getUUID());
    }

    /** Group duels whose gathering time is up start now, with everyone who accepted and is still close by. */
    private static void startGroupDuels() {
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        for (var it = GROUP_PVP.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            GroupPvp g = e.getValue();
            if (tick < g.deadline) continue;
            it.remove();
            ServerPlayer host = server.getPlayerList().getPlayer(e.getKey());
            if (host == null || inDuel(host.getUUID())) continue;
            List<ServerPlayer> all = new ArrayList<>(List.of(host));
            List<DeckChoice> decks = new ArrayList<>();
            DeckChoice hd = deckOf(host, false);
            if (hd == null) continue;
            decks.add(hd);
            for (UUID id : g.accepted) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p == null || inDuel(id) || p.level() != host.level() || p.distanceTo(host) > JOIN_RANGE) continue;
                DeckChoice dc = deckOf(p, true);
                if (dc == null) continue;
                all.add(p);
                decks.add(dc);
            }
            if (all.size() < 2) tell(host, "Nobody joined your group duel.");
            else startPvp(all, decks, g.stake, g.commander);
        }
    }

    /** What each pending invite plays for: invitee -> inviter -> "STAKE FORMAT". */
    private static final Map<UUID, Map<UUID, String>> STAKES = new HashMap<>();
    /** Reward duels already won today: "winner|loser" -> Minecraft day. */
    private static final Map<String, Long> REWARDED = new HashMap<>();

    private static String stakeText(String stake) {
        return switch (stake) {
            case "DEATH" -> "a duel to the death (the loser dies)";
            case "ANTE" -> "an ante duel (3 random cards from each deck; the winner takes them all)";
            case "REWARD" -> "a reward duel (the winner gets booster packs, once a day)";
            default -> "a friendly duel (nobody dies)";
        };
    }

    /** The second step of a player challenge, from the duel-type menu. */
    /** A group duel being gathered: everyone who accepts before the deadline plays one free-for-all. */
    private static final class GroupPvp {
        String stake;
        boolean commander;
        long deadline;
        final Set<UUID> invited = new HashSet<>();
        final List<UUID> accepted = new ArrayList<>();
    }

    private static final Map<UUID, GroupPvp> GROUP_PVP = new HashMap<>();

    private static ItemStack gearOf(ServerPlayer p) {
        if (p.getMainHandItem().getItem() instanceof GauntletItem) return p.getMainHandItem();
        if (p.getOffhandItem().getItem() instanceof GauntletItem) return p.getOffhandItem();
        for (ItemStack s : p.getInventory().items) if (s.getItem() instanceof GauntletItem) return s;
        return ItemStack.EMPTY;
    }

    /**
     * From the duel-type menu. With the gear in group mode, a friendly duel invites everyone nearby with gear too, and
     * starts in 30 seconds with everyone who accepted (a free-for-all).
     */
    private static void challengeWith(ServerPlayer from, ServerPlayer to, String stake, boolean commander) {
        ItemStack gear = gearOf(from);
        if (!"FRIENDLY".equals(stake) || gear.isEmpty() || !GauntletItem.groupFights(gear) || inDuel(from.getUUID())) {
            sendChallenge(from, to, stake, commander);
            return;
        }
        GroupPvp g = new GroupPvp();
        g.stake = stake;
        g.commander = commander;
        g.deadline = tick + 20 * 30;
        List<ServerPlayer> targets = new ArrayList<>(List.of(to));
        for (ServerPlayer o : from.serverLevel().getEntitiesOfClass(ServerPlayer.class, from.getBoundingBox().inflate(FRIEND_RANGE * 2))) {
            if (o != from && o != to && !inDuel(o.getUUID()) && holdsGauntlet(o)) targets.add(o);
        }
        GROUP_PVP.put(from.getUUID(), g);
        for (ServerPlayer t : targets) {
            g.invited.add(t.getUUID());
            sendChallenge(from, t, stake, commander);
        }
        tell(from, "Group duel: " + targets.size() + " invited. It starts in 30 seconds with everyone who accepts.");
    }

    private static void sendChallenge(ServerPlayer from, ServerPlayer to, String stake, boolean commander) {
        if (!List.of("FRIENDLY", "DEATH", "ANTE", "REWARD").contains(stake)) return;
        if ("REWARD".equals(stake) && !Champions.hasCup(from) && !Champions.trophyNear(from)) {
            tell(from, "Reward duels need the MTG World Cup: carry it, or place it on a Magic Table nearby. Beat a Village Champion to win it.");
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
        STAKES.computeIfAbsent(to.getUUID(), k -> new HashMap<>()).put(from.getUUID(), stake + " " + (commander ? "C" : "K"));
        String what = stakeText(stake) + (commander ? ", Commander" : ", classic");
        to.sendSystemMessage(Component.literal(fromName + " challenges you to " + what + "! ").withStyle(ChatFormatting.GOLD)
                .append(button("[Accept]", "/mtgduel accept " + fromName, ChatFormatting.GREEN, "Duel " + fromName))
                .append(Component.literal(" "))
                .append(button("[Decline]", "/mtgduel decline " + fromName, ChatFormatting.RED, "No thanks")));
        Net.toPlayer(to, new Packets.Prompt("Duel challenge!", fromName + " challenges you to " + what + ".",
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
        Map<UUID, String> st = STAKES.get(to.getUUID());
        String terms = st == null ? null : st.remove(from.getUUID());
        if (terms == null) terms = "FRIENDLY C";
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
            STAKES.computeIfAbsent(to.getUUID(), k -> new HashMap<>()).put(from.getUUID(), terms);
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
        GroupPvp group = GROUP_PVP.get(from.getUUID());
        if (group != null && group.invited.contains(to.getUUID())) {
            if (!group.accepted.contains(to.getUUID())) group.accepted.add(to.getUUID());
            tell(to, "You're in! The group duel starts soon.");
            tell(from, to.getGameProfile().getName() + " joined your group duel.");
            return;
        }
        String[] t = terms.split(" ");
        startPvp(from, theirs, to, mine, t[0], t.length < 2 || t[1].equals("C"));
    }

    public static void decline(ServerPlayer to, ServerPlayer from) {
        if (takeInvite(to, from)) tell(from, to.getGameProfile().getName() + " declined your duel.");
        tell(to, "Declined.");
    }

    private static void startPvp(ServerPlayer a, DeckChoice da, ServerPlayer b, DeckChoice db, String stake, boolean wantCommander) {
        startPvp(List.of(a, b), List.of(da, db), stake, wantCommander);
    }

    /** A player duel for two or more players, each on their own side (a free-for-all when there are more than two). */
    private static void startPvp(List<ServerPlayer> all, List<DeckChoice> decks, String stake, boolean wantCommander) {
        ServerPlayer a = all.get(0), b = all.get(1);
        Duel d = new Duel();
        d.pvp = true;
        d.stake = stake;
        d.level = a.serverLevel();
        float widest = 0;
        Vec3 sum = Vec3.ZERO;
        for (ServerPlayer p : all) {
            widest = Math.max(widest, p.getBbWidth());
            sum = sum.add(p.position());
        }
        d.radius = 5f + (all.size() - 2) * 1.2f + Math.max(0, widest - 1) * 1.3f;
        Vec3 mid = sum.scale(1.0 / all.size());
        d.center = new Vec3(mid.x, stageHeight(d.level, mid, d.radius + 1.5f), mid.z);

        boolean commander = wantCommander;
        boolean allReady = true;
        for (DeckChoice dc : decks) allReady &= commanderReady(dc);
        if (commander && !allReady) {
            commander = false;
            for (ServerPlayer p : all) tell(p, "Classic duel: Commander needs a 100-card deck with a commander for both of you.");
        }
        MatchSetup setup = new MatchSetup();
        setup.mode = commander ? MatchSetup.Mode.COMMANDER : MatchSetup.Mode.TEAMS;
        List<Entity> seatEntities = new ArrayList<>();
        int team = 1;
        for (int i = 0; i < all.size(); i++) {
            ServerPlayer p = all.get(i);
            d.players.put(p.getUUID(), setup.seats.size());
            setup.seats.add(new MatchSetup.Seat(false, p.getGameProfile().getName(), p.getUUID(), decks.get(i), team++));
            seatEntities.add(p);
        }
        d.setup = setup;
        takeSeats(d, a, seatEntities);
        for (UUID id : d.players.keySet()) BY_PLAYER.put(id, d);

        BlockPos key = BlockPos.containing(d.center);
        d.live = DuelHosting.launch(a.getServer(), setup, key, true,
                err -> {
                    for (ServerPlayer p : all) tell(p, "The duel couldn't start: " + err);
                    end(d, null);
                },
                result -> end(d, result));
        if (d.live == null) return;
        d.watchConcessions(a.getServer());
        List<Packets.ArenaSeat> seats = new ArrayList<>();
        for (int i = 0; i < setup.seats.size(); i++) {
            seats.add(new Packets.ArenaSeat(setup.seats.get(i).name, seatEntities.get(i).getId()));
        }
        Packets.Arena arena = new Packets.Arena(key, d.center.x, d.center.y, d.center.z, d.radius, false, seats);
        List<String> names = new ArrayList<>();
        for (ServerPlayer p : all) names.add(p.getGameProfile().getName());
        for (ServerPlayer p : all) {
            Net.toPlayer(p, arena);
            p.displayClientMessage(Component.literal("Duel! " + String.join(" vs ", names)
                    + (commander ? " (Commander)" : "")).withStyle(ChatFormatting.GOLD), true);
        }
    }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mtgduel")
                .then(Commands.literal("style").then(Commands.argument("kind", StringArgumentType.word())
                        .executes(c -> c.getSource().getPlayer() == null ? 0
                                : StarterKits.pickStyle(c.getSource().getPlayer(), "disk".equals(StringArgumentType.getString(c, "kind"))))))
                .then(Commands.literal("challenge").then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("stake", StringArgumentType.word()).then(Commands.argument("format", StringArgumentType.word())
                                .executes(c -> withPlayer(c.getSource(), StringArgumentType.getString(c, "player"), (me, other) -> challengeWith(me, other,
                                        StringArgumentType.getString(c, "stake"), !"K".equals(StringArgumentType.getString(c, "format")))))))))
                .then(Commands.literal("unstick").executes(c -> c.getSource().getPlayer() == null ? 0 : unstick(c.getSource().getPlayer())))
                .then(Commands.literal("deck").then(Commands.argument("slot", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                        .then(Commands.argument("mob", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .executes(c -> c.getSource().getPlayer() == null ? 0 : pickDeck(c.getSource().getPlayer(),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "slot"),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "mob"))))))
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
        startGroupDuels();
        Set<Duel> duels = new HashSet<>(BY_PLAYER.values());
        for (Duel d : duels) {
            if (d.allForfeitedAt > 0 && System.currentTimeMillis() - d.allForfeitedAt > 15_000) {
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
                if (p != null && p.isAlive() && p.level() == d.level && spot != null) {
                    // The stage has no blocks under it: keep everyone flying (double-tapping jump or touching
                    // down would otherwise turn it off, they'd fall, and get pulled back up over and over).
                    if (!p.getAbilities().mayfly || !p.getAbilities().flying) {
                        p.getAbilities().mayfly = true;
                        p.getAbilities().flying = true;
                        p.onUpdateAbilities();
                    }
                    if (d.boss) {
                        // Boss arenas are big: walk around the stage, but stay on its floor and inside the barrier.
                        Vec3 pos = p.position();
                        double dx = pos.x - d.center.x, dz = pos.z - d.center.z, dist = Math.sqrt(dx * dx + dz * dz);
                        double max = Math.max(1, d.radius - 0.6);
                        double nx = pos.x, nz = pos.z;
                        // Only correct real drift: a teleport makes the game ignore movement until the client
                        // answers it, so doing it too often makes it feel like you can't move.
                        boolean fix = Math.abs(pos.y - spot.y) > 0.75;
                        if (dist > max) {
                            nx = d.center.x + dx / dist * max;
                            nz = d.center.z + dz / dist * max;
                            fix = true;
                        }
                        if (fix) {
                            if (DEV) System.out.println("[MTGCraft dev] boss clamp: pos=" + pos + " spot=" + spot + " dist=" + dist + " max=" + max);
                            p.teleportTo(d.level, nx, spot.y, nz, p.getYRot(), p.getXRot());
                            p.setDeltaMovement(Vec3.ZERO);
                        }
                    } else if (p.position().distanceToSqr(spot) > 0.36) {
                        if (DEV) System.out.println("[MTGCraft dev] podium snap: pos=" + p.position() + " spot=" + spot + " boss=" + d.boss);
                        // No flying off: back onto the podium (looking where they were looking).
                        p.teleportTo(d.level, spot.x, spot.y, spot.z, p.getYRot(), p.getXRot());
                        p.setDeltaMovement(Vec3.ZERO);
                    }
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
                // Only the dragon holds a fixed facing; other mobs turn to watch the duelists.
                Float face = m instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon ? d.facing.get(m.getUUID()) : null;
                if (spot == null || !m.isAlive()) continue;
                boolean turned = face != null && Math.abs(net.minecraft.util.Mth.wrapDegrees(m.getYRot() - face)) > 3;
                if (m.position().distanceToSqr(spot) > 0.5 || turned) {
                    float yaw = face != null ? face : m.getYRot();
                    m.moveTo(spot.x, spot.y, spot.z, yaw, 0);
                    m.setYBodyRot(yaw);
                    m.setYHeadRot(yaw);
                }
                m.setDeltaMovement(Vec3.ZERO);
            }
            if (tick % 20 == 0) stopTargeting(d.level, d.center, d.radius + 24, d.players.keySet());
            runeCircle(d);
            if (looker != null) {
                for (Mob m : d.everyone) {
                    // Mobs watch the duelists. Not the dragon: lookAt turns its body the wrong way round (its head
                    // points opposite to other mobs' faces), and it holds the facing it was seated with instead.
                    if (m.isAlive() && !(m instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon)) {
                        m.lookAt(EntityAnchorArgument.Anchor.EYES, looker.getEyePosition());
                    }
                }
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
        if (d == null) return;
        // They're saved right after this: give their flight back and let them drift down from the stage when they
        // come back, instead of falling when they rejoin mid-air.
        if (event.getEntity() instanceof ServerPlayer p) {
            boolean[] f = d.hadFlight.remove(p.getUUID());
            if (f != null) {
                p.getAbilities().mayfly = f[0] || p.isCreative() || p.isSpectator();
                p.getAbilities().flying = f[1] && p.getAbilities().mayfly;
                p.onUpdateAbilities();
            }
            p.fallDistance = 0;
            p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.SLOW_FALLING, 400, 0, false, false));
        }
        d.forfeit(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void died(LivingDeathEvent event) {
        Duel d = BY_PLAYER.get(event.getEntity().getUUID());
        if (d == null) return;
        d.forfeit(event.getEntity().getUUID());
        // Everyone on the players' side is gone: close the duel now (a stuck game would otherwise linger on screen).
        if (d.forfeited.size() >= d.players.size()) end(d, new Matches.Result(true, -1, ""));
    }
}

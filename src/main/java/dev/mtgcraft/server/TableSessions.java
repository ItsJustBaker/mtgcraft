package dev.mtgcraft.server;

import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.engine.ForgeEngine;
import dev.mtgcraft.engine.MatchSetup;
import dev.mtgcraft.net.Net;
import dev.mtgcraft.net.Packets;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side of every Magic Table: lobbies, seats, and running games. Games run on the server's copy of
 * Card-Forge; each seated player's client gets its own byte tunnel (see {@link RemoteSeat}), so friends on a
 * server, LAN guests and the single-player host all play the same way.
 */
public final class TableSessions {
    private record Key(ResourceKey<Level> dimension, BlockPos pos) {}

    private static final Map<Key, TableSession> SESSIONS = new HashMap<>();
    /** Players whose client has Card-Forge loaded. */
    private static final Set<UUID> READY = ConcurrentHashMap.newKeySet();
    private static MinecraftServer server;

    private TableSessions() {}

    public static void serverStarted(MinecraftServer s) {
        server = s;
    }

    public static void serverStopping() {
        for (TableSession t : SESSIONS.values()) closeSeats(t);
        SESSIONS.clear();
        READY.clear();
        DuelHosting.closeAll();
        server = null;
    }

    // ------------------------------------------------------------------ packets

    public static void open(ServerPlayer player, BlockPos pos) {
        if (!player.level().getBlockState(pos).is(MtgCraft.MAGIC_TABLE.get())) return;
        if (player.distanceToSqr(pos.getCenter()) > 64) return;
        Key key = new Key(player.level().dimension(), pos.immutable());
        TableSession t = SESSIONS.computeIfAbsent(key, k ->
                new TableSession(k.dimension(), k.pos(), player.getUUID(), player.getGameProfile().getName()));
        t.viewers.add(player.getUUID());
        broadcast(t);
    }

    public static void engineReady(ServerPlayer player) {
        READY.add(player.getUUID());
        StarterKits.maybeOffer(player);
        for (TableSession t : SESSIONS.values()) {
            if (t.viewers.contains(player.getUUID())) broadcast(t);
        }
    }

    public static void edit(ServerPlayer player, Packets.EditTable msg) {
        TableSession t = SESSIONS.get(new Key(player.level().dimension(), msg.table()));
        if (t == null) return;
        UUID me = player.getUUID();
        boolean host = me.equals(t.host);
        var seats = t.setup.seats;
        int s = msg.seat();
        boolean validSeat = s >= 0 && s < seats.size();
        t.message = "";

        if (msg.op() == Packets.Op.CLOSE) {
            t.viewers.remove(me);
            cleanup(t);
            return;
        }
        if (t.running && msg.op() != Packets.Op.STAND) {
            return;
        }
        switch (msg.op()) {
            case SET_MODE -> {
                if (host && msg.number() >= 0 && msg.number() < MatchSetup.Mode.values().length) {
                    t.setup.mode = MatchSetup.Mode.values()[msg.number()];
                }
            }
            case ADD_SEAT -> {
                if (host && seats.size() < MatchSetup.MAX_SEATS) {
                    seats.add(new MatchSetup.Seat(true, TableSession.aiName(seats.size()), null,
                            dev.mtgcraft.engine.DeckChoice.colors(java.util.List.of()), seats.size() + 1));
                }
            }
            case REMOVE_SEAT -> {
                if (host && validSeat && seats.size() > MatchSetup.MIN_SEATS && !me.equals(seats.get(s).player)) {
                    seats.remove(s);
                }
            }
            case SEAT_AI -> {
                if (host && validSeat && !me.equals(seats.get(s).player)) {
                    MatchSetup.Seat seat = seats.get(s);
                    seat.ai = true;
                    seat.player = null;
                    seat.name = TableSession.aiName(s);
                }
            }
            case SEAT_OPEN -> {
                if (host && validSeat && seats.get(s).ai) {
                    MatchSetup.Seat seat = seats.get(s);
                    seat.ai = false;
                    seat.player = null;
                    seat.name = "Open seat";
                    seat.deck = dev.mtgcraft.engine.DeckChoice.colors(java.util.List.of());
                }
            }
            case SIT -> {
                if (validSeat && seats.get(s).isOpen()) {
                    int old = t.seatOf(me);
                    if (old >= 0) vacate(seats.get(old));
                    MatchSetup.Seat seat = seats.get(s);
                    seat.player = me;
                    seat.name = player.getGameProfile().getName();
                }
            }
            case STAND -> {
                int mine = t.seatOf(me);
                if (mine >= 0) {
                    if (t.running) {
                        concede(t, mine);
                    } else {
                        vacate(seats.get(mine));
                    }
                }
            }
            case SET_TEAM -> {
                if (validSeat && (host || me.equals(seats.get(s).player))) {
                    seats.get(s).team = Math.max(1, Math.min(MatchSetup.MAX_SEATS, msg.number()));
                }
            }
            case SET_DECK -> {
                if (validSeat && ((host && seats.get(s).ai) || me.equals(seats.get(s).player))) {
                    seats.get(s).deck = Packets.decode(msg.text());
                }
            }
            case START -> {
                if (host) start(t);
            }
            default -> { }
        }
        broadcast(t);
    }

    public static void onTunnel(ServerPlayer player, Packets.Tunnel msg) {
        DuelHosting.onTunnel(player, msg);
    }

    /** Whether the player's client has Card-Forge loaded (needed before they can be dealt in). */
    public static boolean isReady(UUID player) {
        return READY.contains(player);
    }

    public static void playerLeft(ServerPlayer player) {
        UUID me = player.getUUID();
        READY.remove(me);
        for (Iterator<TableSession> it = SESSIONS.values().iterator(); it.hasNext(); ) {
            TableSession t = it.next();
            t.viewers.remove(me);
            int mine = t.seatOf(me);
            if (mine >= 0) {
                if (t.running) concede(t, mine);
                else vacate(t.setup.seats.get(mine));
            }
            if (me.equals(t.host)) {
                t.host = t.viewers.isEmpty() ? null : t.viewers.iterator().next();
            }
            if (t.host == null && !t.running) {
                closeSeats(t);
                it.remove();
            } else {
                broadcast(t);
            }
        }
    }

    // ------------------------------------------------------------------ game start / end

    private static void start(TableSession t) {
        if (ForgeEngine.state() != ForgeEngine.State.READY) {
            t.message = "The card engine is still loading on the server. Try again in a moment.";
            return;
        }
        var seats = t.setup.seats;
        for (MatchSetup.Seat s : seats) {
            if (s.isOpen()) {
                t.message = "Fill or remove the open seats first.";
                return;
            }
            if (!s.ai && !READY.contains(s.player)) {
                t.message = s.name + " is still loading cards.";
                return;
            }
        }
        if (t.setup.mode == MatchSetup.Mode.TEAMS) {
            Set<Integer> teams = new HashSet<>();
            for (MatchSetup.Seat s : seats) teams.add(s.team);
            if (teams.size() < 2) {
                t.message = "Teams mode needs at least two different teams.";
                return;
            }
        }
        closeSeats(t);
        t.running = true;
        t.message = "";
        t.live = DuelHosting.launch(server, copy(t.setup), t.pos,
                err -> {
                    t.running = false;
                    t.message = "Couldn't start: " + err;
                    broadcast(t);
                },
                result -> {
                    t.running = false;
                    broadcast(t);
                });
        if (t.live == null) return;
        // The 3D battlefield floats over the table: players' cards in front of them, AI seats around it.
        java.util.List<Packets.ArenaSeat> arenaSeats = new java.util.ArrayList<>();
        for (MatchSetup.Seat s : seats) {
            ServerPlayer p = s.ai ? null : server.getPlayerList().getPlayer(s.player);
            arenaSeats.add(new Packets.ArenaSeat(s.name, p == null ? -1 : p.getId()));
        }
        Packets.Arena arena = new Packets.Arena(t.pos, t.pos.getX() + 0.5, t.pos.getY() + 1, t.pos.getZ() + 0.5, 2.5f, false, arenaSeats);
        for (MatchSetup.Seat s : seats) {
            if (s.ai) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(s.player);
            if (p != null) {
                p.displayClientMessage(Component.literal("The cards are dealt!"), true);
                Net.toPlayer(p, arena);
            }
        }
    }

    /** A snapshot, so lobby edits can't change a game in progress. */
    private static MatchSetup copy(MatchSetup src) {
        MatchSetup c = new MatchSetup();
        c.mode = src.mode;
        for (MatchSetup.Seat s : src.seats) c.seats.add(new MatchSetup.Seat(s.ai, s.name, s.player, s.deck, s.team));
        return c;
    }

    private static void concede(TableSession t, int seatIndex) {
        if (t.live != null) t.live.concede(seatIndex);
    }

    private static void vacate(MatchSetup.Seat seat) {
        seat.player = null;
        seat.name = "Open seat";
    }

    private static void closeSeats(TableSession t) {
        if (t.live != null) t.live.close();
        t.live = null;
    }

    /** Forgets a table nobody is looking at or playing at. */
    private static void cleanup(TableSession t) {
        boolean anyone = !t.viewers.isEmpty() || t.running;
        for (MatchSetup.Seat s : t.setup.seats) if (s.player != null) anyone = true;
        if (!anyone) {
            closeSeats(t);
            SESSIONS.values().remove(t);
        }
    }

    private static void broadcast(TableSession t) {
        if (server == null) return;
        Packets.TableState state = t.state(READY);
        Set<UUID> targets = new HashSet<>(t.viewers);
        for (MatchSetup.Seat s : t.setup.seats) if (s.player != null) targets.add(s.player);
        for (UUID id : targets) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) Net.toPlayer(p, state);
        }
    }
}

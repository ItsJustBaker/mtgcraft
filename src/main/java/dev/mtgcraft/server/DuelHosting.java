package dev.mtgcraft.server;

import dev.mtgcraft.engine.MatchSetup;
import dev.mtgcraft.engine.Matches;
import dev.mtgcraft.engine.net.RemoteSeat;
import dev.mtgcraft.net.Net;
import dev.mtgcraft.net.Packets;
import forge.gui.interfaces.IGuiGame;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Starts a Card-Forge match on the server with a byte tunnel to each human player's client. Used by Magic Tables
 * and by Duel Gauntlet challenges.
 */
public final class DuelHosting {
    /** Live tunnels by session id, with the player allowed to feed each one. */
    private static final Map<Integer, RemoteSeat> TUNNELS = new ConcurrentHashMap<>();
    private static final Map<Integer, UUID> OWNERS = new ConcurrentHashMap<>();
    private static final AtomicInteger IDS = new AtomicInteger(1);

    /** Players registered by GameTests (fake players aren't in the player list). Empty in normal play. */
    public static final Map<UUID, ServerPlayer> TEST_PLAYERS = new ConcurrentHashMap<>();

    private DuelHosting() {}

    /** An online player by id (or a GameTest player). */
    public static ServerPlayer online(MinecraftServer server, UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        return p != null ? p : TEST_PLAYERS.get(id);
    }

    /** A running match's tunnels, by seat index. */
    public static final class Handle {
        final Map<Integer, RemoteSeat> seats = new HashMap<>();
        final Map<Integer, Integer> ids = new HashMap<>();
        final long launchedAt = System.currentTimeMillis();
        /** Card-Forge can't take a concession while it's still dealing; wait this long after launch. */
        private static final long SETUP_GRACE_MS = 6000;

        public RemoteSeat seat(int index) {
            return seats.get(index);
        }

        /** Concedes a human seat (they left or logged out). */
        public void concede(int index) {
            RemoteSeat seat = seats.get(index);
            if (seat == null) return;
            long wait = launchedAt + SETUP_GRACE_MS - System.currentTimeMillis();
            if (wait > 0) {
                Thread t = new Thread(() -> {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException ignored) {
                    }
                    concede(index);
                }, "MTGCraft concede");
                t.setDaemon(true);
                t.start();
                return;
            }
            dev.mtgcraft.engine.ForgeEngine.runOnUi(() -> {
                if (seat.gui.getGameController() != null) seat.gui.getGameController().concede();
                // If the game was waiting on this player's answer, release it so the game can end.
                seat.cancelPending();
            });
        }

        public void close() {
            for (int id : ids.values()) {
                RemoteSeat seat = TUNNELS.remove(id);
                OWNERS.remove(id);
                if (seat != null) seat.close();
            }
            seats.clear();
            ids.clear();
        }
    }

    /**
     * Opens tunnels for every human seat, tells their clients a duel is starting at {@code key}, and starts the
     * match. Callbacks run on the server thread. Returns null (after calling onError) if a player is offline.
     */
    public static Handle launch(MinecraftServer server, MatchSetup setup, BlockPos key,
                                Consumer<String> onError, Consumer<Matches.Result> onOver) {
        return launch(server, setup, key, false, onError, onOver);
    }

    /** @param gauntlet a Duel Gauntlet fight rather than a Magic Table game */
    public static Handle launch(MinecraftServer server, MatchSetup setup, BlockPos key, boolean gauntlet,
                                Consumer<String> onError, Consumer<Matches.Result> onOver) {
        Handle h = new Handle();
        Map<Integer, IGuiGame> guis = new HashMap<>();
        for (int i = 0; i < setup.seats.size(); i++) {
            MatchSetup.Seat s = setup.seats.get(i);
            if (s.ai) continue;
            ServerPlayer p = online(server, s.player);
            if (p == null) {
                h.close();
                onError.accept(s.name + " isn't online.");
                return null;
            }
            int id = IDS.getAndIncrement();
            UUID uuid = s.player;
            RemoteSeat seat = new RemoteSeat(s.name, data -> {
                ServerPlayer target = online(server, uuid);
                if (target != null) Net.toPlayer(target, new Packets.Tunnel(id, data));
            });
            TUNNELS.put(id, seat);
            OWNERS.put(id, uuid);
            h.seats.put(i, seat);
            h.ids.put(i, id);
            guis.put(i, seat.gui);
            Net.toPlayer(p, new Packets.DuelStart(id, key, gauntlet));
        }
        Matches.start(setup, guis,
                err -> server.execute(() -> onError.accept(err)),
                result -> server.execute(() -> onOver.accept(result)));
        return h;
    }

    /** Duel bytes from a player's client. */
    public static void onTunnel(ServerPlayer player, Packets.Tunnel msg) {
        RemoteSeat seat = TUNNELS.get(msg.session());
        if (seat != null && player.getUUID().equals(OWNERS.get(msg.session()))) {
            seat.receive(msg.data());
        }
    }

    public static void closeAll() {
        for (RemoteSeat s : TUNNELS.values()) s.close();
        TUNNELS.clear();
        OWNERS.clear();
    }
}

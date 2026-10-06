package dev.mtgcraft.client;

import dev.mtgcraft.engine.ForgeEngine;
import dev.mtgcraft.engine.net.RemoteDuel;
import dev.mtgcraft.net.Net;
import dev.mtgcraft.net.Packets;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The client's view of tables: lobby state from the server, and the duels this player is seated in. */
public final class ClientTables {
    /** Latest lobby state per table. Main thread. */
    private static final Map<BlockPos, Packets.TableState> STATES = new ConcurrentHashMap<>();
    /** Duel tunnels by session id. Fed from the network thread. */
    private static final Map<Integer, RemoteDuel> DUELS = new ConcurrentHashMap<>();
    /** The duel at each table this player is in. */
    private static final Map<BlockPos, RemoteDuel> DUEL_AT = new ConcurrentHashMap<>();
    /** Keys of duels that are Duel Gauntlet fights rather than table games. */
    private static final java.util.Set<BlockPos> GAUNTLET = ConcurrentHashMap.newKeySet();
    private static boolean sentReady;

    private ClientTables() {}

    public static Packets.TableState state(BlockPos table) {
        return STATES.get(table);
    }

    public static RemoteDuel duelAt(BlockPos table) {
        return DUEL_AT.get(table);
    }

    /** Whether the duel at this key is a Duel Gauntlet fight (it has no table lobby to return to). */
    public static boolean isGauntlet(BlockPos key) {
        return GAUNTLET.contains(key);
    }

    /** Right-click on a table: back into a running duel, or the table's lobby. */
    public static void open(BlockPos table) {
        Minecraft mc = Minecraft.getInstance();
        RemoteDuel duel = DUEL_AT.get(table);
        if (duel != null && !duel.gui.isOver()) {
            mc.setScreen(new DuelScreen(duel.gui, table));
            return;
        }
        Net.toServer(new Packets.OpenTable(table));
        mc.setScreen(new TableScreen(table));
    }

    public static void onState(Packets.TableState state) {
        STATES.put(state.table(), state);
        if (Minecraft.getInstance().screen instanceof TableScreen ts && ts.table().equals(state.table())) {
            ts.refresh(state);
        }
    }

    /** Network thread: the tunnel has to exist before the first duel bytes arrive. */
    public static void onDuelStart(Packets.DuelStart msg) {
        RemoteDuel old = DUEL_AT.remove(msg.table());
        if (old != null) close(old);
        RemoteDuel duel = new RemoteDuel(data -> Net.toServer(new Packets.Tunnel(msg.session(), data)));
        DUELS.put(msg.session(), duel);
        DUEL_AT.put(msg.table(), duel);
        if (msg.gauntlet()) GAUNTLET.add(msg.table());
        else GAUNTLET.remove(msg.table());
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> DuelIntro.play(() -> mc.setScreen(new DuelScreen(duel.gui, msg.table()))));
    }

    public static void onTunnel(Packets.Tunnel msg) {
        RemoteDuel duel = DUELS.get(msg.session());
        if (duel != null) duel.receive(msg.data());
    }

    /** Back into whichever duel this player is in (Duel Gauntlet right-click in the air). */
    public static void reopenAny() {
        for (Map.Entry<BlockPos, RemoteDuel> e : DUEL_AT.entrySet()) {
            if (!e.getValue().gui.isOver()) {
                Minecraft.getInstance().setScreen(new DuelScreen(e.getValue().gui, e.getKey()));
                return;
            }
        }
    }

    /** Drops a finished duel's tunnel. */
    public static void forget(BlockPos table) {
        RemoteDuel duel = DUEL_AT.remove(table);
        if (duel != null) close(duel);
    }

    private static void close(RemoteDuel duel) {
        DUELS.values().remove(duel);
        duel.gui.abandon();
        duel.close();
    }

    /** Whether this duel is a Duel Gauntlet fight (false for Magic Table games). */
    public static boolean isGauntletDuel(dev.mtgcraft.engine.DuelGui gui) {
        for (Map.Entry<BlockPos, RemoteDuel> e : DUEL_AT.entrySet()) {
            if (e.getValue().gui == gui) return GAUNTLET.contains(e.getKey());
        }
        return false;
    }

    /** Client tick: tell the server once Card-Forge has finished loading here. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (!sentReady && mc.getConnection() != null && ForgeEngine.state() == ForgeEngine.State.READY) {
            Net.toServer(new Packets.EngineReady());
            sendAutoPass();
            sentReady = true;
            // The Boosters tab can now list every set: rebuild it.
            if (mc.player != null && mc.level != null) {
                var params = new net.minecraft.world.item.CreativeModeTab.ItemDisplayParameters(
                        mc.player.connection.enabledFeatures(), mc.player.canUseGameMasterBlocks(), mc.level.registryAccess());
                dev.mtgcraft.MtgCraft.BOOSTERS_TAB.get().buildContents(params);
                dev.mtgcraft.MtgCraft.TAB.get().buildContents(params);
                // The creative search was indexed before the sets (and their names) were known: index it again so
                // searching "Aetherdrift" finds its booster.
                net.minecraft.world.item.CreativeModeTabs.searchTab().buildContents(params);
            }
        }
    }

    /** Tells the server whether to auto-pass for this player (their client setting). */
    public static void sendAutoPass() {
        if (Minecraft.getInstance().getConnection() == null) return;
        boolean on;
        int skips;
        try {
            on = dev.mtgcraft.MtgClientConfig.AUTO_PASS.get();
            skips = dev.mtgcraft.MtgClientConfig.SKIP_PHASES.get() | dev.mtgcraft.MtgClientConfig.STOP_PHASES.get() << 16;
        } catch (IllegalStateException notLoaded) {
            on = true;
            skips = 0;
        }
        Net.toServer(new Packets.AutoPassPref(on, skips));
    }

    public static void loggedOut() {
        sentReady = false;
        STATES.clear();
        for (RemoteDuel d : DUELS.values()) {
            d.gui.abandon();
            d.close();
        }
        DUELS.clear();
        DUEL_AT.clear();
    }
}

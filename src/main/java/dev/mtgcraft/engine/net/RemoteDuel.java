package dev.mtgcraft.engine.net;

import dev.mtgcraft.engine.DuelGui;
import dev.mtgcraft.shadow.io.netty.buffer.ByteBuf;
import dev.mtgcraft.shadow.io.netty.channel.Channel;
import dev.mtgcraft.shadow.io.netty.channel.ChannelHandlerContext;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.gamemodes.net.CompatibleObjectDecoder;
import forge.gamemodes.net.CompatibleObjectEncoder;
import forge.gamemodes.net.GameProtocolHandler;
import forge.gamemodes.net.IRemote;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.ReplyPool;
import forge.gamemodes.net.client.IToServer;
import forge.gamemodes.net.client.NetGameController;
import forge.gamemodes.net.event.IdentifiableNetEvent;
import forge.gamemodes.net.event.NetEvent;
import forge.gui.interfaces.IGuiGame;
import forge.trackable.TrackableCollection;
import forge.trackable.TrackableObject;
import forge.trackable.TrackableTypes;
import forge.trackable.Tracker;

import java.util.EnumMap;

/**
 * Client side of a duel hosted on the Minecraft server. The server's calls arrive through the tunnel and are applied
 * to an ordinary {@link DuelGui}, so the duel screen doesn't care whether the game is local or remote. Clicks go
 * back through a Card-Forge {@link NetGameController}.
 *
 * <p>The tracker bookkeeping in {@link Handler#beforeCall} follows Card-Forge's GameClientHandler (GPL-3.0).
 */
public final class RemoteDuel {
    public final DuelGui gui = new DuelGui();
    private final ReplyPool replies = new ReplyPool();
    private final Tunnel tunnel;
    private final IToServer toServer = new ToServer();

    public RemoteDuel(Wire wire) {
        tunnel = new Tunnel("duel", new Handler(), wire);
    }

    /** Bytes from the server. */
    public void receive(byte[] data) {
        tunnel.receive(data);
    }

    public void close() {
        tunnel.close();
    }

    private final class ToServer implements IToServer {
        @Override
        public void send(NetEvent event) {
            Channel ch = tunnel.peer;
            CompatibleObjectEncoder encoder = ch.pipeline().get(CompatibleObjectEncoder.class);
            try {
                ByteBuf encoded = encoder.encodeToBuf(event, ch.alloc());
                ch.writeAndFlush(encoded);
            } catch (Exception e) {
                System.err.println("[MTGCraft] Could not send " + event + ": " + e);
            }
        }

        @Override
        public Object sendAndWait(IdentifiableNetEvent event) {
            replies.initialize(event.getId());
            send(event);
            return replies.get(event.getId());
        }
    }

    private final class Handler extends GameProtocolHandler<IGuiGame> {
        private Tracker tracker;

        Handler() {
            super(true);
        }

        @Override protected ReplyPool getReplyPool(ChannelHandlerContext ctx) { return replies; }
        @Override protected IRemote getRemote(ChannelHandlerContext ctx) { return toServer; }
        @Override protected IGuiGame getToInvoke(ChannelHandlerContext ctx) { return gui; }

        /** Questions block until the player answers, so they wait on a background thread, not the UI thread. */
        @Override
        protected boolean shouldDispatchToGuiThread(ProtocolMethod method) {
            if (!method.getReturnType().equals(Void.TYPE)
                    || method == ProtocolMethod.message || method == ProtocolMethod.showErrorDialog) {
                return false;
            }
            return super.shouldDispatchToGuiThread(method);
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void beforeCall(ChannelHandlerContext ctx, ProtocolMethod method, Object[] args) {
            switch (method) {
                case setGameView -> {
                    if (args.length > 0 && args[0] instanceof GameView gameView) {
                        if (tracker == null) {
                            tracker = new Tracker();
                            CompatibleObjectEncoder encoder = ctx.pipeline().get(CompatibleObjectEncoder.class);
                            if (encoder != null) encoder.setTracker(tracker);
                            CompatibleObjectDecoder decoder = ctx.pipeline().get(CompatibleObjectDecoder.class);
                            if (decoder != null) decoder.setTracker(tracker);
                            if (gameView.getGameLog() == null) gameView.initGameLog();
                        }
                        if (gameView.getTracker() == null) updateTrackers(new Object[]{gameView});
                    }
                }
                case openView -> {
                    gui.setNetGame();
                    TrackableCollection<PlayerView> mine = (TrackableCollection<PlayerView>) args[0];
                    for (PlayerView p : mine) {
                        if (p.getTracker() == null) p.setTracker(tracker);
                        gui.setOriginalGameController(p, new NetGameController(toServer));
                    }
                }
                default -> { }
            }
            if (tracker != null) {
                updateTrackers(args);
                if (method == ProtocolMethod.setGameView && args.length > 0 && args[0] instanceof GameView gv) {
                    gv.updateObjLookup();
                    refreshTrackerCardViews(gv);
                }
            }
        }

        private void updateTrackers(Object[] objs) {
            for (Object obj : objs) {
                if (obj instanceof TrackableObject t) {
                    if (t.getTracker() == null) {
                        t.setTracker(tracker);
                        EnumMap<?, ?> props = t.getProps();
                        if (props != null) {
                            for (Object prop : props.values()) updateTrackers(new Object[]{prop});
                        }
                    }
                } else if (obj instanceof TrackableCollection<?> c) {
                    for (Object o : c) updateTrackers(new Object[]{o});
                }
            }
        }

        /** Replaces stale tracker entries with the server's current card views (see GameClientHandler). */
        private void refreshTrackerCardViews(GameView gv) {
            if (gv.getPlayers() == null) return;
            for (PlayerView pv : gv.getPlayers()) {
                EnumMap<?, ?> props = pv.getProps();
                if (props == null) continue;
                for (Object value : props.values()) {
                    if (value instanceof TrackableCollection<?> collection) {
                        for (Object item : collection) {
                            if (item instanceof CardView cv) tracker.putObj(TrackableTypes.CardViewType, cv.getId(), cv);
                        }
                    }
                }
            }
        }
    }
}

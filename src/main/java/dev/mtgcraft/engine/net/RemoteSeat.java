package dev.mtgcraft.engine.net;

import dev.mtgcraft.shadow.io.netty.channel.ChannelHandlerContext;
import forge.gamemodes.net.GameProtocolHandler;
import forge.gamemodes.net.IRemote;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.ReplyPool;
import forge.gamemodes.net.server.RemoteClient;
import forge.gamemodes.net.server.RemoteClientGuiGame;
import forge.interfaces.IGameController;

import java.lang.reflect.Method;

/**
 * Server side of one remote player's seat. {@link #gui} is what the match talks to; it serialises every call and
 * ships it through the tunnel. The player's actions come back through the same tunnel and are applied to their
 * game controller.
 */
public final class RemoteSeat {
    private static final Method REPLY_POOL;
    static {
        try {
            // Package-private in Card-Forge; the server handler needs it to deliver the client's replies.
            REPLY_POOL = RemoteClient.class.getDeclaredMethod("getReplyPool");
            REPLY_POOL.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Tunnel tunnel;
    public final RemoteClient client;
    public final RemoteClientGuiGame gui;

    public RemoteSeat(String playerName, Wire wire) {
        tunnel = new Tunnel("seat", new Handler(), wire);
        client = new RemoteClient(tunnel.peer);
        client.setUsername(playerName);
        client.setIndex(0);
        gui = new RemoteClientGuiGame(client) {
            @Override
            public void showPromptMessage(forge.game.player.PlayerView playerView, String message, forge.game.card.CardView card) {
                super.showPromptMessage(playerView, message, card);
                // Nothing to do? Pass on the player's behalf instead of making them press OK.
                if (getGameController() instanceof forge.player.PlayerControllerHuman human) AutoPass.onPrompt(human);
            }
        };
    }

    /** Bytes from this player's client. */
    public void receive(byte[] data) {
        tunnel.receive(data);
    }

    public void close() {
        cancelPending();
        tunnel.close();
    }

    /**
     * Answers every question the game is still waiting on from this player with "no answer", so a game thread
     * blocked on a player who left (or conceded mid-choice) can carry on and finish.
     */
    public void cancelPending() {
        replyPool().cancelAll();
    }

    private ReplyPool replyPool() {
        try {
            return (ReplyPool) REPLY_POOL.invoke(client);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Runs the player's actions (select card, OK, concede...) against their controller. */
    private final class Handler extends GameProtocolHandler<IGameController> {
        Handler() {
            super(false);
        }

        @Override protected ReplyPool getReplyPool(ChannelHandlerContext ctx) { return replyPool(); }
        @Override protected IRemote getRemote(ChannelHandlerContext ctx) { return client; }
        @Override protected IGameController getToInvoke(ChannelHandlerContext ctx) { return gui.getGameController(); }

        @Override
        protected void beforeCall(ChannelHandlerContext ctx, ProtocolMethod method, Object[] args) {
            if (method == ProtocolMethod.requestResync) {
                gui.setResyncPending();
            }
        }
    }
}

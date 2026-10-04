package dev.mtgcraft.engine.net;

// Netty is relocated into the mod by engine/build.gradle.
import dev.mtgcraft.shadow.io.netty.bootstrap.Bootstrap;
import dev.mtgcraft.shadow.io.netty.bootstrap.ServerBootstrap;
import dev.mtgcraft.shadow.io.netty.buffer.ByteBuf;
import dev.mtgcraft.shadow.io.netty.buffer.Unpooled;
import dev.mtgcraft.shadow.io.netty.channel.Channel;
import dev.mtgcraft.shadow.io.netty.channel.ChannelHandler;
import dev.mtgcraft.shadow.io.netty.channel.ChannelHandlerContext;
import dev.mtgcraft.shadow.io.netty.channel.ChannelInboundHandlerAdapter;
import dev.mtgcraft.shadow.io.netty.channel.ChannelInitializer;
import dev.mtgcraft.shadow.io.netty.channel.DefaultEventLoopGroup;
import dev.mtgcraft.shadow.io.netty.channel.EventLoopGroup;
import dev.mtgcraft.shadow.io.netty.channel.local.LocalAddress;
import dev.mtgcraft.shadow.io.netty.channel.local.LocalChannel;
import dev.mtgcraft.shadow.io.netty.channel.local.LocalServerChannel;
import dev.mtgcraft.shadow.io.netty.handler.codec.serialization.ClassResolvers;
import forge.gamemodes.net.CompatibleObjectDecoder;
import forge.gamemodes.net.CompatibleObjectEncoder;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One end of a Card-Forge protocol connection whose bytes travel over a {@link Wire} instead of a TCP socket.
 *
 * <p>Card-Forge's own codec and handler run on an in-JVM Netty channel ({@code peer}); a second "bridge" channel is
 * connected to it and simply copies the encoded bytes out to the wire, and bytes from the wire back in. So the
 * whole network-play stack (serialisation, delta sync, replies) is reused unchanged, and Minecraft's own connection
 * carries the traffic: friends join through the server like any other mod, no extra port.
 */
final class Tunnel {
    /** Minecraft custom payloads are limited to 32 KiB client to server; stay well under. */
    static final int CHUNK = 30_000;

    private static final EventLoopGroup GROUP = new DefaultEventLoopGroup(2, r -> {
        Thread t = new Thread(r, "MTGCraft net");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger IDS = new AtomicInteger();

    final Channel peer;
    private final Channel bridge;
    private final Channel server;

    Tunnel(String tag, ChannelHandler protocolHandler, Wire wire) {
        LocalAddress address = new LocalAddress("mtgcraft-" + tag + "-" + IDS.incrementAndGet());
        CompletableFuture<Channel> accepted = new CompletableFuture<>();
        server = new ServerBootstrap()
                .group(GROUP)
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<LocalChannel>() {
                    @Override
                    protected void initChannel(LocalChannel ch) {
                        ch.pipeline().addLast(
                                new CompatibleObjectEncoder(null),
                                new CompatibleObjectDecoder(9766 * 1024, ClassResolvers.cacheDisabled(null)),
                                protocolHandler);
                        accepted.complete(ch);
                    }
                })
                .bind(address).syncUninterruptibly().channel();
        bridge = new Bootstrap()
                .group(GROUP)
                .channel(LocalChannel.class)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        ByteBuf buf = (ByteBuf) msg;
                        try {
                            while (buf.isReadable()) {
                                byte[] chunk = new byte[Math.min(CHUNK, buf.readableBytes())];
                                buf.readBytes(chunk);
                                wire.send(chunk);
                            }
                        } finally {
                            buf.release();
                        }
                    }
                })
                .connect(address).syncUninterruptibly().channel();
        peer = accepted.join();
    }

    /** Bytes that arrived from the other end. Thread-safe; order is kept. */
    void receive(byte[] data) {
        bridge.writeAndFlush(Unpooled.wrappedBuffer(data));
    }

    void close() {
        bridge.close();
        peer.close();
        server.close();
    }
}

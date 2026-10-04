package dev.mtgcraft.net;

import dev.mtgcraft.MtgCraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** The mod's network channel. Packet handling lives in {@link Packets}. */
public final class Net {
    private static final String VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MtgCraft.MODID, "main"), () -> VERSION, VERSION::equals, VERSION::equals);

    private Net() {}

    public static void register() {
        int id = 0;
        // Duel traffic runs on the network thread: it must stay in order and never wait for a game tick.
        CHANNEL.messageBuilder(Packets.Tunnel.class, id++)
                .encoder(Packets.Tunnel::write).decoder(Packets.Tunnel::read)
                .consumerNetworkThread(Packets.Tunnel::handle).add();
        CHANNEL.messageBuilder(Packets.DuelStart.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Packets.DuelStart::write).decoder(Packets.DuelStart::read)
                .consumerNetworkThread(Packets.DuelStart::handle).add();
        CHANNEL.messageBuilder(Packets.OpenTable.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Packets.OpenTable::write).decoder(Packets.OpenTable::read)
                .consumerMainThread(Packets.OpenTable::handle).add();
        CHANNEL.messageBuilder(Packets.EditTable.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Packets.EditTable::write).decoder(Packets.EditTable::read)
                .consumerMainThread(Packets.EditTable::handle).add();
        CHANNEL.messageBuilder(Packets.TableState.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Packets.TableState::write).decoder(Packets.TableState::read)
                .consumerMainThread(Packets.TableState::handle).add();
        CHANNEL.messageBuilder(Packets.PackOpened.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Packets.PackOpened::write).decoder(Packets.PackOpened::read)
                .consumerMainThread(Packets.PackOpened::handle).add();
        CHANNEL.messageBuilder(Packets.CollectionOp.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Packets.CollectionOp::write).decoder(Packets.CollectionOp::read)
                .consumerMainThread(Packets.CollectionOp::handle).add();
        CHANNEL.messageBuilder(Packets.OfferStarter.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Packets.OfferStarter::write).decoder(Packets.OfferStarter::read)
                .consumerMainThread(Packets.OfferStarter::handle).add();
        CHANNEL.messageBuilder(Packets.ChooseStarter.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Packets.ChooseStarter::write).decoder(Packets.ChooseStarter::read)
                .consumerMainThread(Packets.ChooseStarter::handle).add();
        CHANNEL.messageBuilder(Packets.Arena.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Packets.Arena::write).decoder(Packets.Arena::read)
                .consumerMainThread(Packets.Arena::handle).add();
        CHANNEL.messageBuilder(Packets.SetUniversalDeck.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Packets.SetUniversalDeck::write).decoder(Packets.SetUniversalDeck::read)
                .consumerMainThread(Packets.SetUniversalDeck::handle).add();
        CHANNEL.messageBuilder(Packets.EngineReady.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Packets.EngineReady::write).decoder(Packets.EngineReady::read)
                .consumerMainThread(Packets.EngineReady::handle).add();
    }

    /**
     * Sends to a player's client. Skips fake players (automation from other mods, test players), which have no
     * network connection and would otherwise crash the server tick.
     */
    public static void toPlayer(ServerPlayer player, Object msg) {
        if (player instanceof net.minecraftforge.common.util.FakePlayer || player.connection == null) return;
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
        } catch (NullPointerException noChannel) {
            // A connection without a channel: nobody to send to.
        }
    }

    public static void toServer(Object msg) {
        CHANNEL.sendToServer(msg);
    }
}

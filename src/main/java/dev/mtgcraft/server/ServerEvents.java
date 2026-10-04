package dev.mtgcraft.server;

import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.engine.ForgeEngine;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MtgCraft.MODID)
public final class ServerEvents {
    private ServerEvents() {}

    /** Games run on the server, so it loads its own copy of Card-Forge in the background. */
    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        TableSessions.serverStarted(event.getServer());
        ForgeEngine.start(event.getServer().getServerDirectory().toPath().resolve("mtgcraft"),
                () -> MtgCraft.class.getResourceAsStream("/mtgcraft_data/res.zip"), "Server");
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        TableSessions.serverStopping();
    }

    @SubscribeEvent
    public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) TableSessions.playerLeft(p);
    }
}

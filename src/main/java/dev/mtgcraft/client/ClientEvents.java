package dev.mtgcraft.client;

import dev.mtgcraft.MtgCraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MtgCraft.MODID, value = Dist.CLIENT)
public final class ClientEvents {
    private ClientEvents() {}

    /** Ticks left before the card engine starts loading in the background; -1 when nothing is pending. */
    private static int engineCountdown = -1;

    /**
     * Load the card engine shortly after a world is joined, so tables open instantly. Not right away: joining a
     * big pack (ATM9) already floods the client with data at login, and loading 30,000 cards on top of that
     * could stall it long enough to drop the connection. Opening a table or deck box still loads it at once.
     */
    @SubscribeEvent
    public static void loggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
        engineCountdown = 20 * 20;
    }

    @SubscribeEvent
    public static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        engineCountdown = -1;
        ClientTables.loggedOut();
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ClientTables.tick();
        // Only count down once the player is actually in the world (not on the loading-terrain screen).
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (engineCountdown > 0 && mc.player != null && mc.level != null && !(mc.screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen)) {
            if (--engineCountdown == 0) {
                engineCountdown = -1;
                ClientHooks.startEngine();
            }
        }
    }
}

package dev.mtgcraft;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;

/**
 * Connection errors ("Internal Exception: ..." on the disconnect screen) are only logged at debug level, which
 * packs like ATM9 don't write to disk. This sends Minecraft's connection logger, at debug, to its own small file
 * (logs/mtgcraft-network.log), so the full error and the mod behind it can be found.
 */
@Mod.EventBusSubscriber(modid = MtgCraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class NetDebugLog {
    private NetDebugLog() {}

    @SubscribeEvent
    public static void setup(FMLCommonSetupEvent event) {
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Configuration config = ctx.getConfiguration();
            FileAppender file = FileAppender.newBuilder()
                    .withFileName(FMLPaths.GAMEDIR.get().resolve("logs/mtgcraft-network.log").toString())
                    .withAppend(false)
                    .setName("MTGCraftNetwork")
                    .setLayout(PatternLayout.newBuilder().withPattern("[%d{HH:mm:ss}] [%t/%level] [%logger]: %msg%n%throwable").build())
                    .setConfiguration(config)
                    .build();
            file.start();
            config.addAppender(file);
            for (String name : new String[]{"net.minecraft.network.Connection", "net.minecraftforge.network"}) {
                LoggerConfig lc = new LoggerConfig(name, Level.DEBUG, true);
                lc.addAppender(file, Level.DEBUG, null);
                config.addLogger(name, lc);
            }
            ctx.updateLoggers();
        } catch (Throwable t) {
            // Logging setup is best effort; never break the game over it.
        }
    }
}

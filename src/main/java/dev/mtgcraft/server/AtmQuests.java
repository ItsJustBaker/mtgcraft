package dev.mtgcraft.server;

import dev.mtgcraft.MtgCraft;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Puts the MTGCraft chapter into the pack's quest book (FTB Quests, as in ATM9). FTB Quests reads its chapters from
 * config/ftbquests/quests/chapters, so the chapter file is copied there at startup (and refreshed on updates). The
 * duel quests are ticked off through hidden advancements (data/mtgcraft/advancements/quests, granted by Quests).
 */
@Mod.EventBusSubscriber(modid = MtgCraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class AtmQuests {
    private static final Logger LOG = LoggerFactory.getLogger("MTGCraft");

    private AtmQuests() {}

    @SubscribeEvent
    public static void setup(FMLCommonSetupEvent event) {
        if (!ModList.get().isLoaded("ftbquests")) return;
        Path chapters = FMLPaths.CONFIGDIR.get().resolve("ftbquests/quests/chapters");
        if (!Files.isDirectory(chapters)) return;
        try (InputStream in = AtmQuests.class.getResourceAsStream("/data/mtgcraft/ftbquests/mtgcraft.snbt")) {
            if (in == null) return;
            Files.copy(in, chapters.resolve("mtgcraft.snbt"), StandardCopyOption.REPLACE_EXISTING);
            LOG.info("MTGCraft quest chapter installed in {}", chapters);
        } catch (Exception e) {
            LOG.warn("Couldn't install the MTGCraft quest chapter: {}", e.toString());
        }
    }
}

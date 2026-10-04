package dev.mtgcraft.client;

import dev.mtgcraft.MtgCraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Lets each player use their own duel-start sound: put a file named {@code duel_start.ogg} in the game folder's
 * {@code mtgcraft} directory and it replaces the built-in sting on that computer only. It's loaded as an always-on
 * local resource pack, so nothing is shipped with the mod or sent to other players.
 */
@Mod.EventBusSubscriber(modid = MtgCraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class PersonalSounds {
    private PersonalSounds() {}

    @SubscribeEvent
    public static void addPacks(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) return;
        Path dir = FMLPaths.GAMEDIR.get().resolve("mtgcraft");
        Path clip = dir.resolve("duel_start.ogg");
        if (!Files.isRegularFile(clip)) return;
        Path pack = dir.resolve("personal_pack");
        try {
            Path target = pack.resolve("assets/mtgcraft/sounds/duel_start.ogg");
            Files.createDirectories(target.getParent());
            Files.copy(clip, target, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(pack.resolve("pack.mcmeta"),
                    "{\"pack\":{\"pack_format\":15,\"description\":\"MTGCraft personal sounds\"}}");
        } catch (IOException e) {
            System.err.println("[MTGCraft] Couldn't set up personal sounds: " + e);
            return;
        }
        event.addRepositorySource(consumer -> {
            Pack p = Pack.readMetaAndCreate("mtgcraft_personal", Component.literal("MTGCraft personal sounds"), true,
                    id -> new PathPackResources(id, pack, false), PackType.CLIENT_RESOURCES, Pack.Position.TOP,
                    PackSource.BUILT_IN);
            if (p != null) consumer.accept(p);
        });
    }
}

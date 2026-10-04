package dev.mtgcraft.client;

import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.engine.Packs;
import dev.mtgcraft.item.PackItem;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = MtgCraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {}

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // Packs and boxes pick their wrapper art from the theme (see the item models' overrides).
            ResourceLocation theme = new ResourceLocation(MtgCraft.MODID, "theme");
            ItemProperties.register(MtgCraft.BOOSTER_PACK.get(), theme, (stack, level, entity, seed) -> themeValue(stack));
            ItemProperties.register(MtgCraft.BOOSTER_BOX.get(), theme, (stack, level, entity, seed) -> themeValue(stack));
        });
    }

    private static float themeValue(net.minecraft.world.item.ItemStack stack) {
        Packs.Theme t = PackItem.theme(stack);
        return t == null ? 0 : (t.ordinal() + 1) / 100f;
    }
}

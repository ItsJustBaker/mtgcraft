package dev.mtgcraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.item.CardItem;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Hold the "View card" key (Z by default) to see the card in your hand at full size, or the card under the mouse in
 * an inventory.
 */
@Mod.EventBusSubscriber(modid = MtgCraft.MODID, value = Dist.CLIENT)
public final class HeldCardView {
    public static final KeyMapping VIEW = new KeyMapping("key.mtgcraft.view_card", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Z, "key.categories.mtgcraft");

    private HeldCardView() {}

    @Mod.EventBusSubscriber(modid = MtgCraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Keys {
        @SubscribeEvent
        public static void register(RegisterKeyMappingsEvent event) {
            event.register(VIEW);
        }
    }

    private static boolean held() {
        Minecraft mc = Minecraft.getInstance();
        if (VIEW.isUnbound()) return false;
        return InputConstants.isKeyDown(mc.getWindow().getWindow(), VIEW.getKey().getValue());
    }

    /** In the world: the card in your main hand (or off hand). */
    @SubscribeEvent
    public static void hud(RenderGuiOverlayEvent.Post event) {
        if (!event.getOverlay().id().equals(VanillaGuiOverlay.HOTBAR.id())) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || !held()) return;
        ItemStack s = mc.player.getMainHandItem();
        if (!(s.getItem() instanceof CardItem)) s = mc.player.getOffhandItem();
        if (!(s.getItem() instanceof CardItem) || CardItem.key(s) == null) return;
        show(event.getGuiGraphics(), s, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
    }

    /** In an inventory: the card under the mouse. */
    @SubscribeEvent
    public static void inventory(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen) || !held()) return;
        Slot slot = screen.getSlotUnderMouse();
        if (slot == null || !(slot.getItem().getItem() instanceof CardItem) || CardItem.key(slot.getItem()) == null) return;
        show(event.getGuiGraphics(), slot.getItem(), screen.width, screen.height);
    }

    private static void show(GuiGraphics g, ItemStack s, int w, int h) {
        float ch = h * 0.86f, cw = ch * 63f / 88f;
        float x = (w - cw) / 2, y = (h - ch) / 2;
        g.pose().pushPose();
        g.pose().translate(0, 0, 450);
        Theme.fillF(g, g.pose().last().pose(), x + 4, y + 6, x + cw + 4, y + ch + 6, 0x90000000);
        CardArt.draw(g, CardItem.key(s), CardItem.foil(s), x, y, cw, ch, 1f);
        g.pose().popPose();
    }
}

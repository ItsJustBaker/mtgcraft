package dev.mtgcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.mtgcraft.engine.Cards;
import dev.mtgcraft.engine.ForgeEngine;
import dev.mtgcraft.item.CardItem;
import forge.item.PaperCard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws a card item as a flat card with its real art: in hand, on the ground, in item frames and in inventory
 * slots. Until the art has downloaded it shows the card back.
 */
public final class CardItemRenderer extends BlockEntityWithoutLevelRenderer {
    private static CardItemRenderer instance;
    /** Card key to Card-Forge image key, so the card database is only asked once per card. */
    private static final Map<String, String> IMAGE_KEYS = new HashMap<>();

    private CardItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    public static CardItemRenderer get() {
        if (instance == null) instance = new CardItemRenderer();
        return instance;
    }

    /** The art texture for a card key, or null while unknown or loading. */
    public static ResourceLocation art(String cardKey, boolean large) {
        if (cardKey == null || ForgeEngine.state() != ForgeEngine.State.READY) return null;
        String image = IMAGE_KEYS.get(cardKey);
        if (image == null) {
            PaperCard pc = Cards.card(cardKey);
            if (pc == null) return null;
            image = Cards.imageKey(pc);
            IMAGE_KEYS.put(cardKey, image);
        }
        return CardTextures.get(image, large);
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext ctx, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        ResourceLocation front = art(CardItem.key(stack), ctx == ItemDisplayContext.FIXED || ctx.firstPerson()
                || ctx == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND || ctx == ItemDisplayContext.THIRD_PERSON_LEFT_HAND);
        if (front == null) front = Theme.CARD_BACK;
        // Card proportions 63x88 inside the 1x1 item space.
        float h = 0.94f, w = h * 63f / 88f;
        float x0 = 0.5f - w / 2, x1 = 0.5f + w / 2, y0 = 0.5f - h / 2, y1 = 0.5f + h / 2;
        float z = 0.5f;
        pose.pushPose();
        Matrix4f m = pose.last().pose();
        Matrix3f n = pose.last().normal();
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(front));
        quad(vc, m, n, x0, y0, x1, y1, z + 0.001f, false, light, overlay);
        VertexConsumer back = buffers.getBuffer(RenderType.entityCutoutNoCull(Theme.CARD_BACK));
        quad(back, m, n, x0, y0, x1, y1, z - 0.001f, true, light, overlay);
        pose.popPose();
    }

    private static void quad(VertexConsumer vc, Matrix4f m, Matrix3f n, float x0, float y0, float x1, float y1, float z,
                             boolean back, int light, int overlay) {
        float nz = back ? -1 : 1;
        if (!back) {
            vertex(vc, m, n, x0, y0, z, 0, 1, nz, light, overlay);
            vertex(vc, m, n, x1, y0, z, 1, 1, nz, light, overlay);
            vertex(vc, m, n, x1, y1, z, 1, 0, nz, light, overlay);
            vertex(vc, m, n, x0, y1, z, 0, 0, nz, light, overlay);
        } else {
            vertex(vc, m, n, x1, y0, z, 0, 1, nz, light, overlay);
            vertex(vc, m, n, x0, y0, z, 1, 1, nz, light, overlay);
            vertex(vc, m, n, x0, y1, z, 1, 0, nz, light, overlay);
            vertex(vc, m, n, x1, y1, z, 0, 0, nz, light, overlay);
        }
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Matrix3f n, float x, float y, float z, float u, float v,
                               float nz, int light, int overlay) {
        vc.vertex(m, x, y, z).color(255, 255, 255, 255).uv(u, v).overlayCoords(overlay).uv2(light).normal(n, 0, 0, nz).endVertex();
    }
}

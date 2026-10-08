package dev.mtgcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.mtgcraft.MtgCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.npc.Villager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** The Village Champion is drawn as a player wearing the Champion skin (when the skin is in the mod). */
@Mod.EventBusSubscriber(modid = MtgCraft.MODID, value = Dist.CLIENT)
public final class ChampionRenderer {
    private static final ResourceLocation SKIN = new ResourceLocation(MtgCraft.MODID, "textures/entity/champion.png");
    private static final ResourceLocation DUELIST_SKIN = new ResourceLocation(MtgCraft.MODID, "textures/entity/duelist.png");
    private static PlayerModel<Villager> model;
    private static Boolean hasSkin;

    private ChampionRenderer() {}

    /** The skin for a Champion or a Duelist (known by their names, which reach clients, unlike entity tags), else null. */
    private static ResourceLocation skin(Villager v) {
        if (!v.hasCustomName()) return null;
        String n = v.getCustomName().getString();
        return "Village Champion".equals(n) ? SKIN : "Village Duelist".equals(n) ? DUELIST_SKIN : null;
    }

    @SubscribeEvent
    public static void render(RenderLivingEvent.Pre<?, ?> event) {
        if (!(event.getEntity() instanceof Villager v)) return;
        ResourceLocation tex = skin(v);
        if (tex == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (hasSkin == null) hasSkin = mc.getResourceManager().getResource(SKIN).isPresent();
        if (!hasSkin) return;
        if (model == null) model = new PlayerModel<>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
        event.setCanceled(true);

        PoseStack ps = event.getPoseStack();
        float pt = event.getPartialTick();
        int light = event.getPackedLight();
        // The name above the head (the cancelled renderer would have drawn it).
        ps.pushPose();
        ps.translate(0, v.getBbHeight() + 0.5, 0);
        ps.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        ps.scale(-0.025f, -0.025f, 0.025f);
        Font font = mc.font;
        Component name = v.getDisplayName();
        font.drawInBatch(name, -font.width(name) / 2f, 0, -1, false, ps.last().pose(), event.getMultiBufferSource(),
                Font.DisplayMode.NORMAL, 0x40000000, light);
        ps.popPose();

        // The body, as LivingEntityRenderer draws a player.
        ps.pushPose();
        float bodyYaw = Mth.rotLerp(pt, v.yBodyRotO, v.yBodyRot);
        float headYaw = Mth.rotLerp(pt, v.yHeadRotO, v.yHeadRot) - bodyYaw;
        float pitch = Mth.lerp(pt, v.xRotO, v.getXRot());
        float limb = v.walkAnimation.position(pt), limbAmount = Math.min(1, v.walkAnimation.speed(pt));
        ps.mulPose(Axis.YP.rotationDegrees(180 - bodyYaw));
        ps.scale(-0.9375f, -0.9375f, 0.9375f);
        ps.translate(0, -1.501f, 0);
        model.attackTime = 0;
        model.riding = false;
        model.young = false;
        model.prepareMobModel(v, limb, limbAmount, pt);
        model.setupAnim(v, limb, limbAmount, v.tickCount + pt, headYaw, pitch);
        model.renderToBuffer(ps, event.getMultiBufferSource().getBuffer(model.renderType(tex)), light,
                LivingEntityRenderer.getOverlayCoords(v, 0), 1, 1, 1, 1);
        ps.popPose();
    }
}

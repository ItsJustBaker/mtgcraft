package dev.mtgcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.item.GauntletItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The Duel Gauntlet is worn, not held: it's drawn on the player's hand in third person (the held-item version is
 * hidden by its model's display transforms) and on a bare arm in first person.
 */
public final class GloveRenderer {
    private GloveRenderer() {}

    private static final net.minecraft.resources.ResourceLocation GLOVE_SKIN =
            new net.minecraft.resources.ResourceLocation(MtgCraft.MODID, "textures/entity/glove.png");

    /** Draws the glove on {@code arm} of {@code model}, whose arm pose is already set up. */
    static void renderOnArm(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player,
                            ItemStack glove, HumanoidArm arm, PlayerModel<AbstractClientPlayer> model) {
        boolean right = arm == HumanoidArm.RIGHT;
        if (!(glove.getItem() instanceof dev.mtgcraft.item.DuelDiskItem)) {
            // The gauntlet is worn like armour: painted over the hand on the arm's outer (sleeve) layer, so it follows
            // the arm exactly instead of looking like something held.
            net.minecraft.client.model.geom.ModelPart sleeve = right ? model.rightSleeve : model.leftSleeve;
            boolean visible = sleeve.visible;
            sleeve.visible = true;
            sleeve.render(pose, buffers.getBuffer(net.minecraft.client.renderer.RenderType.entityCutoutNoCull(GLOVE_SKIN)),
                    light, OverlayTexture.NO_OVERLAY);
            sleeve.visible = visible;
            return;
        }
        pose.pushPose();
        (right ? model.rightArm : model.leftArm).translateAndRotate(pose);
        // Arm boxes are 4px wide (3px for slim skins), centred 1px (0.5px) out from the arm's pivot.
        float cx = ("slim".equals(player.getModelName()) ? 0.5f : 1f) * (right ? -1 : 1);
        // The Duel Disk sits on the forearm (model centre at mid-forearm), its card blade sticking out on the outer side.
        pose.translate(cx / 16f, 4f / 16f, 0);
        if (right) pose.mulPose(Axis.YP.rotationDegrees(180));
        Minecraft.getInstance().getItemRenderer().renderStatic(player, glove, ItemDisplayContext.NONE, !right,
                pose, buffers, player.level(), light, OverlayTexture.NO_OVERLAY, player.getId());
        pose.popPose();
    }

    private static HumanoidArm armFor(AbstractClientPlayer player, InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
    }

    /** Third person: the glove on whichever hand holds it. */
    static final class Layer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        Layer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player, float limbSwing,
                           float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack stack = player.getItemInHand(hand);
                if (stack.getItem() instanceof GauntletItem) renderOnArm(pose, buffers, light, player, stack, armFor(player, hand), getParentModel());
            }
        }
    }

    @Mod.EventBusSubscriber(modid = MtgCraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Setup {
        private Setup() {}

        @SubscribeEvent
        public static void addLayers(EntityRenderersEvent.AddLayers event) {
            for (String skin : event.getSkins()) {
                if (event.getSkin(skin) instanceof PlayerRenderer r) r.addLayer(new Layer(r));
            }
        }
    }

    @Mod.EventBusSubscriber(modid = MtgCraft.MODID, value = Dist.CLIENT)
    public static final class FirstPerson {
        private FirstPerson() {}

        /** First person: a bare arm (as with an empty hand) wearing the glove, instead of a held item. */
        @SubscribeEvent
        public static void renderHand(RenderHandEvent event) {
            ItemStack stack = event.getItemStack();
            Minecraft mc = Minecraft.getInstance();
            if (!(stack.getItem() instanceof GauntletItem) || mc.player == null || mc.player.isInvisible()) return;
            if (!(mc.getEntityRenderDispatcher().getRenderer(mc.player) instanceof PlayerRenderer renderer)) return;
            event.setCanceled(true);
            HumanoidArm arm = armFor(mc.player, event.getHand());
            PoseStack p = event.getPoseStack();
            p.pushPose();
            // Vanilla's empty-hand arm transform (ItemInHandRenderer.renderPlayerArm).
            float swing = event.getSwingProgress(), equip = event.getEquipProgress();
            float f = arm == HumanoidArm.RIGHT ? 1 : -1;
            float s1 = Mth.sqrt(swing);
            p.translate(f * (-0.3f * Mth.sin(s1 * Mth.PI) + 0.64f), 0.4f * Mth.sin(s1 * Mth.TWO_PI) - 0.6f - equip * 0.6f,
                    -0.4f * Mth.sin(swing * Mth.PI) - 0.72f);
            p.mulPose(Axis.YP.rotationDegrees(f * 45));
            p.mulPose(Axis.YP.rotationDegrees(f * Mth.sin(s1 * Mth.PI) * 70));
            p.mulPose(Axis.ZP.rotationDegrees(f * Mth.sin(swing * swing * Mth.PI) * -20));
            p.translate(f * -1, 3.6f, 3.5f);
            p.mulPose(Axis.ZP.rotationDegrees(f * 120));
            p.mulPose(Axis.XP.rotationDegrees(200));
            p.mulPose(Axis.YP.rotationDegrees(f * -135));
            p.translate(f * 5.6f, 0, 0);
            if (arm == HumanoidArm.RIGHT) renderer.renderRightHand(p, event.getMultiBufferSource(), event.getPackedLight(), mc.player);
            else renderer.renderLeftHand(p, event.getMultiBufferSource(), event.getPackedLight(), mc.player);
            renderOnArm(p, event.getMultiBufferSource(), event.getPackedLight(), mc.player, stack, arm, renderer.getModel());
            p.popPose();
        }
    }
}

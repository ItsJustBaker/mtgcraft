package dev.mtgcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.engine.net.RemoteDuel;
import dev.mtgcraft.net.Packets;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.combat.CombatView;
import forge.game.player.PlayerView;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The 3D battlefield: every seat's permanents float as holographic cards between that player (or mob) and the
 * centre of the duel circle. Creatures in front, lands behind; tapped cards lie sideways; attackers glide toward
 * the centre. Life totals hover over each seat and a rune ring turns on the ground.
 */
@Mod.EventBusSubscriber(modid = MtgCraft.MODID, value = Dist.CLIENT)
public final class ArenaRenderer {
    private static final Map<BlockPos, Packets.Arena> ARENAS = new HashMap<>();
    /** Smoothed card positions by card id. */
    private static final Map<Integer, Vec3> POS = new HashMap<>();
    private static final Map<Integer, Float> ROLL = new HashMap<>();
    private static long lastFrame;

    private ArenaRenderer() {}

    public static void put(Packets.Arena arena) {
        ARENAS.put(arena.key(), arena);
    }

    public static boolean has(BlockPos key) {
        return ARENAS.containsKey(key);
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || ARENAS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            ARENAS.clear();
            return;
        }
        long now = System.currentTimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        Camera cam = event.getCamera();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        for (Iterator<Map.Entry<BlockPos, Packets.Arena>> it = ARENAS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, Packets.Arena> e = it.next();
            RemoteDuel duel = ClientTables.duelAt(e.getKey());
            if (duel == null || duel.gui.isOver()) {
                it.remove();
                continue;
            }
            try {
                renderArena(e.getValue(), duel, pose, cam, buffers, now, dt);
            } catch (RuntimeException concurrentEdit) {
                // Game state changed mid-frame; draw the next frame instead.
            }
        }
        buffers.endBatch();
    }

    private static void renderArena(Packets.Arena a, RemoteDuel duel, PoseStack pose, Camera cam,
                                    MultiBufferSource buffers, long now, float dt) {
        GameView gv = duel.gui.getGameView();
        Vec3 center = new Vec3(a.x(), Math.floor(a.y()), a.z());
        ring(pose, cam, buffers, center, a.radius(), a.boss(), now);
        if (gv == null) return;
        Minecraft mc = Minecraft.getInstance();
        Map<String, PlayerView> byName = new HashMap<>();
        for (PlayerView p : gv.getPlayers()) byName.put(p.getName(), p);
        CombatView combat = gv.getCombat();
        float scale = a.boss() ? 1.4f : 1f;
        float k = 1 - (float) Math.exp(-dt * 8);

        List<Packets.ArenaSeat> seats = a.seats();
        for (int i = 0; i < seats.size(); i++) {
            Packets.ArenaSeat seat = seats.get(i);
            PlayerView pv = byName.get(seat.name());
            if (pv == null) continue;
            Entity ent = seat.entity() >= 0 ? mc.level.getEntity(seat.entity()) : null;
            Vec3 anchor;
            if (ent != null) {
                anchor = ent.position();
            } else {
                double ang = i * Math.PI * 2 / seats.size();
                anchor = center.add(Math.cos(ang) * a.radius() * 0.8, 0, Math.sin(ang) * a.radius() * 0.8);
            }
            Vec3 dir = new Vec3(center.x - anchor.x, 0, center.z - anchor.z);
            if (dir.lengthSqr() < 0.01) dir = new Vec3(1, 0, 0);
            dir = dir.normalize();
            Vec3 right = new Vec3(-dir.z, 0, dir.x);
            Vec3 base = new Vec3(anchor.x, center.y, anchor.z).add(dir.scale(1.2 * scale));

            List<CardView> creatures = new ArrayList<>(), back = new ArrayList<>();
            if (pv.getBattlefield() != null) {
                for (CardView c : new ArrayList<>((java.util.Collection<CardView>) pv.getBattlefield())) {
                    if (c.getCurrentState().isCreature()) creatures.add(c);
                    else back.add(c);
                }
            }
            placeRow(back, base, right, 0.55 * scale, scale, combat, dir, pose, cam, buffers, now, k, false);
            placeRow(creatures, base.add(dir.scale(0.8 * scale)), right, 0.85 * scale, scale, combat, dir, pose, cam, buffers, now, k, true);

            // life over the seat
            Vec3 head = ent != null ? ent.position().add(0, ent.getBbHeight() + 0.55, 0) : base.add(0, 1.8, 0);
            label(pose, cam, buffers, head, "♥ " + pv.getLife(), pv.getLife() <= 5 ? 0xFFFF6060 : 0xFFFFFFFF);
        }
    }

    private static void placeRow(List<CardView> cards, Vec3 rowCenter, Vec3 right, double height, float scale,
                                 CombatView combat, Vec3 dir, PoseStack pose, Camera cam, MultiBufferSource buffers,
                                 long now, float k, boolean front) {
        int n = cards.size();
        if (n == 0) return;
        double w = 0.42 * scale, gap = 0.06 * scale;
        double maxSpan = 3.2 * scale;
        double step = n == 1 ? 0 : Math.min(w + gap, (maxSpan - w) / (n - 1));
        double start = -step * (n - 1) / 2;
        for (int i = 0; i < n; i++) {
            CardView c = cards.get(i);
            Vec3 target = rowCenter.add(right.scale(start + i * step)).add(0, height, 0);
            if (front && combat != null && combat.isAttacking(c)) target = target.add(dir.scale(1.0 * scale)).add(0, 0.15, 0);
            if (front && combat != null && combat.isBlocking(c)) target = target.add(dir.scale(0.5 * scale));
            Vec3 cur = POS.getOrDefault(c.getId(), target.add(0, -0.6, 0));
            cur = cur.add(target.subtract(cur).scale(k));
            POS.put(c.getId(), cur);
            float roll = ROLL.getOrDefault(c.getId(), 0f);
            roll += ((c.isTapped() ? 90f : 0f) - roll) * k;
            ROLL.put(c.getId(), roll);
            double bob = Math.sin((now + c.getId() * 371L) / 700.0) * 0.03;
            card(pose, cam, buffers, c, cur.add(0, bob, 0), w, w * 88 / 63, roll, combat != null && combat.isAttacking(c));
        }
    }

    /** One card, turned to face the camera, art on the front and card back behind, glowing like a hologram. */
    private static void card(PoseStack pose, Camera cam, MultiBufferSource buffers, CardView c, Vec3 at,
                             double w, double h, float roll, boolean attacking) {
        Vec3 camPos = cam.getPosition();
        pose.pushPose();
        pose.translate(at.x - camPos.x, at.y - camPos.y, at.z - camPos.z);
        pose.mulPose(Axis.YP.rotationDegrees(-cam.getYRot()));
        pose.mulPose(Axis.XP.rotationDegrees(-12));
        pose.mulPose(Axis.ZP.rotationDegrees(roll));
        Matrix4f m = pose.last().pose();
        float hw = (float) w / 2, hh = (float) h / 2;
        // halo
        VertexConsumer glow = buffers.getBuffer(RenderType.lightning());
        int halo = attacking ? 0x60FF5040 : 0x3050E0FF;
        quadColor(glow, m, -hw - 0.03f, -hh - 0.03f, hw + 0.03f, hh + 0.03f, -0.002f, halo);
        ResourceLocation art = null;
        String key = c.getCurrentState() == null ? null : c.getCurrentState().getImageKey();
        if (key != null && !c.isFaceDown()) art = CardTextures.get(key, false);
        if (art == null) art = Theme.CARD_BACK;
        VertexConsumer front = buffers.getBuffer(RenderType.entityTranslucentEmissive(art));
        quadTex(front, m, -hw, -hh, hw, hh, 0, 0xF0, false);
        VertexConsumer backFace = buffers.getBuffer(RenderType.entityTranslucentEmissive(Theme.CARD_BACK));
        quadTex(backFace, m, -hw, -hh, hw, hh, -0.001f, 0xF0, true);
        pose.popPose();
    }

    private static void quadTex(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1, float z, int alpha, boolean back) {
        int light = LightTexture.FULL_BRIGHT;
        int ov = OverlayTexture.NO_OVERLAY;
        if (!back) {
            vc.vertex(m, x0, y0, z).color(255, 255, 255, alpha).uv(0, 1).overlayCoords(ov).uv2(light).normal(0, 0, 1).endVertex();
            vc.vertex(m, x1, y0, z).color(255, 255, 255, alpha).uv(1, 1).overlayCoords(ov).uv2(light).normal(0, 0, 1).endVertex();
            vc.vertex(m, x1, y1, z).color(255, 255, 255, alpha).uv(1, 0).overlayCoords(ov).uv2(light).normal(0, 0, 1).endVertex();
            vc.vertex(m, x0, y1, z).color(255, 255, 255, alpha).uv(0, 0).overlayCoords(ov).uv2(light).normal(0, 0, 1).endVertex();
        } else {
            vc.vertex(m, x1, y0, z).color(255, 255, 255, alpha).uv(0, 1).overlayCoords(ov).uv2(light).normal(0, 0, -1).endVertex();
            vc.vertex(m, x0, y0, z).color(255, 255, 255, alpha).uv(1, 1).overlayCoords(ov).uv2(light).normal(0, 0, -1).endVertex();
            vc.vertex(m, x0, y1, z).color(255, 255, 255, alpha).uv(1, 0).overlayCoords(ov).uv2(light).normal(0, 0, -1).endVertex();
            vc.vertex(m, x1, y1, z).color(255, 255, 255, alpha).uv(0, 0).overlayCoords(ov).uv2(light).normal(0, 0, -1).endVertex();
        }
    }

    private static void quadColor(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1, float z, int argb) {
        int a = argb >>> 24, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        vc.vertex(m, x0, y0, z).color(r, g, b, a).endVertex();
        vc.vertex(m, x1, y0, z).color(r, g, b, a).endVertex();
        vc.vertex(m, x1, y1, z).color(r, g, b, a).endVertex();
        vc.vertex(m, x0, y1, z).color(r, g, b, a).endVertex();
    }

    /** A turning ring of light on the ground (purple for bosses). */
    private static void ring(PoseStack pose, Camera cam, MultiBufferSource buffers, Vec3 center, float radius, boolean boss, long now) {
        Vec3 camPos = cam.getPosition();
        pose.pushPose();
        pose.translate(center.x - camPos.x, center.y + 0.06 - camPos.y, center.z - camPos.z);
        pose.mulPose(Axis.YP.rotationDegrees((now % 36000) / 100f));
        Matrix4f m = pose.last().pose();
        VertexConsumer vc = buffers.getBuffer(RenderType.lightning());
        int color = boss ? 0x90C040FF : 0x8040E0FF;
        int segs = 64;
        for (int ring = 0; ring < 2; ring++) {
            float r = ring == 0 ? radius : radius * 0.82f;
            float t = ring == 0 ? 0.09f : 0.05f;
            for (int i = 0; i < segs; i++) {
                if (ring == 1 && i % 4 == 3) continue; // dashed inner ring
                double a0 = i * Math.PI * 2 / segs, a1 = (i + 1) * Math.PI * 2 / segs;
                float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
                int a = color >>> 24, rr = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
                vc.vertex(m, c0 * (r - t), 0, s0 * (r - t)).color(rr, g, b, a).endVertex();
                vc.vertex(m, c0 * (r + t), 0, s0 * (r + t)).color(rr, g, b, a).endVertex();
                vc.vertex(m, c1 * (r + t), 0, s1 * (r + t)).color(rr, g, b, a).endVertex();
                vc.vertex(m, c1 * (r - t), 0, s1 * (r - t)).color(rr, g, b, a).endVertex();
            }
        }
        pose.popPose();
    }

    private static void label(PoseStack pose, Camera cam, MultiBufferSource buffers, Vec3 at, String text, int color) {
        Font font = Minecraft.getInstance().font;
        Vec3 camPos = cam.getPosition();
        pose.pushPose();
        pose.translate(at.x - camPos.x, at.y - camPos.y, at.z - camPos.z);
        pose.mulPose(cam.rotation());
        pose.scale(-0.03f, -0.03f, 0.03f);
        float x = -font.width(text) / 2f;
        font.drawInBatch(text, x, 0, color, false, pose.last().pose(), buffers, Font.DisplayMode.SEE_THROUGH,
                0x60000000, LightTexture.FULL_BRIGHT);
        pose.popPose();
    }
}

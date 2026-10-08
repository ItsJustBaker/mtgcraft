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
import forge.game.zone.ZoneType;
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
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The duel field, Yu-Gi-Oh style. Each duelist stands on a glowing podium; in front of them a duel mat lies on the
 * floor with card zones: lands and other permanents in the back row, creatures in the front row (each projecting a
 * hologram of itself), the deck as a face-down stack, the graveyard with its top card, and the command zone.
 * Opponents' hands float face-down behind them. An energy barrier rings the arena, a glowing grid covers the floor,
 * and the fog darkens inside so the field glows. Tapped cards turn sideways; attackers stride to the centre.
 */
@Mod.EventBusSubscriber(modid = MtgCraft.MODID, value = Dist.CLIENT)
public final class ArenaRenderer {
    private static final Map<BlockPos, Packets.Arena> ARENAS = new HashMap<>();
    /** Smoothed floor positions, holo heights and in-plane turns by card id. */
    private static final Map<Integer, Vec3> POS = new HashMap<>();
    private static final Map<Integer, Float> TURN = new HashMap<>();
    private static long lastFrame;

    private static final int GLOW = 0x4050E0FF, GLOW_BOSS = 0x60C040FF, GOLD = 0x90FFD050;

    private ArenaRenderer() {}

    public static void put(Packets.Arena arena) {
        if (!ARENAS.containsKey(arena.key())) OPENED.put(arena.key(), System.currentTimeMillis());
        ARENAS.put(arena.key(), arena);
    }

    /** When each arena first appeared: the world fades away over {@link #PULL_MS} from then. */
    private static final Map<BlockPos, Long> OPENED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final float PULL_MS = 1500f;

    /** 0 to 1: how far the world has been pulled away around this arena (eased). */
    private static float pull(Packets.Arena a) {
        Long t = OPENED.get(a.key());
        float x = t == null ? 1f : Math.min(1f, (System.currentTimeMillis() - t) / PULL_MS);
        return x * x * (3 - 2 * x);
    }

    /** Duel arenas (not the small table-top ones) become a pocket of space. */
    private static boolean voidArena(Packets.Arena a) {
        return a.radius() >= 3.5f;
    }

    public static boolean has(BlockPos key) {
        return ARENAS.containsKey(key);
    }

    /** The entity sitting in the named seat of this duel's arena (a player or the mob), or null. */
    public static Entity entityFor(BlockPos key, String name) {
        Packets.Arena a = ARENAS.get(key);
        Minecraft mc = Minecraft.getInstance();
        if (a == null || mc.level == null) return null;
        for (Packets.ArenaSeat seat : a.seats()) {
            if (seat.name().equals(name) && seat.entity() >= 0) return mc.level.getEntity(seat.entity());
        }
        return null;
    }

    /** The arena the camera is standing in, if any (for the fog). */
    private static Packets.Arena around(Vec3 at) {
        for (Packets.Arena a : ARENAS.values()) {
            double dx = at.x - a.x(), dz = at.z - a.z();
            if (dx * dx + dz * dz < (a.radius() + 3) * (a.radius() + 3) && Math.abs(at.y - a.y()) < 12) return a;
        }
        return null;
    }

    // ------------------------------------------------------------------ fog: the world dims inside the arena

    @SubscribeEvent
    public static void fogColor(ViewportEvent.ComputeFogColor event) {
        Packets.Arena a = around(event.getCamera().getPosition());
        if (a == null) return;
        // Deep space; a boss arena glows a little purple.
        float mix = voidArena(a) ? 0.92f + 0.08f * pull(a) : 0.92f;
        float r = a.boss() ? 0.10f : 0.01f, g = 0.01f, b = a.boss() ? 0.14f : 0.05f;
        event.setRed(event.getRed() * (1 - mix) + r * mix);
        event.setGreen(event.getGreen() * (1 - mix) + g * mix);
        event.setBlue(event.getBlue() * (1 - mix) + b * mix);
    }

    @SubscribeEvent
    public static void fog(ViewportEvent.RenderFog event) {
        Packets.Arena a = around(event.getCamera().getPosition());
        if (a == null) return;
        // The world beyond the arena is pulled away: the fog closes in from normal view distance to just past the stars.
        float p = voidArena(a) ? pull(a) : 0;
        float near = a.radius() * (voidArena(a) ? 2.6f : 1.1f), far = a.radius() * (voidArena(a) ? 4.2f : 3.2f);
        event.setNearPlaneDistance(near + (event.getNearPlaneDistance() - near) * (1 - p));
        event.setFarPlaneDistance(far + (event.getFarPlaneDistance() - far) * (1 - p));
        event.setCanceled(true);
    }

    // ------------------------------------------------------------------ the void: the world is pulled away into space

    private static final int STARS = 320;
    private static final net.minecraft.resources.ResourceLocation WHITE =
            new net.minecraft.resources.ResourceLocation(MtgCraft.MODID, "textures/misc/white.png");
    /** How long the dome takes to close around the arena. */
    private static final float SHIFT_MS = 1600f;

    /** 0 to 1: how far the dome has closed (linear; the tiles ease themselves). */
    private static float shift(Packets.Arena a) {
        Long t = OPENED.get(a.key());
        return t == null ? 1f : Math.min(1f, (System.currentTimeMillis() - t) / SHIFT_MS);
    }

    /**
     * The world is pulled away: a dome of deep space closes around the stage, its tiles flipping in from the floor
     * upward in a sweeping wave with a bright violet edge, then twinkling stars. It is drawn as solid entity geometry
     * (full-bright), which shader packs render like any mob, and which hides clouds and terrain behind it.
     */
    // ------------------------------------------------------------------ shader packs

    private static boolean shaderCache;
    private static long shaderCheckedAt;

    /** Whether an Oculus/Iris shader pack is on (checked through Iris's public API, at most once a second). */
    static boolean shaders() {
        long now = System.currentTimeMillis();
        if (now - shaderCheckedAt > 1000) {
            shaderCheckedAt = now;
            try {
                Object api = Class.forName("net.irisshaders.iris.api.v0.IrisApi").getMethod("getInstance").invoke(null);
                shaderCache = (Boolean) api.getClass().getMethod("isShaderPackInUse").invoke(api);
            } catch (Throwable notInstalled) {
                shaderCache = false;
            }
        }
        return shaderCache;
    }

    /** Glow geometry (additive). Shader packs turn it into see-through holes, so with one on it isn't drawn. */
    private static VertexConsumer glow(MultiBufferSource buffers) {
        return shaders() ? NO_DRAW : buffers.getBuffer(RenderType.lightning());
    }

    /** Card faces: glowing normally; solid cutouts with a shader pack (which mangles the glowing kind). */
    private static RenderType cardType(net.minecraft.resources.ResourceLocation tex) {
        return shaders() ? RenderType.entityCutoutNoCull(tex) : RenderType.entityTranslucentEmissive(tex);
    }

    private static final VertexConsumer NO_DRAW = new VertexConsumer() {
        @Override public VertexConsumer vertex(double x, double y, double z) { return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer uv(float u, float v) { return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { return this; }
        @Override public void endVertex() { }
        @Override public void defaultColor(int r, int g, int b, int a) { }
        @Override public void unsetDefaultColor() { }
    };

    /** Called from {@link #render} before its single flush, exactly like the stage floor (shader packs place it right). */
    private static void drawVoid(RenderLevelStageEvent event, MultiBufferSource buffers) {
        Camera cam = event.getCamera();
        Packets.Arena a = around(cam.getPosition());
        if (a == null || !voidArena(a)) return;
        float p = shift(a);
        long now = System.currentTimeMillis();
        Vec3 c = new Vec3(a.x(), a.y(), a.z()).subtract(cam.getPosition());
        Matrix4f m = event.getPoseStack().last().pose();
        RenderType type = RenderType.entityTranslucent(WHITE);
        VertexConsumer vc = buffers.getBuffer(type);
        double r = a.radius() * 1.45 + 2;

        int lat = 16, lon = 40;
        java.util.Random tiles = new java.util.Random(a.key().asLong() * 31);
        for (int i = 0; i < lat; i++) {
            double t0 = Math.PI * i / lat - Math.PI / 2, t1 = Math.PI * (i + 1) / lat - Math.PI / 2;
            for (int j = 0; j < lon; j++) {
                // Each tile has its moment: lower rows first (a wave rising from the floor), a little jitter.
                float when = 0.75f * (i / (float) lat) + 0.2f * tiles.nextFloat();
                float k = Math.min(1, Math.max(0, (p - when) / 0.12f));
                if (k <= 0) continue;
                double f0 = 2 * Math.PI * j / lon, f1 = 2 * Math.PI * (j + 1) / lon;
                // A tile grows from its centre as it flips in.
                double tm = (t0 + t1) / 2, fm = (f0 + f1) / 2, ease = k * k * (3 - 2 * k);
                double a0 = tm + (t0 - tm) * ease, a1 = tm + (t1 - tm) * ease, b0 = fm + (f0 - fm) * ease, b1 = fm + (f1 - fm) * ease;
                Vec3 v00 = sph(c, r, a0, b0), v01 = sph(c, r, a0, b1), v11 = sph(c, r, a1, b1), v10 = sph(c, r, a1, b0);
                int col = k < 1 ? mix(0xB070FF, sky(tm, a.boss()), ease) : sky(tm, a.boss());
                Vec3 n = c.subtract(sph(c, r, tm, fm)).normalize();
                quad(vc, m, v00, v01, v11, v10, col, n);
                quad(vc, m, v10, v11, v01, v00, col, n);
            }
        }
        // Stars, once the dome is mostly closed: small bright tiles just inside it, twinkling by size.
        if (p > 0.6f) {
            float sp = Math.min(1, (p - 0.6f) / 0.4f);
            org.joml.Vector3f left = cam.getLeftVector(), up = cam.getUpVector();
            java.util.Random rng = new java.util.Random(a.key().asLong());
            double sr = r * 0.97;
            for (int i = 0; i < STARS; i++) {
                double yy = rng.nextDouble() * 1.2 - 0.2, ang = rng.nextDouble() * Math.PI * 2 + now / 120000.0;
                double rr = Math.sqrt(Math.max(0, 1 - yy * yy));
                Vec3 s = c.add(Math.cos(ang) * rr * sr, yy * sr, Math.sin(ang) * rr * sr);
                float tw = 0.55f + 0.45f * (float) Math.sin(now / (250.0 + rng.nextInt(900)) + i);
                float size = sp * tw * (float) (0.05 + rng.nextDouble() * rng.nextDouble() * 0.14) * (float) (r / 8);
                int tint = rng.nextInt(6);
                int col = tint == 0 ? 0xA8C4FF : tint == 1 ? 0xFFE2A8 : tint == 2 ? 0xE0B8FF : 0xFFFFFF;
                Vec3 l = new Vec3(left.x() * size, left.y() * size, left.z() * size), u = new Vec3(up.x() * size, up.y() * size, up.z() * size);
                Vec3 n = c.subtract(s).normalize();
                quad(vc, m, s.subtract(l).subtract(u), s.add(l).subtract(u), s.add(l).add(u), s.subtract(l).add(u), col, n);
                quad(vc, m, s.subtract(l).add(u), s.add(l).add(u), s.add(l).subtract(u), s.subtract(l).subtract(u), col, n);
            }
        }
    }

    private static Vec3 sph(Vec3 c, double r, double theta, double phi) {
        return c.add(Math.cos(theta) * Math.cos(phi) * r, Math.sin(theta) * r, Math.cos(theta) * Math.sin(phi) * r);
    }

    /** Space colour by height on the dome (theta -pi/2 at the bottom to pi/2 overhead). */
    private static int sky(double theta, boolean boss) {
        float h = (float) Math.max(0, Math.sin(theta));
        int r = (int) ((boss ? 46 : 28) * (1 - h) + 5 * h), g = (int) (12 * (1 - h) + 7 * h), b = (int) (58 * (1 - h) + 26 * h);
        return r << 16 | g << 8 | b;
    }

    private static int mix(int from, int to, double t) {
        int r = (int) ((from >> 16 & 255) * (1 - t) + (to >> 16 & 255) * t);
        int g = (int) ((from >> 8 & 255) * (1 - t) + (to >> 8 & 255) * t);
        int b = (int) ((from & 255) * (1 - t) + (to & 255) * t);
        return r << 16 | g << 8 | b;
    }

    private static void quad(VertexConsumer vc, Matrix4f m, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int rgb, Vec3 n) {
        for (Vec3 v : new Vec3[]{a, b, c, d}) {
            vc.vertex(m, (float) v.x, (float) v.y, (float) v.z).color(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, 255)
                    .uv(0.5f, 0.5f).overlayCoords(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                    .uv2(0xF000F0).normal(0, 1, 0).endVertex();
        }
    }

    // ------------------------------------------------------------------ the field

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
                // Game state changed mid-frame; the next frame draws it.
            }
        }
        drawVoid(event, buffers);
        buffers.endBatch();
    }

    private static void renderArena(Packets.Arena a, RemoteDuel duel, PoseStack pose, Camera cam,
                                    MultiBufferSource buffers, long now, float dt) {
        Minecraft mc = Minecraft.getInstance();
        GameView gv = duel.gui.getGameView();
        Vec3 center = new Vec3(a.x(), a.y(), a.z());
        // A table arena (small radius) is drawn on the table top at a smaller scale.
        float s = a.radius() < 3.5f ? 0.38f : a.boss() ? 1.25f : 1f;
        int glow = a.boss() ? GLOW_BOSS : GLOW;
        boolean table = a.radius() < 3.5f;
        if (!table) {
            floor(pose, cam, buffers, center, a.radius(), glow, now);
            barrier(pose, cam, buffers, center, a.radius() + 0.6f, a.boss() ? 9 : 6, glow, now);
        }
        if (gv == null) return;
        Map<String, PlayerView> byName = new HashMap<>();
        for (PlayerView p : gv.getPlayers()) byName.put(p.getName(), p);
        CombatView combat = gv.getCombat();
        float k = 1 - (float) Math.exp(-dt * 8);
        PlayerView me = duel.gui.me();

        CENTER_NOW = center.add(0, 1.6 * s, 0);
        stack(gv, center, s, pose, cam, buffers, now);

        List<Packets.ArenaSeat> seats = a.seats();
        for (int i = 0; i < seats.size(); i++) {
            Packets.ArenaSeat seat = seats.get(i);
            PlayerView pv = byName.get(seat.name());
            if (pv == null) continue;
            Entity ent = seat.entity() >= 0 ? mc.level.getEntity(seat.entity()) : null;
            // The field runs from this seat's spot on the ring toward the centre.
            Vec3 dirTo;
            if (ent != null && ent.position().distanceToSqr(center) > 0.25) {
                dirTo = new Vec3(ent.getX() - center.x, 0, ent.getZ() - center.z).normalize();
            } else {
                double ang = i * Math.PI * 2 / seats.size();
                dirTo = new Vec3(Math.cos(ang), 0, Math.sin(ang));
            }
            Vec3 f = dirTo.scale(-1);                 // toward the centre
            Vec3 r = new Vec3(-f.z, 0, f.x);          // the seat's right hand
            Vec3 spot = center.add(dirTo.scale(a.radius() * 0.9));
            // Big mobs: the podium fits their footprint and their field starts past their body.
            double body = ent == null ? 0.6 : Math.max(0.6, ent.getBbWidth() * 0.5 + 0.2);
            if (!table && ent != null) podium(pose, cam, buffers, ent.position(), (float) Math.max(s, body / 0.75), glow, pv.getLife() <= 5, now);

            double cw = 0.66 * s, ch = cw * 88 / 63;
            double y = center.y + (table ? 0.02 : 0.03);
            Vec3 back = new Vec3(spot.x, y, spot.z).add(f.scale(Math.max(1.0 * s, body + 0.4) + ch / 2));
            Vec3 front = back.add(f.scale(ch + 0.3 * s));
            boolean fourWay = seats.size() > 2;
            int slots = fourWay ? 4 : 5;

            List<CardView> creatures = new ArrayList<>(), others = new ArrayList<>();
            for (CardView c : cards(pv, ZoneType.Battlefield)) {
                if (c.getCurrentState() != null && c.getCurrentState().isCreature()) creatures.add(c);
                else others.add(c);
            }
            mat(pose, cam, buffers, back, front, r, f, cw, ch, slots, glow);
            row(others, back, r, f, cw, ch, slots, s, combat, false, pose, cam, buffers, now, k);
            row(creatures, front, r, f, cw, ch, slots, s, combat, true, pose, cam, buffers, now, k);

            // Deck (face down, height = cards left), graveyard (top card up) on the right; command zone on the left.
            double side = slots / 2.0 * (cw + 0.12 * s) + cw * 0.9;
            Vec3 deckAt = back.add(r.scale(side));
            Vec3 gyAt = front.add(r.scale(side));
            Vec3 cmdAt = back.add(r.scale(-side));
            int lib = pv.getZoneSize(ZoneType.Library);
            int layers = Math.min(14, (lib + 4) / 5);
            for (int l = 0; l < layers; l++) {
                flat(pose, cam, buffers, null, deckAt.add(0, 0.012 * l * s, 0), r, f, cw, ch, false, 0xF0);
            }
            frame(pose, cam, buffers, deckAt, r, f, cw, ch, GOLD);
            label(pose, cam, buffers, deckAt.add(0, 0.25 * s + layers * 0.012 * s, 0), "Deck " + lib, 0xFFE0E8FF, s);
            List<CardView> gy = cards(pv, ZoneType.Graveyard);
            frame(pose, cam, buffers, gyAt, r, f, cw, ch, 0x60A0A0A0);
            if (!gy.isEmpty()) {
                flat(pose, cam, buffers, gy.get(gy.size() - 1), gyAt.add(0, 0.01, 0), r, f, cw, ch, false, 0xE0);
                label(pose, cam, buffers, gyAt.add(0, 0.25 * s, 0), "Graveyard " + gy.size(), 0xFFC8C8C8, s);
            }
            // Exile: a swirling void beside the front row, swallowing the last card exiled.
            Vec3 exAt = front.add(r.scale(-side));
            List<CardView> ex = cards(pv, ZoneType.Exile);
            voidPortal(pose, cam, buffers, exAt, (float) (cw * 0.75), now, a.boss());
            if (!ex.isEmpty()) {
                double spin = now / 700.0;
                double shrink = 0.55 + 0.1 * Math.sin(now / 500.0);
                Vec3 rr = new Vec3(Math.cos(spin), 0, Math.sin(spin)), ff = new Vec3(-rr.z, 0, rr.x);
                flat(pose, cam, buffers, ex.get(ex.size() - 1), exAt.add(0, 0.02, 0), rr, ff, cw * shrink, ch * shrink, false, 0xB0);
                label(pose, cam, buffers, exAt.add(0, 0.25 * s, 0), "Exile " + ex.size(), 0xFFD0A0FF, s);
            }
            List<CardView> cmd = cards(pv, ZoneType.Command);
            if (!cmd.isEmpty()) {
                frame(pose, cam, buffers, cmdAt, r, f, cw, ch, GOLD);
                flat(pose, cam, buffers, cmd.get(0), cmdAt.add(0, 0.01, 0), r, f, cw, ch, false, 0xF0);
                label(pose, cam, buffers, cmdAt.add(0, 0.25 * s, 0), "Commander", 0xFFFFD050, s);
            }

            // Hands float behind opponents, face down.
            if (ent != null && (me == null || pv.getId() != me.getId())) {
                int hand = pv.getZoneSize(ZoneType.Hand);
                Vec3 behind = ent.position().add(dirTo.scale(body + 0.3)).add(0, ent.getBbHeight() + 0.1, 0);
                for (int h = 0; h < Math.min(hand, 10); h++) {
                    double off = (h - (Math.min(hand, 10) - 1) / 2.0) * 0.22 * Math.max(0.6, s);
                    upright(pose, cam, buffers, null, behind.add(r.scale(off)).add(0, Math.abs(off) * -0.15, 0),
                            0.3 * Math.max(0.6, s), (float) (off * 25), 0xC0);
                }
            }
            Vec3 head = ent != null ? ent.position().add(0, ent.getBbHeight() + (ent instanceof net.minecraft.world.entity.player.Player ? 0.9 : 0.6), 0)
                    : spot.add(0, 1.6 * s, 0);
            label(pose, cam, buffers, head, "♥ " + pv.getLife(), pv.getLife() <= 5 ? 0xFFFF6060 : 0xFFFFFFFF, Math.max(0.8f, s));
        }
    }

    private static List<CardView> cards(PlayerView pv, ZoneType zone) {
        Collection<CardView> c = (Collection<CardView>) pv.getCards(zone);
        return c == null ? List.of() : new ArrayList<>(c);
    }

    /** One row of zones. Creatures also project an upright hologram of themselves above their card. */
    private static void row(List<CardView> cards, Vec3 rowCenter, Vec3 r, Vec3 f, double cw, double ch, int slots,
                            float s, CombatView combat, boolean creatures, PoseStack pose, Camera cam,
                            MultiBufferSource buffers, long now, float k) {
        int n = cards.size();
        if (n == 0) return;
        double span = Math.max(slots, n) * (cw + 0.12 * s);
        double step = n == 1 ? 0 : Math.min(cw + 0.12 * s, (span - cw) / (n - 1));
        double start = -step * (n - 1) / 2;
        for (int i = 0; i < n; i++) {
            CardView c = cards.get(i);
            boolean attacking = combat != null && combat.isAttacking(c);
            boolean blocking = combat != null && combat.isBlocking(c);
            Vec3 target = rowCenter.add(r.scale(start + i * step));
            if (attacking) target = target.add(f.scale(1.6 * s));
            if (blocking) target = target.add(f.scale(0.7 * s));
            // A card that just arrived flies out from the middle of the field, where it was cast.
            Vec3 cur = POS.getOrDefault(c.getId(), CENTER_NOW != null ? CENTER_NOW : target.add(0, 0.6, 0));
            cur = cur.add(target.subtract(cur).scale(k));
            POS.put(c.getId(), cur);
            float turn = TURN.getOrDefault(c.getId(), 0f);
            turn += ((c.isTapped() ? 1f : 0f) - turn) * k;
            TURN.put(c.getId(), turn);
            flat(pose, cam, buffers, c, cur, r, f, cw, ch, turn > 0.5f, 0xF8);
            int edge = attacking ? 0xC0FF5040 : blocking ? 0xC0FFD050 : 0;
            if (edge != 0) frame(pose, cam, buffers, cur, r, f, turn > 0.5f ? ch : cw, turn > 0.5f ? cw : ch, edge);
            if (creatures && !c.isTapped()) {
                // the monster rises out of its card
                double bob = Math.sin((now + c.getId() * 311L) / 600.0) * 0.05 * s;
                Vec3 holo = cur.add(0, (attacking ? 1.25 : 0.95) * s + bob, 0);
                beam(pose, cam, buffers, cur, holo, attacking ? 0x50FF6040 : 0x3050E0FF);
                upright(pose, cam, buffers, c, holo, 0.55 * s, 0, 0xB8);
            }
        }
    }

    /** The middle of the arena being drawn (where newly played cards come from). */
    private static Vec3 CENTER_NOW;

    /**
     * Spells and abilities on the stack float above the centre of the field, big enough to read, the newest on top,
     * slowly turning so everyone around the ring can see them.
     */
    private static void stack(GameView gv, Vec3 center, float s, PoseStack pose, Camera cam, MultiBufferSource buffers, long now) {
        List<forge.game.spellability.StackItemView> items;
        try {
            items = new ArrayList<>((Collection<forge.game.spellability.StackItemView>) gv.getStack());
        } catch (RuntimeException concurrentEdit) {
            return;
        }
        int n = items.size();
        for (int i = n - 1; i >= 0; i--) {
            CardView src = items.get(i).getSourceCard();
            if (src == null) continue;
            // index 0 is the top of the stack: highest and in front
            double lift = (n - 1 - i) * 0.35 * s;
            double bob = Math.sin((now + i * 500L) / 700.0) * 0.06 * s;
            Vec3 at = center.add(0, (1.4 + lift) * s + bob, 0);
            beam(pose, cam, buffers, center.add(0, 0.05, 0), at, i == 0 ? 0x60FFD050 : 0x3050E0FF);
            upright(pose, cam, buffers, src, at, (i == 0 ? 1.1 : 0.8) * s, 0, i == 0 ? 0xF0 : 0xA0);
        }
    }

    /** A swirling purple-black vortex on the floor: the exile zone. */
    private static void voidPortal(PoseStack pose, Camera cam, MultiBufferSource buffers, Vec3 at, float radius, long now, boolean boss) {
        Vec3 o = at.subtract(cam.getPosition()).add(0, 0.008, 0);
        VertexConsumer vc = glow(buffers);
        Matrix4f m = pose.last().pose();
        // dark core
        ring(vc, m, o, radius * 0.55, radius * 0.55, 0xC0100018, 24, 0);
        // spiral arms turning inward
        double spin = now / 600.0;
        int arms = 5;
        for (int a = 0; a < arms; a++) {
            Vec3 prev = null;
            for (int k = 0; k <= 14; k++) {
                double t = k / 14.0;
                double ang = spin + a * Math.PI * 2 / arms + t * 2.6;
                double rad = radius * (1 - t * 0.85);
                Vec3 p = o.add(Math.cos(ang) * rad, 0.001 * k, Math.sin(ang) * rad);
                if (prev != null) {
                    int alpha = (int) (40 + 140 * t);
                    int col = (alpha << 24) | (boss ? 0xC040FF : 0x9050FF);
                    strip(vc, m, prev, p, new Vec3(0, 1, 0).cross(p.subtract(prev)), 0.07 * (1 - t * 0.6) * radius / 0.35, col);
                }
                prev = p;
            }
        }
        // a faint glowing rim
        ring(vc, m, o, radius, 0.04, 0x70B080FF, 40, -spin);
    }

    // ------------------------------------------------------------------ drawing primitives

    /** A card lying flat on the floor, its top edge toward the centre (readable from its owner's seat). */
    private static void flat(PoseStack pose, Camera cam, MultiBufferSource buffers, CardView c, Vec3 at, Vec3 r, Vec3 f,
                             double cw, double ch, boolean tapped, int alpha) {
        Vec3 cp = cam.getPosition();
        Vec3 ax = tapped ? f : r, ay = tapped ? r.scale(-1) : f;
        Vec3 o = at.subtract(cp);
        Vec3 tl = o.subtract(ax.scale(cw / 2)).add(ay.scale(ch / 2));
        Vec3 tr = o.add(ax.scale(cw / 2)).add(ay.scale(ch / 2));
        Vec3 br = o.add(ax.scale(cw / 2)).subtract(ay.scale(ch / 2));
        Vec3 bl = o.subtract(ax.scale(cw / 2)).subtract(ay.scale(ch / 2));
        ResourceLocation tex = art(c);
        Matrix4f m = pose.last().pose();
        VertexConsumer vc = buffers.getBuffer(cardType(tex));
        v(vc, m, tl, 0, 0, alpha);
        v(vc, m, bl, 0, 1, alpha);
        v(vc, m, br, 1, 1, alpha);
        v(vc, m, tr, 1, 0, alpha);
        // underside, so it isn't invisible from below
        v(vc, m, tr, 1, 0, alpha);
        v(vc, m, br, 1, 1, alpha);
        v(vc, m, bl, 0, 1, alpha);
        v(vc, m, tl, 0, 0, alpha);
    }

    /** A card standing up, turned to face the camera (holograms and floating hands). */
    private static void upright(PoseStack pose, Camera cam, MultiBufferSource buffers, CardView c, Vec3 at, double w,
                                float roll, int alpha) {
        Vec3 cp = cam.getPosition();
        pose.pushPose();
        pose.translate(at.x - cp.x, at.y - cp.y, at.z - cp.z);
        pose.mulPose(Axis.YP.rotationDegrees(-cam.getYRot()));
        pose.mulPose(Axis.ZP.rotationDegrees(roll));
        Matrix4f m = pose.last().pose();
        float hw = (float) w / 2, hh = (float) (w * 88 / 63) / 2;
        VertexConsumer glow = glow(buffers);
        quad(glow, m, -hw - 0.03f, -hh - 0.03f, hw + 0.03f, hh + 0.03f, -0.003f, 0x3050E0FF);
        VertexConsumer vc = buffers.getBuffer(cardType(art(c)));
        float x0 = -hw, x1 = hw, y0 = -hh, y1 = hh;
        int l = LightTexture.FULL_BRIGHT, ov = OverlayTexture.NO_OVERLAY;
        vc.vertex(m, x0, y0, 0).color(255, 255, 255, alpha).uv(0, 1).overlayCoords(ov).uv2(l).normal(0, 0, 1).endVertex();
        vc.vertex(m, x1, y0, 0).color(255, 255, 255, alpha).uv(1, 1).overlayCoords(ov).uv2(l).normal(0, 0, 1).endVertex();
        vc.vertex(m, x1, y1, 0).color(255, 255, 255, alpha).uv(1, 0).overlayCoords(ov).uv2(l).normal(0, 0, 1).endVertex();
        vc.vertex(m, x0, y1, 0).color(255, 255, 255, alpha).uv(0, 0).overlayCoords(ov).uv2(l).normal(0, 0, 1).endVertex();
        VertexConsumer back = buffers.getBuffer(cardType(Theme.CARD_BACK));
        back.vertex(m, x1, y0, -0.001f).color(255, 255, 255, alpha).uv(0, 1).overlayCoords(ov).uv2(l).normal(0, 0, -1).endVertex();
        back.vertex(m, x0, y0, -0.001f).color(255, 255, 255, alpha).uv(1, 1).overlayCoords(ov).uv2(l).normal(0, 0, -1).endVertex();
        back.vertex(m, x0, y1, -0.001f).color(255, 255, 255, alpha).uv(1, 0).overlayCoords(ov).uv2(l).normal(0, 0, -1).endVertex();
        back.vertex(m, x1, y1, -0.001f).color(255, 255, 255, alpha).uv(0, 0).overlayCoords(ov).uv2(l).normal(0, 0, -1).endVertex();
        pose.popPose();
    }

    private static ResourceLocation art(CardView c) {
        if (c == null || c.isFaceDown() || c.getCurrentState() == null) return Theme.CARD_BACK;
        String key = c.getCurrentState().getImageKey();
        ResourceLocation tex = key == null ? null : CardTextures.get(key, false);
        return tex == null ? Theme.CARD_BACK : tex;
    }

    private static void v(VertexConsumer vc, Matrix4f m, Vec3 p, float u, float vv, int alpha) {
        vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(255, 255, 255, alpha).uv(u, vv)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0, 1, 0).endVertex();
    }

    /** A glowing outline on the floor around a zone. */
    private static void frame(PoseStack pose, Camera cam, MultiBufferSource buffers, Vec3 at, Vec3 r, Vec3 f,
                              double cw, double ch, int color) {
        Vec3 o = at.subtract(cam.getPosition()).add(0, 0.006, 0);
        double t = 0.04;
        VertexConsumer vc = glow(buffers);
        Matrix4f m = pose.last().pose();
        Vec3 hx = r.scale(cw / 2), hy = f.scale(ch / 2);
        strip(vc, m, o.subtract(hx).add(hy), o.add(hx).add(hy), f, t, color);
        strip(vc, m, o.subtract(hx).subtract(hy), o.add(hx).subtract(hy), f, t, color);
        strip(vc, m, o.subtract(hx).subtract(hy), o.subtract(hx).add(hy), r, t, color);
        strip(vc, m, o.add(hx).subtract(hy), o.add(hx).add(hy), r, t, color);
    }

    /** The duel mat: a translucent plate under both rows with an empty zone frame for each slot. */
    private static void mat(PoseStack pose, Camera cam, MultiBufferSource buffers, Vec3 back, Vec3 front, Vec3 r, Vec3 f,
                            double cw, double ch, int slots, int glow) {
        Vec3 cp = cam.getPosition();
        double w = slots * (cw + 0.12) + 0.4, step = cw + 0.12;
        Vec3 mid = back.add(front).scale(0.5).subtract(cp).add(0, -0.004, 0);
        double depth = front.distanceTo(back) + ch + 0.4;
        VertexConsumer vc = glow(buffers);
        Matrix4f m = pose.last().pose();
        int plate = (glow & 0x00FFFFFF) | 0x18000000;
        Vec3 hx = r.scale(w / 2), hy = f.scale(depth / 2);
        quadW(vc, m, mid.subtract(hx).add(hy), mid.add(hx).add(hy), mid.add(hx).subtract(hy), mid.subtract(hx).subtract(hy), plate);
        for (int i = 0; i < slots; i++) {
            double off = (i - (slots - 1) / 2.0) * step;
            frame(pose, cam, buffers, back.add(r.scale(off)), r, f, cw, ch, (glow & 0x00FFFFFF) | 0x50000000);
            frame(pose, cam, buffers, front.add(r.scale(off)), r, f, cw, ch, (glow & 0x00FFFFFF) | 0x50000000);
        }
    }

    /** A hexagonal podium with light pillars at its corners under a duelist. */
    private static void podium(PoseStack pose, Camera cam, MultiBufferSource buffers, Vec3 feet, float s, int glow,
                               boolean danger, long now) {
        Vec3 o = feet.subtract(cam.getPosition());
        VertexConsumer vc = glow(buffers);
        Matrix4f m = pose.last().pose();
        double rad = 0.95 * Math.max(1, s), h = 0.3;
        int col = danger ? 0x70FF4040 : (glow & 0x00FFFFFF) | 0x70000000;
        int side = (col & 0x00FFFFFF) | 0x38000000;
        double spin = now / 4000.0;
        for (int i = 0; i < 6; i++) {
            double a0 = spin + i * Math.PI / 3, a1 = spin + (i + 1) * Math.PI / 3;
            Vec3 p0 = o.add(Math.cos(a0) * rad, -0.02, Math.sin(a0) * rad), p1 = o.add(Math.cos(a1) * rad, -0.02, Math.sin(a1) * rad);
            Vec3 q0 = p0.add(0, h, 0), q1 = p1.add(0, h, 0);
            quadW(vc, m, p0, p1, q1, q0, side);                    // wall
            Vec3 i0 = o.add(Math.cos(a0) * rad * 0.82, h - 0.02, Math.sin(a0) * rad * 0.82);
            Vec3 i1 = o.add(Math.cos(a1) * rad * 0.82, h - 0.02, Math.sin(a1) * rad * 0.82);
            quadW(vc, m, q0, q1, i1, i0, col);                      // glowing rim
            // a pillar of light at each corner
            float pulse = (float) (0.6 + 0.4 * Math.sin(now / 300.0 + i));
            int pc = ((int) (0x50 * pulse) << 24) | (col & 0x00FFFFFF);
            Vec3 base = q0;
            Vec3 tip = q0.add(0, 1.6 + 0.4 * pulse, 0);
            Vec3 side0 = new Vec3(-Math.sin(a0), 0, Math.cos(a0)).scale(0.05);
            quadW(vc, m, base.subtract(side0), base.add(side0), tip.add(side0.scale(0.2)), tip.subtract(side0.scale(0.2)), pc);
        }
    }

    /** A thin vertical beam from a card on the floor up to its hologram. */
    private static void beam(PoseStack pose, Camera cam, MultiBufferSource buffers, Vec3 from, Vec3 to, int color) {
        Vec3 cp = cam.getPosition();
        Vec3 a = from.subtract(cp), b = to.subtract(cp);
        Vec3 view = new Vec3(cam.getLookVector());
        Vec3 side = view.cross(new Vec3(0, 1, 0)).normalize().scale(0.06);
        VertexConsumer vc = glow(buffers);
        quadW(vc, pose.last().pose(), a.subtract(side), a.add(side), b.add(side.scale(2)), b.subtract(side.scale(2)), color);
    }

    /** The arena floor: rings, spokes and a spinning five-colour emblem in the middle. */
    private static final ResourceLocation STAGE = new ResourceLocation(MtgCraft.MODID, "textures/gui/arena_floor.png");

    private static void floor(PoseStack pose, Camera cam, MultiBufferSource buffers, Vec3 center, float radius, int glow, long now) {
        Vec3 o = center.subtract(cam.getPosition()).add(0, 0.015, 0);
        Matrix4f m = pose.last().pose();
        // The stage itself: a dark rune-etched disc (tinted purple for bosses), seen from above and below.
        boolean boss = glow == GLOW_BOSS;
        int tr = boss ? 210 : 255, tg = boss ? 170 : 255, tb = 255;
        double sr = radius + 0.8;
        VertexConsumer st = buffers.getBuffer(RenderType.entityTranslucent(STAGE));
        Vec3 c0 = o.add(-sr, -0.01, -sr), c1 = o.add(sr, -0.01, -sr), c2 = o.add(sr, -0.01, sr), c3 = o.add(-sr, -0.01, sr);
        int l = LightTexture.FULL_BRIGHT, ov = OverlayTexture.NO_OVERLAY;
        for (int side = 0; side < 2; side++) {
            Vec3[] q = side == 0 ? new Vec3[]{c0, c3, c2, c1} : new Vec3[]{c0, c1, c2, c3};
            float[][] uv = side == 0 ? new float[][]{{0, 0}, {0, 1}, {1, 1}, {1, 0}} : new float[][]{{0, 0}, {1, 0}, {1, 1}, {0, 1}};
            for (int i = 0; i < 4; i++) {
                st.vertex(m, (float) q[i].x, (float) q[i].y, (float) q[i].z).color(tr, tg, tb, 255).uv(uv[i][0], uv[i][1])
                        .overlayCoords(ov).uv2(l).normal(0, side == 0 ? 1 : -1, 0).endVertex();
            }
        }
        VertexConsumer vc = glow(buffers);
        int line = (glow & 0x00FFFFFF) | 0x60000000;
        int faint = (glow & 0x00FFFFFF) | 0x20000000;
        double spin = now / 9000.0;
        for (float ringR : new float[]{radius, radius * 0.66f, radius * 0.33f}) {
            ring(vc, m, o, ringR, ringR == radius ? 0.1 : 0.04, ringR == radius ? line : faint, 72, spin);
        }
        for (int i = 0; i < 12; i++) {
            double a = spin + i * Math.PI / 6;
            Vec3 dir = new Vec3(Math.cos(a), 0, Math.sin(a));
            strip(vc, m, o.add(dir.scale(radius * 0.33)), o.add(dir.scale(radius)), new Vec3(-dir.z, 0, dir.x), 0.03, faint);
        }
        // the emblem: five mana colours around a pentagon
        int[] mana = {0xA0F6EFD0, 0xA03F7FD6, 0xA03A3238, 0xA0D9493A, 0xA03FA65A};
        double er = Math.min(1.2, radius * 0.25);
        double es = -now / 2500.0;
        for (int i = 0; i < 5; i++) {
            double a0 = es + i * Math.PI * 2 / 5, a1 = es + (i + 1) * Math.PI * 2 / 5;
            Vec3 p0 = o.add(Math.cos(a0) * er, 0, Math.sin(a0) * er), p1 = o.add(Math.cos(a1) * er, 0, Math.sin(a1) * er);
            strip(vc, m, p0, p1, new Vec3(0, 1, 0).cross(p1.subtract(p0)).normalize(), 0.06, GOLD);
            Vec3 dot = o.add(Math.cos(a0) * er * 1.25, 0.002, Math.sin(a0) * er * 1.25);
            ring(vc, m, dot, 0.12, 0.12, mana[i], 10, 0);
        }
    }

    /** A cylinder of shimmering energy around the arena, fading upward, with bands drifting up it. */
    private static void barrier(PoseStack pose, Camera cam, MultiBufferSource buffers, Vec3 center, float radius,
                                float height, int glow, long now) {
        Vec3 o = center.subtract(cam.getPosition());
        VertexConsumer vc = glow(buffers);
        Matrix4f m = pose.last().pose();
        int segs = 64;
        int rgb = glow & 0x00FFFFFF;
        float band = (now % 3000) / 3000f;
        for (int i = 0; i < segs; i++) {
            double a0 = i * Math.PI * 2 / segs, a1 = (i + 1) * Math.PI * 2 / segs;
            Vec3 p0 = o.add(Math.cos(a0) * radius, 0, Math.sin(a0) * radius), p1 = o.add(Math.cos(a1) * radius, 0, Math.sin(a1) * radius);
            int steps = 6;
            for (int j = 0; j < steps; j++) {
                float t0 = j / (float) steps, t1 = (j + 1) / (float) steps;
                float shimmer = Math.abs(((t0 + band) % 1f) - 0.5f) < 0.08f ? 1.8f : 1f;
                int a = (int) (70 * (1 - t0) * (1 - t0) * shimmer);
                int col = (Math.min(255, a) << 24) | rgb;
                quadW(vc, m, p0.add(0, height * t0, 0), p1.add(0, height * t0, 0), p1.add(0, height * t1, 0), p0.add(0, height * t1, 0), col);
                quadW(vc, m, p0.add(0, height * t1, 0), p1.add(0, height * t1, 0), p1.add(0, height * t0, 0), p0.add(0, height * t0, 0), col);
            }
        }
    }

    private static void ring(VertexConsumer vc, Matrix4f m, Vec3 o, double radius, double width, int color, int segs, double spin) {
        for (int i = 0; i < segs; i++) {
            double a0 = spin + i * Math.PI * 2 / segs, a1 = spin + (i + 1) * Math.PI * 2 / segs;
            double ri = Math.max(0, radius - width), ro = radius;
            Vec3 i0 = o.add(Math.cos(a0) * ri, 0, Math.sin(a0) * ri), i1 = o.add(Math.cos(a1) * ri, 0, Math.sin(a1) * ri);
            Vec3 o0 = o.add(Math.cos(a0) * ro, 0, Math.sin(a0) * ro), o1 = o.add(Math.cos(a1) * ro, 0, Math.sin(a1) * ro);
            quadW(vc, m, i0, o0, o1, i1, color);
            quadW(vc, m, i1, o1, o0, i0, color);
        }
    }

    /** A flat strip on the floor from a to b, {@code width} wide along {@code across}. */
    private static void strip(VertexConsumer vc, Matrix4f m, Vec3 a, Vec3 b, Vec3 across, double width, int color) {
        Vec3 w = across.normalize().scale(width / 2);
        quadW(vc, m, a.subtract(w), a.add(w), b.add(w), b.subtract(w), color);
        quadW(vc, m, b.subtract(w), b.add(w), a.add(w), a.subtract(w), color);
    }

    private static void quadW(VertexConsumer vc, Matrix4f m, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int argb) {
        int al = argb >>> 24, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, bl = argb & 0xFF;
        vc.vertex(m, (float) a.x, (float) a.y, (float) a.z).color(r, g, bl, al).endVertex();
        vc.vertex(m, (float) b.x, (float) b.y, (float) b.z).color(r, g, bl, al).endVertex();
        vc.vertex(m, (float) c.x, (float) c.y, (float) c.z).color(r, g, bl, al).endVertex();
        vc.vertex(m, (float) d.x, (float) d.y, (float) d.z).color(r, g, bl, al).endVertex();
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1, float z, int argb) {
        int a = argb >>> 24, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        vc.vertex(m, x0, y0, z).color(r, g, b, a).endVertex();
        vc.vertex(m, x1, y0, z).color(r, g, b, a).endVertex();
        vc.vertex(m, x1, y1, z).color(r, g, b, a).endVertex();
        vc.vertex(m, x0, y1, z).color(r, g, b, a).endVertex();
    }

    private static void label(PoseStack pose, Camera cam, MultiBufferSource buffers, Vec3 at, String text, int color, float s) {
        Font font = Minecraft.getInstance().font;
        Vec3 camPos = cam.getPosition();
        pose.pushPose();
        pose.translate(at.x - camPos.x, at.y - camPos.y, at.z - camPos.z);
        pose.mulPose(cam.rotation());
        float sc = 0.025f * Math.max(0.6f, s);
        pose.scale(-sc, -sc, sc);
        float x = -font.width(text) / 2f;
        font.drawInBatch(text, x, 0, color, false, pose.last().pose(), buffers, Font.DisplayMode.SEE_THROUGH,
                0x60000000, LightTexture.FULL_BRIGHT);
        pose.popPose();
    }
}

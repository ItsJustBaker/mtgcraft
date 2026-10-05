package dev.mtgcraft.client;

import dev.mtgcraft.MtgCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Random;

/**
 * The anime-style "it's time to duel" moment before a duel: the sting plays, the camera judders in stepped jumps
 * (like held animation frames) while it punches in, black bars slide in, a white flash hits, then the duel opens.
 */
@Mod.EventBusSubscriber(modid = MtgCraft.MODID, value = Dist.CLIENT)
public final class DuelIntro {
    private static final long LENGTH_MS = 2200;
    /** How long each "held frame" of the stutter lasts. */
    private static final long STEP_MS = 85;
    private static final long FLASH_AT_MS = 900;

    private static long startedAt = -1;
    private static Runnable then;
    private static final Random RNG = new Random();
    private static long lastStep = -1;
    private static float shakeYaw, shakePitch, shakeRoll, zoom;

    private DuelIntro() {}

    /** Plays the intro, then runs {@code after} (opening the duel screen). Main thread. */
    public static void play(Runnable after) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !dev.mtgcraft.MtgClientConfig.DUEL_INTRO.get()) {
            after.run();
            return;
        }
        mc.setScreen(null);
        startedAt = System.currentTimeMillis();
        lastStep = -1;
        then = after;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(MtgCraft.DUEL_START.get(), 1f, 1f));
    }

    public static boolean active() {
        return startedAt >= 0;
    }

    private static float progress() {
        return (System.currentTimeMillis() - startedAt) / (float) LENGTH_MS;
    }

    /** Recomputes the shake only every STEP_MS, so the camera jumps instead of sliding: the stutter. */
    private static void step() {
        long now = System.currentTimeMillis();
        long frame = (now - startedAt) / STEP_MS;
        if (frame == lastStep) return;
        lastStep = frame;
        float p = progress();
        float strength = p < 0.4f ? p / 0.4f : Math.max(0, 1 - (p - 0.4f) / 0.6f);
        shakeYaw = (RNG.nextFloat() - 0.5f) * 6f * strength;
        shakePitch = (RNG.nextFloat() - 0.5f) * 4f * strength;
        shakeRoll = (RNG.nextFloat() - 0.5f) * 8f * strength;
        // Punch in toward the target in two hard steps, then ease back.
        zoom = p < 0.38f ? 0.12f : p < 0.75f ? 0.28f : 0.28f * (1 - (p - 0.75f) / 0.25f);
    }

    private static void finishIfDone() {
        if (startedAt >= 0 && progress() >= 1) {
            startedAt = -1;
            Runnable r = then;
            then = null;
            if (r != null) r.run();
        }
    }

    @SubscribeEvent
    public static void cameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!active()) return;
        step();
        event.setYaw(event.getYaw() + shakeYaw);
        event.setPitch(event.getPitch() + shakePitch);
        event.setRoll(event.getRoll() + shakeRoll);
    }

    @SubscribeEvent
    public static void fov(ViewportEvent.ComputeFov event) {
        if (!active()) return;
        step();
        event.setFOV(event.getFOV() * (1 - zoom));
    }

    @SubscribeEvent
    public static void overlay(RenderGuiEvent.Post event) {
        if (!active()) return;
        GuiGraphics g = event.getGuiGraphics();
        int w = g.guiWidth(), h = g.guiHeight();
        float p = progress();
        long ms = System.currentTimeMillis() - startedAt;
        // letterbox bars
        int bar = (int) (h * 0.12f * Math.min(1, p * 4));
        g.fill(0, 0, w, bar, 0xFF000000);
        g.fill(0, h - bar, w, h, 0xFF000000);
        // speed-line vignette that strengthens toward the hit
        int edge = (int) (Math.min(1, p * 2.2f) * 140);
        g.fillGradient(0, bar, w, bar + h / 5, edge << 24, 0);
        g.fillGradient(0, h - bar - h / 5, w, h - bar, 0, edge << 24);
        // white flash on the hit
        long sinceHit = ms - FLASH_AT_MS;
        if (sinceHit >= 0 && sinceHit < 260) {
            int a = (int) (230 * (1 - sinceHit / 260f));
            g.fill(0, 0, w, h, (a << 24) | 0xFFFFFF);
        }
        // the title, stamped in on the hit
        if (ms > FLASH_AT_MS) {
            float s = 3f + Math.max(0, 1 - (ms - FLASH_AT_MS) / 150f) * 2f;
            g.pose().pushPose();
            g.pose().translate(w / 2f, h / 2f - 12, 0);
            g.pose().scale(s, s, 1);
            g.drawCenteredString(Minecraft.getInstance().font, "DUEL!", 0, 0, 0xFFFFD050);
            g.pose().popPose();
        }
        finishIfDone();
    }
}

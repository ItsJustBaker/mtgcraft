package dev.mtgcraft.client;

import com.mojang.math.Axis;
import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.item.CardItem;
import dev.mtgcraft.net.Packets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Opening a booster, like the real thing: tear the wrapper, then flip the cards one at a time, commons first and
 * the rare last. Rares burst gold, mythics fanfare, foils shimmer. The cards are already in your inventory.
 */
public class PackOpeningScreen extends Screen {
    private enum Phase { WRAPPED, TEARING, REVEALING, DONE }

    private static final class Slot {
        final Packets.Pull pull;
        boolean revealed;
        long revealedAt;

        Slot(Packets.Pull pull) { this.pull = pull; }
    }

    private final Packets.PackOpened pack;
    private final ResourceLocation wrapperTex;
    private final List<Slot> slots = new ArrayList<>();
    private Phase phase = Phase.WRAPPED;
    private long phaseAt = System.currentTimeMillis();
    private int next;
    private long openedAt;

    public static void show(Packets.PackOpened msg) {
        Minecraft.getInstance().setScreen(new PackOpeningScreen(msg));
    }

    public PackOpeningScreen(Packets.PackOpened pack) {
        super(Component.literal(pack.title()));
        this.pack = pack;
        this.wrapperTex = new ResourceLocation(MtgCraft.MODID, "textures/gui/wrapper/" + pack.wrapper().toLowerCase(Locale.ROOT) + ".png");
        for (Packets.Pull p : pack.cards()) slots.add(new Slot(p));
        // Commons first, the best card last; foils count as a step up.
        slots.sort(Comparator.comparingInt(s -> rank(s.pull)));
    }

    private static int rank(Packets.Pull p) {
        int r = switch (p.rarity()) {
            case 'L' -> 0;
            case 'C' -> 1;
            case 'U' -> 2;
            case 'R' -> 4;
            case 'M' -> 5;
            case 'S' -> 3;
            default -> 1;
        };
        return r * 2 + (p.foil() ? 1 : 0);
    }

    @Override
    protected void init() {
        openedAt = System.currentTimeMillis();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        advance();
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_ENTER) {
            advance();
            return true;
        }
        if (key == GLFW.GLFW_KEY_R && phase == Phase.REVEALING) {
            while (next < slots.size()) revealNext(false);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    private void advance() {
        long now = System.currentTimeMillis();
        switch (phase) {
            case WRAPPED -> {
                phase = Phase.TEARING;
                phaseAt = now;
                Theme.play(SoundEvents.BOOK_PAGE_TURN, 0.6f, 1f);
                Theme.play(SoundEvents.ITEM_PICKUP, 0.7f, 0.6f);
            }
            case TEARING -> {
                if (now - phaseAt > 300) startRevealing();
            }
            case REVEALING -> {
                if (next < slots.size()) revealNext(true);
                else {
                    phase = Phase.DONE;
                    phaseAt = now;
                }
            }
            case DONE -> onClose();
        }
    }

    private void startRevealing() {
        phase = Phase.REVEALING;
        phaseAt = System.currentTimeMillis();
        revealNext(true);
    }

    private void revealNext(boolean sound) {
        Slot s = slots.get(next++);
        s.revealed = true;
        s.revealedAt = System.currentTimeMillis();
        if (!sound) return;
        SoundEvent snd = SoundEvents.BOOK_PAGE_TURN;
        float pitch = 1.3f;
        if (s.pull.rarity() == 'R') { snd = SoundEvents.AMETHYST_BLOCK_CHIME; pitch = 1f; }
        if (s.pull.rarity() == 'M') { snd = SoundEvents.PLAYER_LEVELUP; pitch = 1.2f; }
        Theme.play(snd, pitch, 0.9f);
        if (s.pull.foil()) Theme.play(SoundEvents.AMETHYST_CLUSTER_BREAK, 1.4f, 0.7f);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        long now = System.currentTimeMillis();
        g.fill(0, 0, width, height, 0xE0080A09);
        g.fillGradient(0, 0, width, height / 2, 0x40203020, 0x00000000);
        if (phase == Phase.TEARING && now - phaseAt > 650) startRevealing();

        float bigH = Math.min(height * 0.62f, (width / 3f) * 88f / 63f);
        float bigW = bigH * 63f / 88f;
        float cx = width / 2f, cy = height * 0.42f;

        switch (phase) {
            case WRAPPED -> drawWrapper(g, cx, cy, bigH, now, 0);
            case TEARING -> drawWrapper(g, cx, cy, bigH, now, Math.min(1f, (now - phaseAt) / 650f));
            case REVEALING, DONE -> {
                drawTray(g, mx, my, now);
                if (phase == Phase.REVEALING && next > 0) drawCurrent(g, slots.get(next - 1), cx, cy, bigW, bigH, now);
                if (phase == Phase.DONE) drawDoneHover(g, mx, my, now);
            }
        }

        String hint = switch (phase) {
            case WRAPPED -> "Click to tear open";
            case TEARING -> "";
            case REVEALING -> next < slots.size() ? "Click for the next card  (" + (slots.size() - next) + " left · R reveals all)" : "Click to see everything";
            case DONE -> "Your cards are in your inventory · click to close";
        };
        g.drawCenteredString(font, Component.literal(pack.title()).withStyle(s -> s.withBold(true)), width / 2, 6, Theme.GOLD);
        if (!hint.isEmpty()) g.drawCenteredString(font, hint, width / 2, height - 12, Theme.MUTED);
    }

    /** The sealed wrapper, gently floating; while tearing, the top flies off and the body drops away. */
    private void drawWrapper(GuiGraphics g, float cx, float cy, float h, long now, float tear) {
        float w = h * 64f / 112f;
        float bob = (float) Math.sin((now - openedAt) / 400.0) * 3;
        float x = cx - w / 2, y = cy - h / 2 + bob;
        float topH = h * 0.16f;
        // body
        float bodyDrop = tear * tear * h * 0.9f;
        float alpha = 1 - Math.max(0, tear - 0.5f) * 2;
        g.pose().pushPose();
        g.pose().translate(0, bodyDrop, 0);
        g.setColor(1, 1, 1, alpha);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        blitRegion(g, wrapperTex, x, y + topH, w, h - topH, 0, 112 * 0.16f, 64, 112 * 0.84f);
        g.setColor(1, 1, 1, 1);
        g.pose().popPose();
        // top strip
        g.pose().pushPose();
        g.pose().translate(x + w / 2 + tear * w * 0.8f, y + topH / 2 - tear * h * 0.6f, 0);
        g.pose().mulPose(Axis.ZP.rotationDegrees(tear * 50));
        blitRegion(g, wrapperTex, -w / 2, -topH / 2, w, topH, 0, 0, 64, 112 * 0.16f);
        g.pose().popPose();
        // the cards peeking out once torn
        if (tear > 0.1f) {
            float cw = w * 0.86f, ch = cw * 88f / 63f;
            float rise = Math.min(1, tear * 1.4f) * h * 0.1f;
            for (int i = 0; i < 3; i++) {
                blitScaled(g, Theme.CARD_BACK, cx - cw / 2 + i * 1.5f, y + topH - rise - i * 1.5f + h * 0.04f, cw, ch, 126, 176, 1);
            }
        }
        if (tear == 0) {
            // title band
            g.pose().pushPose();
            g.pose().translate(cx, y + h * 0.75f - 4, 0);
            float s = Math.max(0.6f, Math.min(1.5f, w / 90f));
            g.pose().scale(s, s, 1);
            g.drawCenteredString(font, Theme.ellipsize(font, pack.title(), (int) (w / s) - 6), 0, 0, 0xFFFFFFFF);
            g.pose().popPose();
        }
    }

    /** The card just flipped, big in the middle. Flips over 250 ms; rares get a burst, foils a sheen. */
    private void drawCurrent(GuiGraphics g, Slot s, float cx, float cy, float w, float h, long now) {
        float t = Math.min(1f, (now - s.revealedAt) / 250f);
        float flip = (float) Math.cos(t * Math.PI); // 1 -> -1
        boolean face = flip < 0;
        float sx = Math.abs(flip);
        boolean special = s.pull.rarity() == 'R' || s.pull.rarity() == 'M';
        if (face && special) burst(g, cx, cy, h, now - s.revealedAt, s.pull.rarity() == 'M' ? 0xFF6040 : 0xFFD050);
        g.pose().pushPose();
        g.pose().translate(cx, cy, 50);
        g.pose().scale(Math.max(0.02f, sx), 1, 1);
        g.pose().translate(-w / 2, -h / 2, 0);
        Theme.fillF(g, g.pose().last().pose(), 5, 7, w + 5, h + 7, 0x80000000);
        if (face) drawFace(g, s.pull, 0, 0, w, h, now);
        else blitScaled(g, Theme.CARD_BACK, 0, 0, w, h, 126, 176, 1);
        g.pose().popPose();
        if (face) {
            String label = CardItem.rarityName(s.pull.rarity()) + (s.pull.foil() ? " · Foil" : "");
            int color = 0xFF000000 | rarityRgb(s.pull.rarity());
            g.drawCenteredString(font, label, (int) cx, (int) (cy + h / 2 + 6), color);
        }
    }

    /** Cards already revealed, small along the bottom (a grid once everything is out). */
    private void drawTray(GuiGraphics g, int mx, int my, long now) {
        int n = slots.size();
        boolean grid = phase == Phase.DONE;
        float cw, ch;
        if (grid) {
            int cols = Math.min(8, n);
            int rows = (n + cols - 1) / cols;
            ch = Math.min((height - 50f) / rows - 6, ((width - 40f) / cols - 6) * 88f / 63f);
            cw = ch * 63f / 88f;
            float totalW = cols * (cw + 6) - 6, totalH = rows * (ch + 6) - 6;
            for (int i = 0; i < n; i++) {
                float x = (width - totalW) / 2 + (i % cols) * (cw + 6);
                float y = 22 + (height - 40 - totalH) / 2 + (i / cols) * (ch + 6);
                boolean hover = mx >= x && mx < x + cw && my >= y && my < y + ch;
                g.pose().pushPose();
                g.pose().translate(x + cw / 2, y + ch / 2, hover ? 100 : 0);
                float sc = hover ? 1.08f : 1f;
                g.pose().scale(sc, sc, 1);
                drawFace(g, slots.get(i).pull, -cw / 2, -ch / 2, cw, ch, now);
                g.pose().popPose();
            }
            return;
        }
        ch = Math.min(height * 0.2f, 80);
        cw = ch * 63f / 88f;
        float step = Math.min(cw + 4, (width - 40f - cw) / Math.max(1, n - 1));
        float x0 = (width - (step * (n - 1) + cw)) / 2;
        for (int i = 0; i < n; i++) {
            Slot s = slots.get(i);
            float x = x0 + i * step, y = height - ch - 18;
            if (!s.revealed || i == next - 1) {
                Theme.rounded(g, (int) x, (int) y, (int) cw, (int) ch, 0x30FFFFFF);
                continue;
            }
            drawFace(g, s.pull, x, y, cw, ch, now);
        }
    }

    private void drawDoneHover(GuiGraphics g, int mx, int my, long now) {
        // Hover zoom is handled by the grid's lift; nothing else to draw.
    }

    private void drawFace(GuiGraphics g, Packets.Pull p, float x, float y, float w, float h, long now) {
        double real = h * minecraft.getWindow().getGuiScale();
        ResourceLocation art = CardItemRenderer.art(p.card(), real > CardTextures.SMALL_H * 1.15);
        if (art != null) {
            boolean large = real > CardTextures.SMALL_H * 1.15;
            blitScaled(g, art, x, y, w, h, large ? CardTextures.LARGE_W : CardTextures.SMALL_W,
                    large ? CardTextures.LARGE_H : CardTextures.SMALL_H, 1);
        } else {
            var m = g.pose().last().pose();
            Theme.fillF(g, m, x, y, x + w, y + h, 0xFF1A1A1A);
            Theme.fillF(g, m, x + 2, y + 2, x + w - 2, y + h - 2, 0xFF3A3530);
            g.pose().pushPose();
            g.pose().translate(x + 4, y + 4, 0);
            float s = Math.max(0.5f, w / 120f);
            g.pose().scale(s, s, 1);
            for (var line : font.split(Component.literal(dev.mtgcraft.engine.Cards.name(p.card())), (int) ((w - 8) / s))) {
                g.drawString(font, line, 0, 0, Theme.TEXT, false);
                g.pose().translate(0, 10, 0);
            }
            g.pose().popPose();
        }
        if (p.foil()) sheen(g, x, y, w, h, now);
        int edge = rarityRgb(p.rarity());
        if (p.rarity() == 'R' || p.rarity() == 'M') {
            var m = g.pose().last().pose();
            int c = 0xC0000000 | edge;
            Theme.fillF(g, m, x - 1, y - 1, x + w + 1, y, c);
            Theme.fillF(g, m, x - 1, y + h, x + w + 1, y + h + 1, c);
            Theme.fillF(g, m, x - 1, y, x, y + h, c);
            Theme.fillF(g, m, x + w, y, x + w + 1, y + h, c);
        }
    }

    /** A rainbow band sweeping across a foil card. */
    private void sheen(GuiGraphics g, float x, float y, float w, float h, long now) {
        float t = (now % 2200) / 2200f;
        float bx = x - w + t * (w * 3);
        int[] colors = {0x40FF6080, 0x40FFD060, 0x4060FF90, 0x4060C0FF, 0x40C080FF};
        float bw = w * 0.12f;
        g.enableScissor((int) x, (int) y, (int) Math.ceil(x + w), (int) Math.ceil(y + h));
        for (int i = 0; i < colors.length; i++) {
            float sx = bx + i * bw;
            Theme.line(g, sx, y + h + 4, sx + h * 0.5f, y - 4, bw, colors[i]);
        }
        g.disableScissor();
    }

    /** Rays of light behind a rare or mythic. */
    private void burst(GuiGraphics g, float cx, float cy, float h, long age, int rgb) {
        float grow = Math.min(1f, age / 400f);
        float rot = age / 1600f;
        int rays = 14;
        for (int i = 0; i < rays; i++) {
            double a = rot + i * Math.PI * 2 / rays;
            float len = h * (0.55f + 0.25f * grow);
            int alpha = (int) (110 * grow);
            Theme.line(g, cx, cy, cx + (float) Math.cos(a) * len, cy + (float) Math.sin(a) * len, 6 + 6 * grow, (alpha << 24) | rgb);
        }
    }

    private static int rarityRgb(char r) {
        return switch (r) {
            case 'U' -> 0x9FD8E8;
            case 'R' -> 0xFFD050;
            case 'M' -> 0xFF7040;
            case 'S' -> 0xD080FF;
            default -> 0xD8D8D8;
        };
    }

    private void blitScaled(GuiGraphics g, ResourceLocation tex, float x, float y, float w, float h, int tw, int th, float alpha) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(w / tw, h / th, 1);
        g.setColor(1, 1, 1, alpha);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        g.blit(tex, 0, 0, 0, 0, tw, th, tw, th);
        g.setColor(1, 1, 1, 1);
        g.pose().popPose();
    }

    /** Part of a texture (u, v, uw, vh in texture pixels of a 64x112 wrapper) stretched over a float rect. */
    private void blitRegion(GuiGraphics g, ResourceLocation tex, float x, float y, float w, float h,
                            float u, float v, float uw, float vh) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(w / uw, h / vh, 1);
        g.blit(tex, 0, 0, (int) uw, (int) vh, u, v, (int) uw, (int) vh, 64, 112);
        g.pose().popPose();
    }
}

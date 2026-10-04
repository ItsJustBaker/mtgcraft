package dev.mtgcraft.client;

import dev.mtgcraft.MtgCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import org.joml.Matrix4f;

/** Shared look for the table screens: felt, gold-trimmed panels, mana orbs, rounded buttons. */
public final class Theme {
    public static final ResourceLocation FELT = new ResourceLocation(MtgCraft.MODID, "textures/gui/felt.png");
    public static final ResourceLocation CARD_BACK = new ResourceLocation(MtgCraft.MODID, "textures/gui/card_back.png");

    public static final int TEXT = 0xFFF2EEE3;
    public static final int MUTED = 0xFFA9B5A8;
    public static final int GOLD = 0xFFE0B65A;
    public static final int PANEL = 0xD8141A16;
    public static final int PANEL_EDGE = 0xFF7A6233;
    public static final int RED = 0xFFE5534B;
    public static final int GREEN = 0xFF57C46A;
    public static final int SELECT = 0xFFFFD45A;

    /** Fill and rim colours for W, U, B, R, G. */
    public static final int[] MANA_FILL = {0xFFF6EFD0, 0xFF3F7FD6, 0xFF3A3238, 0xFFD9493A, 0xFF3FA65A};
    public static final int[] MANA_TEXT = {0xFF4A4030, 0xFFFFFFFF, 0xFFE0D6E0, 0xFFFFFFFF, 0xFFFFFFFF};
    public static final String[] MANA_SYMBOL = {"W", "U", "B", "R", "G"};

    private Theme() {}

    public static void drawFelt(GuiGraphics g, int w, int h) {
        g.blit(FELT, 0, 0, 0, 0, w, h, 64, 64);
        // soft vignette
        g.fillGradient(0, 0, w, h / 3, 0x55000000, 0x00000000);
        g.fillGradient(0, h - h / 3, w, h, 0x00000000, 0x66000000);
    }

    /** A rounded rectangle (corners cut by one pixel step). */
    public static void rounded(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x + 2, y, x + w - 2, y + h, color);
        g.fill(x, y + 2, x + 2, y + h - 2, color);
        g.fill(x + w - 2, y + 2, x + w, y + h - 2, color);
        g.fill(x + 1, y + 1, x + 2, y + 2, color);
        g.fill(x + w - 2, y + 1, x + w - 1, y + 2, color);
        g.fill(x + 1, y + h - 2, x + 2, y + h - 1, color);
        g.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, color);
    }

    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        rounded(g, x - 1, y - 1, w + 2, h + 2, PANEL_EDGE);
        rounded(g, x, y, w, h, PANEL);
    }

    /** A button face; returns true if the mouse is over it. */
    public static boolean button(GuiGraphics g, Font font, String label, int x, int y, int w, int h,
                                 int mx, int my, boolean enabled, boolean primary) {
        boolean hover = enabled && mx >= x && mx < x + w && my >= y && my < y + h;
        int edge = !enabled ? 0xFF3A403B : primary ? GOLD : 0xFF8C8F86;
        int fill = !enabled ? 0xC0202622 : hover ? (primary ? 0xF0705A26 : 0xF04A4F49) : (primary ? 0xE0503F1C : 0xE0333833);
        rounded(g, x - 1, y - 1, w + 2, h + 2, edge);
        rounded(g, x, y, w, h, fill);
        int color = !enabled ? 0xFF6E746E : TEXT;
        g.drawCenteredString(font, ellipsize(font, label, w - 6), x + w / 2, y + (h - 8) / 2, color);
        return hover;
    }

    public static void manaOrb(GuiGraphics g, int cx, int cy, int r, int color, boolean on, boolean hover) {
        int fill = on ? MANA_FILL[color] : 0xFF2A302B;
        int rim = on ? GOLD : hover ? 0xFFB0B5A8 : 0xFF5A605A;
        disc(g, cx, cy, r + 1, rim);
        disc(g, cx, cy, r, fill);
        Font font = Minecraft.getInstance().font;
        g.drawCenteredString(font, MANA_SYMBOL[color], cx, cy - 4, on ? MANA_TEXT[color] : 0xFF8A908A);
    }

    public static void disc(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int dx = (int) Math.round(Math.sqrt(r * r - dy * dy));
            g.fill(cx - dx, cy + dy, cx + dx, cy + dy + 1, color);
        }
    }

    public static void spinner(GuiGraphics g, int cx, int cy, long now) {
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4 + now / 160.0;
            int x = (int) (cx + Math.cos(a) * 9), y = (int) (cy + Math.sin(a) * 9);
            int alpha = 60 + i * 24;
            disc(g, x, y, 2, (alpha << 24) | 0xE0B65A);
        }
    }

    /** A straight line of the given width between two points (used for combat arrows). */
    public static void line(GuiGraphics g, float x1, float y1, float x2, float y2, float width, int color) {
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.5f) return;
        g.pose().pushPose();
        g.pose().translate(x1, y1, 0);
        g.pose().mulPose(com.mojang.math.Axis.ZP.rotation((float) Math.atan2(dy, dx)));
        Matrix4f m = g.pose().last().pose();
        fillF(g, m, 0, -width / 2, len, width / 2, color);
        g.pose().popPose();
    }

    /** Fill with float coordinates, for smooth sub-pixel motion. */
    public static void fillF(GuiGraphics g, Matrix4f m, float x1, float y1, float x2, float y2, int color) {
        var vc = g.bufferSource().getBuffer(net.minecraft.client.renderer.RenderType.gui());
        float a = (color >>> 24) / 255f, r = ((color >> 16) & 0xFF) / 255f, gr = ((color >> 8) & 0xFF) / 255f, b = (color & 0xFF) / 255f;
        vc.vertex(m, x1, y1, 0).color(r, gr, b, a).endVertex();
        vc.vertex(m, x1, y2, 0).color(r, gr, b, a).endVertex();
        vc.vertex(m, x2, y2, 0).color(r, gr, b, a).endVertex();
        vc.vertex(m, x2, y1, 0).color(r, gr, b, a).endVertex();
        g.flush();
    }

    public static String ellipsize(Font font, String s, int width) {
        if (font.width(s) <= width) return s;
        return font.plainSubstrByWidth(s, Math.max(0, width - font.width("…"))) + "…";
    }

    public static void click() {
        play(SoundEvents.UI_BUTTON_CLICK.value(), 1f, 0.25f);
    }

    public static void play(SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }
}

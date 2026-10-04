package dev.mtgcraft.client;

import dev.mtgcraft.engine.Cards;
import dev.mtgcraft.engine.ForgeEngine;
import forge.card.ColorSet;
import forge.item.PaperCard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Comparator;

/** Draws cards by key (art when downloaded, a readable frame until then), for the binder, deck builder and packs. */
public final class CardArt {
    private CardArt() {}

    public static void draw(GuiGraphics g, String key, boolean foil, float x, float y, float w, float h, float alpha) {
        Minecraft mc = Minecraft.getInstance();
        double real = h * mc.getWindow().getGuiScale();
        boolean large = real > CardTextures.SMALL_H * 1.15;
        ResourceLocation art = CardItemRenderer.art(key, large);
        if (art != null) {
            blit(g, art, x, y, w, h, large ? CardTextures.LARGE_W : CardTextures.SMALL_W, large ? CardTextures.LARGE_H : CardTextures.SMALL_H, alpha);
        } else {
            var m = g.pose().last().pose();
            Theme.fillF(g, m, x, y, x + w, y + h, 0xFF1A1A1A);
            Theme.fillF(g, m, x + 1.5f, y + 1.5f, x + w - 1.5f, y + h - 1.5f, frame(key));
            Font font = mc.font;
            g.pose().pushPose();
            g.pose().translate(x + 3, y + 3, 0);
            float s = Math.max(0.45f, Math.min(1f, w / 110f));
            g.pose().scale(s, s, 1);
            int line = 0;
            for (var l : font.split(Component.literal(Cards.name(key)), (int) ((w - 6) / s))) {
                if (line++ > 3) break;
                g.drawString(font, l, 0, 0, 0xFF101010, false);
                g.pose().translate(0, 9, 0);
            }
            g.pose().popPose();
        }
        if (foil) {
            long now = System.currentTimeMillis();
            float t = (now % 2400) / 2400f;
            float bx = x - w + t * w * 3;
            g.enableScissor((int) x, (int) y, (int) Math.ceil(x + w), (int) Math.ceil(y + h));
            int[] colors = {0x38FF6080, 0x38FFD060, 0x3860FF90, 0x3860C0FF, 0x38C080FF};
            float bw = w * 0.12f;
            for (int i = 0; i < colors.length; i++) {
                float sx = bx + i * bw;
                Theme.line(g, sx, y + h + 4, sx + h * 0.5f, y - 4, bw, colors[i]);
            }
            g.disableScissor();
        }
    }

    /** A big readable copy, on the side of the screen away from the mouse. */
    public static void preview(GuiGraphics g, String key, boolean foil, int mouseX, int screenW, int screenH) {
        if (key == null) return;
        float h = Math.min(screenH * 0.8f, (screenW / 2f - 20) * 88f / 63f);
        float w = h * 63f / 88f;
        float x = mouseX < screenW / 2 ? screenW - w - 12 : 12;
        float y = (screenH - h) / 2;
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        Theme.fillF(g, g.pose().last().pose(), x + 4, y + 6, x + w + 4, y + h + 6, 0x80000000);
        draw(g, key, foil, x, y, w, h, 1);
        g.pose().popPose();
    }

    public static void blit(GuiGraphics g, ResourceLocation tex, float x, float y, float w, float h, int tw, int th, float alpha) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(w / tw, h / th, 1);
        g.setColor(1, 1, 1, alpha);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        g.blit(tex, 0, 0, 0, 0, tw, th, tw, th);
        g.setColor(1, 1, 1, 1);
        g.pose().popPose();
    }

    private static int frame(String key) {
        PaperCard pc = ForgeEngine.state() == ForgeEngine.State.READY ? Cards.card(key) : null;
        ColorSet cs = pc == null ? null : pc.getRules().getColor();
        if (cs == null || cs.isColorless()) return 0xFFB8B8B0;
        if (cs.isMulticolor()) return 0xFFE0C060;
        if (cs.hasWhite()) return 0xFFF2EBD0;
        if (cs.hasBlue()) return 0xFF7AA6E0;
        if (cs.hasBlack()) return 0xFF8A7E88;
        if (cs.hasRed()) return 0xFFE07A68;
        return 0xFF78C088;
    }

    /** Collection order: white, blue, black, red, green, multicolour, colourless, lands; then by name. */
    public static Comparator<String> collectionOrder() {
        return Comparator.<String>comparingInt(CardArt::colorRank).thenComparing(Cards::name);
    }

    private static int colorRank(String key) {
        PaperCard pc = ForgeEngine.state() == ForgeEngine.State.READY ? Cards.card(key) : null;
        if (pc == null) return 9;
        if (pc.getRules().getType().isLand()) return 8;
        ColorSet cs = pc.getRules().getColor();
        if (cs.isColorless()) return 7;
        if (cs.isMulticolor()) return 6;
        if (cs.hasWhite()) return 1;
        if (cs.hasBlue()) return 2;
        if (cs.hasBlack()) return 3;
        if (cs.hasRed()) return 4;
        return 5;
    }
}

package dev.mtgcraft.client;

import dev.mtgcraft.engine.Cards;
import forge.card.CardRules;
import forge.card.CardType;
import forge.card.ColorSet;
import forge.item.PaperCard;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The filter bar beside a collection's search box: colour toggles (W U B R G, and C for colourless), a card type
 * that cycles on click, and foils only. The search text matches names and type lines ("goblin", "equipment").
 */
final class CardFilter {
    private static final String[] COLORS = {"W", "U", "B", "R", "G", "C"};
    private static final String[] TYPES = {"All types", "Creature", "Instant", "Sorcery", "Artifact", "Enchantment",
            "Land", "Planeswalker"};
    private static final int CW = 12, TW = 66, FW = 30, GAP = 2, H = 14;

    /** Bits 0-4: W U B R G (Card-Forge's colour bits); bit 5: colourless. */
    private int colors;
    private int type;
    private boolean foilOnly;

    int width() {
        return COLORS.length * (CW + GAP) + TW + GAP + FW;
    }

    void render(GuiGraphics g, Font font, int x, int y, int mx, int my) {
        for (int i = 0; i < COLORS.length; i++) {
            Theme.button(g, font, COLORS[i], x + i * (CW + GAP), y, CW, H, mx, my, true, (colors & (1 << i)) != 0);
        }
        int tx = x + COLORS.length * (CW + GAP);
        Theme.button(g, font, TYPES[type], tx, y, TW, H, mx, my, true, type != 0);
        Theme.button(g, font, "Foil", tx + TW + GAP, y, FW, H, mx, my, true, foilOnly);
    }

    /** Handles a click on the bar; {@code changed} runs when the filter changes (e.g. to go back to page 1). */
    boolean click(double mx, double my, int button, int x, int y, Runnable changed) {
        if (my < y || my >= y + H) return false;
        for (int i = 0; i < COLORS.length; i++) {
            int bx = x + i * (CW + GAP);
            if (mx >= bx && mx < bx + CW) {
                colors ^= 1 << i;
                return done(changed);
            }
        }
        int tx = x + COLORS.length * (CW + GAP);
        if (mx >= tx && mx < tx + TW) {
            type = (type + (button == 1 ? TYPES.length - 1 : 1)) % TYPES.length;
            return done(changed);
        }
        if (mx >= tx + TW + GAP && mx < tx + TW + GAP + FW) {
            foilOnly = !foilOnly;
            return done(changed);
        }
        return false;
    }

    private static boolean done(Runnable changed) {
        Theme.click();
        changed.run();
        return true;
    }

    /** Whether a card passes the search text and the filters. */
    boolean matches(String key, boolean foil, String query) {
        if (foilOnly && !foil) return false;
        PaperCard pc = Cards.card(key);
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (pc == null) return colors == 0 && type == 0 && (q.isEmpty() || Cards.name(key).toLowerCase(Locale.ROOT).contains(q));
        if (!q.isEmpty() && !Cards.name(key).toLowerCase(Locale.ROOT).contains(q)
                && !Cards.typeLine(pc).toLowerCase(Locale.ROOT).contains(q)) return false;
        CardRules r = pc.getRules();
        if (colors != 0) {
            ColorSet cs = r.getColor();
            boolean colorless = cs == null || cs.isColorless();
            boolean ok = colorless ? (colors & (1 << 5)) != 0 : (cs.getColor() & colors & 0x1F) != 0;
            if (!ok) return false;
        }
        if (type != 0) {
            CardType t = r.getType();
            boolean ok = switch (type) {
                case 1 -> t.isCreature();
                case 2 -> t.isInstant();
                case 3 -> t.isSorcery();
                case 4 -> t.isArtifact();
                case 5 -> t.isEnchantment();
                case 6 -> t.isLand();
                default -> t.isPlaneswalker();
            };
            if (!ok) return false;
        }
        return true;
    }
}

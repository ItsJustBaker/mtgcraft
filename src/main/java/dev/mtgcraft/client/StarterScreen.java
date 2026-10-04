package dev.mtgcraft.client;

import dev.mtgcraft.net.Net;
import dev.mtgcraft.net.Packets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** The one-time starter deck choice for survival. */
public class StarterScreen extends Screen {
    /** Mana colours of each starter, as indices into Theme.MANA_FILL (W U B R G). */
    private static final int[][] ORBS = {{0, 4}, {0, 1}, {1, 2}, {2, 3}, {3, 4}};
    private final List<String> names;

    public static void show(Packets.OfferStarter msg) {
        Minecraft.getInstance().setScreen(new StarterScreen(msg.names()));
    }

    public StarterScreen(List<String> names) {
        super(Component.literal("Choose your starter deck"));
        this.names = names;
    }

    private int rowY(int i) { return height / 2 - names.size() * 13 + i * 26 + 10; }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        Theme.drawFelt(g, width, height);
        int pw = 300, ph = names.size() * 26 + 60;
        int px = (width - pw) / 2, py = height / 2 - ph / 2;
        Theme.panel(g, px, py, pw, ph);
        g.drawCenteredString(font, Component.literal("Choose your starter deck").withStyle(s -> s.withBold(true)), width / 2, py + 8, Theme.GOLD);
        g.drawCenteredString(font, "It comes in a Deck Box, with a Binder and a Duel Gauntlet.", width / 2, py + 20, Theme.MUTED);
        for (int i = 0; i < names.size(); i++) {
            int y = rowY(i);
            boolean hover = Theme.button(g, font, "", px + 12, y, pw - 24, 22, mx, my, true, false);
            Theme.manaOrb(g, px + 26, y + 11, 7, ORBS[i][0], true, hover);
            Theme.manaOrb(g, px + 44, y + 11, 7, ORBS[i][1], true, hover);
            g.drawString(font, names.get(i), px + 58, y + 7, hover ? Theme.GOLD : Theme.TEXT);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int pw = 300, px = (width - pw) / 2;
        for (int i = 0; i < names.size(); i++) {
            int y = rowY(i);
            if (mx >= px + 12 && mx < px + pw - 12 && my >= y && my < y + 22) {
                Net.toServer(new Packets.ChooseStarter(i));
                Theme.click();
                onClose();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

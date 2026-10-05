package dev.mtgcraft.client;

import dev.mtgcraft.net.Net;
import dev.mtgcraft.net.Packets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A Booster Pick ticket: choose any real Magic set and get a booster pack (or, for the rare box ticket, a whole
 * booster box) of it. Newest sets first; type to search by name or set code.
 */
public class SetPickerScreen extends Screen {
    private static final int ROW = 13;
    private final Packets.SetPicker msg;
    private final List<Integer> shown = new ArrayList<>();
    private EditBox search;
    private int scroll;
    private int chosen = -1;

    public static void show(Packets.SetPicker msg) {
        Minecraft.getInstance().setScreen(new SetPickerScreen(msg));
        Theme.play(SoundEvents.PLAYER_LEVELUP, 1.6f, 0.5f);
    }

    public SetPickerScreen(Packets.SetPicker msg) {
        super(Component.literal(msg.box() ? "Pick a Booster Box" : "Pick a Booster"));
        this.msg = msg;
    }

    private int[] panel() {
        int pw = Math.min(width - 20, 300), ph = Math.min(height - 20, 260);
        return new int[]{(width - pw) / 2, (height - ph) / 2, pw, ph};
    }

    @Override
    protected void init() {
        int[] p = panel();
        String old = search == null ? "" : search.getValue();
        search = new EditBox(font, p[0] + 10, p[1] + 30, p[2] - 20, 14, Component.literal("Search"));
        search.setValue(old);
        search.setHint(Component.literal("Search sets...").withStyle(s -> s.withColor(0x808080)));
        search.setResponder(s -> filter());
        addRenderableWidget(search);
        setInitialFocus(search);
        filter();
    }

    private void filter() {
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        // Newest first: the server sends sets oldest first.
        for (int i = msg.codes().size() - 1; i >= 0; i--) {
            if (q.isEmpty() || msg.names().get(i).toLowerCase(Locale.ROOT).contains(q)
                    || msg.codes().get(i).toLowerCase(Locale.ROOT).contains(q)) shown.add(i);
        }
        scroll = 0;
        if (!shown.contains(chosen)) chosen = -1;
    }

    private int listTop() { return panel()[1] + 50; }

    private int visibleRows() { return Math.max(1, (panel()[3] - 50 - 34) / ROW); }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        renderBackground(g);
        int[] p = panel();
        int px = p[0], py = p[1], pw = p[2], ph = p[3];
        Theme.panel(g, px, py, pw, ph);
        g.drawCenteredString(font, Component.literal(title.getString()).withStyle(s -> s.withBold(true)), px + pw / 2, py + 8, Theme.GOLD);
        g.drawCenteredString(font, msg.box() ? "A whole box of any set you like" : "Any set you like", px + pw / 2, py + 18, Theme.MUTED);
        int top = listTop(), rows = visibleRows();
        for (int r = 0; r < rows && scroll + r < shown.size(); r++) {
            int i = shown.get(scroll + r), y = top + r * ROW;
            boolean hover = mx >= px + 10 && mx < px + pw - 10 && my >= y && my < y + ROW;
            if (i == chosen) Theme.rounded(g, px + 10, y, pw - 20, ROW - 1, 0xE0503F1C);
            else if (hover) Theme.rounded(g, px + 10, y, pw - 20, ROW - 1, 0x40FFFFFF);
            g.drawString(font, Theme.ellipsize(font, msg.names().get(i), pw - 70), px + 14, y + 2, i == chosen ? Theme.GOLD : Theme.TEXT);
            String code = msg.codes().get(i);
            g.drawString(font, code, px + pw - 14 - font.width(code), y + 2, Theme.MUTED);
        }
        if (shown.isEmpty()) g.drawCenteredString(font, "No sets match", px + pw / 2, top + 10, Theme.MUTED);
        if (shown.size() > rows) {
            int trackH = rows * ROW, barH = Math.max(10, trackH * rows / shown.size());
            int barY = top + (trackH - barH) * scroll / Math.max(1, shown.size() - rows);
            g.fill(px + pw - 8, barY, px + pw - 6, barY + barH, 0x80FFFFFF);
        }
        int by = py + ph - 26, bw = (pw - 26) / 2;
        String pick = chosen < 0 ? "Choose a set" : "Take " + msg.codes().get(chosen);
        Theme.button(g, font, pick, px + 10, by, bw, 18, mx, my, chosen >= 0, true);
        Theme.button(g, font, "Later", px + 16 + bw, by, bw, 18, mx, my, true, false);
        super.render(g, mx, my, partialTick);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int[] p = panel();
        int px = p[0], py = p[1], pw = p[2], ph = p[3];
        int top = listTop(), rows = visibleRows();
        if (mx >= px + 10 && mx < px + pw - 10 && my >= top && my < top + rows * ROW) {
            int r = (int) ((my - top) / ROW);
            if (scroll + r < shown.size()) {
                int i = shown.get(scroll + r);
                if (i == chosen && button == 0) {
                    take();
                } else {
                    chosen = i;
                    Theme.click();
                }
                return true;
            }
        }
        int by = py + ph - 26, bw = (pw - 26) / 2;
        if (my >= by && my < by + 18) {
            if (mx >= px + 10 && mx < px + 10 + bw && chosen >= 0) {
                take();
                return true;
            }
            if (mx >= px + 16 + bw && mx < px + 16 + 2 * bw) {
                Theme.click();
                onClose();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private void take() {
        Net.toServer(new Packets.PickSet(msg.box(), msg.codes().get(chosen)));
        Theme.click();
        onClose();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int max = Math.max(0, shown.size() - visibleRows());
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(delta) * 3));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER && chosen >= 0) {
            take();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

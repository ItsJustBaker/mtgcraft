package dev.mtgcraft.client;

import dev.mtgcraft.MtgClientConfig;
import dev.mtgcraft.net.Net;
import dev.mtgcraft.net.Packets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * Settings, opened from a Magic Table. Server settings (duel rules, loss penalty, gauntlet mode, drops, starter kit)
 * can be changed by the world host or an operator; everyone can change their own (duel intro, starting view).
 */
public class SettingsScreen extends Screen {
    private static Packets.SettingsState state;
    private final Screen parent;
    private int px, py, pw, ph;

    public SettingsScreen(Screen parent) {
        super(Component.literal("Settings"));
        this.parent = parent;
    }

    public static void onState(Packets.SettingsState s) {
        state = s;
    }

    @Override
    protected void init() {
        Net.toServer(new Packets.SettingsRequest());
        pw = Math.min(width - 20, 320);
        ph = Math.min(height - 16, 250);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
    }

    private static String pretty(String key, String v) {
        return switch (v) {
            case "true" -> key.equals("groupFights") ? "Group fights" : "On";
            case "false" -> key.equals("groupFights") ? "1v1 duels" : "Off";
            case "COMMANDER" -> "Commander (40 life, 100 cards)";
            case "CLASSIC" -> "Classic (20 life)";
            case "DEATH" -> "You die (deck box kept)";
            case "DAMAGE" -> "5 hearts of damage";
            case "NONE" -> "Nothing";
            default -> {
                try {
                    double d = Double.parseDouble(v);
                    yield key.endsWith("Chance") ? (d == 0 ? "Never" : String.format(Locale.ROOT, "%.1f%% of kills", d * 100)) : v;
                } catch (NumberFormatException e) {
                    yield v;
                }
            }
        };
    }

    private int rowY(int i) { return py + 34 + i * 20; }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        Theme.drawFelt(g, width, height);
        Theme.panel(g, px, py, pw, ph);
        g.drawCenteredString(font, Component.literal("Settings").withStyle(s -> s.withBold(true)), width / 2, py + 8, Theme.GOLD);
        int labelX = px + 12, btnX = px + pw / 2 - 10, btnW = pw / 2;
        g.drawString(font, "World", labelX, py + 22, Theme.MUTED);
        List<Packets.Setting> server = state == null ? List.of() : state.settings();
        boolean canEdit = state != null && state.canEdit();
        if (state == null) g.drawString(font, "Loading...", labelX, rowY(0) + 4, Theme.MUTED);
        for (int i = 0; i < server.size(); i++) {
            Packets.Setting s = server.get(i);
            g.drawString(font, s.label(), labelX, rowY(i) + 4, Theme.TEXT);
            Theme.button(g, font, pretty(s.key(), s.value()), btnX, rowY(i), btnW, 15, mx, my, canEdit, false);
        }
        int n = Math.max(1, server.size());
        int yy = rowY(n) + 6;
        if (state != null && !canEdit) {
            g.drawString(font, "Only the world host or an operator can change these.", labelX, yy - 4, 0xFF907860);
            yy += 8;
        }
        g.drawString(font, "Just you", labelX, yy + 2, Theme.MUTED);
        int c0 = yy + 14, c1 = c0 + 20;
        g.drawString(font, "Duel intro", labelX, c0 + 4, Theme.TEXT);
        Theme.button(g, font, MtgClientConfig.DUEL_INTRO.get() ? "On" : "Off", btnX, c0, btnW, 15, mx, my, true, false);
        g.drawString(font, "Battlefield view", labelX, c1 + 4, Theme.TEXT);
        Theme.button(g, font, MtgClientConfig.ARENA_VIEW.get() ? "3D arena" : "2D screen", btnX, c1, btnW, 15, mx, my, true, false);
        Theme.button(g, font, "Done", width / 2 - 40, py + ph - 24, 80, 18, mx, my, true, true);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int btnX = px + pw / 2 - 10, btnW = pw / 2;
        List<Packets.Setting> server = state == null ? List.of() : state.settings();
        if (state != null && state.canEdit()) {
            for (int i = 0; i < server.size(); i++) {
                Packets.Setting s = server.get(i);
                if (in(mx, my, btnX, rowY(i), btnW, 15)) {
                    List<String> opts = s.options();
                    int at = opts.indexOf(s.value());
                    String next = opts.get(((at < 0 ? 0 : at) + (button == 1 ? opts.size() - 1 : 1)) % opts.size());
                    Net.toServer(new Packets.SettingsSet(s.key(), next));
                    Theme.click();
                    return true;
                }
            }
        }
        int n = Math.max(1, server.size());
        int yy = rowY(n) + 6 + (state != null && !state.canEdit() ? 8 : 0);
        int c0 = yy + 14, c1 = c0 + 20;
        if (in(mx, my, btnX, c0, btnW, 15)) {
            MtgClientConfig.DUEL_INTRO.set(!MtgClientConfig.DUEL_INTRO.get());
            Theme.click();
            return true;
        }
        if (in(mx, my, btnX, c1, btnW, 15)) {
            MtgClientConfig.ARENA_VIEW.set(!MtgClientConfig.ARENA_VIEW.get());
            Theme.click();
            return true;
        }
        if (in(mx, my, width / 2 - 40, py + ph - 24, 80, 18)) {
            onClose();
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    private static boolean in(double mx, double my, double x, double y, double w, double h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

package dev.mtgcraft.client;

import dev.mtgcraft.net.Packets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * A small popup over the world for duel invites and join offers: a title, a line of text, a countdown and a
 * button per choice. The game keeps running behind it.
 */
public class PromptScreen extends Screen {
    private final Packets.Prompt prompt;
    private final long until;

    public static void show(Packets.Prompt msg) {
        Minecraft mc = Minecraft.getInstance();
        // Don't pull someone out of a duel; the chat buttons still work there.
        if (mc.screen instanceof DuelScreen) return;
        mc.setScreen(new PromptScreen(msg));
        Theme.play(SoundEvents.NOTE_BLOCK_BELL.value(), 1.4f, 0.8f);
    }

    public PromptScreen(Packets.Prompt prompt) {
        super(Component.literal(prompt.title()));
        this.prompt = prompt;
        this.until = prompt.seconds() > 0 ? System.currentTimeMillis() + prompt.seconds() * 1000L : 0;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int[] panel() {
        int pw = Math.min(width - 20, 260);
        int lines = font.split(Component.literal(prompt.body()), pw - 20).size();
        int ph = 40 + lines * 10 + 30;
        return new int[]{(width - pw) / 2, height / 2 - ph / 2 - 20, pw, ph};
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        if (until > 0 && System.currentTimeMillis() > until) {
            onClose();
            return;
        }
        int[] p = panel();
        int px = p[0], py = p[1], pw = p[2], ph = p[3];
        Theme.panel(g, px, py, pw, ph);
        g.drawCenteredString(font, Component.literal(prompt.title()).withStyle(s -> s.withBold(true)), px + pw / 2, py + 8, Theme.GOLD);
        List<FormattedCharSequence> lines = font.split(Component.literal(prompt.body()), pw - 20);
        for (int i = 0; i < lines.size(); i++) g.drawCenteredString(font, lines.get(i), px + pw / 2, py + 22 + i * 10, Theme.TEXT);
        if (until > 0) {
            long left = Math.max(0, until - System.currentTimeMillis());
            // A shrinking bar shows how long the offer lasts.
            int barW = (int) ((pw - 20) * left / (prompt.seconds() * 1000f));
            g.fill(px + 10, py + ph - 34, px + 10 + barW, py + ph - 32, Theme.GOLD);
        }
        int n = prompt.buttons().size();
        int bw = (pw - 20 - (n - 1) * 6) / Math.max(1, n);
        for (int i = 0; i < n; i++) {
            Theme.button(g, font, prompt.buttons().get(i), px + 10 + i * (bw + 6), py + ph - 26, bw, 18, mx, my, true, i == 0);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int[] p = panel();
        int px = p[0], py = p[1], pw = p[2], ph = p[3];
        int n = prompt.buttons().size();
        int bw = (pw - 20 - (n - 1) * 6) / Math.max(1, n);
        for (int i = 0; i < n; i++) {
            int bx = px + 10 + i * (bw + 6), by = py + ph - 26;
            if (mx >= bx && mx < bx + bw && my >= by && my < by + 18) {
                String cmd = prompt.commands().get(i);
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                if (!cmd.isEmpty() && minecraft.player != null) minecraft.player.connection.sendCommand(cmd);
                Theme.click();
                onClose();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }
}

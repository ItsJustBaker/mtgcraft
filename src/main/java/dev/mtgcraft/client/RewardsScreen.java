package dev.mtgcraft.client;

import dev.mtgcraft.net.Packets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** After a won duel: what you got (it's already in your inventory). */
public class RewardsScreen extends Screen {
    private final Packets.Rewards rewards;
    private final long opened = System.currentTimeMillis();

    public static void show(Packets.Rewards msg) {
        if (Boolean.getBoolean("mtgcraft.devDuel")) {
            StringBuilder items = new StringBuilder();
            for (var it : msg.items()) items.append(it.getCount()).append("x ").append(it.getHoverName().getString()).append(", ");
            System.out.println("[MTGCraft dev] rewards: " + msg.title() + " / " + msg.subtitle() + " / " + items);
        }
        Minecraft mc = Minecraft.getInstance();
        // The duel screen shows its result first; open the rewards once it's closed.
        if (mc.screen instanceof DuelScreen ds) {
            ds.queueRewards(msg);
            return;
        }
        mc.setScreen(new RewardsScreen(msg));
    }

    public RewardsScreen(Packets.Rewards rewards) {
        super(Component.literal(rewards.title()));
        this.rewards = rewards;
        Theme.play(SoundEvents.PLAYER_LEVELUP, 1.2f, 0.7f);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int[] panel() {
        int n = rewards.items().size();
        int rows = Math.max(1, (n + 5) / 6);
        int pw = 240, ph = 50 + rows * 26 + 30;
        return new int[]{(width - pw) / 2, (height - ph) / 2, pw, ph};
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        g.fill(0, 0, width, height, 0x88000000);
        int[] p = panel();
        int px = p[0], py = p[1], pw = p[2], ph = p[3];
        Theme.panel(g, px, py, pw, ph);
        g.pose().pushPose();
        g.pose().translate(px + pw / 2f, py + 8, 0);
        g.pose().scale(1.5f, 1.5f, 1);
        g.drawCenteredString(font, rewards.title(), 0, 0, Theme.GOLD);
        g.pose().popPose();
        g.drawCenteredString(font, rewards.subtitle(), px + pw / 2, py + 24, Theme.MUTED);
        List<ItemStack> items = rewards.items();
        int perRow = Math.min(6, Math.max(1, items.size()));
        int gridW = perRow * 36;
        ItemStack hover = null;
        for (int i = 0; i < items.size(); i++) {
            // Each reward pops in one after the other.
            if (System.currentTimeMillis() - opened < i * 180L) break;
            int x = px + (pw - gridW) / 2 + (i % 6) * 36 + 10, y = py + 40 + (i / 6) * 26;
            Theme.rounded(g, x - 3, y - 3, 22, 22, 0x40FFFFFF);
            g.renderItem(items.get(i), x, y);
            g.renderItemDecorations(font, items.get(i), x, y);
            if (mx >= x - 3 && mx < x + 19 && my >= y - 3 && my < y + 19) hover = items.get(i);
        }
        if (items.isEmpty()) g.drawCenteredString(font, "Nothing this time.", px + pw / 2, py + 46, Theme.MUTED);
        Theme.button(g, font, "Collect", px + pw / 2 - 45, py + ph - 26, 90, 18, mx, my, true, true);
        if (hover != null) g.renderTooltip(font, hover, mx, my);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int[] p = panel();
        if (mx >= p[0] + p[2] / 2 - 45 && mx < p[0] + p[2] / 2 + 45 && my >= p[1] + p[3] - 26 && my < p[1] + p[3] - 8) {
            Theme.click();
            onClose();
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }
}

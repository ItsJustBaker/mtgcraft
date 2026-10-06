package dev.mtgcraft.client;

import dev.mtgcraft.engine.Cards;
import dev.mtgcraft.item.BinderItem;
import dev.mtgcraft.item.CardBag;
import dev.mtgcraft.item.CardItem;
import dev.mtgcraft.net.Net;
import dev.mtgcraft.net.Packets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A card binder, page by page like TCG Card Shop Simulator: two 3x3 pages per spread. Click a card to take it out
 * (shift: every copy); click a loose card along the bottom to file it (shift: the whole stack).
 */
public class BinderScreen extends Screen {
    private record Cell(String key, boolean foil, int count) {}

    private final InteractionHand hand;
    private EditBox search;
    private final CardFilter filter = new CardFilter();
    private int spread;
    private float pageTurn; // animation: 0 at rest, +-1 just turned
    private Cell hovered;

    public static void open(InteractionHand hand) {
        Minecraft.getInstance().setScreen(new BinderScreen(hand));
    }

    public BinderScreen(InteractionHand hand) {
        super(Component.literal("Binder"));
        this.hand = hand;
    }

    private ItemStack binder() {
        ItemStack s = minecraft.player.getItemInHand(hand);
        return s.getItem() instanceof BinderItem ? s : ItemStack.EMPTY;
    }

    @Override
    protected void init() {
        search = new EditBox(font, width / 2 - 70, 6, 140, 14, Component.literal("Search"));
        search.setHint(Component.literal("Search binder..."));
        search.setResponder(v -> spread = 0);
        addRenderableWidget(search);
    }

    private List<Cell> pages() {
        List<Cell> out = new ArrayList<>();
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        for (CardBag.Entry e : CardBag.entries(binder())) {
            if (!filter.matches(e.key(), e.foil(), q)) continue;
            out.add(new Cell(e.key(), e.foil(), e.count()));
        }
        var order = CardArt.collectionOrder();
        out.sort((a, b) -> order.compare(a.key(), b.key()));
        return out;
    }

    private List<Cell> loose() {
        Map<String, Cell> agg = new LinkedHashMap<>();
        for (ItemStack s : minecraft.player.getInventory().items) {
            if (!(s.getItem() instanceof CardItem) || CardItem.key(s) == null) continue;
            String id = CardItem.key(s) + (CardItem.foil(s) ? "#f" : "");
            Cell c = agg.get(id);
            agg.put(id, new Cell(CardItem.key(s), CardItem.foil(s), (c == null ? 0 : c.count()) + s.getCount()));
        }
        return new ArrayList<>(agg.values());
    }

    // ------------------------------------------------------------------ layout

    private float cardH() { return Math.min((height - 120f) / 3f - 6, ((width / 2f - 40) / 3f - 6) * 88f / 63f); }
    private float cardW() { return cardH() * 63f / 88f; }
    private float pageW() { return 3 * (cardW() + 6) + 10; }
    private float bookX() { return width / 2f - pageW(); }
    private float bookY() { return 26; }

    /** Position of slot i (0-17) in the current spread. */
    private float[] slotPos(int i) {
        int page = i / 9, idx = i % 9;
        float x = bookX() + page * (pageW() + 6) + 8 + (idx % 3) * (cardW() + 6);
        float y = bookY() + 8 + (idx / 3) * (cardH() + 6);
        return new float[]{x, y};
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        renderBackground(g);
        if (binder().isEmpty()) {
            onClose();
            return;
        }
        pageTurn *= 0.82f;
        List<Cell> cells = pages();
        int spreads = Math.max(1, (cells.size() + 17) / 18);
        spread = Math.min(spread, spreads - 1);
        hovered = null;

        // the open book
        float bx = bookX(), by = bookY(), pw = pageW(), ph = 3 * (cardH() + 6) + 10;
        Theme.rounded(g, (int) bx - 6, (int) by - 4, (int) (pw * 2 + 18), (int) ph + 8, 0xFF3A2414);
        for (int p = 0; p < 2; p++) {
            float px = bx + p * (pw + 6);
            Theme.rounded(g, (int) px, (int) by, (int) pw, (int) ph, 0xFF20262A);
            for (int i = 0; i < 9; i++) {
                float[] pos = slotPos(p * 9 + i);
                Theme.rounded(g, (int) pos[0], (int) pos[1], (int) cardW(), (int) cardH(), 0xFF141A1E);
            }
        }
        g.fill((int) (bx + pw), (int) by, (int) (bx + pw + 6), (int) (by + ph), 0xFF2A1A10);

        float slide = pageTurn * 30;
        for (int i = 0; i < 18; i++) {
            int idx = spread * 18 + i;
            if (idx >= cells.size()) break;
            Cell c = cells.get(idx);
            float[] pos = slotPos(i);
            float x = pos[0] + slide, y = pos[1];
            boolean hover = mx >= x && mx < x + cardW() && my >= y && my < y + cardH();
            if (hover) hovered = c;
            CardArt.draw(g, c.key(), c.foil(), x, y, cardW(), cardH(), 1);
            if (c.count() > 1) badge(g, "×" + c.count(), x + cardW() - 2, y + cardH() - 2);
            if (hover) outline(g, x, y, cardW(), cardH(), 0xFFFFFFFF);
        }

        String pageLabel = "Pages " + (spread * 2 + 1) + "–" + (spread * 2 + 2) + " of " + spreads * 2;
        g.drawCenteredString(font, pageLabel, width / 2, (int) (by + ph + 6), Theme.MUTED);
        Theme.button(g, font, "◀", (int) bx - 2, (int) (by + ph + 3), 20, 14, mx, my, spread > 0, false);
        Theme.button(g, font, "▶", (int) (bx + pw * 2 + 6 - 18), (int) (by + ph + 3), 20, 14, mx, my, spread < spreads - 1, false);

        // loose cards in the inventory
        List<Cell> bag = loose();
        float sy = by + ph + 22;
        g.drawString(font, "In your inventory (" + bag.size() + ") — click to file", (int) bx, (int) sy, Theme.TEXT);
        Theme.button(g, font, "File all", (int) (bx + pw * 2 + 12 - 60), (int) sy - 3, 60, 13, mx, my, !bag.isEmpty(), true);
        float sh = Math.min(height - sy - 16, cardH() * 0.7f), sw = sh * 63f / 88f;
        float step = bag.size() <= 1 ? 0 : Math.min(sw + 3, (pw * 2 + 12 - sw) / (bag.size() - 1));
        for (int i = 0; i < bag.size(); i++) {
            Cell c = bag.get(i);
            float x = bx + i * step, y = sy + 12;
            boolean hover = mx >= x && mx < x + Math.max(step, 4) && my >= y && my < y + sh || (i == bag.size() - 1 && mx >= x && mx < x + sw && my >= y && my < y + sh);
            if (hover) hovered = c;
            CardArt.draw(g, c.key(), c.foil(), x, y - (hover ? 4 : 0), sw, sh, 1);
            if (c.count() > 1) badge(g, "×" + c.count(), x + sw - 2, y + sh - 2);
        }

        g.drawString(font, CardBag.total(binder()) + " cards", 6, 8, Theme.MUTED);
        super.render(g, mx, my, partialTick);
        filter.render(g, font, width / 2 + 76, 6, mx, my);
        if (hovered != null) CardArt.preview(g, hovered.key(), hovered.foil(), mx, width, height);
    }

    private void badge(GuiGraphics g, String text, float rx, float by) {
        int w = font.width(text) + 4;
        Theme.rounded(g, (int) rx - w, (int) by - 11, w, 11, 0xE0101010);
        g.drawString(font, text, (int) rx - w + 2, (int) by - 9, Theme.GOLD, false);
    }

    private static void outline(GuiGraphics g, float x, float y, float w, float h, int c) {
        var m = g.pose().last().pose();
        Theme.fillF(g, m, x - 1, y - 1, x + w + 1, y, c);
        Theme.fillF(g, m, x - 1, y + h, x + w + 1, y + h + 1, c);
        Theme.fillF(g, m, x - 1, y, x, y + h, c);
        Theme.fillF(g, m, x + w, y, x + w + 1, y + h, c);
    }

    // ------------------------------------------------------------------ input

    private void send(Packets.CollOp op, Cell c, int count) {
        Net.toServer(new Packets.CollectionOp(hand == InteractionHand.OFF_HAND, op, c == null ? "" : c.key(),
                c != null && c.foil(), count));
        Theme.play(SoundEvents.BOOK_PAGE_TURN, 1.4f, 0.5f);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (filter.click(mx, my, button, width / 2 + 76, 6, () -> spread = 0)) return true;
        if (super.mouseClicked(mx, my, button)) return true;
        List<Cell> cells = pages();
        int spreads = Math.max(1, (cells.size() + 17) / 18);
        float bx = bookX(), by = bookY(), pw = pageW(), ph = 3 * (cardH() + 6) + 10;
        if (in(mx, my, bx - 2, by + ph + 3, 20, 14) && spread > 0) {
            spread--;
            pageTurn = -1;
            Theme.play(SoundEvents.BOOK_PAGE_TURN, 1f, 0.8f);
            return true;
        }
        if (in(mx, my, bx + pw * 2 + 6 - 18, by + ph + 3, 20, 14) && spread < spreads - 1) {
            spread++;
            pageTurn = 1;
            Theme.play(SoundEvents.BOOK_PAGE_TURN, 1f, 0.8f);
            return true;
        }
        float sy = by + ph + 22;
        if (in(mx, my, bx + pw * 2 + 12 - 60, sy - 3, 60, 13)) {
            send(Packets.CollOp.BINDER_PUT_ALL, null, 0);
            return true;
        }
        if (hovered == null) return false;
        boolean all = hasShiftDown();
        boolean inBinder = my < by + ph;
        if (inBinder) send(Packets.CollOp.BINDER_TAKE, hovered, all ? hovered.count() : 1);
        else send(Packets.CollOp.BINDER_PUT, hovered, all ? hovered.count() : 1);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int spreads = Math.max(1, (pages().size() + 17) / 18);
        if (delta < 0 && spread < spreads - 1) { spread++; pageTurn = 1; }
        else if (delta > 0 && spread > 0) { spread--; pageTurn = -1; }
        return true;
    }

    private static boolean in(double mx, double my, double x, double y, double w, double h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

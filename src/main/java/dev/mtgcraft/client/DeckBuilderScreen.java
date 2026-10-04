package dev.mtgcraft.client;

import dev.mtgcraft.engine.Cards;
import dev.mtgcraft.engine.ForgeEngine;
import dev.mtgcraft.item.BinderItem;
import dev.mtgcraft.item.CardBag;
import dev.mtgcraft.item.CardItem;
import dev.mtgcraft.item.DeckBoxItem;
import dev.mtgcraft.net.Net;
import dev.mtgcraft.net.Packets;
import forge.item.PaperCard;
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
 * Builds the deck in a Deck Box from the cards you carry (loose cards and binders). Cards really move: adding one
 * takes it out of your collection, removing puts it back in a binder. Basic lands are free.
 */
public class DeckBuilderScreen extends Screen {
    private record Cell(String key, boolean foil, int count) {}

    private static final int ROW = 11;
    private final InteractionHand hand;
    private EditBox search;
    private int page;
    private int deckScroll;
    private Cell hovered;
    private boolean hoveredInDeck;
    /** The collection card whose crown (make commander) button is under the mouse. */
    private Cell crownHover;
    private float[] crownRect;

    private static final int SLOT_W = 40, SLOT_H = 56;

    public static void open(InteractionHand hand) {
        Minecraft.getInstance().setScreen(new DeckBuilderScreen(hand));
    }

    public DeckBuilderScreen(InteractionHand hand) {
        super(Component.literal("Deck Box"));
        this.hand = hand;
    }

    private ItemStack box() {
        ItemStack s = minecraft.player.getItemInHand(hand);
        return s.getItem() instanceof DeckBoxItem ? s : ItemStack.EMPTY;
    }

    @Override
    protected void init() {
        search = new EditBox(font, 8, 6, Math.min(160, leftW() - 16), 14, Component.literal("Search"));
        search.setHint(Component.literal("Search collection..."));
        search.setResponder(v -> page = 0);
        addRenderableWidget(search);
    }

    private int leftW() { return (int) (width * 0.6f); }

    /** Every card you carry, loose or in binders, merged by printing. */
    private List<Cell> collection() {
        Map<String, Cell> agg = new LinkedHashMap<>();
        List<ItemStack> stacks = new ArrayList<>(minecraft.player.getInventory().items);
        stacks.add(minecraft.player.getOffhandItem());
        for (ItemStack s : stacks) {
            if (s.getItem() instanceof CardItem && CardItem.key(s) != null) {
                merge(agg, CardItem.key(s), CardItem.foil(s), s.getCount());
            } else if (s.getItem() instanceof BinderItem) {
                for (CardBag.Entry e : CardBag.entries(s)) merge(agg, e.key(), e.foil(), e.count());
            }
        }
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        List<Cell> out = new ArrayList<>();
        for (Cell c : agg.values()) {
            if (q.isEmpty() || Cards.name(c.key()).toLowerCase(Locale.ROOT).contains(q)) out.add(c);
        }
        var order = CardArt.collectionOrder();
        out.sort((a, b) -> order.compare(a.key(), b.key()));
        return out;
    }

    private static void merge(Map<String, Cell> agg, String key, boolean foil, int n) {
        String id = key + (foil ? "#f" : "");
        Cell c = agg.get(id);
        agg.put(id, new Cell(key, foil, (c == null ? 0 : c.count()) + n));
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        Theme.drawFelt(g, width, height);
        if (box().isEmpty()) {
            onClose();
            return;
        }
        hovered = null;
        crownHover = null;
        drawCollection(g, mx, my);
        drawDeck(g, mx, my);
        super.render(g, mx, my, partialTick);
        if (hovered != null) CardArt.preview(g, hovered.key(), hovered.foil(), mx, width, height);
    }

    private int perPage(float cw, float ch) {
        int cols = (int) ((leftW() - 16) / (cw + 5));
        int rows = (int) ((height - 50) / (ch + 5));
        return Math.max(1, cols * rows);
    }

    private void drawCollection(GuiGraphics g, int mx, int my) {
        List<Cell> cells = collection();
        float ch = Math.max(48, Math.min(96, height / 3.6f)), cw = ch * 63f / 88f;
        int cols = Math.max(1, (int) ((leftW() - 16) / (cw + 5)));
        int per = perPage(cw, ch);
        int pages = Math.max(1, (cells.size() + per - 1) / per);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < per; i++) {
            int idx = page * per + i;
            if (idx >= cells.size()) break;
            Cell c = cells.get(idx);
            float x = 8 + (i % cols) * (cw + 5), y = 26 + (i / cols) * (ch + 5);
            boolean hover = mx >= x && mx < x + cw && my >= y && my < y + ch;
            if (hover) {
                hovered = c;
                hoveredInDeck = false;
            }
            CardArt.draw(g, c.key(), c.foil(), x, y - (hover ? 3 : 0), cw, ch, 1);
            if (hover && canLead(c.key())) {
                // Crown button: make this legend the deck's commander.
                float bx = x + 2, by = y - 1, bs = 14;
                boolean onCrown = mx >= bx && mx < bx + bs && my >= by && my < by + bs;
                Theme.rounded(g, (int) bx, (int) by, (int) bs, (int) bs, onCrown ? 0xF0E0B65A : 0xD0201810);
                g.drawCenteredString(font, "♛", (int) (bx + bs / 2), (int) by + 3, onCrown ? 0xFF201808 : Theme.GOLD);
                if (onCrown) {
                    crownHover = c;
                    crownRect = new float[]{bx, by, bs, bs};
                }
            }
            if (c.count() > 1) {
                String t = "×" + c.count();
                int w = font.width(t) + 4;
                Theme.rounded(g, (int) (x + cw - w - 1), (int) (y + ch - 12), w, 11, 0xE0101010);
                g.drawString(font, t, (int) (x + cw - w + 1), (int) (y + ch - 10), Theme.GOLD, false);
            }
        }
        if (cells.isEmpty()) {
            g.drawString(font, "No cards with you. Open packs, or carry a binder.", 8, 30, Theme.MUTED);
        }
        g.drawString(font, "Page " + (page + 1) + "/" + pages + " · scroll to turn", 8, height - 12, Theme.MUTED);
        if (hovered != null && !hoveredInDeck) {
            String hint = crownHover != null ? "Make commander" : "Click: add · Shift: add 4";
            int hw = font.width(hint) + 6;
            int hx = Math.min(mx + 10, leftW() - hw - 2), hy = my + 12;
            Theme.rounded(g, hx, hy, hw, 12, 0xE0101010);
            g.drawString(font, hint, hx + 3, hy + 2, Theme.TEXT, false);
        }
    }

    private void drawDeck(GuiGraphics g, int mx, int my) {
        int x = leftW() + 4, w = width - x - 6;
        Theme.panel(g, x, 4, w, height - 8);
        ItemStack box = box();
        int total = CardBag.total(box);
        String cmd = DeckBoxItem.commander(box);
        int need = cmd != null ? DeckBoxItem.COMMANDER_CARDS : DeckBoxItem.MIN_CARDS;
        String target = cmd != null ? " / " + need + " Commander" : " / " + need;
        g.drawString(font, "Deck " + total + target, x + 6, 9, total >= need ? Theme.GREEN : Theme.GOLD);

        // commander slot
        int sx = x + 6, sy = 21;
        Theme.rounded(g, sx - 1, sy - 1, SLOT_W + 2, SLOT_H + 2, cmd != null ? Theme.GOLD : 0xFF5A4A28);
        Theme.rounded(g, sx, sy, SLOT_W, SLOT_H, 0xFF141210);
        int tx = sx + SLOT_W + 6, tw = w - SLOT_W - 18;
        if (cmd != null) {
            CardArt.draw(g, cmd, false, sx, sy, SLOT_W, SLOT_H, 1);
            if (mx >= sx && mx < sx + SLOT_W && my >= sy && my < sy + SLOT_H) {
                hovered = new Cell(cmd, false, 1);
                hoveredInDeck = true;
            }
            g.drawString(font, "Commander", tx, sy + 2, Theme.MUTED);
            g.drawString(font, Theme.ellipsize(font, Cards.name(cmd), tw), tx, sy + 14, Theme.GOLD);
            Theme.button(g, font, "Clear", tx, sy + 30, 40, 13, mx, my, true, false);
        } else {
            g.drawCenteredString(font, "♛", sx + SLOT_W / 2, sy + SLOT_H / 2 - 4, 0xFF5A4A28);
            g.drawString(font, "No commander", tx, sy + 2, Theme.MUTED);
            for (var line : font.split(Component.literal("Hover a legendary card on the left and click ♛ for a 100-card Commander deck."), tw)) {
                sy += 10;
                g.drawString(font, line, tx, sy + 4, 0xFF808880, false);
            }
            sy = 21;
        }

        // free basic lands
        int by = sy + SLOT_H + 6;
        for (int i = 0; i < 5; i++) {
            int bx = x + 6 + i * ((w - 12) / 5);
            int count = basicCount(DeckBoxItem.BASICS[i]);
            Theme.manaOrb(g, bx + 9, by + 8, 7, i, true, false);
            g.drawString(font, String.valueOf(count), bx + 19, by + 4, Theme.TEXT);
            Theme.button(g, font, "+", bx + 2, by + 18, 12, 11, mx, my, true, false);
            Theme.button(g, font, "−", bx + 16, by + 18, 12, 11, mx, my, count > 0, false);
        }

        // mana curve
        int[] curve = new int[8];
        List<CardBag.Entry> creatures = new ArrayList<>(), spells = new ArrayList<>(), lands = new ArrayList<>();
        for (CardBag.Entry e : CardBag.entries(box)) {
            PaperCard pc = ForgeEngine.state() == ForgeEngine.State.READY ? Cards.card(e.key()) : null;
            if (pc == null || pc.getRules().getType().isLand()) {
                lands.add(e);
                continue;
            }
            int cmc = Math.min(7, pc.getRules().getManaCost().getCMC());
            curve[cmc] += e.count();
            (pc.getRules().getType().isCreature() ? creatures : spells).add(e);
        }
        int cy = by + 34, maxBar = 1;
        for (int v : curve) maxBar = Math.max(maxBar, v);
        int barW = (w - 12) / 8;
        for (int i = 0; i < 8; i++) {
            int bh = curve[i] * 20 / maxBar;
            g.fill(x + 6 + i * barW + 1, cy + 20 - bh, x + 6 + (i + 1) * barW - 1, cy + 20, 0xC0E0B65A);
            g.drawCenteredString(font, i == 7 ? "7+" : String.valueOf(i), x + 6 + i * barW + barW / 2, cy + 22, Theme.MUTED);
        }

        // list
        int ly = cy + 34;
        int rowsFit = (height - ly - 10) / ROW;
        List<Object> rows = new ArrayList<>();
        addGroup(rows, "Creatures", creatures);
        addGroup(rows, "Spells", spells);
        addGroup(rows, "Lands", lands);
        deckScroll = Math.max(0, Math.min(deckScroll, Math.max(0, rows.size() - rowsFit)));
        g.enableScissor(x, ly, x + w, height - 8);
        for (int i = 0; i < rows.size() - deckScroll && i < rowsFit + 1; i++) {
            Object r = rows.get(i + deckScroll);
            int ry = ly + i * ROW;
            if (r instanceof String header) {
                g.drawString(font, header, x + 6, ry + 2, Theme.GOLD);
            } else if (r instanceof CardBag.Entry e) {
                boolean hover = mx >= x && mx < x + w && my >= ry && my < ry + ROW;
                if (hover) {
                    g.fill(x + 2, ry, x + w - 2, ry + ROW, 0x40FFFFFF);
                    hovered = new Cell(e.key(), e.foil(), e.count());
                    hoveredInDeck = true;
                }
                boolean isCmd = e.key().equals(DeckBoxItem.commander(box));
                String label = (isCmd ? "★ " : "") + e.count() + "  " + Cards.name(e.key()) + (e.foil() ? " ✦" : "");
                g.drawString(font, Theme.ellipsize(font, label, w - 14), x + 8, ry + 2, Theme.TEXT, false);
            }
        }
        g.disableScissor();
    }

    private static void addGroup(List<Object> rows, String title, List<CardBag.Entry> entries) {
        if (entries.isEmpty()) return;
        int n = 0;
        for (CardBag.Entry e : entries) n += e.count();
        rows.add(title + " (" + n + ")");
        entries.sort((a, b) -> Cards.name(a.key()).compareTo(Cards.name(b.key())));
        rows.addAll(entries);
    }

    private static boolean canLead(String key) {
        PaperCard pc = ForgeEngine.state() == ForgeEngine.State.READY ? Cards.card(key) : null;
        return pc != null && pc.getRules().canBeCommander();
    }

    private int basicCount(String name) {
        int n = 0;
        for (CardBag.Entry e : CardBag.entries(box())) if (Cards.name(e.key()).equals(name)) n += e.count();
        return n;
    }

    // ------------------------------------------------------------------ input

    private void send(Packets.CollOp op, String key, boolean foil, int count) {
        Net.toServer(new Packets.CollectionOp(hand == InteractionHand.OFF_HAND, op, key, foil, count));
        Theme.play(SoundEvents.BOOK_PUT, 1.3f, 0.5f);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        int x = leftW() + 4, w = width - x - 6, by = 21 + SLOT_H + 6;
        if (crownHover != null) {
            send(Packets.CollOp.DECK_COMMANDER, crownHover.key(), crownHover.foil(), 1);
            return true;
        }
        String cmd = DeckBoxItem.commander(box());
        if (cmd != null && in(mx, my, x + 6 + SLOT_W + 6, 21 + 30, 40, 13)) {
            send(Packets.CollOp.DECK_COMMANDER, cmd, false, 1);
            return true;
        }
        for (int i = 0; i < 5; i++) {
            int bx = x + 6 + i * ((w - 12) / 5);
            String name = DeckBoxItem.BASICS[i];
            if (in(mx, my, bx + 2, by + 18, 12, 11)) {
                send(Packets.CollOp.DECK_ADD, name, false, hasShiftDown() ? 5 : 1);
                return true;
            }
            if (in(mx, my, bx + 16, by + 18, 12, 11)) {
                for (CardBag.Entry e : CardBag.entries(box())) {
                    if (Cards.name(e.key()).equals(name)) {
                        send(Packets.CollOp.DECK_REMOVE, e.key(), e.foil(), hasShiftDown() ? 5 : 1);
                        break;
                    }
                }
                return true;
            }
        }
        if (hovered == null) return false;
        if (hoveredInDeck) send(Packets.CollOp.DECK_REMOVE, hovered.key(), hovered.foil(), hasShiftDown() ? hovered.count() : 1);
        else send(Packets.CollOp.DECK_ADD, hovered.key(), hovered.foil(), hasShiftDown() ? Math.min(4, hovered.count()) : 1);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx > leftW()) {
            deckScroll = Math.max(0, deckScroll - (int) Math.signum(delta) * 2);
        } else {
            page = Math.max(0, page - (int) Math.signum(delta));
        }
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

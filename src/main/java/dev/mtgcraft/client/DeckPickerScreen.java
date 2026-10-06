package dev.mtgcraft.client;

import dev.mtgcraft.engine.DeckChoice;
import dev.mtgcraft.engine.Decks;
import dev.mtgcraft.engine.Duels;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Picks one seat's deck: generated from colours, one of Card-Forge's preconstructed decks, or the player's own
 * (files in the decks folder, or a decklist pasted from the clipboard). Own decks are sent as their full text,
 * because the game runs on the server and can't read this computer's files.
 */
public class DeckPickerScreen extends Screen {
    private static final String[] TABS = {"Colours", "Precons", "My decks", "Commander", "Types"};
    private static final int ROW_H = 11;
    // Remembered between visits.
    private static int tab;
    private static final boolean[] COLORS = {false, false, false, true, true};
    private static String precon;
    private static String user;
    private static String cmdPrecon; // null = random commander deck
    private static String tribe = "Angel";

    private final Screen parent;
    private final Consumer<DeckChoice> onPick;
    private final boolean allowOwn;
    private List<String> precons = List.of();
    private List<String> userDecks = List.of();
    private List<String> cmdPrecons = List.of();
    private EditBox search;
    private int scroll;
    private String status = "";
    private int statusColor = Theme.MUTED;
    private int px, py, pw, ph;

    /** @param allowOwn false for AI seats on a server, where "own deck" makes less sense but still works */
    public DeckPickerScreen(Screen parent, String title, boolean allowOwn, Consumer<DeckChoice> onPick) {
        super(Component.literal(title));
        this.parent = parent;
        this.onPick = onPick;
        this.allowOwn = allowOwn;
    }

    @Override
    protected void init() {
        pw = Math.min(width - 20, 300);
        ph = Math.min(height - 16, 260);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        precons = Decks.precons();
        userDecks = Decks.userDecks();
        cmdPrecons = Decks.commanderPrecons();
        String old = search == null ? "" : search.getValue();
        search = new EditBox(font, px + 10, py + 44, pw - 20, 14, Component.literal("Search"));
        search.setHint(Component.literal("Search decks..."));
        search.setValue(old);
        search.setResponder(v -> scroll = 0);
        addRenderableWidget(search);
        addRenderableWidget(Button.builder(Component.literal("Use this deck"), b -> pick())
                .bounds(px + pw / 2 - 104, py + ph - 26, 120, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                .bounds(px + pw / 2 + 22, py + ph - 26, 82, 20).build());
    }

    private void pick() {
        DeckChoice choice;
        switch (tab) {
            case 1 -> choice = DeckChoice.precon(precon);
            case 3 -> choice = DeckChoice.commanderPrecon(cmdPrecon);
            case 4 -> choice = DeckChoice.tribe(tribe);
            case 2 -> {
                if (user == null) {
                    setStatus("Pick one of your decks first.", Theme.RED);
                    return;
                }
                try {
                    choice = DeckChoice.inline(Decks.toText(Decks.userDeck(user)));
                } catch (Exception e) {
                    setStatus("Couldn't read " + user + ": " + e.getMessage(), Theme.RED);
                    return;
                }
            }
            default -> {
                List<String> c = new ArrayList<>();
                for (int i = 0; i < 5; i++) if (COLORS[i]) c.add(Duels.COLORS.get(i));
                choice = DeckChoice.colors(c);
            }
        }
        onPick.accept(choice);
        onClose();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private void setStatus(String s, int color) {
        status = s;
        statusColor = color;
    }

    @Override
    public void tick() {
        search.visible = tab == 1 || tab == 3;
        search.tick();
    }

    private int listTop() { return py + 62; }
    private int listBottom() { return py + ph - 34 - (tab == 2 ? 22 : 0); }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        Theme.drawFelt(g, width, height);
        Theme.panel(g, px, py, pw, ph);
        g.drawCenteredString(font, title, width / 2, py + 8, Theme.GOLD);
        int tw = (pw - 28) / 5;
        for (int i = 0; i < 5; i++) {
            boolean enabled = i != 2 || allowOwn;
            Theme.button(g, font, TABS[i], px + 10 + i * (tw + 2), py + 24, tw, 14, mx, my, enabled, tab == i);
        }
        int x = px + 10, w = pw - 20;
        switch (tab) {
            case 0 -> {
                int ox = x + (w - (5 * 26 - 4)) / 2;
                for (int i = 0; i < 5; i++) {
                    int bx = ox + i * 26;
                    Theme.manaOrb(g, bx + 11, py + 60, 10, i, COLORS[i], in(mx, my, bx, py + 49, 22, 22));
                }
                List<FormattedCharSequence> lines = font.split(Component.literal(
                        "Pick up to 3 colours (none = random). A fresh deck is built from Modern-legal cards every game."), w);
                for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x, py + 80 + i * 10, Theme.MUTED);
            }
            case 1 -> drawList(g, x, w, filtered(precons), precon, "Random precon", mx, my);
            case 4 -> {
                g.drawString(font, "What do you want to play?", x, py + 46, Theme.MUTED, false);
                String[][] tribes = dev.mtgcraft.engine.Tribal.TRIBES;
                int cols = 5, cw = (w - (cols - 1) * 3) / cols;
                for (int i = 0; i < tribes.length; i++) {
                    int bx = x + (i % cols) * (cw + 3), by = py + 58 + (i / cols) * 15;
                    Theme.button(g, font, tribes[i][0], bx, by, cw, 13, mx, my, true, tribes[i][1].equals(tribe));
                }
                int ty = py + 58 + ((tribes.length + cols - 1) / cols) * 15 + 2;
                List<FormattedCharSequence> lines = font.split(Component.literal(
                        "A deck full of " + dev.mtgcraft.engine.Tribal.label(tribe).toLowerCase(Locale.ROOT)
                                + ". In Commander games, one of them leads it."), w);
                for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x, ty + i * 10, Theme.MUTED, false);
            }
            case 3 -> drawList(g, x, w, filtered(cmdPrecons), cmdPrecon, "Random commander deck", mx, my);
            case 2 -> {
                if (userDecks.isEmpty()) {
                    List<FormattedCharSequence> help = font.split(Component.literal(
                            "No decks yet. Copy a decklist (Arena, MTGO or a deck site export) and press Paste, " +
                            "or drop .dck/.txt files into the decks folder."), w);
                    for (int i = 0; i < help.size(); i++) g.drawString(font, help.get(i), x, py + 48 + i * 10, Theme.MUTED);
                } else {
                    drawList(g, x, w, userDecks, user, null, mx, my);
                }
                int by = py + ph - 52, bw = (w - 4) / 2;
                Theme.button(g, font, "Paste decklist", x, by, bw, 16, mx, my, true, false);
                Theme.button(g, font, "Open folder", x + bw + 4, by, bw, 16, mx, my, true, false);
            }
        }
        if (!status.isEmpty()) {
            g.drawCenteredString(font, Theme.ellipsize(font, status, pw - 20), width / 2, py + ph - 38, statusColor);
        }
        super.render(g, mx, my, partialTick);
    }

    private List<String> filtered(List<String> names) {
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return names;
        List<String> out = new ArrayList<>();
        for (String n : names) if (n.toLowerCase(Locale.ROOT).contains(q)) out.add(n);
        return out;
    }

    private void drawList(GuiGraphics g, int x, int w, List<String> items, String selected, String randomLabel, int mx, int my) {
        int top = listTop(), bottom = listBottom();
        Theme.rounded(g, x, top, w, bottom - top, 0x50000000);
        g.enableScissor(x, top, x + w, bottom);
        int total = items.size() + (randomLabel != null ? 1 : 0);
        int visible = (bottom - top - 4) / ROW_H;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - visible)));
        for (int row = 0; row < visible && row + scroll < total; row++) {
            int idx = row + scroll;
            String name = randomLabel != null ? (idx == 0 ? null : items.get(idx - 1)) : items.get(idx);
            String label = name == null ? "✦ " + randomLabel : name.replaceFirst("\\.(dck|txt|dec)$", "");
            int ry = top + 2 + row * ROW_H;
            boolean sel = name == null ? selected == null && randomLabel != null : name.equals(selected);
            boolean hover = in(mx, my, x, ry, w, ROW_H);
            if (sel) g.fill(x + 1, ry, x + w - 1, ry + ROW_H, 0x90705A26);
            else if (hover) g.fill(x + 1, ry, x + w - 1, ry + ROW_H, 0x40FFFFFF);
            g.drawString(font, Theme.ellipsize(font, label, w - 8), x + 4, ry + 2, sel ? Theme.GOLD : Theme.TEXT, false);
        }
        g.disableScissor();
        if (total > visible) {
            int barH = Math.max(8, (bottom - top) * visible / total);
            int barY = top + (bottom - top - barH) * scroll / Math.max(1, total - visible);
            g.fill(x + w - 3, barY, x + w - 1, barY + barH, 0x80E0B65A);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int x = px + 10, w = pw - 20;
        int tw = (pw - 28) / 5;
        for (int i = 0; i < 5; i++) {
            if (in(mx, my, px + 10 + i * (tw + 2), py + 24, tw, 14) && (i != 2 || allowOwn)) {
                tab = i;
                scroll = 0;
                if (i == 2) userDecks = Decks.userDecks();
                Theme.click();
                return true;
            }
        }
        if (tab == 0) {
            int ox = x + (w - (5 * 26 - 4)) / 2;
            for (int i = 0; i < 5; i++) {
                if (in(mx, my, ox + i * 26, py + 49, 22, 22)) {
                    COLORS[i] = !COLORS[i];
                    int n = 0;
                    for (boolean b : COLORS) if (b) n++;
                    if (n > 3) COLORS[i] = false; // the generator builds 1-3 colour decks
                    Theme.click();
                    return true;
                }
            }
        }
        if (tab == 4) {
            String[][] tribes = dev.mtgcraft.engine.Tribal.TRIBES;
            int cols = 5, cw = (w - (cols - 1) * 3) / cols;
            for (int i = 0; i < tribes.length; i++) {
                if (in(mx, my, x + (i % cols) * (cw + 3), py + 58 + (i / cols) * 15, cw, 13)) {
                    tribe = tribes[i][1];
                    Theme.click();
                    return true;
                }
            }
        }
        if (tab == 2) {
            int by = py + ph - 52, bw = (w - 4) / 2;
            if (in(mx, my, x, by, bw, 16)) {
                pasteDeck();
                return true;
            }
            if (in(mx, my, x + bw + 4, by, bw, 16)) {
                try {
                    java.nio.file.Files.createDirectories(Decks.userDir());
                } catch (Exception ignored) {
                }
                Util.getPlatform().openFile(Decks.userDir().toFile());
                return true;
            }
        }
        if (tab >= 1 && tab <= 3 && in(mx, my, x, listTop(), w, listBottom() - listTop())) {
            int idx = (int) ((my - listTop() - 2) / ROW_H) + scroll;
            if (tab == 1) {
                List<String> items = filtered(precons);
                if (idx == 0) precon = null;
                else if (idx - 1 < items.size()) precon = items.get(idx - 1);
            } else if (tab == 3) {
                List<String> items = filtered(cmdPrecons);
                if (idx == 0) cmdPrecon = null;
                else if (idx - 1 < items.size()) cmdPrecon = items.get(idx - 1);
            } else if (idx < userDecks.size()) {
                user = userDecks.get(idx);
            }
            Theme.click();
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private void pasteDeck() {
        String text = minecraft.keyboardHandler.getClipboard();
        if (text == null || text.isBlank()) {
            setStatus("Clipboard is empty. Copy a decklist first.", Theme.RED);
            return;
        }
        try {
            Decks.Parsed p = Decks.importText(text);
            if (p.cards() == 0) {
                setStatus("No cards recognised in the clipboard.", Theme.RED);
                return;
            }
            userDecks = Decks.userDecks();
            user = p.deck().getName() + ".dck";
            String msg = "Imported \"" + p.deck().getName() + "\" (" + p.cards() + " cards)";
            if (!p.unknown().isEmpty()) {
                msg += ", skipped " + p.unknown().size() + ": " + String.join(", ", p.unknown().subList(0, Math.min(3, p.unknown().size())));
            }
            setStatus(msg, p.unknown().isEmpty() ? Theme.GREEN : Theme.GOLD);
            Theme.click();
        } catch (Exception e) {
            setStatus("Import failed: " + e.getMessage(), Theme.RED);
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (in(mx, my, px + 10, listTop(), pw - 20, listBottom() - listTop())) {
            scroll = Math.max(0, scroll - (int) Math.signum(delta) * 3);
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    private static boolean in(double mx, double my, double x, double y, double w, double h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

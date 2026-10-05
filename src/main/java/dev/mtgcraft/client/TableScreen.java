package dev.mtgcraft.client;

import dev.mtgcraft.engine.ForgeEngine;
import dev.mtgcraft.engine.MatchSetup;
import dev.mtgcraft.engine.net.RemoteDuel;
import dev.mtgcraft.net.Net;
import dev.mtgcraft.net.Packets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.UUID;

/**
 * A Magic Table's lobby: pick the mode, fill 2-4 seats with AI or players, choose decks and teams, then deal.
 * The server owns the lobby; this screen shows its latest state and sends edits. The player who opened the
 * table first is the host and controls the mode, the seats and the AI decks; everyone controls their own seat.
 */
public class TableScreen extends Screen {
    private static final int ROW_H = 22;
    private static final int[] TEAM_COLORS = {0xFFB0B0B0, 0xFF5B8FD6, 0xFFD9614F, 0xFF57C46A, 0xFFE0B65A,
            0xFFB070D0, 0xFF50C0C0, 0xFFE08040, 0xFFE070A0};

    private final BlockPos table;
    private Packets.TableState state;
    private long openedAt;
    private int px, py, pw, ph, modesW;

    public TableScreen(BlockPos table) {
        super(Component.literal("Magic Table"));
        this.table = table;
        this.state = ClientTables.state(table);
    }

    public BlockPos table() {
        return table;
    }

    void refresh(Packets.TableState s) {
        state = s;
    }

    @Override
    protected void init() {
        openedAt = System.currentTimeMillis();
        pw = Math.min(width - 16, 540);
        ph = Math.min(height - 12, 300);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        modesW = Math.max(110, Math.min(150, pw / 4));
    }

    private UUID me() {
        return minecraft.player == null ? null : minecraft.player.getUUID();
    }

    private boolean amHost() {
        return state != null && state.host().equals(me());
    }

    private MatchSetup.Mode mode() {
        return MatchSetup.Mode.values()[state.mode()];
    }

    private void send(Packets.Op op, int seat, int number, String text) {
        Net.toServer(new Packets.EditTable(table, op, seat, number, text == null ? "" : text));
        Theme.click();
    }

    // ------------------------------------------------------------------ layout helpers

    private int seatsX() { return px + modesW + 20; }
    private int seatsW() { return pw - modesW - 30; }
    private int rowY(int i) { return py + 34 + i * ROW_H; }

    /** x, width of the parts of a seat row: type, name, deck, team, action. */
    private int[] cols() {
        int x = seatsX(), w = seatsW();
        int type = 46, team = 26, action = 42;
        int rest = w - type - team - action - 12;
        int name = rest * 2 / 5, deck = rest - name;
        int[] c = new int[10];
        c[0] = x; c[1] = type;
        c[2] = x + type + 3; c[3] = name;
        c[4] = c[2] + name + 3; c[5] = deck;
        c[6] = c[4] + deck + 3; c[7] = team;
        c[8] = c[6] + team + 3; c[9] = action;
        return c;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        Theme.drawFelt(g, width, height);
        float t = Math.min(1f, (System.currentTimeMillis() - openedAt) / 250f);
        float ease = 1 - (1 - t) * (1 - t);
        g.pose().pushPose();
        g.pose().translate(0, (1 - ease) * 16, 0);
        Theme.panel(g, px, py, pw, ph);
        g.drawCenteredString(font, Component.literal("Magic Table").withStyle(st -> st.withBold(true)), width / 2, py + 8, Theme.GOLD);
        Theme.button(g, font, "Settings", px + pw - 72, py + 5, 66, 13, mx, my, true, false);

        if (ForgeEngine.state() != ForgeEngine.State.READY || state == null) {
            String msg = ForgeEngine.state() == ForgeEngine.State.FAILED ? "Card engine failed to load: " + ForgeEngine.failure()
                    : ForgeEngine.state() != ForgeEngine.State.READY ? ForgeEngine.status() + "..." : "Sitting down...";
            g.drawCenteredString(font, Theme.ellipsize(font, msg, pw - 20), width / 2, height / 2 - 10, Theme.TEXT);
            Theme.spinner(g, width / 2, height / 2 + 12, System.currentTimeMillis());
            g.pose().popPose();
            return;
        }
        drawModes(g, mx, my);
        drawSeats(g, mx, my);
        drawFooter(g, mx, my);
        g.pose().popPose();
    }

    private void drawModes(GuiGraphics g, int mx, int my) {
        int x = px + 10;
        g.drawString(font, "Mode", x, py + 22, Theme.TEXT);
        MatchSetup.Mode[] modes = MatchSetup.Mode.values();
        boolean host = amHost() && !state.running();
        for (int i = 0; i < modes.length; i++) {
            Theme.button(g, font, modes[i].label, x, py + 34 + i * 17, modesW, 14, mx, my,
                    host || modes[i] == mode(), modes[i] == mode());
        }
        int dy = py + 34 + modes.length * 17 + 4;
        List<FormattedCharSequence> lines = font.split(Component.literal(mode().description), modesW);
        for (int i = 0; i < lines.size() && dy + i * 10 < py + ph - 34; i++) {
            g.drawString(font, lines.get(i), x, dy + i * 10, Theme.MUTED);
        }
    }

    private void drawSeats(GuiGraphics g, int mx, int my) {
        List<Packets.SeatView> seats = state.seats();
        boolean host = amHost() && !state.running();
        boolean teams = mode().allowsTeams && mode() != MatchSetup.Mode.FREE_FOR_ALL;
        UUID me = me();
        int[] c = cols();
        g.drawString(font, "Seats (" + seats.size() + "/" + MatchSetup.MAX_SEATS + ")", seatsX(), py + 22, Theme.TEXT);
        for (int i = 0; i < seats.size(); i++) {
            Packets.SeatView s = seats.get(i);
            int y = rowY(i);
            boolean mine = me != null && me.equals(s.player());
            Theme.rounded(g, seatsX() - 2, y - 2, seatsW() + 4, ROW_H - 2, mine ? 0x40E0B65A : 0x30000000);

            String type = mine ? "YOU" : s.ai() ? "AI" : s.open() ? "OPEN" : "PLAYER";
            boolean typeClickable = host && !mine;
            Theme.button(g, font, type, c[0], y + 1, c[1], 15, mx, my, typeClickable || mine, mine || (!s.ai() && !s.open()));

            String name = mode().isArchenemy() && i == 0 ? "★ " + s.name() : s.name();
            int nameColor = s.open() ? Theme.MUTED : Theme.TEXT;
            g.drawString(font, Theme.ellipsize(font, name, c[3] - 10), c[2], y + 5, nameColor);
            if (!s.ai() && !s.open()) {
                Theme.disc(g, c[2] + c[3] - 4, y + 8, 3, s.ready() ? Theme.GREEN : 0xFFE0B65A);
            }

            boolean deckEditable = (host && s.ai()) || (mine && !state.running());
            Theme.button(g, font, s.deck(), c[4], y + 1, c[5], 15, mx, my, deckEditable, false);

            if (teams) {
                int team = mode().isArchenemy() ? (i == 0 ? 1 : 2) : s.team();
                int tc = TEAM_COLORS[Math.max(0, Math.min(TEAM_COLORS.length - 1, team))];
                Theme.rounded(g, c[6], y + 1, c[7], 15, (tc & 0x00FFFFFF) | 0xC0000000);
                g.drawCenteredString(font, "T" + team, c[6] + c[7] / 2, y + 5, Theme.TEXT);
            }

            String action = null;
            if (s.open() && !state.running()) action = "Sit";
            else if (mine && !state.running()) action = "Stand";
            else if (host && !mine && seats.size() > MatchSetup.MIN_SEATS) action = "✕";
            if (action != null) Theme.button(g, font, action, c[8], y + 1, c[9], 15, mx, my, true, action.equals("Sit"));
        }
        if (host && seats.size() < MatchSetup.MAX_SEATS) {
            Theme.button(g, font, "+ Add seat", seatsX(), rowY(seats.size()) + 1, 80, 15, mx, my, true, false);
        }
    }

    private void drawFooter(GuiGraphics g, int mx, int my) {
        int by = py + ph - 26;
        String note = state.message();
        if (note.isEmpty()) {
            if (state.running()) note = "A game is being played at this table.";
            else if (!amHost()) note = "Waiting for the host to deal.";
        }
        if (!note.isEmpty()) {
            g.drawCenteredString(font, Theme.ellipsize(font, note, pw - 20), width / 2, by - 12,
                    state.message().isEmpty() ? Theme.MUTED : Theme.RED);
        }
        RemoteDuel duel = ClientTables.duelAt(table);
        if (state.running() && duel != null && !duel.gui.isOver()) {
            Theme.button(g, font, "Back to the game", width / 2 - 104, by, 120, 20, mx, my, true, true);
        } else {
            Theme.button(g, font, "Deal the cards", width / 2 - 104, by, 120, 20, mx, my, amHost() && !state.running(), true);
        }
        Theme.button(g, font, "Leave", width / 2 + 22, by, 82, 20, mx, my, true, false);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (in(mx, my, px + pw - 72, py + 5, 66, 13)) {
            minecraft.setScreen(new SettingsScreen(this));
            Theme.click();
            return true;
        }
        if (state == null || ForgeEngine.state() != ForgeEngine.State.READY) return super.mouseClicked(mx, my, button);
        int by = py + ph - 26;
        if (in(mx, my, width / 2 + 22, by, 82, 20)) {
            onClose();
            return true;
        }
        if (in(mx, my, width / 2 - 104, by, 120, 20)) {
            RemoteDuel duel = ClientTables.duelAt(table);
            if (state.running() && duel != null && !duel.gui.isOver()) {
                minecraft.setScreen(new DuelScreen(duel.gui, table));
            } else if (amHost() && !state.running()) {
                send(Packets.Op.START, 0, 0, null);
            }
            return true;
        }
        if (state.running()) return true;
        boolean host = amHost();
        MatchSetup.Mode[] modes = MatchSetup.Mode.values();
        for (int i = 0; i < modes.length; i++) {
            if (host && in(mx, my, px + 10, py + 34 + i * 17, modesW, 14)) {
                send(Packets.Op.SET_MODE, 0, i, null);
                return true;
            }
        }
        List<Packets.SeatView> seats = state.seats();
        UUID me = me();
        int[] c = cols();
        boolean teams = mode().allowsTeams && mode() != MatchSetup.Mode.FREE_FOR_ALL && !mode().isArchenemy();
        for (int i = 0; i < seats.size(); i++) {
            Packets.SeatView s = seats.get(i);
            int y = rowY(i) + 1;
            boolean mine = me != null && me.equals(s.player());
            if (host && !mine && in(mx, my, c[0], y, c[1], 15)) {
                send(s.ai() ? Packets.Op.SEAT_OPEN : Packets.Op.SEAT_AI, i, 0, null);
                return true;
            }
            if (((host && s.ai()) || mine) && in(mx, my, c[4], y, c[5], 15)) {
                int seat = i;
                String title = mine ? "Your deck" : s.name() + "'s deck";
                minecraft.setScreen(new DeckPickerScreen(this, title, true,
                        choice -> send(Packets.Op.SET_DECK, seat, 0, Packets.encode(choice))));
                return true;
            }
            if (teams && (host || mine) && in(mx, my, c[6], y, c[7], 15)) {
                int next = button == 1 ? (s.team() <= 1 ? MatchSetup.MAX_SEATS : s.team() - 1) : s.team() % MatchSetup.MAX_SEATS + 1;
                send(Packets.Op.SET_TEAM, i, next, null);
                return true;
            }
            if (in(mx, my, c[8], y, c[9], 15)) {
                if (s.open()) send(Packets.Op.SIT, i, 0, null);
                else if (mine) send(Packets.Op.STAND, i, 0, null);
                else if (host && seats.size() > MatchSetup.MIN_SEATS) send(Packets.Op.REMOVE_SEAT, i, 0, null);
                return true;
            }
        }
        if (host && seats.size() < MatchSetup.MAX_SEATS && in(mx, my, seatsX(), rowY(seats.size()) + 1, 80, 15)) {
            send(Packets.Op.ADD_SEAT, 0, 0, null);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void onClose() {
        Net.toServer(new Packets.EditTable(table, Packets.Op.CLOSE, 0, 0, ""));
        super.onClose();
    }

    private static boolean in(double mx, double my, double x, double y, double w, double h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

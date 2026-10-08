package dev.mtgcraft.client;

import com.mojang.math.Axis;
import dev.mtgcraft.engine.ChoiceRequest;
import dev.mtgcraft.engine.DuelGui;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.game.GameEntityView;
import forge.game.GameLogEntry;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.combat.CombatView;
import forge.game.phase.PhaseType;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;
import forge.model.FModel;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Magic table. Everything is laid out fresh each frame from Card-Forge's game view; each card then glides
 * toward its spot, so draws, casts, attacks and deaths all animate without any per-action code.
 *
 * <p>Controls: drag a card from your hand onto the table to play it (or onto a creature/player to aim it); drag a
 * creature toward an opponent to attack (also from your main phase); drag your creature onto an attacker to block;
 * click anything to select it; right-click to take a creature out of combat, or a player to see their zones;
 * Space for the main button; Z for zones, L for the log; hold Tab to peek under a question.
 */
public class DuelScreen extends Screen {
    private enum Zone { HAND, BATTLEFIELD, STACK, COMMAND, TRAY }

    /** Where a card is drawn this frame. */
    private static final class Placed {
        final CardView card;
        final Zone zone;
        final boolean mine;
        final boolean hidden;
        float x, y, w, h, rot;
        /** Player whose side of the table the card is on (for animations), -1 for the stack. */
        int side = -1;

        Placed(CardView card, Zone zone, boolean mine, boolean hidden, float x, float y, float w, float h, float rot) {
            this.card = card; this.zone = zone; this.mine = mine; this.hidden = hidden;
            this.x = x; this.y = y; this.w = w; this.h = h; this.rot = rot;
        }

        /** Hit test at the card's resting spot (hand cards lift when hovered; testing the lifted card made them flicker). */
        boolean containsAtRest(double mx, double my) {
            boolean sideways = Math.abs(Math.sin(Math.toRadians(rot))) > 0.7;
            float cx = x + w / 2, cy = y + h / 2, hw = (sideways ? h : w) / 2, hh = (sideways ? w : h) / 2;
            return mx >= cx - hw && mx <= cx + hw && my >= cy - hh && my <= cy + hh;
        }

        boolean contains(double mx, double my, Anim a) {
            float cx = a.x + w / 2, cy = a.y + h / 2;
            boolean sideways = Math.abs(Math.sin(Math.toRadians(a.rot))) > 0.7;
            float hw = (sideways ? h : w) * a.scale / 2, hh = (sideways ? w : h) * a.scale / 2;
            return mx >= cx - hw && mx <= cx + hw && my >= cy - hh && my <= cy + hh;
        }
    }

    /** A card's on-screen state, eased toward its Placed target every frame. */
    private static final class Anim {
        float x, y, rot, scale = 1, alpha = 0;
        float lastX, lastY, w, h;
        boolean mine, hidden;
        int side = -1;
        CardView card;
        long goneSince;
    }

    /** One opponent's slice of the top half of the board. */
    private record Column(PlayerView player, float x0, float x1, float pillY) {}

    /** A clickable spot drawn this frame (chips, tool buttons, tabs). */
    private record Hit(float x, float y, float w, float h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final DuelGui duel;
    private final net.minecraft.core.BlockPos table;
    /** Opponents in turn order after me, each with their column; refreshed every frame. */
    private List<Column> columns = new ArrayList<>();
    private float oppCardW, oppCardH;
    /** Animated relative widths of the opponent columns, by player id. */
    private final Map<Integer, Float> columnWeights = new HashMap<>();
    /** Show the 3D battlefield in the world behind a see-through board (remembered between duels). */
    private static boolean arenaView = !dev.mtgcraft.MtgClientConfig.SPEC.isLoaded() || dev.mtgcraft.MtgClientConfig.ARENA_VIEW.get();
    private final Map<Integer, Anim> anims = new HashMap<>();
    private List<Placed> placed = new ArrayList<>();
    private long lastFrame = System.currentTimeMillis();
    private final List<Hit> hits = new ArrayList<>();

    // layout
    private int sideW, boardW, rowX0, rowX1;
    private float cardW, cardH, handW, handH, midY, handTop;
    private float oppLandsY, oppCreY, myCreY, myLandsY;
    private int oppPillY, myPillY, zoomX, zoomY, zoomW, zoomH, promptY, buttonsY, toolsY;

    // interaction
    private Placed pressed;
    private double pressX, pressY;
    private boolean dragging;
    private float dragDX, dragDY, dragTilt;
    private Placed hovered;
    private CardView zoomCard;
    /** While set, this card is drawn with its other side up (Shift on a two-sided card). */
    private CardView showBackOf;
    private Object pendingTarget;
    private long pendingUntil;
    private boolean confirmConcede;
    private int lastHandSize = -1;

    // creatures dragged at an opponent before combat: declared as soon as the attack step starts
    private final Set<Integer> queuedAttack = new LinkedHashSet<>();
    private PlayerView queuedDefender;
    private int queuedTurn = -1;

    // cards to choose that aren't on the table (graveyard targets, revealed cards...)
    private String trayLabel = "";
    private float trayX, trayY, trayW, trayH;

    // big hover preview
    private static final long PREVIEW_DELAY_MS = 220;
    private CardView choiceHover;
    private int previewId = -1;
    private long previewSince;

    // choice overlay
    private ChoiceRequest shownRequest;
    private final List<Integer> picked = new ArrayList<>();
    private final List<Integer> distValues = new ArrayList<>();
    private int numberValue;
    private int optionScroll;
    private boolean peeking;

    // zone viewer and log
    private boolean viewerOpen;
    private PlayerView viewerFor;
    private ZoneType viewerZone = ZoneType.Graveyard;
    private int viewerScroll;
    private CardView viewerHover;
    private final List<Hit> viewerHits = new ArrayList<>();
    private boolean showLog;
    /** The stat sheet: every player's life, counters, mana and zones at a glance (the Stats button). */
    private boolean showStats;

    public DuelScreen(DuelGui duel, net.minecraft.core.BlockPos table) {
        super(Component.literal("Magic Table"));
        this.duel = duel;
        this.table = table;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        sideW = (int) Math.max(118, Math.min(200, width * 0.22f));
        boardW = width - sideW;
        cardH = Math.max(30, Math.min(110, height * 0.15f));
        cardW = cardH * 63f / 88f;
        handH = cardH * 1.3f;
        handW = handH * 63f / 88f;
        rowX0 = (int) (6 + cardW + 12);
        rowX1 = boardW - 6;
        handTop = height - handH * 0.58f;
        oppLandsY = 6;
        oppCreY = oppLandsY + cardH + 4;
        myLandsY = handTop - 6 - cardH;
        myCreY = myLandsY - 4 - cardH;
        midY = (oppCreY + cardH + myCreY) / 2f;

        oppPillY = 4;
        myPillY = height - 40;
        int zoomTop = oppPillY + 36 + 18;
        int maxZoomH = height - zoomTop - 40 - 30 - 46 - 14;
        zoomW = sideW - 12;
        zoomH = (int) (zoomW * 88f / 63f);
        if (zoomH > maxZoomH) {
            zoomH = maxZoomH;
            zoomW = (int) (zoomH * 63f / 88f);
        }
        if (zoomH < 80) {
            // Too small to read on a small screen: leave the room to the instructions (hovering still shows
            // the big preview).
            zoomH = 0;
            zoomW = 0;
            zoomTop -= 4;
        }
        zoomX = boardW + (sideW - zoomW) / 2;
        zoomY = zoomTop;
        promptY = zoomY + zoomH + 4;
        buttonsY = myPillY - 28;
        toolsY = buttonsY - 16;
    }

    // ------------------------------------------------------------------ frame

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        try {
            phaseTip = null;
            renderFrame(g, mouseX, mouseY);
            drawTurnSignal(g);
            if (phaseTip != null) {
                boolean skipped = (skipPhases() & (1 << phaseTip[2])) != 0;
                boolean stopped = (stopPhases() & (1 << phaseTip[2])) != 0;
                g.renderTooltip(font, List.of(Component.literal(PHASE_LONG[phaseTip[2]]),
                        Component.literal(stopped ? "Always stops here - click to skip it" : skipped ? "Always skipped - click for normal"
                                : "Auto-pass decides - click to always stop here").withStyle(net.minecraft.ChatFormatting.GRAY)),
                        java.util.Optional.empty(), phaseTip[0], phaseTip[1]);
            }
        } catch (RuntimeException e) {
            // Game state arrives from another thread (or the network) and can be briefly incomplete.
            // Skip this frame instead of crashing the game; log each distinct problem once.
            if (loggedErrors.add(String.valueOf(e))) {
                System.err.println("[MTGCraft] Duel screen frame skipped: " + e);
                e.printStackTrace();
            }
            g.flush();
        }
    }

    private final Set<String> loggedErrors = new HashSet<>();

    /** Mana of one colour in a player's pool; 0 until the pool has synced over the network. */
    private static int mana(PlayerView p, byte color) {
        try {
            return p.getMana(color);
        } catch (NullPointerException notSyncedYet) {
            return 0;
        }
    }

    /** A zone's cards as a list (empty while it hasn't synced, or if the game thread is mid-change). */
    private static List<CardView> cards(Iterable<CardView> zone) {
        List<CardView> out = new ArrayList<>();
        if (zone == null) return out;
        try {
            for (CardView c : zone) if (c != null) out.add(c);
        } catch (RuntimeException concurrentEdit) {
            // keep what we have
        }
        return out;
    }

    private void renderFrame(GuiGraphics g, int mouseX, int mouseY) {
        long now = System.currentTimeMillis();
        float dt = Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        hits.clear();
        frameMouseX = mouseX;
        frameMouseY = mouseY;

        boolean arena = arenaActive();
        if (arena) {
            // See-through board: the 3D battlefield in the world shows behind it.
            g.fillGradient(0, (int) (height * 0.62f), boardW, height, 0x00000000, 0xB0000000);
        } else {
            Theme.drawFelt(g, boardW, height);
        }
        g.fill(boardW, 0, width, height, 0xFF0E1310);
        g.fill(boardW, 0, boardW + 1, height, Theme.PANEL_EDGE);

        GameView view = duel.getGameView();
        PlayerView me = duel.me();
        if (duel.failed != null) {
            g.drawCenteredString(font, duel.failed, boardW / 2, height / 2 - 14, Theme.RED);
            g.drawCenteredString(font, "Click to go back", boardW / 2, height / 2 + 2, Theme.MUTED);
            return;
        }
        if (view == null || me == null || !duel.viewOpen) {
            g.drawCenteredString(font, "Shuffling decks...", boardW / 2, height / 2 - 14, Theme.TEXT);
            Theme.spinner(g, boardW / 2, height / 2 + 6, now);
            return;
        }
        PlayerView opp = opponent(view, me);
        layoutColumns(view, me, mouseX, mouseY, dt);

        try {
            placed = layout(view, me);
        } catch (RuntimeException concurrentEdit) {
            // The game thread changed a zone mid-read; keep last frame's layout.
        }
        ChoiceRequest req = duel.currentRequest();
        syncRequest(req);
        boolean overlay = req != null && !peeking;
        boolean viewer = viewerOpen && !overlay;

        hovered = (overlay || viewer || dragging) ? null : topCardAt(mouseX, mouseY);
        if (!overlay && !dragging && hovered != null) zoomCard = hovered.card;
        if (dragging && pressed != null) zoomCard = pressed.card;

        animate(dt, mouseX, mouseY);
        resolvePendingTarget();
        updateQueuedAttack(view, me);
        handSound(me);

        drawMiddleLine(g);
        if (!arena) drawPiles(g, me, opp);
        drawTrayBacking(g);
        checkBlocks(view);
        drawCombatLines(g, view, mouseX, mouseY);
        drawAttachmentLinks(g);
        drawCards(g, now);
        drawCommanderTags(g, me);
        drawStackLabel(g, view);
        if (!overlay) drawStepBanner(g, view, me, req);
        drawSidebar(g, view, me, opp, mouseX, mouseY, now);
        if (showLog && !overlay) drawLog(g, view);
        if (showStats && !overlay) drawStats(g, view);
        viewerHover = null;
        if (viewer) drawViewer(g, view, me, mouseX, mouseY, now);
        else if (!overlay && startingPlayerPrompt()) drawStarterPicker(g, view, mouseX, mouseY);
        choiceHover = null;
        if (overlay) drawChoice(g, req, mouseX, mouseY, now);
        if (duel.isOver() && req == null) {
            drawGameOver(g, view, me, mouseX, mouseY);
        }
        CardView preview = overlay ? choiceHover : viewer ? viewerHover : hovered == null ? null : hovered.card;
        if (!overlay && !viewer) drawHoverInfo(g, mouseX, mouseY);
        drawToast(g);
        drawBigPreview(g, preview, mouseX, now);
    }

    /**
     * Resting the mouse on a card shows it large enough to read, on the side of the board away from the cursor.
     * Uses the full-resolution image (Scryfall "normal", 488x680) so the rules text stays sharp.
     */
    private void drawBigPreview(GuiGraphics g, CardView card, int mouseX, long now) {
        if (card == null || dragging || !duel.mayView(card)) {
            previewId = -1;
            return;
        }
        if (card.getId() != previewId) {
            previewId = card.getId();
            previewSince = now;
        }
        float t = (now - previewSince - PREVIEW_DELAY_MS) / 120f;
        if (t <= 0) return;
        float ease = Math.min(1f, t);
        float h = Math.min(height * 0.8f, (boardW / 2f - 16) * 88f / 63f);
        float w = h * 63f / 88f;
        float x = mouseX < boardW / 2 ? boardW - w - 10 : 10;
        float y = (height - h) / 2;
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.pose().translate(x + w / 2, y + h / 2, 0);
        float s = 0.94f + 0.06f * ease;
        g.pose().scale(s, s, 1);
        g.pose().translate(-w / 2, -h / 2, 0);
        Theme.fillF(g, g.pose().last().pose(), 4, 6, w + 4, h + 6, ((int) (ease * 120)) << 24);
        drawCardFace(g, card, 0, 0, w, h, ease, true);
        g.pose().popPose();
    }

    private static PlayerView opponent(GameView view, PlayerView me) {
        for (PlayerView p : view.getPlayers()) {
            if (p.getId() != me.getId()) return p;
        }
        return me;
    }

    private boolean multiplayer() {
        return columns.size() > 1;
    }

    private boolean myTurn(GameView view) {
        PlayerView me = duel.me();
        return me != null && view.getPlayerTurn() != null && view.getPlayerTurn().getId() == me.getId();
    }

    /**
     * Splits the top half between the other players, in turn order after me. With one opponent the board looks
     * like a classic duel; with more, each gets a column with a name tag and smaller cards.
     */
    private void layoutColumns(GameView view, PlayerView me, int mouseX, int mouseY, float dt) {
        List<PlayerView> all = new ArrayList<>((java.util.Collection<PlayerView>) view.getPlayers());
        int at = 0;
        for (int i = 0; i < all.size(); i++) if (all.get(i).getId() == me.getId()) at = i;
        List<PlayerView> others = new ArrayList<>();
        for (int i = 1; i < all.size(); i++) others.add(all.get((at + i) % all.size()));
        List<Column> cols = new ArrayList<>();
        int n = others.size();
        if (n <= 1) {
            oppCardW = cardW;
            oppCardH = cardH;
            oppLandsY = 6;
            oppCreY = oppLandsY + cardH + 4;
            if (n == 1) cols.add(new Column(others.get(0), rowX0, rowX1, -1));
        } else {
            float scale = n == 2 ? 0.9f : 0.82f;
            oppCardH = cardH * scale;
            oppCardW = cardW * scale;
            oppLandsY = 19;
            oppCreY = oppLandsY + oppCardH + 3;

            // The opponent in focus (under the mouse, else whose turn it is) gets a wide column; the rest
            // become narrower strips. Widths ease toward their targets so the board never jumps.
            int focus = -1;
            if (mouseY < oppCreY + oppCardH + 6 && mouseX < boardW) {
                for (Column c : columns) if (mouseX >= c.x0() && mouseX < c.x1()) focus = c.player().getId();
            }
            PlayerView turn = view.getPlayerTurn();
            if (focus < 0 && turn != null && turn.getId() != me.getId()) focus = turn.getId();
            float k = 1 - (float) Math.exp(-dt * 8);
            float total = 0;
            for (PlayerView o : others) {
                float target = o.getId() == focus ? 2.2f : 1f;
                float w = columnWeights.getOrDefault(o.getId(), 1f);
                w += (target - w) * k;
                columnWeights.put(o.getId(), w);
                total += w;
            }
            float x = 4, span = boardW - 8f;
            for (PlayerView o : others) {
                float w = span * columnWeights.get(o.getId()) / total;
                cols.add(new Column(o, x, x + w - 4, 2));
                x += w;
            }
        }
        columns = cols;
        midY = (oppCreY + oppCardH + myCreY) / 2f;
    }

    private Column columnOf(int playerId) {
        for (Column c : columns) if (c.player().getId() == playerId) return c;
        return null;
    }

    // ------------------------------------------------------------------ layout

    private List<Placed> layout(GameView view, PlayerView me) {
        List<Placed> out = new ArrayList<>();
        for (Column c : columns) {
            float pad = multiplayer() ? 2 : 0;
            layoutBattlefield(out, c.player(), false, c.x0() + pad, c.x1() - pad, oppLandsY, oppCreY, oppCardW, oppCardH);
        }
        layoutBattlefield(out, me, true, rowX0, rowX1, myLandsY, myCreY, cardW, cardH);
        layoutHand(out, me);
        layoutCommand(out, me);
        boolean tray = layoutTray(out, view, me);
        layoutStack(out, view, tray);
        applyCombatOffsets(out, view);
        return out;
    }

    private void layoutBattlefield(List<Placed> out, PlayerView p, boolean mine, float x0, float x1,
                                   float landY, float creY, float cw, float ch) {
        List<CardView> creatures = new ArrayList<>(), back = new ArrayList<>(), lands = new ArrayList<>();
        Iterable<CardView> bf = p.getBattlefield();
        if (bf == null) return;
        for (CardView c : new ArrayList<>((java.util.Collection<CardView>) bf)) {
            CardStateView s = c.getCurrentState();
            if (s.isCreature()) creatures.add(c);
            else if (s.isLand()) lands.add(c);
            else back.add(c);
        }
        int start = out.size();
        row(out, creatures, creY, x0, x1, mine, false, cw, ch);

        // Back row: lands (identical ones stacked into small piles) on the left, other permanents on the right.
        Map<String, List<CardView>> piles = new LinkedHashMap<>();
        for (CardView c : lands) {
            piles.computeIfAbsent(c.getCurrentState().getName() + (c.isTapped() ? "#t" : ""), k -> new ArrayList<>()).add(c);
        }
        int slots = piles.size() + back.size();
        float span = x1 - x0;
        float step = slots <= 1 ? 0 : Math.max(2, Math.min(cw + 6, (span - cw) / (slots - 1)));
        float x = x0;
        for (List<CardView> pile : piles.values()) {
            for (int i = 0; i < pile.size(); i++) {
                CardView c = pile.get(i);
                float off = Math.min(i, 4) * 3f * (cw / cardW);
                out.add(new Placed(c, Zone.BATTLEFIELD, mine, false, x + off, landY - off, cw, ch, c.isTapped() ? 90 : 0));
            }
            x += step;
        }
        for (CardView c : back) {
            out.add(new Placed(c, Zone.BATTLEFIELD, mine, false, x, landY, cw, ch, c.isTapped() ? 90 : 0));
            x += step;
        }
        for (int i = start; i < out.size(); i++) out.get(i).side = p.getId();
    }

    private void row(List<Placed> out, List<CardView> cards, float y, float x0, float x1, boolean mine, boolean hidden,
                     float cw, float ch) {
        int n = cards.size();
        if (n == 0) return;
        float span = x1 - x0;
        float step = n == 1 ? 0 : Math.max(2, Math.min(cw + 8, (span - cw) / (n - 1)));
        float total = step * (n - 1) + cw;
        float x = x0 + Math.max(0, (span - total) / 2);
        for (CardView c : cards) {
            out.add(new Placed(c, Zone.BATTLEFIELD, mine, hidden, x, y, cw, ch, c.isTapped() ? 90 : 0));
            x += step;
        }
    }

    private void layoutStack(List<Placed> out, GameView view, boolean trayShown) {
        List<StackItemView> stack = new ArrayList<>((java.util.Collection<StackItemView>) view.getStack());
        int n = stack.size();
        if (n == 0) return;
        float w = cardW * 1.05f, h = cardH * 1.05f;
        float step = Math.min(w * 0.55f, (rowX1 - rowX0 - w) / Math.max(1, n));
        // With the pick tray open in the middle, the stack moves to the right edge.
        float cx = trayShown ? rowX1 - w / 2 - 4 : (rowX0 + rowX1) / 2f;
        float x = cx + (trayShown ? 0 : (n - 1) * step / 2f) - w / 2;
        // The top of the stack is index 0; draw it last (in front, rightmost).
        for (int i = n - 1; i >= 0; i--) {
            CardView src = stack.get(i).getSourceCard();
            if (src == null) continue;
            out.add(new Placed(src, Zone.STACK, false, false, x - i * step, midY - h / 2, w, h, 0));
        }
    }

    private void layoutHand(List<Placed> out, PlayerView me) {
        List<CardView> hand = cards(me.getHand());
        int n = hand.size();
        if (n == 0) return;
        float maxSpan = boardW * 0.62f;
        float step = n == 1 ? 0 : Math.min(handW * 0.78f, (maxSpan - handW) / (n - 1));
        float total = step * (n - 1) + handW;
        float x = (boardW - total) / 2;
        for (int i = 0; i < n; i++) {
            float t = n == 1 ? 0 : (i - (n - 1) / 2f) / ((n - 1) / 2f);
            float rot = t * Math.min(12, n * 1.6f);
            float y = handTop + t * t * handH * 0.08f;
            Placed p = new Placed(hand.get(i), Zone.HAND, true, false, x + i * step, y, handW, handH, rot);
            p.side = me.getId();
            out.add(p);
        }
    }

    /** My commander(s) wait in the bottom-left corner, beside the hand; click or drag one out to cast it. */
    private void layoutCommand(List<Placed> out, PlayerView me) {
        List<CardView> cmd = new ArrayList<>();
        for (CardView c : cards(me.getCommand())) if (c.isCommander()) cmd.add(c);
        float w = handW * 0.85f, h = handH * 0.85f;
        for (int i = 0; i < cmd.size(); i++) {
            Placed p = new Placed(cmd.get(i), Zone.COMMAND, true, false, 6 + i * w * 0.55f, handTop + 4, w, h, 0);
            p.side = me.getId();
            out.add(p);
        }
    }

    /**
     * Cards the game wants picked that aren't on the table (a creature in a graveyard to bring back, a card in
     * exile...) and cards it's showing (scry, reveal) appear in a tray in the middle of the board.
     */
    private boolean layoutTray(List<Placed> out, GameView view, PlayerView me) {
        Set<Integer> shown = new HashSet<>();
        for (Placed p : out) shown.add(p.card.getId());
        List<CardView> tray = new ArrayList<>();
        Set<String> from = new LinkedHashSet<>();
        boolean revealed = false;
        for (CardView c : duel.revealed) {
            if (shown.add(c.getId())) {
                tray.add(c);
                revealed = true;
            }
        }
        if (picking()) {
            for (PlayerView p : view.getPlayers()) {
                for (ZoneType z : new ZoneType[]{ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command, ZoneType.Library, ZoneType.Hand}) {
                    for (CardView c : cards(p.getCards(z))) {
                        if (duel.isSelectable(c) && shown.add(c.getId())) {
                            tray.add(c);
                            from.add((p.getId() == me.getId() ? "your " : p.getName() + "'s ") + zoneName(z).toLowerCase());
                        }
                    }
                }
            }
        }
        if (tray.isEmpty()) {
            trayLabel = "";
            return false;
        }
        trayLabel = from.isEmpty() ? (revealed ? "Revealed" : "") : "Choose from " + String.join(", ", from);
        float h = cardH * 1.15f, w = h * 63f / 88f;
        int n = tray.size();
        float maxW = (rowX1 - rowX0) * 0.7f;
        float step = n == 1 ? 0 : Math.min(w + 4, (maxW - w) / (n - 1));
        float total = step * (n - 1) + w;
        trayX = rowX0 + 4;
        trayY = midY - h / 2;
        trayW = total + 12;
        trayH = h + 18;
        for (int i = 0; i < n; i++) {
            Placed p = new Placed(tray.get(i), Zone.TRAY, false, false, trayX + 6 + i * step, trayY + 12, w, h, 0);
            out.add(p);
        }
        trayY -= 2;
        return true;
    }

    private static String zoneName(ZoneType z) {
        return switch (z) {
            case Graveyard -> "Graveyard";
            case Exile -> "Exile";
            case Command -> "Command zone";
            case Library -> "Library";
            case Hand -> "Hand";
            default -> z.name();
        };
    }

    /** Attackers step toward the middle; blockers lean in to meet them. */
    private void applyCombatOffsets(List<Placed> out, GameView view) {
        CombatView combat = view.getCombat();
        if (combat == null) return;
        for (Placed p : out) {
            if (p.zone != Zone.BATTLEFIELD) continue;
            float dir = p.mine ? -1 : 1;
            if (combat.isAttacking(p.card)) p.y += dir * p.h * 0.32f;
            else if (combat.isBlocking(p.card)) p.y += dir * p.h * 0.16f;
        }
    }

    // ------------------------------------------------------------------ animation

    private void animate(float dt, int mx, int my) {
        float k = 1 - (float) Math.exp(-dt * 14);
        Set<Integer> seen = new HashSet<>();
        for (Placed p : placed) {
            int id = p.card.getId();
            seen.add(id);
            Anim a = anims.get(id);
            if (a == null) {
                a = new Anim();
                float[] from = spawnPoint(p);
                a.x = from[0];
                a.y = from[1];
                a.rot = p.rot;
                a.scale = 0.85f;
                anims.put(id, a);
            }
            a.card = p.card;
            a.w = p.w;
            a.h = p.h;
            a.mine = p.mine;
            a.hidden = p.hidden;
            if (p.side >= 0) a.side = p.side;
            a.goneSince = 0;

            float tx = p.x, ty = p.y, trot = p.rot, tscale = 1;
            if (p == hovered && (p.zone == Zone.HAND || p.zone == Zone.COMMAND)) {
                // Lift the card right up, big (and so drawn from the sharp texture): readable without the zoom panel.
                tscale = 1.5f;
                ty = height - p.h * tscale - 6;
                trot = 0;
            } else if (p == hovered) {
                tscale = 1.06f;
            }
            if (dragging && pressed != null && pressed.card.getId() == id) {
                tx = mx - dragDX;
                ty = my - dragDY;
                float vx = (tx - a.lastX) / Math.max(dt, 0.001f);
                dragTilt += ((Math.max(-12, Math.min(12, vx * 0.02f))) - dragTilt) * k;
                trot = dragTilt;
                tscale = 1.1f;
                a.x = tx;
                a.y = ty;
            }
            a.lastX = a.x;
            a.lastY = a.y;
            a.x += (tx - a.x) * k;
            a.y += (ty - a.y) * k;
            a.rot += (trot - a.rot) * k;
            a.scale += (tscale - a.scale) * k;
            a.alpha += (1 - a.alpha) * k;
        }
        // Cards that left the visible zones slide toward their owner's graveyard and fade out.
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<Integer, Anim>> it = anims.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Anim> e = it.next();
            if (seen.contains(e.getKey())) continue;
            Anim a = e.getValue();
            if (a.goneSince == 0) a.goneSince = now;
            Column col = a.mine ? null : columnOf(a.side);
            boolean colTarget = col != null && multiplayer();
            float gx = colTarget ? col.x0() : 6;
            float gy = a.mine ? myLandsY - cardH - 4 : colTarget ? col.pillY() : oppCreY;
            a.x += (gx - a.x) * k * 0.6f;
            a.y += (gy - a.y) * k * 0.6f;
            a.alpha -= dt * 3.5f;
            if (a.alpha <= 0 || now - a.goneSince > 600) it.remove();
        }
    }

    /** New cards fly in from where they came from: your library for draws, the opponent's side for their plays. */
    private float[] spawnPoint(Placed p) {
        if (p.zone == Zone.HAND) return new float[]{6, myLandsY};
        if (p.zone == Zone.TRAY || p.zone == Zone.COMMAND) return new float[]{p.x, p.y + 12};
        Column col = columnOf(p.side);
        if (col != null && multiplayer()) return new float[]{(col.x0() + col.x1()) / 2, -p.h};
        if (!p.mine) return new float[]{boardW + 20, oppPillY};
        return new float[]{p.x, p.y + cardH * 0.4f};
    }

    private void handSound(PlayerView me) {
        int size = me.getZoneSize(ZoneType.Hand);
        if (lastHandSize >= 0 && size > lastHandSize) {
            Theme.play(SoundEvents.BOOK_PAGE_TURN, 1.3f, 0.6f);
        }
        lastHandSize = size;
    }

    // ------------------------------------------------------------------ attacking from the main phase

    /**
     * Creatures dragged at an opponent during the main phase: the game moves on to combat and they're declared as
     * attackers as soon as the attack step asks.
     */
    private void updateQueuedAttack(GameView view, PlayerView me) {
        if (queuedAttack.isEmpty()) return;
        if (!myTurn(view) || view.getTurn() != queuedTurn) {
            clearQueuedAttack();
            return;
        }
        PhaseType phase = view.getPhase();
        if (phase == null) return;
        if (phase == PhaseType.COMBAT_DECLARE_ATTACKERS) {
            if (duel.prompt == null || duel.prompt.startsWith("Priority") || !duel.okEnabled) return;
            CombatView combat = view.getCombat();
            if (multiplayer() && queuedDefender != null) duel.clickPlayer(queuedDefender);
            for (CardView c : cards(me.getBattlefield())) {
                if (queuedAttack.contains(c.getId()) && (combat == null || !combat.isAttacking(c))) duel.clickCard(c, 1);
            }
            clearQueuedAttack();
        } else if (phase.isAfter(PhaseType.COMBAT_DECLARE_ATTACKERS)) {
            clearQueuedAttack();
        }
    }

    private void clearQueuedAttack() {
        queuedAttack.clear();
        queuedDefender = null;
    }

    // ------------------------------------------------------------------ drawing: board

    private void drawMiddleLine(GuiGraphics g) {
        int y = (int) midY;
        for (int x = rowX0; x < rowX1; x += 6) {
            g.fill(x, y, x + 3, y + 1, 0x30FFFFFF);
        }
    }

    private void drawPiles(GuiGraphics g, PlayerView me, PlayerView opp) {
        if (!multiplayer()) drawPile(g, opp, 6, oppLandsY, oppCreY);
        drawPile(g, me, 6, myLandsY, myCreY);
    }

    /** Library and graveyard at the left edge; click either to look through it. */
    private void drawPile(GuiGraphics g, PlayerView p, float x, float libY, float gyY) {
        long now = System.currentTimeMillis();
        int lib = p.getZoneSize(ZoneType.Library);
        boolean libHover = !dragging && in(frameMouseX, frameMouseY, x, libY, cardW, cardH);
        float lift = libHover ? 2 : 0;
        if (lib > 0) {
            for (int i = Math.min(3, lib) - 1; i >= 0; i--) {
                blitScaled(g, Theme.CARD_BACK, x + i * 1.2f, libY - i * 1.2f - lift, cardW, cardH, 126, 176, 1f);
            }
        }
        if (libHover || anySelectable(p, ZoneType.Library)) pileOutline(g, x, libY - lift, libHover ? 0xE0FFFFFF : pulse(now));
        label(g, "Deck " + lib, x + cardW / 2, libY + cardH - 9 - lift);
        if (libHover) pileCaption(g, "Deck: click to look", x, libY - lift);
        hits.add(new Hit(x, libY, cardW, cardH, () -> openViewer(p, ZoneType.Library)));
        List<CardView> gy = cards(p.getGraveyard());
        boolean gyHover = !dragging && in(frameMouseX, frameMouseY, x, gyY, cardW, cardH);
        float gl = gyHover ? 2 : 0;
        if (!gy.isEmpty()) {
            CardView top = gy.get(gy.size() - 1);
            drawCardFace(g, top, x, gyY - gl, cardW, cardH, gyHover ? 1f : 0.85f);
            label(g, "GY " + gy.size(), x + cardW / 2, gyY + cardH - 9 - gl);
        } else {
            Theme.rounded(g, (int) x, (int) gyY, (int) cardW, (int) cardH, gyHover ? 0x50FFFFFF : 0x30000000);
            if (gyHover) label(g, "GY 0", x + cardW / 2, gyY + cardH - 9);
        }
        // A pick waiting in the graveyard makes it glow, so you know where to look.
        if (gyHover || anySelectable(p, ZoneType.Graveyard)) pileOutline(g, x, gyY - gl, gyHover ? 0xE0FFFFFF : pulse(now));
        if (gyHover) pileCaption(g, "Graveyard: click to look", x, gyY - gl);
        hits.add(new Hit(x, gyY, cardW, cardH, () -> openViewer(p, ZoneType.Graveyard)));
    }

    private int frameMouseX, frameMouseY;

    private boolean anySelectable(PlayerView p, ZoneType zone) {
        if (!picking()) return false;
        for (CardView c : cards(p.getCards(zone))) if (duel.isSelectable(c)) return true;
        return false;
    }

    private static int pulse(long now) {
        return (((int) (150 + 105 * Math.sin(now / 160.0))) << 24) | (Theme.SELECT & 0xFFFFFF);
    }

    private void pileOutline(GuiGraphics g, float x, float y, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        outline(g, cardW, cardH, color);
        g.pose().popPose();
    }

    private void pileCaption(GuiGraphics g, String text, float x, float y) {
        int w = font.width(text) + 6;
        int cx = (int) Math.max(2, x);
        g.pose().pushPose();
        g.pose().translate(0, 0, 250);
        Theme.rounded(g, cx, (int) y - 12, w, 11, 0xE0101010);
        g.drawString(font, text, cx + 3, (int) y - 10, Theme.GOLD, false);
        g.pose().popPose();
    }

    private void label(GuiGraphics g, String s, float cx, float y) {
        int w = font.width(s) + 6;
        Theme.rounded(g, (int) (cx - w / 2f), (int) y - 1, w, 10, 0xC0000000);
        g.drawCenteredString(font, s, (int) cx, (int) y, Theme.TEXT);
    }

    private void drawTrayBacking(GuiGraphics g) {
        if (!hasTray()) return;
        int x = (int) trayX, y = (int) trayY, w = (int) trayW, h = (int) trayH;
        Theme.rounded(g, x - 1, y - 1, w + 2, h + 2, Theme.SELECT);
        Theme.rounded(g, x, y, w, h, 0xE0141A16);
        if (!trayLabel.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(x + 5, y + 3, 0);
            g.pose().scale(0.75f, 0.75f, 1);
            g.drawString(font, Theme.ellipsize(font, trayLabel, (int) ((w - 10) / 0.75f)), 0, 0, Theme.GOLD, false);
            g.pose().popPose();
        }
    }

    /** The game is waiting for a pick (max 0 means Card-Forge is only highlighting playable cards). */
    private boolean picking() {
        return duel.isSelecting() && duel.getSelectionMax() > 0;
    }

    private boolean hasTray() {
        for (Placed p : placed) if (p.zone == Zone.TRAY) return true;
        return false;
    }

    /**
     * Blocks you've made this step, blocker id to attacker id, shown at once (the game's own combat view only
     * catches up when you confirm).
     */
    private final Map<Integer, Integer> pendingBlocks = new HashMap<>();

    private void drawCombatLines(GuiGraphics g, GameView view, int mx, int my) {
        CombatView combat = view.getCombat();
        if (view.getPhase() != PhaseType.COMBAT_DECLARE_BLOCKERS) pendingBlocks.clear();
        if (!pendingBlocks.isEmpty()) {
            List<String> plan = new ArrayList<>();
            for (Map.Entry<Integer, Integer> e : pendingBlocks.entrySet()) {
                Anim b = anims.get(e.getKey()), a = anims.get(e.getValue());
                if (a == null || b == null || a.card == null || b.card == null) continue;
                Theme.line(g, b.x + b.w / 2, b.y + b.h / 2, a.x + a.w / 2, a.y + a.h / 2, 3f, 0xE0FFD45A);
                String chip = "→ " + a.card.getName();
                float s = 0.7f;
                int cw = (int) (font.width(chip) * s) + 6;
                int cx = (int) (b.x + b.w / 2 - cw / 2f), cy = (int) (b.y + b.h + 2);
                Theme.rounded(g, cx, cy, cw, 10, 0xE0302408);
                g.pose().pushPose();
                g.pose().translate(cx + 3, cy + 2, 300);
                g.pose().scale(s, s, 1);
                g.drawString(font, chip, 0, 0, Theme.GOLD, false);
                g.pose().popPose();
                plan.add(b.card.getName() + " → " + a.card.getName());
            }
            if (!plan.isEmpty()) {
                String text = "Your blocks: " + String.join(" · ", plan) + "   (OK to confirm)";
                text = Theme.ellipsize(font, text, boardW - 40);
                int tw = font.width(text) + 12;
                int tx = (boardW - tw) / 2, ty = (int) midY - 7;
                Theme.rounded(g, tx, ty, tw, 14, 0xE0201808);
                g.drawString(font, text, tx + 6, ty + 3, Theme.GOLD, false);
            }
        }
        if (combat != null) {
            for (CardView attacker : combat.getAttackers()) {
                Anim a = anims.get(attacker.getId());
                if (a == null) continue;
                var blockers = combat.getBlockers(attacker);
                if (blockers == null) continue;
                for (CardView b : blockers) {
                    Anim bl = anims.get(b.getId());
                    if (bl == null) continue;
                    Theme.line(g, a.x + a.w / 2, a.y + a.h / 2, bl.x + bl.w / 2, bl.y + bl.h / 2, 2.5f, 0xC0E5534B);
                }
            }
        }
        // While dragging a blocker, show which attacker it would block.
        if (dragging && pressed != null && combat != null && view.getPhase() == PhaseType.COMBAT_DECLARE_BLOCKERS) {
            Anim src = anims.get(pressed.card.getId());
            Placed target = attackerAt(combat, mx, my);
            if (src != null && target != null) {
                Anim t = anims.get(target.card.getId());
                if (t != null) Theme.line(g, src.x + src.w / 2, src.y + src.h / 2, t.x + t.w / 2, t.y + t.h / 2, 3f, 0xE0FFD45A);
            }
        }
    }

    private Placed attackerAt(CombatView combat, double mx, double my) {
        for (Placed p : placed) {
            if (p.zone != Zone.BATTLEFIELD || (pressed != null && p.card.getId() == pressed.card.getId())) continue;
            Anim a = anims.get(p.card.getId());
            if (a != null && p.contains(mx, my, a) && combat.isAttacking(p.card)) return p;
        }
        return null;
    }

    private void drawCards(GuiGraphics g, long now) {
        Placed draggedP = dragging ? pressed : null;
        // Order: battlefield, stack, commanders, hand, the pick tray, then whatever is hovered or dragged on top.
        for (Zone z : new Zone[]{Zone.BATTLEFIELD, Zone.STACK, Zone.COMMAND, Zone.HAND, Zone.TRAY}) {
            for (Placed p : placed) {
                if (p.zone != z || p == hovered || (draggedP != null && p.card.getId() == draggedP.card.getId())) continue;
                drawPlaced(g, p, now);
            }
        }
        for (Map.Entry<Integer, Anim> e : anims.entrySet()) {
            Anim a = e.getValue();
            if (a.goneSince != 0 && a.card != null) {
                drawCardAt(g, a.card, a, a.hidden, now, false);
            }
        }
        if (hovered != null) drawPlaced(g, hovered, now);
        if (draggedP != null) {
            Anim a = anims.get(draggedP.card.getId());
            if (a != null) drawCardAt(g, draggedP.card, a, false, now, true);
        }
    }

    private void drawPlaced(GuiGraphics g, Placed p, long now) {
        Anim a = anims.get(p.card.getId());
        if (a == null) return;
        boolean battlefield = p.zone == Zone.BATTLEFIELD;
        if (arenaActive() && battlefield && p != hovered) {
            // In arena view the battlefield lives in the world; keep faint 2D copies for clicking and dragging.
            float keep = a.alpha;
            a.alpha = keep * 0.3f;
            drawCardAt(g, p.card, a, p.hidden, now, false);
            a.alpha = keep;
        } else {
            drawCardAt(g, p.card, a, p.hidden, now, false);
        }
        if (battlefield && !p.hidden && duel.mayView(p.card)) drawStatus(g, p.card, a);
    }

    private boolean arenaActive() {
        return arenaView && ArenaRenderer.has(table);
    }

    private void toggleView() {
        arenaView = !arenaView;
        dev.mtgcraft.MtgClientConfig.ARENA_VIEW.set(arenaView);
        Theme.click();
    }

    private void drawCardAt(GuiGraphics g, CardView c, Anim a, boolean hidden, long now, boolean lifted) {
        g.pose().pushPose();
        g.pose().translate(a.x + a.w / 2, a.y + a.h / 2, lifted ? 200 : 0);
        g.pose().mulPose(Axis.ZP.rotationDegrees(a.rot));
        g.pose().scale(a.scale, a.scale, 1);
        g.pose().translate(-a.w / 2, -a.h / 2, 0);
        float alpha = Math.max(0, Math.min(1, a.alpha));

        // drop shadow, larger when the card is lifted
        int sh = lifted ? 6 : 2;
        Theme.fillF(g, g.pose().last().pose(), sh, sh + 1, a.w + sh, a.h + sh + 1, ((int) (alpha * (lifted ? 110 : 70)) << 24));

        if (hidden || (c.getCurrentState() == null) || !duel.mayView(c)) {
            blitScaled(g, Theme.CARD_BACK, 0, 0, a.w, a.h, 126, 176, alpha);
        } else {
            drawCardFace(g, c, 0, 0, a.w, a.h, alpha);
            // My creatures that can't attack or tap yet are dimmed.
            if (c.isSick() && c.getController() != null && duel.isLocalPlayer(c.getController())) {
                Theme.fillF(g, g.pose().last().pose(), 0, 0, a.w, a.h, 0x30000000);
            }
        }

        int border = borderColor(c, now);
        if (border != 0) outline(g, a.w, a.h, border);
        g.pose().popPose();
    }

    private int borderColor(CardView c, long now) {
        if (duel.isSelectable(c)) {
            int pulse = (int) (150 + 105 * Math.sin(now / 160.0));
            return (pulse << 24) | (Theme.SELECT & 0xFFFFFF);
        }
        if (queuedAttack.contains(c.getId())) {
            int pulse = (int) (150 + 105 * Math.sin(now / 160.0));
            return (pulse << 24) | (Theme.RED & 0xFFFFFF);
        }
        if (c.isAttacking()) return Theme.RED;
        CombatView combat = duel.getGameView() == null ? null : duel.getGameView().getCombat();
        if (combat != null && combat.isBlocking(c)) return 0xFF4FB8E0;
        if (duel.isHighlighted(c)) return 0xFF58A6FF;
        if (hovered != null && hovered.card.getId() == c.getId()) return 0xD0FFFFFF;
        // Cards you can play right now (sent by the server at your priority): a soft green glow.
        if (!picking() && highlightPlayable() && duel.isWeaklySelectable(c)) {
            int pulse = (int) (140 + 90 * Math.sin(now / 240.0));
            return (pulse << 24) | 0x4FD06A;
        }
        return 0;
    }

    private void outline(GuiGraphics g, float w, float h, int color) {
        var m = g.pose().last().pose();
        float t = 1.2f;
        Theme.fillF(g, m, -t, -t, w + t, 0, color);
        Theme.fillF(g, m, -t, h, w + t, h + t, color);
        Theme.fillF(g, m, -t, 0, 0, h, color);
        Theme.fillF(g, m, w, 0, w + t, h, color);
    }

    /** Card art if downloaded, otherwise a readable text frame in the card's colours. */
    private void drawCardFace(GuiGraphics g, CardView c, float x, float y, float w, float h, float alpha) {
        drawCardFace(g, c, x, y, w, h, alpha, false);
    }

    /** Set while the zoom panel draws: sideways-printed cards (split, Room, Battle) are turned to read there. */
    private boolean zoomDrawing;
    private boolean turning;
    /** Extra quarter turns for the zoom panel (R), and the card they apply to. */
    private int zoomTurns;
    private CardView zoomTurnCard;

    private void drawCardFace(GuiGraphics g, CardView c, float x, float y, float w, float h, float alpha, boolean forceLarge) {
        if (!turning) {
            boolean flipped = c.isFlipped();
            boolean sideways = zoomDrawing && (c.isSplitCard() || c.isRoom() || (c.getCurrentState() != null && c.getCurrentState().isBattle()));
            int turns = (flipped ? 2 : 0) + (sideways ? 1 : 0) + (zoomDrawing && zoomTurnCard == c ? zoomTurns : 0);
            sideways = turns % 2 == 1;
            flipped = turns % 4 == 2;
            if (turns % 4 != 0) {
                // Flip cards read upside down once flipped; split cards, Rooms and Battles are printed sideways.
                float cw = w, ch = h;
                if (sideways) {
                    float k = Math.min(w / h, h / w);
                    cw = w * k;
                    ch = h * k;
                }
                g.pose().pushPose();
                g.pose().translate(x + w / 2, y + h / 2, 0);
                g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(90 * (turns % 4)));
                turning = true;
                try {
                    drawCardFace(g, c, -cw / 2, -ch / 2, cw, ch, alpha, forceLarge || sideways);
                } finally {
                    turning = false;
                    g.pose().popPose();
                }
                return;
            }
        }
        CardStateView s =c == showBackOf && c.getAlternateState() != null ? c.getAlternateState() : c.getCurrentState();
        String key = s.getImageKey(duel.getLocalPlayers());
        // Pick the texture by on-screen pixels, not GUI units, so text stays sharp at any GUI scale.
        double realH = h * minecraft.getWindow().getGuiScale();
        boolean large = forceLarge || realH > CardTextures.SMALL_H * 1.15;
        ResourceLocation tex = CardTextures.get(key, large);
        if (tex != null) {
            blitScaled(g, tex, x, y, w, h, large ? CardTextures.LARGE_W : CardTextures.SMALL_W,
                    large ? CardTextures.LARGE_H : CardTextures.SMALL_H, alpha);
            return;
        }
        var m = g.pose().last().pose();
        int frame = frameColor(s.getColors());
        int a = (int) (alpha * 255) << 24;
        Theme.fillF(g, m, x, y, x + w, y + h, a | 0x111111);
        Theme.fillF(g, m, x + 1.5f, y + 1.5f, x + w - 1.5f, y + h - 1.5f, a | (frame & 0xFFFFFF));
        Theme.fillF(g, m, x + 3, y + h * 0.16f, x + w - 3, y + h * 0.55f, a | 0x2B2B2B);
        Theme.fillF(g, m, x + 3, y + h * 0.6f, x + w - 3, y + h - 4, a | 0xEEE6D2);
        float ts = Math.max(0.35f, w / 140f);
        g.pose().pushPose();
        g.pose().translate(x + 3, y + 3, 0);
        g.pose().scale(ts, ts, 1);
        int innerW = (int) ((w - 6) / ts);
        g.drawString(font, Theme.ellipsize(font, s.getName(), innerW), 0, 0, 0xFF000000, false);
        String cost = s.getManaCost() == null ? "" : s.getManaCost().toString();
        g.drawString(font, cost, innerW - font.width(cost), (int) (h * 0.08f / ts) + 2, 0xFF000000, false);
        int ty = (int) ((h * 0.6f + 2) / ts);
        String type = s.getType() == null ? "" : s.getType().toString();
        g.drawString(font, Theme.ellipsize(font, type, innerW), 0, ty, 0xFF222222, false);
        List<FormattedCharSequence> lines = font.split(Component.literal(s.getOracleText() == null ? "" : s.getOracleText()), innerW);
        int maxLines = (int) ((h * 0.4f - 16) / ts / 9);
        for (int i = 0; i < Math.min(maxLines, lines.size()); i++) {
            g.drawString(font, lines.get(i), 0, ty + 10 + i * 9, 0xFF333333, false);
        }
        g.pose().popPose();
    }

    private static int frameColor(ColorSet cs) {
        if (cs == null || cs.isColorless()) return 0xFFA8A8A0;
        if (cs.isMulticolor()) return 0xFFD9B54A;
        if (cs.hasWhite()) return 0xFFF0E8C8;
        if (cs.hasBlue()) return 0xFF5B8FD6;
        if (cs.hasBlack()) return 0xFF6A5E68;
        if (cs.hasRed()) return 0xFFD9614F;
        return 0xFF5BAE6A;
    }

    // ------------------------------------------------------------------ status chips

    private static final int CHIP_UP = 0xFF7BE07B, CHIP_DOWN = 0xFFFF7A6E, CHIP_SICK = 0xFFA9C2FF, CHIP_INFO = 0xFFB8F0FF;
    /** Printed power/toughness by card name (empty array: none/unknown), to colour buffs and debuffs. */
    private static final Map<String, int[]> PRINTED = new HashMap<>();

    private static int[] printed(String name) {
        return PRINTED.computeIfAbsent(name, n -> {
            try {
                var pc = FModel.getMagicDb().getCommonCards().getCard(n);
                if (pc != null && pc.getRules() != null && pc.getRules().getType().isCreature()) {
                    var r = pc.getRules();
                    if (!String.valueOf(r.getPower()).contains("*") && !String.valueOf(r.getToughness()).contains("*")) {
                        return new int[]{r.getIntPower(), r.getIntToughness()};
                    }
                }
            } catch (RuntimeException ignored) {
                // tokens and unknown cards have nothing to compare with
            }
            return new int[0];
        });
    }

    /**
     * Power/toughness, loyalty, counters and summoning sickness, drawn upright at the card's corners (also when the
     * card is tapped sideways). Green means bigger than printed, red means smaller or damaged.
     */
    private void drawStatus(GuiGraphics g, CardView c, Anim a) {
        CardStateView s = c.getCurrentState();
        if (s == null) return;
        boolean sideways = Math.abs(Math.sin(Math.toRadians(a.rot))) > 0.7;
        float bw = (sideways ? a.h : a.w) * a.scale, bh = (sideways ? a.w : a.h) * a.scale;
        float x0 = a.x + a.w / 2 - bw / 2, y0 = a.y + a.h / 2 - bh / 2;
        float ts = Math.max(0.5f, Math.min(1f, Math.min(a.w, a.h) * a.scale / 60f));
        g.pose().pushPose();
        g.pose().translate(0, 0, 5);
        if (s.isCreature()) {
            int dmg = c.getDamage();
            int power = s.getPower(), tough = s.getToughness();
            int[] base = printed(s.getName());
            int color = Theme.TEXT;
            if (dmg > 0 || (base.length == 2 && (power < base[0] || tough < base[1]))) color = CHIP_DOWN;
            else if (base.length == 2 && (power > base[0] || tough > base[1])) color = CHIP_UP;
            badge(g, power + "/" + (tough - dmg), x0 + bw - 1, y0 + bh - 1, ts, color, true);
        } else if (s.isPlaneswalker() && s.getLoyalty() != null) {
            badge(g, s.getLoyalty(), x0 + bw - 1, y0 + bh - 1, ts, 0xFFFFE08A, true);
        }

        List<String> texts = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        if (c.isSick()) {
            texts.add("Zz");
            colors.add(CHIP_SICK);
        }
        var counters = c.getCounters();
        if (counters != null) {
            for (var type : counters.elementSet()) {
                int n = counters.count(type);
                String name = type.getName();
                if (name.equalsIgnoreCase("loyalty")) continue;
                if (name.equals("+1/+1")) { texts.add("+" + n); colors.add(CHIP_UP); }
                else if (name.equals("-1/-1")) { texts.add("-" + n); colors.add(CHIP_DOWN); }
                else { texts.add(shortName(name) + (n > 1 ? n : "")); colors.add(CHIP_INFO); }
            }
        }
        float cs = ts * 0.8f, cy = y0 + 1;
        for (int i = 0; i < texts.size(); i++) {
            if (i == 3 && texts.size() > 4) {
                badge(g, "+" + (texts.size() - 3), x0 + 1, cy, cs, Theme.MUTED, false);
                break;
            }
            badge(g, texts.get(i), x0 + 1, cy, cs, colors.get(i), false);
            cy += 11 * cs + 1;
        }

        // Keywords (gained or printed) down the right edge: flying, trample, shroud...
        List<String[]> kws = keywordChips(s);
        float ky = y0 + 1;
        for (int i = 0; i < kws.size(); i++) {
            String text = i == 3 && kws.size() > 4 ? "+" + (kws.size() - 3) : kws.get(i)[0];
            int color = i == 3 && kws.size() > 4 ? Theme.MUTED : (int) Long.parseLong(kws.get(i)[1], 16);
            float tw = (font.width(text) + 4) * cs;
            badge(g, text, x0 + bw - tw - 1, ky, cs, color, false);
            ky += 11 * cs + 1;
            if (i == 3) break;
        }
        // Auras and equipment attached to this card.
        int attached = 0;
        try {
            for (CardView att : c.getAttachedCards()) if (att != null) attached++;
        } catch (RuntimeException unsynced) {
            // attachments arrive with the next sync
        }
        if (attached > 0) badge(g, "⛓" + attached, x0 + 1, y0 + bh - (s.isCreature() ? 0 : 0) - 11 * cs - 1, cs, 0xFFE0C070, false);

        // In a multiplayer game, say who each attacker is going after.
        CombatView combat = duel.getGameView() == null ? null : duel.getGameView().getCombat();
        if (multiplayer() && combat != null && combat.isAttacking(c)) {
            GameEntityView def = combat.getDefender(c);
            if (def != null) {
                String who = def instanceof PlayerView p && duel.isLocalPlayer(p) ? "You" : def.toString();
                badge(g, "→ " + Theme.ellipsize(font, who, (int) (bw / cs)), x0 + 1, y0 + bh - 11 * cs - 1, cs, Theme.RED, false);
            }
        }
        g.pose().popPose();
    }

    /** Short chip text and colour (hex ARGB) for the keywords that matter most at a glance, in a fixed order. */
    private static final Object[][] KEYWORD_CHIPS = {
            {forge.game.keyword.Keyword.FLYING, "Fly", "FFA8D8FF"},
            {forge.game.keyword.Keyword.REACH, "Rch", "FF9FD49F"},
            {forge.game.keyword.Keyword.TRAMPLE, "Trm", "FF9FE07F"},
            {forge.game.keyword.Keyword.FIRST_STRIKE, "1st", "FFFFD27F"},
            {forge.game.keyword.Keyword.DOUBLE_STRIKE, "2x", "FFFFB060"},
            {forge.game.keyword.Keyword.DEATHTOUCH, "DT", "FFC890FF"},
            {forge.game.keyword.Keyword.LIFELINK, "LL", "FFFFF0A0"},
            {forge.game.keyword.Keyword.VIGILANCE, "Vig", "FFF0F0F0"},
            {forge.game.keyword.Keyword.HASTE, "Hst", "FFFF8A70"},
            {forge.game.keyword.Keyword.MENACE, "Men", "FFFF7070"},
            {forge.game.keyword.Keyword.DEFENDER, "Def", "FFB0B0B0"},
            {forge.game.keyword.Keyword.HEXPROOF, "Hex", "FF70C0FF"},
            {forge.game.keyword.Keyword.SHROUD, "Shr", "FF70A0FF"},
            {forge.game.keyword.Keyword.WARD, "Wrd", "FF80B0FF"},
            {forge.game.keyword.Keyword.PROTECTION, "Pro", "FFFFFFFF"},
            {forge.game.keyword.Keyword.INDESTRUCTIBLE, "Ind", "FFFFE070"},
            {forge.game.keyword.Keyword.INFECT, "Inf", "FF90E090"},
            {forge.game.keyword.Keyword.FLASH, "Fls", "FFC0C0FF"},
    };

    private static List<String[]> keywordChips(CardStateView s) {
        List<String[]> out = new ArrayList<>();
        try {
            var kws = s.getKeywords();
            if (kws == null || kws.isEmpty()) return out;
            for (Object[] k : KEYWORD_CHIPS) {
                if (kws.contains((forge.game.keyword.Keyword) k[0])) out.add(new String[]{(String) k[1], (String) k[2]});
            }
        } catch (RuntimeException unsynced) {
            // keywords arrive with the next sync
        }
        return out;
    }

    /** Full keyword names for the hover card (everything the card has right now, not just the chips). */
    private static List<String> keywordNames(CardStateView s) {
        List<String> out = new ArrayList<>();
        try {
            var kws = s.getKeywords();
            if (kws == null) return out;
            Set<String> seen = new LinkedHashSet<>();
            for (var k : kws) {
                String t = k.title();
                if (t == null || t.isBlank()) t = k.keyword() == null ? k.original() : k.keyword().toString();
                if (t != null && !t.isBlank()) seen.add(t);
            }
            out.addAll(seen);
        } catch (RuntimeException unsynced) {
            // keywords arrive with the next sync
        }
        return out;
    }

    /**
     * Lines from each Aura/Equipment to the permanent it's attached to, so it's clear what's on what.
     */
    private void drawAttachmentLinks(GuiGraphics g) {
        for (Placed p : placed) {
            if (p.zone != Zone.BATTLEFIELD) continue;
            CardView host;
            try {
                host = p.card.getAttachedTo();
            } catch (RuntimeException unsynced) {
                continue;
            }
            if (host == null) continue;
            Anim a = anims.get(p.card.getId()), h = anims.get(host.getId());
            if (a == null || h == null) continue;
            boolean focus = hovered != null && (hovered.card.getId() == p.card.getId() || hovered.card.getId() == host.getId());
            Theme.line(g, a.x + a.w / 2, a.y + a.h / 2, h.x + h.w / 2, h.y + h.h / 2, focus ? 2.5f : 1.5f,
                    focus ? 0xF0E0C070 : 0x80E0C070);
        }
    }

    /** A small box beside the hovered permanent: its keywords, counters and what's attached to it. */
    private void drawHoverInfo(GuiGraphics g, int mx, int my) {
        if (hovered == null || hovered.zone != Zone.BATTLEFIELD || dragging || !duel.mayView(hovered.card)) return;
        CardView c = hovered.card;
        CardStateView s = c.getCurrentState();
        if (s == null) return;
        List<String> lines = new ArrayList<>();
        List<String> kws = keywordNames(s);
        if (!kws.isEmpty()) lines.add(String.join(", ", kws));
        if (c.isSick()) lines.add("Summoning sick: can't attack or tap yet");
        var counters = c.getCounters();
        if (counters != null) {
            for (var type : counters.elementSet()) lines.add(counters.count(type) + " " + type.getName() + " counter" + (counters.count(type) == 1 ? "" : "s"));
        }
        try {
            CardView host = c.getAttachedTo();
            if (host != null) lines.add("Attached to " + host.getName());
            List<String> on = new ArrayList<>();
            for (CardView att : c.getAttachedCards()) if (att != null) on.add(att.getName());
            if (!on.isEmpty()) lines.add("Has attached: " + String.join(", ", on));
        } catch (RuntimeException unsynced) {
            // attachments arrive with the next sync
        }
        if (c.getDamage() > 0) lines.add(c.getDamage() + " damage marked");
        if (lines.isEmpty()) return;
        int w = 0;
        List<FormattedCharSequence> wrapped = new ArrayList<>();
        for (String l : lines) wrapped.addAll(font.split(Component.literal(l), 150));
        for (FormattedCharSequence l : wrapped) w = Math.max(w, font.width(l));
        int h = wrapped.size() * 9 + 6;
        int x = Math.min(mx + 12, boardW - w - 10), y = Math.max(4, Math.min(my - h - 6, height - h - 4));
        g.pose().pushPose();
        g.pose().translate(0, 0, 260);
        Theme.rounded(g, x - 1, y - 1, w + 10, h + 2, Theme.PANEL_EDGE);
        Theme.rounded(g, x, y, w + 8, h, 0xF0141A16);
        for (int i = 0; i < wrapped.size(); i++) g.drawString(font, wrapped.get(i), x + 4, y + 4 + i * 9, i == 0 && !kws.isEmpty() ? Theme.GOLD : Theme.TEXT, false);
        g.pose().popPose();
    }

    /** "Trample" → "Tra", "Charge" → "Cha": counter names short enough for a chip. */
    private static String shortName(String name) {
        String n = name.replace("_", " ").trim();
        if (n.isEmpty()) return "?";
        n = n.substring(0, 1).toUpperCase() + n.substring(1).toLowerCase();
        return n.length() <= 4 ? n : n.substring(0, 3);
    }

    private void badge(GuiGraphics g, String text, float x, float y, float scale, int color, boolean rightBottom) {
        g.pose().pushPose();
        g.pose().translate(x, y, 1);
        g.pose().scale(scale, scale, 1);
        int tw = font.width(text) + 4;
        int bx = rightBottom ? -tw : 0, by = rightBottom ? -11 : 0;
        Theme.rounded(g, bx, by, tw, 11, 0xE0101010);
        g.drawString(font, text, bx + 2, by + 2, color, false);
        g.pose().popPose();
    }

    private void blitScaled(GuiGraphics g, ResourceLocation tex, float x, float y, float w, float h, int tw, int th, float alpha) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(w / tw, h / th, 1);
        g.setColor(1, 1, 1, alpha);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        g.blit(tex, 0, 0, 0, 0, tw, th, tw, th);
        g.setColor(1, 1, 1, 1);
        g.pose().popPose();
    }

    /** Commander tax over my commanders in the corner. */
    private void drawCommanderTags(GuiGraphics g, PlayerView me) {
        for (Placed p : placed) {
            if (p.zone != Zone.COMMAND || p == hovered) continue;
            Anim a = anims.get(p.card.getId());
            if (a == null) continue;
            int casts = me.getCommanderCast(p.card);
            String tag = casts > 0 ? "♛ +" + (2 * casts) : "♛";
            badge(g, tag, a.x + 2, a.y + 2, 0.8f, Theme.GOLD, false);
        }
    }

    private void drawStackLabel(GuiGraphics g, GameView view) {
        int n = view.getStack().size();
        if (n == 0) return;
        StackItemView top = view.getStack().getFirst();
        String text = top.getText();
        int w = Math.min(rowX1 - rowX0, font.width(text) + 10);
        int cx = (rowX0 + rowX1) / 2;
        int y = (int) (midY + cardH * 0.55f + 4);
        // The card being played, big in the middle of the board, above what it does.
        CardView src = top.getSourceCard();
        if (src != null && duel.mayView(src)) {
            float ch = cardH * 1.3f, cw = ch * 0.716f;
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            Theme.rounded(g, (int) (cx - cw / 2) - 2, (int) (y - ch) - 4, (int) cw + 4, (int) ch + 4, 0xC0000000);
            drawCardFace(g, src, cx - cw / 2, y - ch - 2, cw, ch, 1f);
            g.pose().popPose();
        }
        Theme.rounded(g, cx - w / 2, y, w, 12, 0xD0101010);
        g.drawCenteredString(font, Theme.ellipsize(font, text, w - 8), cx, y + 2, Theme.GOLD);
    }

    /** A one-line instruction on the middle line for the steps people get stuck on (attacking, blocking). */
    private void drawStepBanner(GuiGraphics g, GameView view, PlayerView me, ChoiceRequest req) {
        String text = null;
        int color = Theme.GOLD;
        boolean myTurn = myTurn(view);
        PhaseType phase = view.getPhase();
        boolean priority = duel.prompt != null && duel.prompt.startsWith("Priority");
        if (req != null) {
            text = "Release Tab to answer: " + req.message;
        } else if (!view.getStack().isEmpty() || hasTray()) {
            return;
        } else if (!queuedAttack.isEmpty()) {
            text = "⚔ " + queuedAttack.size() + " ready to attack: moving to combat...";
        } else if (myTurn && phase == PhaseType.COMBAT_DECLARE_ATTACKERS && !priority && duel.okEnabled) {
            text = "⚔ Drag creatures at an opponent to attack, then press " + duel.okLabel;
            color = Theme.RED;
        } else if (!myTurn && phase == PhaseType.COMBAT_DECLARE_BLOCKERS && !priority && duel.okEnabled && defending(view, me)) {
            text = "⛨ Drag your creatures onto attackers to block, then press " + duel.okLabel;
            color = 0xFF7FD0F0;
        }
        if (text == null) return;
        text = Theme.ellipsize(font, text, rowX1 - rowX0 - 20);
        int w = font.width(text) + 12;
        int cx = (rowX0 + rowX1) / 2, y = (int) midY - 7;
        Theme.rounded(g, cx - w / 2 - 1, y - 1, w + 2, 15, color);
        Theme.rounded(g, cx - w / 2, y, w, 13, 0xF0141A16);
        g.drawCenteredString(font, text, cx, y + 3, color);
    }

    /** Whether any attacker is coming at me (or my planeswalkers). */
    private boolean defending(GameView view, PlayerView me) {
        CombatView combat = view.getCombat();
        if (combat == null) return false;
        for (CardView a : combat.getAttackers()) {
            GameEntityView d = combat.getDefender(a);
            if (d instanceof PlayerView p && p.getId() == me.getId()) return true;
            if (d instanceof CardView c && c.getController() != null && c.getController().getId() == me.getId()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ drawing: sidebar

    private void drawSidebar(GuiGraphics g, GameView view, PlayerView me, PlayerView opp, int mx, int my, long now) {
        int x = boardW + 6, w = sideW - 12;
        if (multiplayer()) {
            drawTurnBanner(g, view, x, oppPillY, w);
            drawColumnTags(g, view, mx, my);
        } else {
            drawPill(g, view, opp, x, oppPillY, w, mx, my);
        }
        drawPhases(g, view, x, oppPillY + 39, w, mx, my);

        if (zoomH == 0) {
            // no zoom panel on small screens
        } else if (zoomCard != null && duel.mayView(zoomCard)) {
            // Two-sided cards: hold Shift to see the other side.
            boolean twoSided = zoomCard.getAlternateState() != null;
            if (twoSided && hasShiftDown()) showBackOf = zoomCard;
            zoomDrawing = true;
            try {
                drawCardFace(g, zoomCard, zoomX, zoomY, zoomW, zoomH, 1f);
            } finally {
                zoomDrawing = false;
            }
            showBackOf = null;
            if (twoSided) {
                String hint = hasShiftDown() ? "Other side" : "Shift: other side  R: rotate";
                int hw = font.width(hint) + 8;
                Theme.rounded(g, zoomX + (zoomW - hw) / 2, zoomY + zoomH - 14, hw, 12, 0xD0101010);
                g.drawCenteredString(font, hint, zoomX + zoomW / 2, zoomY + zoomH - 12, Theme.GOLD);
            }
        } else {
            Theme.rounded(g, zoomX, zoomY, zoomW, zoomH, 0x30FFFFFF);
            g.drawCenteredString(font, "Hover a card", zoomX + zoomW / 2, zoomY + zoomH / 2 - 4, Theme.MUTED);
        }

        // What to do now: a short headline and one or two plain sentences.
        boolean shake = now - duel.flashTime < 300;
        int sx = shake ? (int) (Math.sin(now / 20.0) * 3) : 0;
        String[] guide = guide(view, me);
        int ty = promptY;
        if (!guide[0].isEmpty()) {
            g.drawString(font, Theme.ellipsize(font, guide[0], w), x + sx, ty, Theme.GOLD);
            ty += 11;
        }
        List<FormattedCharSequence> lines = font.split(Component.literal(guide[1]), w);
        int maxLines = Math.max(1, (toolsTop() - ty - 2) / 9);
        g.pose().pushPose();
        for (int i = 0; i < Math.min(maxLines, lines.size()); i++) {
            g.drawString(font, lines.get(i), x + sx, ty + i * 9, i == maxLines - 1 && lines.size() > maxLines ? Theme.MUTED : 0xFFD8DDD2);
        }
        g.pose().popPose();

        drawTools(g, x, w, mx, my);
        int bw = (w - 4) / 2;
        Theme.button(g, font, duel.okLabel + " ␣", x, buttonsY, bw, 20, mx, my, duel.okEnabled, true);
        Theme.button(g, font, duel.cancelLabel, x + bw + 4, buttonsY, bw, 20, mx, my, duel.cancelEnabled, false);

        drawPill(g, view, me, x, myPillY, w, mx, my);
        // The concede flag sits bottom-left, under the avatar, away from the mana counter on the right.
        String flag = confirmConcede ? "Concede?" : "⚑";
        int fw = font.width(flag) + 6;
        boolean fh = in(mx, my, x + 3, myPillY + 23, fw, 11);
        Theme.rounded(g, x + 3, myPillY + 23, fw, 11, fh || confirmConcede ? 0xF0803030 : 0x60000000);
        g.drawString(font, flag, x + 6, myPillY + 25, Theme.TEXT, false);
    }

    /** Small buttons above OK: the log, the zone viewer and the 2D/3D switch. */
    private void drawTools(GuiGraphics g, int x, int w, int mx, int my) {
        boolean arenaToggle = ArenaRenderer.has(table);
        // Creative mode gets a cheat button that wins the duel on the spot.
        boolean creative = minecraft.player != null && minecraft.player.isCreative() && !duel.isOver();
        int n = 5 + (arenaToggle ? 1 : 0) + (creative ? 1 : 0);
        // One row (the instructions above keep their space); labels shrink to fit instead of being cut.
        toolCols = n;
        toolRows = (n + toolCols - 1) / toolCols;
        toolBW = (w - (toolCols - 1) * 3) / toolCols;
        toolX0 = x;
        toolIdx = 0;
        int bw = (w - (n - 1) * 3) / n;
        int bx = x;
        toolButton(g, "Log", bx, bw, mx, my, showLog, () -> showLog = !showLog);
        bx += bw + 3;
        toolButton(g, "Stats", bx, bw, mx, my, showStats, () -> showStats = !showStats);
        bx += bw + 3;
        toolButton(g, "Zones", bx, bw, mx, my, viewerOpen, () -> {
            if (viewerOpen) viewerOpen = false;
            else openViewer(duel.me(), ZoneType.Graveyard);
        });
        bx += bw + 3;
        if (arenaToggle) {
            toolButton(g, arenaView ? "3D" : "2D", bx, bw, mx, my, arenaView, this::toggleView);
            bx += bw + 3;
        }
        // Stuck? Resend the game and clear stuck questions; a second press within 20 s ends the duel as a draw.
        toolButton(g, "Fix", bx, bw, mx, my, false, () -> {
            if (minecraft.player != null) minecraft.player.connection.sendCommand("mtgduel unstick");
            info("Refreshing the duel. Still stuck? Press Fix again within 20 seconds to end it as a draw.");
        });
        bx += bw + 3;
        // Auto-pass on/off (also in Settings): gold when it's on.
        boolean auto = autoPassOn();
        toolButton(g, "Auto", bx, bw, mx, my, auto, () -> {
            dev.mtgcraft.MtgClientConfig.AUTO_PASS.set(!autoPassOn());
            ClientTables.sendAutoPass();
            info(autoPassOn() ? "Auto-pass on: the game passes for you when you have nothing to play."
                    : "Auto-pass off: the game stops every time you could act.");
        });
        bx += bw + 3;
        if (creative) {
            toolButton(g, "Win", bx, bw, mx, my, false, () -> {
                dev.mtgcraft.net.Net.toServer(new dev.mtgcraft.net.Packets.CheatWin());
                info("Creative: winning the duel (works when it's your priority).");
            });
        }
    }

    private static boolean autoPassOn() {
        try {
            return dev.mtgcraft.MtgClientConfig.AUTO_PASS.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    private int toolCols = 1, toolRows = 1, toolBW, toolX0, toolIdx;

    /** The top of the tool buttons (one or two rows). */
    private int toolsTop() {
        return toolsY - (toolRows - 1) * 14;
    }

    private void toolButton(GuiGraphics g, String label, int ignoredX, int ignoredW, int mx, int my, boolean on, Runnable action) {
        int col = toolIdx % toolCols, row = toolIdx / toolCols;
        toolIdx++;
        int x = toolX0 + col * (toolBW + 3), w = toolBW, by = toolsY - (toolRows - 1 - row) * 14;
        boolean hover = in(mx, my, x, by, w, 12);
        Theme.rounded(g, x, by, w, 12, on ? 0xE0503F1C : hover ? 0xE04A4F49 : 0xC0262B27);
        g.pose().pushPose();
        float ls = Math.min(0.75f, (w - 3) / (float) Math.max(1, font.width(label)));
        g.pose().translate(x + w / 2f, by + 6 - 4 * ls, 0);
        g.pose().scale(ls, ls, 1);
        g.drawCenteredString(font, label, 0, 0, on ? Theme.GOLD : Theme.TEXT);
        g.pose().popPose();
        hits.add(new Hit(x, by, w, 12, () -> {
            action.run();
            Theme.click();
        }));
    }

    private void drawTurnBanner(GuiGraphics g, GameView view, int x, int y, int w) {
        Theme.rounded(g, x - 1, y - 1, w + 2, 36, Theme.PANEL_EDGE);
        Theme.rounded(g, x, y, w, 34, 0xF0182019);
        PlayerView turn = view.getPlayerTurn();
        boolean mine = turn != null && duel.me() != null && turn.getId() == duel.me().getId();
        g.drawString(font, "Turn " + view.getTurn(), x + 6, y + 5, Theme.MUTED);
        String who = turn == null ? "" : mine ? "Your turn" : turn.getName() + "'s turn";
        g.drawString(font, Theme.ellipsize(font, who, w - 12), x + 6, y + 18, mine ? Theme.GOLD : Theme.TEXT);
    }

    /** Name, life and zone counts above each opponent's column; also the drop/click target for that player. */
    private void drawColumnTags(GuiGraphics g, GameView view, int mx, int my) {
        PlayerView turn = view.getPlayerTurn();
        for (Column c : columns) {
            PlayerView p = c.player();
            int x = (int) c.x0(), y = (int) c.pillY(), w = (int) (c.x1() - c.x0());
            boolean target = duel.isHighlighted(p);
            boolean hover = in(mx, my, x, y, w, 14);
            boolean active = turn != null && turn.getId() == p.getId();
            boolean out = p.getHasLost();
            int edge = target ? Theme.SELECT : active ? Theme.GOLD : hover ? 0xFFB0B5A8 : Theme.PANEL_EDGE;
            Theme.rounded(g, x - 1, y - 1, w + 2, 16, edge);
            Theme.rounded(g, x, y, w, 14, out ? 0xE0301818 : 0xE0182019);
            String life = String.valueOf(p.getLife());
            int lifeW = font.width(life) + 4;
            g.drawString(font, life, x + w - lifeW, y + 3, p.getLife() <= 5 ? Theme.RED : 0xFFFFFFFF);
            if (w > 90) {
                // their open mana, so you can read what they could still cast
                int[] src = manaSources(p);
                String m = String.valueOf(src[0]);
                int mw = font.width(m) + 9;
                int mx0 = x + w - lifeW - mw - 2;
                Theme.disc(g, mx0 + 3, y + 7, 3, src[0] > 0 ? 0xFF8FE0FF : 0xFF506070);
                g.drawString(font, m, mx0 + 8, y + 3, src[0] > 0 ? 0xFF8FE0FF : Theme.MUTED, false);
                lifeW += mw + 2;
            }
            int nx = x + 3;
            if (Heads.draw(g, table, p.getName(), x + 2, y + 2, 10)) nx += 12;
            String name = Theme.ellipsize(font, p.getName(), Math.max(20, Math.min(font.width(p.getName()), w - lifeW - 8 - (nx - x) - (w > 150 ? 70 : 0))));
            g.drawString(font, name, nx, y + 3, out ? Theme.MUTED : Theme.TEXT);
            if (w > 150) {
                int cx = nx + 3 + font.width(name);
                zoneChips(g, view, p, cx, y + 3, x + w - lifeW - 4 - cx, mx, my);
            }
        }
    }

    /**
     * Clickable zone counts for one player (hand, library, graveyard, exile, command zone), plus poison and the most
     * commander damage they've taken. Clicking one opens the zone viewer there.
     */
    private void zoneChips(GuiGraphics g, GameView view, PlayerView p, int x, int y, int maxW, int mx, int my) {
        float s = 0.75f;
        int cx = x;
        List<String> labels = new ArrayList<>();
        List<ZoneType> zones = new ArrayList<>();
        labels.add("✋" + p.getZoneSize(ZoneType.Hand));
        zones.add(ZoneType.Hand);
        labels.add("Lib " + p.getZoneSize(ZoneType.Library));
        zones.add(ZoneType.Library);
        labels.add("GY " + p.getZoneSize(ZoneType.Graveyard));
        zones.add(ZoneType.Graveyard);
        int ex = p.getZoneSize(ZoneType.Exile), cmd = p.getZoneSize(ZoneType.Command);
        if (ex > 0) { labels.add("Ex " + ex); zones.add(ZoneType.Exile); }
        if (cmd > 0) { labels.add("Cmd " + cmd); zones.add(ZoneType.Command); }
        for (int i = 0; i < labels.size(); i++) {
            int cw = (int) (font.width(labels.get(i)) * s) + 4;
            if (cx + cw > x + maxW) return;
            boolean hover = in(mx, my, cx, y - 1, cw, 9);
            Theme.rounded(g, cx, y - 1, cw, 9, hover ? 0x80FFFFFF : 0x30FFFFFF);
            g.pose().pushPose();
            g.pose().translate(cx + 2, y + 0.5f, 0);
            g.pose().scale(s, s, 1);
            g.drawString(font, labels.get(i), 0, 0, Theme.TEXT, false);
            g.pose().popPose();
            ZoneType z = zones.get(i);
            hits.add(new Hit(cx, y - 1, cw, 9, () -> openViewer(p, z)));
            cx += cw + 2;
        }
        int cmdDamage = commanderDamageTaken(view, p);
        List<String> extra = new ArrayList<>();
        // Every player counter in words (poison from toxic/infect is lethal at 10).
        if (p.getCounters() != null) {
            for (var t : p.getCounters().elementSet()) {
                int n = p.getCounters().count(t);
                if (n <= 0) continue;
                String name = t.getName();
                extra.add(name.equalsIgnoreCase("poison") ? "Poison " + n + "/10" : name + " " + n);
            }
        }
        if (cmdDamage > 0) extra.add("♛" + cmdDamage + "/21");
        for (String e : extra) {
            int cw = (int) (font.width(e) * s) + 4;
            if (cx + cw > x + maxW) return;
            Theme.rounded(g, cx, y - 1, cw, 9, 0x60801818);
            g.pose().pushPose();
            g.pose().translate(cx + 2, y + 0.5f, 0);
            g.pose().scale(s, s, 1);
            g.drawString(font, e, 0, 0, 0xFFFFB0A8, false);
            g.pose().popPose();
            cx += cw + 2;
        }
    }

    /** The most combat damage any single commander has dealt this player (21 is lethal). */
    private static int commanderDamageTaken(GameView view, PlayerView p) {
        int most = 0;
        try {
            for (PlayerView q : view.getPlayers()) {
                List<CardView> cmds = q.getCommanders();
                if (cmds == null) continue;
                for (CardView c : cmds) most = Math.max(most, p.getCommanderDamage(c));
            }
        } catch (RuntimeException notSynced) {
            // commander info arrives with the first full sync
        }
        return most;
    }

    /**
     * The sidebar's instructions: {headline, detail}. Card-Forge's own prompts are kept where they say something
     * specific ("Select target creature"); its generic priority prompt becomes a plain hint for the current step.
     */
    private String[] guide(GameView view, PlayerView me) {
        String raw = duel.prompt == null ? "" : duel.prompt.replace("\n\n", "\n").replaceAll("[ \t]+", " ").trim();
        boolean myTurn = myTurn(view);
        PhaseType phase = view.getPhase();
        String ok = duel.okLabel == null ? "OK" : duel.okLabel;
        boolean stackEmpty = view.getStack().isEmpty();
        if (duel.isOver()) return new String[]{"Game over", ""};
        if (raw.startsWith("Priority") || raw.isEmpty()) {
            if (!duel.okEnabled && !duel.cancelEnabled) {
                PlayerView turn = view.getPlayerTurn();
                return new String[]{"Waiting...", (turn == null ? "The others are" : turn.getName() + " is") + " thinking."};
            }
            if (!stackEmpty) {
                return new String[]{"Respond?", "Cast an instant or use an ability, or press " + ok + " to let the top of the stack resolve."};
            }
            if (myTurn && phase == PhaseType.MAIN1) {
                return new String[]{"Your main phase", "Play a land and cast spells. Drag a creature at an opponent to attack. " + ok + " moves on."};
            }
            if (myTurn && phase == PhaseType.MAIN2) {
                return new String[]{"Your second main phase", "Cast more spells, or press " + ok + " to end your turn."};
            }
            if (myTurn) return new String[]{"Your turn", "Press " + ok + " to continue."};
            PlayerView turn = view.getPlayerTurn();
            return new String[]{(turn == null ? "Their" : turn.getName() + "'s") + " turn", "You may cast instants now. Press " + ok + " to continue."};
        }
        if (phase == PhaseType.COMBAT_DECLARE_ATTACKERS && myTurn) {
            return new String[]{"Declare attackers", "Drag creatures at an opponent (or click them). Right-click one to take it back. Press " + ok + " to attack."};
        }
        if (phase == PhaseType.COMBAT_DECLARE_BLOCKERS && !myTurn && defending(view, me)) {
            return new String[]{"Declare blockers", "Drag your creature onto an attacker to block it (or click the attacker, then your creature). Right-click undoes. Press " + ok + " when done."};
        }
        if (startingPlayerPrompt()) return new String[]{"Who goes first?", "Pick a player in the middle of the board."};
        if (raw.contains("Pay Mana Cost")) {
            String cost = raw.substring(raw.indexOf("Pay Mana Cost")).split("\n")[0].replace("Pay Mana Cost:", "Cost:").trim();
            return new String[]{"Pay the cost", cost + "\nClick lands to tap them" + (duel.okEnabled ? ", or press " + ok + " to pay automatically." : ".")
                    + (duel.cancelEnabled ? " " + duel.cancelLabel + " to stop casting." : "")};
        }
        if (picking() && hasTray()) return new String[]{"Choose", raw + "\nThe cards you can pick are in the middle of the board."};
        if (picking()) return new String[]{"Choose", raw + "\nGlowing cards can be picked."};
        return new String[]{"", raw};
    }

    private boolean startingPlayerPrompt() {
        String p = duel.prompt;
        return p != null && p.contains("start this game");
    }

    private void drawPill(GuiGraphics g, GameView view, PlayerView p, int x, int y, int w, int mx, int my) {
        boolean target = duel.isHighlighted(p);
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 34;
        int edge = target ? Theme.SELECT : hover ? 0xFFB0B5A8 : Theme.PANEL_EDGE;
        Theme.rounded(g, x - 1, y - 1, w + 2, 36, edge);
        Theme.rounded(g, x, y, w, 34, 0xF0182019);
        boolean turn = view.getPlayerTurn() != null && view.getPlayerTurn().getId() == p.getId();
        // Their face (player skin or mob head), framed gold on their turn; the initial if there's no portrait.
        Theme.rounded(g, x + 4, y + 4, 18, 18, turn ? Theme.GOLD : 0xFF3A403B);
        if (!Heads.draw(g, table, p.getName(), x + 5, y + 5, 16)) {
            Theme.disc(g, x + 13, y + 13, 9, turn ? Theme.GOLD : 0xFF3A403B);
            String initial = p.getName().isEmpty() ? "?" : p.getName().substring(0, 1).toUpperCase();
            g.drawCenteredString(font, initial, x + 13, y + 9, turn ? 0xFF2A2010 : Theme.TEXT);
        }
        g.drawString(font, Theme.ellipsize(font, p.getName(), w - 60), x + 26, y + 4, Theme.TEXT);
        String life = String.valueOf(p.getLife());
        g.pose().pushPose();
        g.pose().translate(x + w - 4 - font.width(life) * 1.5f, y + 3, 0);
        g.pose().scale(1.5f, 1.5f, 1);
        g.drawString(font, life, 0, 0, p.getLife() <= 5 ? Theme.RED : 0xFFFFFFFF);
        g.pose().popPose();
        zoneChips(g, view, p, x + 26, y + 15, w - 30, mx, my);
        // Mana available now: untapped mana sources (lands, rocks, dorks) out of all of them, plus floating mana.
        int[] src = manaSources(p);
        int floating = 0;
        for (int i = 0; i < 5; i++) floating += mana(p, MagicColor.WUBRG[i]);
        floating += mana(p, MagicColor.COLORLESS);
        String manaText = (src[0] + floating) + "/" + (src[1] + floating);
        int manaW = font.width(manaText) + 15;
        Theme.rounded(g, x + w - manaW - 3, y + 22, manaW, 11, 0xE0102030);
        Theme.disc(g, x + w - manaW + 3, y + 27, 3, src[0] + floating > 0 ? 0xFF8FE0FF : 0xFF506070);
        g.drawString(font, manaText, x + w - manaW + 9, y + 24, src[0] + floating > 0 ? 0xFF8FE0FF : Theme.MUTED, false);
        // floating mana, clickable to spend
        int ox = x + 26;
        for (int i = 0; i < 5; i++) {
            int n = mana(p, MagicColor.WUBRG[i]);
            if (n <= 0) continue;
            Theme.disc(g, ox + 4, y + 28, 4, Theme.MANA_FILL[i]);
            g.drawString(font, String.valueOf(n), ox + 10, y + 24, Theme.TEXT);
            ox += 22;
        }
        int c = mana(p, MagicColor.COLORLESS);
        if (c > 0) {
            Theme.disc(g, ox + 4, y + 28, 4, 0xFFB0B0B0);
            g.drawString(font, String.valueOf(c), ox + 10, y + 24, Theme.TEXT);
        }
    }

    /**
     * What the player's untapped mana sources can make, by colour: W U B R G, C for colourless only (it can't pay
     * coloured costs) and Any for sources of any colour.
     */
    private static String manaByColor(PlayerView p) {
        int[] n = new int[7];
        String[] sym = {"W", "U", "B", "R", "G", "C", "Any"};
        try {
            for (CardView c : cards(p.getCards(ZoneType.Battlefield))) {
                if (c.isTapped()) continue;
                CardStateView st = c.getCurrentState();
                if (st == null) continue;
                forge.card.ColorSet makes = st.origProduceMana();
                String text = st.getAbilityText();
                if (st.origProduceAnyMana()) {
                    n[6]++;
                } else if (makes != null && !makes.isColorless()) {
                    if (makes.hasWhite()) n[0]++;
                    if (makes.hasBlue()) n[1]++;
                    if (makes.hasBlack()) n[2]++;
                    if (makes.hasRed()) n[3]++;
                    if (makes.hasGreen()) n[4]++;
                } else if (st.isLand() || (text != null && text.contains("Add {"))) {
                    n[5]++;
                }
            }
        } catch (RuntimeException concurrentEdit) {
            return "-";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n.length; i++) if (n[i] > 0) out.append(sym[i]).append(n[i]).append("  ");
        return out.length() == 0 ? "nothing" : out.toString().trim();
    }

    /** The stat sheet panel (top left): one block per player. */
    private void drawStats(GuiGraphics g, GameView view) {
        List<PlayerView> players = new ArrayList<>();
        for (PlayerView p : view.getPlayers()) players.add(p);
        int x = 6, y = 24, w = 178, lh = 10;
        List<List<String>> blocks = new ArrayList<>();
        for (PlayerView p : players) {
            List<String> lines = new ArrayList<>();
            lines.add(p.getName() + "  -  Life " + p.getLife());
            List<String> counters = new ArrayList<>();
            if (p.getCounters() != null) {
                for (var t : p.getCounters().elementSet()) {
                    int n = p.getCounters().count(t);
                    if (n > 0) counters.add((t.getName().equalsIgnoreCase("poison") ? "Poison " + n + "/10" : t.getName() + " " + n));
                }
            }
            lines.add(counters.isEmpty() ? "No counters" : String.join(", ", counters));
            int[] ms = manaSources(p);
            StringBuilder pool = new StringBuilder();
            String[] sym = {"W", "U", "B", "R", "G", "C"};
            byte[] col = {forge.card.MagicColor.WHITE, forge.card.MagicColor.BLUE, forge.card.MagicColor.BLACK, forge.card.MagicColor.RED,
                    forge.card.MagicColor.GREEN, forge.card.MagicColor.COLORLESS};
            for (int i = 0; i < sym.length; i++) {
                int n = p.getMana(col[i]);
                if (n > 0) pool.append(sym[i]).append(n).append(" ");
            }
            lines.add("Mana sources " + ms[0] + "/" + ms[1] + " untapped" + (pool.length() > 0 ? "  Pool: " + pool.toString().trim() : ""));
            lines.add("Untapped makes: " + manaByColor(p));
            lines.add("Hand " + cards(p.getCards(ZoneType.Hand)).size() + "  Library " + cards(p.getCards(ZoneType.Library)).size()
                    + "  Graveyard " + cards(p.getCards(ZoneType.Graveyard)).size() + "  Exile " + cards(p.getCards(ZoneType.Exile)).size());
            int cmd = commanderDamageTaken(view, p);
            if (cmd > 0) lines.add("Commander damage taken " + cmd + "/21");
            blocks.add(lines);
        }
        int h = 6;
        for (List<String> b : blocks) h += b.size() * lh + 6;
        Theme.rounded(g, x, y, w, h, 0xE0101418);
        int yy = y + 4;
        for (List<String> b : blocks) {
            for (int i = 0; i < b.size(); i++) {
                g.pose().pushPose();
                g.pose().translate(x + 5, yy, 0);
                float s = Math.min(1f, (w - 10) / (float) Math.max(1, font.width(b.get(i))));
                g.pose().scale(s, s, 1);
                g.drawString(font, b.get(i), 0, 0, i == 0 ? Theme.GOLD : Theme.TEXT, false);
                g.pose().popPose();
                yy += lh;
            }
            yy += 6;
        }
    }

    /** {untapped, total} permanents a player controls that can make mana. */
    private static int[] manaSources(PlayerView p) {
        int untapped = 0, total = 0;
        try {
            if (p.getBattlefield() == null) return new int[]{0, 0};
            for (CardView c : new ArrayList<>((java.util.Collection<CardView>) p.getBattlefield())) {
                CardStateView st = c.getCurrentState();
                if (st == null) continue;
                boolean source;
                try {
                    forge.card.ColorSet makes = st.origProduceMana();
                    String text = st.getAbilityText();
                    // Lands, anything that makes coloured mana, and colourless rocks ("Add {C}") - not every permanent.
                    source = st.isLand() || st.origProduceAnyMana() || (makes != null && !makes.isColorless())
                            || (text != null && text.contains("Add {"));
                } catch (NullPointerException unsynced) {
                    source = st.isLand();
                }
                if (!source) continue;
                total++;
                if (!c.isTapped()) untapped++;
            }
        } catch (RuntimeException concurrentEdit) {
            return new int[]{0, 0};
        }
        return new int[]{untapped, total};
    }

    private static final PhaseType[][] PHASES = dev.mtgcraft.engine.net.AutoPass.SEGMENTS;
    private static final String[] PHASE_NAMES = {"UP", "DR", "M1", "BC", "ATK", "BLK", "DMG", "M2", "END"};
    private static final String[] PHASE_LONG = {"Upkeep", "Draw", "Main phase 1", "Beginning of combat", "Attacks",
            "Blocks", "Combat damage", "Main phase 2", "End of turn"};

    /**
     * The phase bar. Click a phase to make the game skip it for you (dimmed, crossed out) or stop there again;
     * skipped phases still stop when something is on the stack.
     */
    private void drawPhases(GuiGraphics g, GameView view, int x, int y, int w, int mx, int my) {
        PhaseType now = view.getPhase();
        int skips = skipPhases(), stops = stopPhases();
        float seg = w / (float) PHASES.length;
        for (int i = 0; i < PHASES.length; i++) {
            boolean active = false;
            for (PhaseType t : PHASES[i]) if (t == now) active = true;
            boolean skipped = (skips & (1 << i)) != 0, stopped = (stops & (1 << i)) != 0;
            int sx = (int) (x + i * seg), sw = (int) seg - 1;
            boolean hover = in(mx, my, sx, y, sw, 11);
            int bg = active ? Theme.GOLD : skipped ? 0x18FFFFFF : hover ? 0x70FFFFFF : 0x40FFFFFF;
            Theme.rounded(g, sx, y, sw, 11, bg);
            g.pose().pushPose();
            g.pose().translate(sx + seg / 2f, y + 2.5f, 0);
            float fit = Math.min(0.7f, (seg - 3) / Math.max(1f, font.width(PHASE_NAMES[i])));
            g.pose().scale(fit, fit, 1);
            int col = active ? 0xFF201808 : skipped ? 0x80A0A0A0 : Theme.MUTED;
            g.drawCenteredString(font, PHASE_NAMES[i], 0, 0, col);
            g.pose().popPose();
            if (skipped) g.fill(sx + 2, y + 5, sx + sw - 2, y + 6, active ? 0xC0201808 : 0x90E05050);
            // A stop: green underline, auto-pass always stops here.
            if (stopped) g.fill(sx + 1, y + 9, sx + sw - 1, y + 11, 0xFF4FD06A);
            final int bit = 1 << i, idx = i;
            hits.add(new Hit(sx, y, sw, 11, () -> {
                // Click cycles: auto-pass decides -> always stop -> always skip -> auto-pass decides.
                int skip = skipPhases(), stop = stopPhases();
                String msg;
                if ((stop & bit) != 0) {
                    stop &= ~bit;
                    skip |= bit;
                    msg = "Always skipping " + PHASE_LONG[idx] + " (unless something is on the stack). Click again for normal.";
                } else if ((skip & bit) != 0) {
                    skip &= ~bit;
                    msg = PHASE_LONG[idx] + ": auto-pass decides again.";
                } else {
                    stop |= bit;
                    msg = "Always stopping at " + PHASE_LONG[idx] + ", even with nothing to play. Click again to skip it.";
                }
                try {
                    dev.mtgcraft.MtgClientConfig.SKIP_PHASES.set(skip);
                    dev.mtgcraft.MtgClientConfig.STOP_PHASES.set(stop);
                } catch (IllegalStateException notLoaded) {
                    return;
                }
                ClientTables.sendAutoPass();
                Theme.click();
                info(msg);
            }));
            if (hover) phaseTip = new int[]{mx, my, i};
        }
    }

    private int[] phaseTip;

    private static int skipPhases() {
        try {
            return dev.mtgcraft.MtgClientConfig.SKIP_PHASES.get();
        } catch (IllegalStateException notLoaded) {
            return 0;
        }
    }

    private int splashTurn = -1;
    private long splashAt;

    /**
     * Makes it hard to miss that it's your move: a big "YOUR TURN" splash when your turn starts, and a pulsing gold
     * frame around the whole screen for as long as the game is waiting on you.
     */
    private void drawTurnSignal(GuiGraphics g) {
        GameView view = duel.getGameView();
        PlayerView me = duel.me();
        if (view == null || me == null || duel.isOver()) return;
        long now = System.currentTimeMillis();
        PlayerView turn = view.getPlayerTurn();
        boolean myTurn = turn != null && turn.getId() == me.getId();
        if (myTurn && splashTurn != view.getTurn()) {
            splashTurn = view.getTurn();
            splashAt = now;
        }
        boolean waiting = duel.currentRequest() != null || duel.okEnabled || duel.cancelEnabled;
        if (waiting) {
            int a = (int) (110 + 80 * Math.sin(now / 220.0));
            int c = (a << 24) | (Theme.GOLD & 0xFFFFFF), t = 3;
            g.fill(0, 0, width, t, c);
            g.fill(0, height - t, width, height, c);
            g.fill(0, t, t, height - t, c);
            g.fill(width - t, t, width, height - t, c);
        }
        long age = now - splashAt;
        if (myTurn && splashTurn == view.getTurn() && age < 1800) {
            float fade = age < 1200 ? 1f : 1f - (age - 1200) / 600f;
            int alpha = Math.max(4, (int) (255 * fade));
            int cy = height / 2 - 14;
            g.fill(0, cy - 8, width, cy + 30, ((int) (150 * fade) << 24));
            g.pose().pushPose();
            g.pose().translate(width / 2f, cy, 400);
            g.pose().scale(3f, 3f, 1);
            g.drawCenteredString(font, "YOUR TURN", 0, 0, (alpha << 24) | (Theme.GOLD & 0xFFFFFF));
            g.pose().popPose();
        }
    }

    private static int stopPhases() {
        try {
            return dev.mtgcraft.MtgClientConfig.STOP_PHASES.get();
        } catch (IllegalStateException notLoaded) {
            return 0;
        }
    }

    private static boolean highlightPlayable() {
        try {
            return dev.mtgcraft.MtgClientConfig.HIGHLIGHT_PLAYABLE.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    /** The game log, newest first, in a panel beside the sidebar (toggled with the Log button or L). */
    private void drawLog(GuiGraphics g, GameView view) {
        List<GameLogEntry> log;
        try {
            log = new ArrayList<>(view.getGameLog().getLogEntries(null));
        } catch (RuntimeException appending) {
            return;
        }
        int pw = (int) Math.min(boardW * 0.55f, 280), lineW = pw - 12;
        List<FormattedCharSequence> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        int maxLines = Math.max(4, (int) ((toolsY - 20) / 9f) - 2);
        // Step-by-step phase changes and mana are noise here; the phase bar shows those.
        log.removeIf(e -> e.type() == forge.game.GameLogEntryType.PHASE || e.type() == forge.game.GameLogEntryType.MANA);
        for (int i = 0; i < log.size() && lines.size() < maxLines; i++) {
            List<FormattedCharSequence> l = font.split(Component.literal(log.get(i).message()), lineW);
            for (int j = 0; j < l.size() && lines.size() < maxLines; j++) {
                lines.add(l.get(j));
                colors.add(i == 0 ? Theme.TEXT : 0xFFB8C0B4);
            }
        }
        int ph = 18 + Math.max(1, lines.size()) * 9 + 4;
        int px = boardW - pw - 4, py = Math.max(4, toolsY + 12 - ph);
        g.pose().pushPose();
        g.pose().translate(0, 0, 250);
        Theme.panel(g, px, py, pw, ph);
        g.drawString(font, "Game log", px + 6, py + 5, Theme.GOLD, false);
        if (lines.isEmpty()) g.drawString(font, "Nothing yet.", px + 6, py + 18, Theme.MUTED, false);
        for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), px + 6, py + 18 + i * 9, colors.get(i), false);
        g.pose().popPose();
    }

    // ------------------------------------------------------------------ zone viewer

    private void openViewer(PlayerView p, ZoneType zone) {
        if (p == null) return;
        viewerOpen = true;
        viewerFor = p;
        viewerZone = zone;
        viewerScroll = 0;
        Theme.click();
    }

    private int[] viewerPanel() {
        return new int[]{8, 8, boardW - 16, height - 16};
    }

    private List<CardView> viewerCards(PlayerView p, ZoneType zone) {
        List<CardView> list = cards(p.getCards(zone));
        if (zone == ZoneType.Graveyard || zone == ZoneType.Exile) java.util.Collections.reverse(list); // newest first
        if (zone == ZoneType.Library || zone == ZoneType.Hand) list.removeIf(c -> !duel.mayView(c));
        return list;
    }

    /**
     * Everybody's graveyard, exile, command zone, library and hand, one player and zone at a time. Click a card to
     * use it (cast your commander, flashback, pick it as a target).
     */
    private void drawViewer(GuiGraphics g, GameView view, PlayerView me, int mx, int my, long now) {
        viewerHits.clear();
        PlayerView p = null;
        for (PlayerView q : view.getPlayers()) if (viewerFor != null && q.getId() == viewerFor.getId()) p = q;
        if (p == null) {
            viewerOpen = false;
            return;
        }
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.fill(0, 0, boardW, height, 0x99000000);
        int[] r = viewerPanel();
        int px = r[0], py = r[1], pw = r[2], ph = r[3];
        Theme.panel(g, px, py, pw, ph);

        // player tabs (me first, then the others in turn order)
        List<PlayerView> players = new ArrayList<>();
        players.add(me);
        for (Column c : columns) players.add(c.player());
        int tx = px + 6;
        for (PlayerView q : players) {
            String name = q.getId() == me.getId() ? "You" : q.getName();
            int tw = Math.min(110, font.width(name) + 12);
            boolean sel = q.getId() == p.getId();
            Theme.button(g, font, name, tx, py + 6, tw, 14, mx, my, true, sel);
            PlayerView target = q;
            viewerHits.add(new Hit(tx, py + 6, tw, 14, () -> openViewer(target, viewerZone)));
            tx += tw + 4;
        }
        Theme.button(g, font, "✕", px + pw - 20, py + 6, 14, 14, mx, my, true, false);
        viewerHits.add(new Hit(px + pw - 20, py + 6, 14, 14, () -> viewerOpen = false));

        // zone tabs (short names when the board is narrow)
        ZoneType[] zones = {ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command, ZoneType.Library, ZoneType.Hand};
        String[] shortNames = {"GY", "Exile", "Cmd", "Lib", "Hand"};
        int full = 0;
        for (ZoneType z : zones) full += font.width(zoneName(z) + " " + p.getZoneSize(z)) + 13;
        boolean narrow = full > pw - 30;
        tx = px + 6;
        for (int zi = 0; zi < zones.length; zi++) {
            ZoneType z = zones[zi];
            String label = (narrow ? shortNames[zi] : zoneName(z)) + " " + p.getZoneSize(z);
            int tw = font.width(label) + 10;
            Theme.button(g, font, label, tx, py + 24, tw, 13, mx, my, true, z == viewerZone);
            PlayerView target = p;
            viewerHits.add(new Hit(tx, py + 24, tw, 13, () -> openViewer(target, z)));
            tx += tw + 3;
        }

        List<CardView> list = viewerCards(p, viewerZone);
        int top = py + 44, avail = ph - 44 - 14;
        float ch = Math.min(cardH * 1.4f, avail / 2.1f), cw = ch * 63f / 88f;
        int cols = Math.max(1, (int) ((pw - 12) / (cw + 6)));
        int rows = Math.max(1, (int) (avail / (ch + 6)));
        int totalRows = (list.size() + cols - 1) / cols;
        viewerScroll = Math.max(0, Math.min(viewerScroll, Math.max(0, totalRows - rows)));
        if (list.isEmpty()) {
            int hidden = p.getZoneSize(viewerZone);
            String msg = hidden > 0 ? hidden + " card" + (hidden == 1 ? "" : "s") + ", face down." : "Empty.";
            g.drawCenteredString(font, msg, px + pw / 2, top + avail / 2 - 4, Theme.MUTED);
        }
        for (int i = viewerScroll * cols; i < list.size() && i < (viewerScroll + rows) * cols; i++) {
            int slot = i - viewerScroll * cols;
            float cx = px + 6 + (slot % cols) * (cw + 6), cy = top + (slot / cols) * (ch + 6);
            CardView c = list.get(i);
            boolean hover = mx >= cx && mx < cx + cw && my >= cy && my < cy + ch;
            if (duel.mayView(c)) drawCardFace(g, c, cx, cy, cw, ch, 1f);
            else blitScaled(g, Theme.CARD_BACK, cx, cy, cw, ch, 126, 176, 1f);
            int border = duel.isSelectable(c) ? (((int) (150 + 105 * Math.sin(now / 160.0))) << 24) | (Theme.SELECT & 0xFFFFFF)
                    : hover ? 0xD0FFFFFF : 0;
            if (border != 0) {
                g.pose().pushPose();
                g.pose().translate(cx, cy, 0);
                outline(g, cw, ch, border);
                g.pose().popPose();
            }
            if (hover) {
                viewerHover = c;
                zoomCard = c;
            }
            viewerHits.add(new Hit(cx, cy, cw, ch, () -> {
                duel.clickCard(c, 1);
                viewerOpen = false;
                Theme.click();
            }));
        }
        String foot = totalRows > rows ? "Scroll for more · " : "";
        g.drawString(font, foot + "Click a card to use or pick it · Esc closes", px + 6, py + ph - 11, 0x80A9B5A8, false);
        g.pose().popPose();
    }

    /** "Who goes first?" in multiplayer games: one button per player. */
    private void drawStarterPicker(GuiGraphics g, GameView view, int mx, int my) {
        List<PlayerView> players = new ArrayList<>((java.util.Collection<PlayerView>) view.getPlayers());
        int bw = 120, bh = 18, pw = bw + 20, ph = 26 + players.size() * (bh + 4) + 4;
        int px = (boardW - pw) / 2, py = (height - ph) / 2;
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        Theme.panel(g, px, py, pw, ph);
        g.drawCenteredString(font, "Who goes first?", px + pw / 2, py + 8, Theme.GOLD);
        for (int i = 0; i < players.size(); i++) {
            PlayerView p = players.get(i);
            String name = duel.isLocalPlayer(p) ? "Me (" + p.getName() + ")" : p.getName();
            int by = py + 24 + i * (bh + 4);
            Theme.button(g, font, name, px + 10, by, bw, bh, mx, my, true, duel.isLocalPlayer(p));
            hits.add(new Hit(px + 10, by, bw, bh, () -> {
                duel.clickPlayer(p);
                Theme.click();
            }));
        }
        g.pose().popPose();
    }

    // ------------------------------------------------------------------ choice overlay

    private void syncRequest(ChoiceRequest req) {
        if (req != shownRequest) {
            shownRequest = req;
            picked.clear();
            distValues.clear();
            if (req != null && req.kind == ChoiceRequest.Kind.DISTRIBUTE) distValues.addAll(req.initial);
            optionScroll = 0;
            numberValue = req == null ? 0 : req.max;
        }
    }

    private static final int DIST_ROW = 24;

    private int[] choicePanel(ChoiceRequest req) {
        int pw = Math.min(boardW - 20, 440);
        int ph;
        if (req.kind == ChoiceRequest.Kind.NUMBER) ph = 110;
        else if (req.kind == ChoiceRequest.Kind.DISTRIBUTE) ph = Math.min(height - 30, 84 + Math.min(8, req.labels.size()) * DIST_ROW + 34);
        else if (req.hasCards()) ph = (int) (cardH * 1.6f) + 100;
        else ph = Math.min(height - 40, 80 + Math.min(8, req.labels.size()) * 22 + 30);
        return new int[]{(boardW - pw) / 2, (height - ph) / 2, pw, ph};
    }

    /** One plain line saying how to answer this kind of question. */
    private static String howTo(ChoiceRequest req) {
        int n = req.labels.size();
        return switch (req.kind) {
            case ORDER -> req.min == n && req.max == n ? "Click them in order: number 1 comes first."
                    : "Click the ones you want, in order (" + req.min + "–" + req.max + "). Click again to undo.";
            case DISTRIBUTE -> "Use − and + (or scroll over a row) until nothing is left.";
            case NUMBER -> "";
            case PICK -> req.max == 0 ? "" : req.min == 1 && req.max == 1 ? "Click one."
                    : req.min == req.max ? "Pick " + req.min + "."
                    : "Pick " + (req.min == 0 ? "up to " + req.max : req.min + " to " + req.max) + ".";
        };
    }

    /** Message lines (Card-Forge's question plus how to answer it), wrapped to the panel. */
    private List<FormattedCharSequence> choiceLines(ChoiceRequest req, int pw) {
        List<FormattedCharSequence> msg = new ArrayList<>(font.split(Component.literal(req.message), pw - 20));
        while (msg.size() > 3) msg.remove(msg.size() - 1);
        String how = howTo(req);
        if (!how.isEmpty()) msg.addAll(font.split(Component.literal(how), pw - 20));
        return msg;
    }

    private void drawChoice(GuiGraphics g, ChoiceRequest req, int mx, int my, long now) {
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.fill(0, 0, boardW, height, 0x88000000);
        int[] p = choicePanel(req);
        int px = p[0], py = p[1], pw = p[2], ph = p[3];
        Theme.panel(g, px, py, pw, ph);
        String title = req.title.isEmpty() ? (req.subject != null ? req.subject.getName() : "Decision") : req.title;
        g.drawCenteredString(font, Theme.ellipsize(font, title, pw - 20), px + pw / 2, py + 8, Theme.GOLD);
        List<FormattedCharSequence> msg = choiceLines(req, pw);
        int howFrom = Math.min(3, font.split(Component.literal(req.message), pw - 20).size());
        for (int i = 0; i < msg.size(); i++) {
            g.drawCenteredString(font, msg.get(i), px + pw / 2, py + 22 + i * 10, i >= howFrom ? Theme.MUTED : Theme.TEXT);
        }
        int contentY = py + 26 + msg.size() * 10;

        if (req.kind == ChoiceRequest.Kind.NUMBER) {
            String v = String.valueOf(numberValue);
            g.pose().pushPose();
            g.pose().translate(px + pw / 2f - font.width(v), contentY + 6, 0);
            g.pose().scale(2, 2, 1);
            g.drawString(font, v, 0, 0, Theme.TEXT);
            g.pose().popPose();
            Theme.button(g, font, "−", px + pw / 2 - 60, contentY + 6, 20, 18, mx, my, numberValue > req.min, false);
            Theme.button(g, font, "+", px + pw / 2 + 40, contentY + 6, 20, 18, mx, my, numberValue < req.max, false);
            g.drawCenteredString(font, req.min + " – " + req.max + " (scroll to change)", px + pw / 2, contentY + 30, Theme.MUTED);
        } else if (req.kind == ChoiceRequest.Kind.DISTRIBUTE) {
            drawDistribute(g, req, px, pw, contentY, mx, my);
        } else if (req.hasCards()) {
            float ch = cardH * 1.5f, cw = ch * 63f / 88f;
            int n = req.labels.size();
            float avail = pw - 20;
            float step = n <= 1 ? 0 : Math.min(cw + 6, (avail - cw) / (n - 1));
            float total = step * (n - 1) + cw;
            float x0 = px + (pw - total) / 2;
            int hoverIdx = -1;
            for (int i = n - 1; i >= 0; i--) {
                float cx = x0 + i * step;
                if (mx >= cx && mx < cx + (i == n - 1 ? cw : step) && my >= contentY && my < contentY + ch) {
                    hoverIdx = i;
                    break;
                }
            }
            for (int i = 0; i < n; i++) {
                float cx = x0 + i * step;
                float cy = contentY + (i == hoverIdx ? -6 : 0) + (picked.contains(i) ? -4 : 0);
                CardView c = req.cards.get(i);
                if (c != null) {
                    if (duel.mayView(c)) drawCardFace(g, c, cx, cy, cw, ch, 1f);
                    else blitScaled(g, Theme.CARD_BACK, cx, cy, cw, ch, 126, 176, 1f);
                } else {
                    Theme.rounded(g, (int) cx, (int) cy, (int) cw, (int) ch, 0xFF2A302B);
                    List<FormattedCharSequence> l = font.split(Component.literal(req.labels.get(i)), (int) cw - 4);
                    for (int j = 0; j < Math.min(6, l.size()); j++) g.drawString(font, l.get(j), (int) cx + 2, (int) cy + 3 + j * 9, Theme.TEXT);
                }
                int idx = picked.indexOf(i);
                if (idx >= 0) {
                    g.pose().pushPose();
                    g.pose().translate(cx, cy, 0);
                    outline(g, cw, ch, Theme.GREEN);
                    g.pose().popPose();
                    if (req.kind == ChoiceRequest.Kind.ORDER) {
                        Theme.disc(g, (int) (cx + cw / 2), (int) (cy + ch / 2), 8, 0xE0101010);
                        g.drawCenteredString(font, String.valueOf(idx + 1), (int) (cx + cw / 2), (int) (cy + ch / 2) - 4, Theme.GOLD);
                    }
                }
            }
            if (hoverIdx >= 0 && req.cards.get(hoverIdx) != null) {
                zoomCard = req.cards.get(hoverIdx);
                choiceHover = zoomCard;
            }
            if (hoverIdx >= 0) {
                g.drawCenteredString(font, Theme.ellipsize(font, req.labels.get(hoverIdx), pw - 20), px + pw / 2,
                        (int) (contentY + ch + 4), Theme.MUTED);
            }
        } else {
            int n = req.labels.size();
            int visible = Math.min(8, n);
            for (int i = 0; i < visible; i++) {
                int idx = i + optionScroll;
                if (idx >= n) break;
                boolean sel = picked.contains(idx);
                String label = (req.kind == ChoiceRequest.Kind.ORDER && sel ? (picked.indexOf(idx) + 1) + ". " : "") + req.labels.get(idx);
                Theme.button(g, font, label, px + 16, contentY + 4 + i * 22, pw - 32, 18, mx, my, true, sel);
            }
            if (n > visible) g.drawCenteredString(font, "scroll for more", px + pw / 2, contentY + 4 + visible * 22, Theme.MUTED);
        }

        if (needsConfirm(req)) {
            boolean ok = confirmAllowed(req);
            String label = req.max == 0 && req.kind != ChoiceRequest.Kind.DISTRIBUTE ? "Done"
                    : picked.isEmpty() && req.min == 0 && req.kind != ChoiceRequest.Kind.NUMBER && req.kind != ChoiceRequest.Kind.DISTRIBUTE ? "None"
                    : "Confirm";
            Theme.button(g, font, label, px + pw / 2 - 50, py + ph - 26, 100, 20, mx, my, ok, true);
        }
        g.drawString(font, "Hold Tab to see the board", px + 6, py + ph - 10, 0x80A9B5A8);
        g.pose().popPose();
    }

    /** Rows of "name  [−] amount [+]" for splitting damage or counters. */
    private void drawDistribute(GuiGraphics g, ChoiceRequest req, int px, int pw, int contentY, int mx, int my) {
        int n = req.labels.size();
        int visible = Math.min(8, n);
        for (int i = 0; i < visible; i++) {
            int idx = i + optionScroll;
            if (idx >= n) break;
            int ry = contentY + 4 + i * DIST_ROW;
            boolean hover = in(mx, my, px + 12, ry, pw - 24, DIST_ROW - 4);
            Theme.rounded(g, px + 12, ry, pw - 24, DIST_ROW - 4, hover ? 0x40FFFFFF : 0x24FFFFFF);
            CardView c = req.cards.get(idx);
            int textX = px + 18;
            if (c != null && duel.mayView(c)) {
                float th = DIST_ROW - 6, tw = th * 63f / 88f;
                drawCardFace(g, c, px + 15, ry + 1, tw, th, 1f);
                textX += (int) tw + 4;
                if (hover) {
                    zoomCard = c;
                    choiceHover = c;
                }
            }
            int value = idx < distValues.size() ? distValues.get(idx) : 0;
            int ctrlX = px + pw - 24 - 74;
            g.drawString(font, Theme.ellipsize(font, req.labels.get(idx), ctrlX - textX - 4), textX, ry + 2, Theme.TEXT, false);
            if (idx < req.notes.size()) {
                g.pose().pushPose();
                g.pose().translate(textX, ry + 12, 0);
                g.pose().scale(0.75f, 0.75f, 1);
                g.drawString(font, req.notes.get(idx), 0, 0, Theme.MUTED, false);
                g.pose().popPose();
            }
            int min = idx < req.minEach.size() ? req.minEach.get(idx) : 0;
            Theme.button(g, font, "−", ctrlX, ry + 2, 16, 15, mx, my, value > min, false);
            String v = String.valueOf(value);
            g.drawCenteredString(font, v, ctrlX + 37, ry + 5, value > 0 ? Theme.GOLD : Theme.MUTED);
            Theme.button(g, font, "+", ctrlX + 58, ry + 2, 16, 15, mx, my, distLeft(req) > 0, false);
        }
        if (n > visible) g.drawCenteredString(font, "scroll for more", px + pw / 2, contentY + 4 + visible * DIST_ROW, Theme.MUTED);
        String problem = req.problem(distValues);
        int footY = contentY + 6 + visible * DIST_ROW + (n > visible ? 10 : 0);
        g.drawCenteredString(font, problem == null ? "Ready." : problem, px + pw / 2, footY, problem == null ? Theme.GREEN : 0xFFFFB0A8);
    }

    private int distLeft(ChoiceRequest req) {
        int sum = 0;
        for (int v : distValues) sum += v;
        return req.total - sum;
    }

    /** Index of the distribute row under the mouse, or -1. */
    private int distRowAt(ChoiceRequest req, double mx, double my) {
        int[] p = choicePanel(req);
        int contentY = p[1] + 26 + choiceLines(req, p[2]).size() * 10;
        for (int i = 0; i < Math.min(8, req.labels.size()); i++) {
            if (in(mx, my, p[0] + 12, contentY + 4 + i * DIST_ROW, p[2] - 24, DIST_ROW - 4)) {
                int idx = i + optionScroll;
                return idx < req.labels.size() ? idx : -1;
            }
        }
        return -1;
    }

    private void nudge(ChoiceRequest req, int idx, int delta) {
        if (idx < 0 || idx >= distValues.size()) return;
        int min = idx < req.minEach.size() ? req.minEach.get(idx) : 0;
        int v = distValues.get(idx);
        int next = delta > 0 ? v + Math.min(delta, distLeft(req)) : Math.max(min, v + delta);
        if (next != v) {
            distValues.set(idx, next);
            Theme.click();
        }
    }

    /** Single picks answer on click; everything else needs a Confirm. */
    private static boolean needsConfirm(ChoiceRequest req) {
        return !(req.kind == ChoiceRequest.Kind.PICK && req.min == 1 && req.max == 1);
    }

    private boolean confirmAllowed(ChoiceRequest req) {
        if (req.kind == ChoiceRequest.Kind.NUMBER) return true;
        if (req.kind == ChoiceRequest.Kind.DISTRIBUTE) return req.problem(distValues) == null;
        return picked.size() >= req.min && picked.size() <= req.max;
    }

    private void answer(ChoiceRequest req) {
        if (req.kind == ChoiceRequest.Kind.NUMBER) {
            req.answer.complete(List.of(numberValue));
        } else if (req.kind == ChoiceRequest.Kind.DISTRIBUTE) {
            req.answer.complete(new ArrayList<>(distValues));
        } else {
            req.answer.complete(new ArrayList<>(picked));
        }
        Theme.click();
    }

    private boolean clickChoice(ChoiceRequest req, double mx, double my) {
        int[] p = choicePanel(req);
        int px = p[0], py = p[1], pw = p[2], ph = p[3];
        int contentY = py + 26 + choiceLines(req, pw).size() * 10;

        if (needsConfirm(req) && in(mx, my, px + pw / 2 - 50, py + ph - 26, 100, 20)) {
            if (confirmAllowed(req)) answer(req);
            return true;
        }
        if (req.kind == ChoiceRequest.Kind.NUMBER) {
            if (in(mx, my, px + pw / 2 - 60, contentY + 6, 20, 18)) numberValue = Math.max(req.min, numberValue - 1);
            if (in(mx, my, px + pw / 2 + 40, contentY + 6, 20, 18)) numberValue = Math.min(req.max, numberValue + 1);
            return true;
        }
        if (req.kind == ChoiceRequest.Kind.DISTRIBUTE) {
            int idx = distRowAt(req, mx, my);
            if (idx < 0) return true;
            int ry = contentY + 4 + (idx - optionScroll) * DIST_ROW;
            int ctrlX = px + pw - 24 - 74;
            if (in(mx, my, ctrlX, ry + 2, 16, 15)) nudge(req, idx, -1);
            else if (in(mx, my, ctrlX + 58, ry + 2, 16, 15)) nudge(req, idx, 1);
            return true;
        }
        int hit = -1;
        if (req.hasCards()) {
            float ch = cardH * 1.5f, cw = ch * 63f / 88f;
            int n = req.labels.size();
            float step = n <= 1 ? 0 : Math.min(cw + 6, (pw - 20 - cw) / (n - 1));
            float total = step * (n - 1) + cw;
            float x0 = px + (pw - total) / 2;
            for (int i = n - 1; i >= 0; i--) {
                float cx = x0 + i * step;
                if (mx >= cx && mx < cx + (i == n - 1 ? cw : step) && my >= contentY - 6 && my < contentY + ch) {
                    hit = i;
                    break;
                }
            }
        } else {
            for (int i = 0; i < Math.min(8, req.labels.size()); i++) {
                if (in(mx, my, px + 16, contentY + 4 + i * 22, pw - 32, 18)) hit = i + optionScroll;
            }
        }
        if (hit < 0 || hit >= req.labels.size() || req.max == 0) return true;
        if (!needsConfirm(req)) {
            picked.clear();
            picked.add(hit);
            answer(req);
            return true;
        }
        if (picked.contains(hit)) {
            picked.remove(Integer.valueOf(hit));
        } else if (picked.size() < req.max) {
            picked.add(hit);
        } else if (req.max == 1) {
            picked.set(0, hit);
        }
        Theme.click();
        return true;
    }

    // ------------------------------------------------------------------ game over

    private boolean gauntlet() {
        return ClientTables.isGauntlet(table);
    }

    private int[] gameOverPanel() {
        return gauntlet() ? new int[]{boardW / 2 - 100, height / 2 - 40, 200, 80} : new int[]{boardW / 2 - 90, height / 2 - 45, 180, 90};
    }

    /** "Victory!", "Defeat" or "Draw", from my side of the table (a teammate's win is mine too). */
    private static String outcome(GameView view, PlayerView me) {
        String winner = view.getWinningPlayerName();
        if (winner != null && winner.equals(me.getName()) && !me.getHasLost()) return "Victory!";
        if (me.getHasLost()) return "Defeat";
        if (winner == null || winner.isEmpty()) return "Draw";
        for (PlayerView o : me.getOpponents()) if (o.getName().equals(winner)) return "Defeat";
        return "Victory!";
    }

    private void drawGameOver(GuiGraphics g, GameView view, PlayerView me, int mx, int my) {
        g.pose().pushPose();
        g.pose().translate(0, 0, 350);
        g.fill(0, 0, boardW, height, 0x99000000);
        int[] p = gameOverPanel();
        Theme.panel(g, p[0], p[1], p[2], p[3]);
        String title = outcome(view, me);
        boolean won = title.equals("Victory!");
        g.pose().pushPose();
        g.pose().translate(p[0] + p[2] / 2f, p[1] + 10, 0);
        g.pose().scale(2, 2, 1);
        g.drawCenteredString(font, title, 0, 0, won ? Theme.GOLD : Theme.TEXT);
        g.pose().popPose();
        if (gauntlet()) {
            // A gauntlet fight has no table to go back to: take the reward (or the loss) and walk away.
            String sub = won ? "Your opponents fall. Their loot is yours." : title.equals("Draw") ? "Nobody wins this time." : "Better luck next time.";
            g.drawCenteredString(font, Theme.ellipsize(font, sub, p[2] - 12), p[0] + p[2] / 2, p[1] + 32, Theme.MUTED);
            Theme.button(g, font, won ? "Collect reward" : "Continue", p[0] + 30, p[1] + 50, p[2] - 60, 20, mx, my, true, true);
        } else {
            Theme.button(g, font, "Back to the table", p[0] + 20, p[1] + 38, p[2] - 40, 18, mx, my, true, true);
            Theme.button(g, font, "Leave table", p[0] + 20, p[1] + 62, p[2] - 40, 18, mx, my, true, false);
        }
        g.pose().popPose();
    }

    /** Rewards that arrived while the result was still on screen; shown when the player moves on. */
    private dev.mtgcraft.net.Packets.Rewards pendingRewards;

    public void queueRewards(dev.mtgcraft.net.Packets.Rewards rewards) {
        pendingRewards = rewards;
    }

    private boolean clickGameOver(double mx, double my) {
        int[] p = gameOverPanel();
        if (gauntlet()) {
            if (in(mx, my, p[0] + 30, p[1] + 50, p[2] - 60, 20)) {
                duel.leave();
                ClientTables.forget(table);
                minecraft.setScreen(pendingRewards != null ? new RewardsScreen(pendingRewards) : null);
                pendingRewards = null;
            }
            return true;
        }
        if (in(mx, my, p[0] + 20, p[1] + 38, p[2] - 40, 18)) {
            duel.leave();
            backToTable();
        } else if (in(mx, my, p[0] + 20, p[1] + 62, p[2] - 40, 18)) {
            duel.leave();
            ClientTables.forget(table);
            minecraft.setScreen(null);
        }
        return true;
    }

    // ------------------------------------------------------------------ input

    private Placed topCardAt(double mx, double my) {
        Placed best = null;
        int bestRank = -1;
        for (int i = 0; i < placed.size(); i++) {
            Placed p = placed.get(i);
            Anim a = anims.get(p.card.getId());
            boolean rest = p.zone == Zone.HAND || p.zone == Zone.COMMAND;
            if (a == null || !(rest ? p.containsAtRest(mx, my) : p.contains(mx, my, a))) continue;
            int rank = switch (p.zone) {
                case TRAY -> 3000 + i;
                case HAND, COMMAND -> 2000 + i;
                case STACK -> 1000 + i;
                default -> i;
            };
            if (rank > bestRank) {
                best = p;
                bestRank = rank;
            }
        }
        return best;
    }

    private PlayerView pillAt(double mx, double my) {
        PlayerView me = duel.me();
        GameView view = duel.getGameView();
        if (me == null || view == null) return null;
        int x = boardW + 6, w = sideW - 12;
        if (multiplayer()) {
            for (Column c : columns) {
                if (in(mx, my, c.x0(), c.pillY(), c.x1() - c.x0(), 14)) return c.player();
            }
        } else if (in(mx, my, x, oppPillY, w, 34)) {
            return opponent(view, me);
        }
        if (in(mx, my, x, myPillY, w, 34)) return me;
        return null;
    }

    /** The opponent whose column a point is in (multiplayer), or the only opponent. */
    private PlayerView opponentAt(double mx) {
        if (!multiplayer()) return columns.isEmpty() ? null : columns.get(0).player();
        for (Column c : columns) if (mx >= c.x0() && mx < c.x1()) return c.player();
        return null;
    }

    /** Back to this table's lobby (for a rematch or a new setup). */
    private void backToTable() {
        ClientTables.forget(table);
        ClientTables.open(table);
    }

    private static boolean in(double mx, double my, double x, double y, double w, double h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (duel.failed != null) {
            backToTable();
            return true;
        }
        ChoiceRequest req = duel.currentRequest();
        if (req != null && !peeking) {
            if (in(mx, my, 0, 0, boardW, height)) return clickChoice(req, mx, my);
        }
        if (duel.isOver() && req == null && in(mx, my, 0, 0, boardW, height)) {
            return clickGameOver(mx, my);
        }
        if (viewerOpen && in(mx, my, 0, 0, boardW, height)) {
            for (int i = viewerHits.size() - 1; i >= 0; i--) {
                if (viewerHits.get(i).contains(mx, my)) {
                    viewerHits.get(i).action().run();
                    return true;
                }
            }
            int[] r = viewerPanel();
            if (!in(mx, my, r[0], r[1], r[2], r[3])) viewerOpen = false;
            return true;
        }
        if (button == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                if (hits.get(i).contains(mx, my)) {
                    hits.get(i).action().run();
                    return true;
                }
            }
        }
        int x = boardW + 6, w = sideW - 12, bw = (w - 4) / 2;
        if (in(mx, my, x, buttonsY, bw, 20)) {
            if (duel.okEnabled) { duel.ok(); Theme.click(); }
            return true;
        }
        if (in(mx, my, x + bw + 4, buttonsY, bw, 20)) {
            if (duel.cancelEnabled) { duel.cancel(); Theme.click(); }
            return true;
        }
        int fw = font.width(confirmConcede ? "Concede?" : "⚑") + 6;
        if (in(mx, my, x + 3, myPillY + 23, fw, 11)) {
            if (confirmConcede) duel.concedeGame();
            confirmConcede = !confirmConcede;
            Theme.click();
            return true;
        }
        confirmConcede = false;
        PlayerView pill = pillAt(mx, my);
        if (pill != null) {
            if (button == 1) {
                openViewer(pill, ZoneType.Graveyard);
                return true;
            }
            if (pill == duel.me() && clickMana(mx, my)) return true;
            duel.clickPlayer(pill);
            return true;
        }
        Placed p = topCardAt(mx, my);
        if (p != null) {
            if (button == 1) {
                if (queuedAttack.remove(p.card.getId())) return true;
                pendingBlocks.remove(p.card.getId());
                duel.clickCard(p.card, 3);
                return true;
            }
            pressed = p;
            pressX = mx;
            pressY = my;
            Anim a = anims.get(p.card.getId());
            dragDX = a == null ? p.w / 2 : (float) (mx - a.x);
            dragDY = a == null ? p.h / 2 : (float) (my - a.y);
            dragTilt = 0;
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private boolean clickMana(double mx, double my) {
        PlayerView me = duel.me();
        int ox = boardW + 6 + 26, y = myPillY;
        for (int i = 0; i < 5; i++) {
            if (mana(me, MagicColor.WUBRG[i]) <= 0) continue;
            if (in(mx, my, ox, y + 22, 20, 12)) {
                duel.useMana(MagicColor.WUBRG[i]);
                return true;
            }
            ox += 22;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (pressed != null && !dragging && Math.hypot(mx - pressX, my - pressY) > 4 && canDrag(pressed)) {
            dragging = true;
            Theme.play(SoundEvents.BOOK_PAGE_TURN, 1.6f, 0.4f);
        }
        return dragging || super.mouseDragged(mx, my, button, dx, dy);
    }

    private boolean canDrag(Placed p) {
        return p.mine && (p.zone == Zone.HAND || p.zone == Zone.BATTLEFIELD || p.zone == Zone.COMMAND);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        Placed src = pressed;
        boolean wasDragging = dragging;
        pressed = null;
        dragging = false;
        if (src == null) return super.mouseReleased(mx, my, button);
        if (!wasDragging) {
            trackClickBlock(src);
            duel.clickCard(src.card, 1);
            Theme.click();
            return true;
        }
        drop(src, mx, my);
        return true;
    }

    /** The attacker last clicked while declaring blockers (click attacker, then your creature, to block). */
    private int clickedAttacker = -1;

    /** Mirrors Card-Forge's click-to-block so the planned block shows right away. */
    private void trackClickBlock(Placed p) {
        GameView view = duel.getGameView();
        PlayerView me = duel.me();
        if (view == null || me == null || view.getPhase() != PhaseType.COMBAT_DECLARE_BLOCKERS) return;
        CombatView combat = view.getCombat();
        if (combat != null && combat.isAttacking(p.card)) {
            clickedAttacker = p.card.getId();
        } else if (p.mine && p.zone == Zone.BATTLEFIELD && clickedAttacker >= 0) {
            if (Integer.valueOf(clickedAttacker).equals(pendingBlocks.get(p.card.getId()))) {
                pendingBlocks.remove(p.card.getId());
                return;
            }
            CardView attacker = null;
            for (CardView a : combat == null ? List.<CardView>of() : cards(combat.getAttackers())) if (a.getId() == clickedAttacker) attacker = a;
            String why = attacker == null ? null : blockProblem(attacker, p.card);
            if (why != null) {
                toast(why);
                return;
            }
            pendingBlocks.put(p.card.getId(), clickedAttacker);
            watchBlock(p.card.getId(), clickedAttacker);
        }
    }

    /** Why this creature can't block that attacker (the common rules), or null if it looks fine. */
    private static String blockProblem(CardView attacker, CardView blocker) {
        CardStateView a = attacker.getCurrentState(), b = blocker.getCurrentState();
        if (a == null || b == null) return null;
        if (blocker.isTapped()) return blocker.getName() + " is tapped: tapped creatures can't block.";
        try {
            if (a.hasKeyword(forge.game.keyword.Keyword.FLYING) && !b.hasKeyword(forge.game.keyword.Keyword.FLYING)
                    && !b.hasKeyword(forge.game.keyword.Keyword.REACH)) {
                return attacker.getName() + " has flying: only creatures with flying or reach can block it.";
            }
            if (a.hasKeyword(forge.game.keyword.Keyword.SHADOW) && !b.hasKeyword(forge.game.keyword.Keyword.SHADOW)) {
                return attacker.getName() + " has shadow: only creatures with shadow can block it.";
            }
            if (a.hasKeyword(forge.game.keyword.Keyword.HORSEMANSHIP) && !b.hasKeyword(forge.game.keyword.Keyword.HORSEMANSHIP)) {
                return attacker.getName() + " has horsemanship: only creatures with horsemanship can block it.";
            }
        } catch (RuntimeException unsynced) {
            // keywords not synced yet: let the game decide
        }
        return null;
    }

    /** Block attempts to double-check: if the game rejects one (it flashes), it's taken off the plan. */
    private final Map<Integer, long[]> blockChecks = new HashMap<>();

    private void watchBlock(int blocker, int attacker) {
        blockChecks.put(blocker, new long[]{attacker, System.currentTimeMillis()});
    }

    private void checkBlocks(GameView view) {
        if (blockChecks.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<Integer, long[]>> it = blockChecks.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, long[]> e = it.next();
            long sentAt = e.getValue()[1];
            if (duel.flashTime >= sentAt) {
                // The game said no: drop it from the plan and say so.
                pendingBlocks.remove(e.getKey());
                Anim b = anims.get(e.getKey()), a = anims.get((int) e.getValue()[0]);
                toast((b != null && b.card != null ? b.card.getName() : "That creature") + " can't block "
                        + (a != null && a.card != null ? a.card.getName() : "that attacker") + ".");
                it.remove();
            } else if (now - sentAt > 1500) {
                it.remove();
            }
        }
        // Menace attackers need two or more blockers; warn while the plan has just one.
        if (view.getPhase() == PhaseType.COMBAT_DECLARE_BLOCKERS) {
            Map<Integer, Integer> count = new HashMap<>();
            for (int att : pendingBlocks.values()) count.merge(att, 1, Integer::sum);
            for (Map.Entry<Integer, Integer> e : count.entrySet()) {
                Anim a = anims.get(e.getKey());
                if (e.getValue() == 1 && a != null && a.card != null && a.card.getCurrentState() != null) {
                    try {
                        if (a.card.getCurrentState().hasKeyword(forge.game.keyword.Keyword.MENACE) && !toastText.contains("menace")) {
                            toast(a.card.getName() + " has menace: block it with two or more creatures, or that block won't count.");
                        }
                    } catch (RuntimeException unsynced) {
                        // try again next frame
                    }
                }
            }
        }
    }

    private String toastText = "";
    private long toastUntil;

    /** A short message in the middle of the board, for things the game won't allow. */
    private void toast(String text) {
        toastText = text;
        toastUntil = System.currentTimeMillis() + 3500;
        Theme.play(SoundEvents.VILLAGER_NO, 1.2f, 0.5f);
    }

    /** Like toast, for news rather than a refusal (no "no" sound). */
    private void info(String text) {
        toastText = text;
        toastUntil = System.currentTimeMillis() + 3000;
    }

    private void drawToast(GuiGraphics g) {
        long left = toastUntil - System.currentTimeMillis();
        if (left <= 0 || toastText.isEmpty()) return;
        List<FormattedCharSequence> lines = font.split(Component.literal(toastText), Math.min(300, boardW - 40));
        int w = 0;
        for (FormattedCharSequence l : lines) w = Math.max(w, font.width(l));
        int h = lines.size() * 10 + 8, x = (boardW - w) / 2 - 6, y = (int) midY - 32 - h;
        int alpha = (int) Math.min(255, left / 3);
        g.pose().pushPose();
        g.pose().translate(0, 0, 280);
        Theme.rounded(g, x - 1, y - 1, w + 14, h + 2, (alpha << 24) | 0xE5534B);
        Theme.rounded(g, x, y, w + 12, h, (Math.min(alpha, 0xF0) << 24) | 0x201010);
        for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x + 6, y + 5 + i * 10, (alpha << 24) | 0xFFD8D0, false);
        g.pose().popPose();
    }

    /** What letting go of a dragged card means depends on where it lands and which step of the turn it is. */
    private void drop(Placed src, double mx, double my) {
        GameView view = duel.getGameView();
        PlayerView me = duel.me();
        if (view == null || me == null) return;
        Placed onCard = null;
        for (Placed p : placed) {
            if (p.card.getId() == src.card.getId()) continue;
            Anim a = anims.get(p.card.getId());
            if (a != null && p.contains(mx, my, a) && (onCard == null || p.zone == Zone.HAND || p.zone == Zone.STACK)) onCard = p;
        }
        PlayerView onPlayer = pillAt(mx, my);
        boolean oppSide = my < midY && mx < boardW;
        boolean handArea = my >= handTop - 4 && mx < boardW;
        boolean myTurn = myTurn(view);
        PhaseType phase = view.getPhase();

        if (src.zone == Zone.HAND || src.zone == Zone.COMMAND) {
            if (src.zone == Zone.HAND && handArea && onPlayer == null && (onCard == null || onCard.zone == Zone.HAND)) {
                reorder(src, me, mx);
                return;
            }
            if (src.zone == Zone.COMMAND && handArea && onPlayer == null && onCard == null) return;
            duel.clickCard(src.card, 1);
            aimAt(onCard != null && onCard.zone != Zone.HAND ? onCard.card : onPlayer);
            Theme.play(SoundEvents.BOOK_PUT, 1.2f, 0.7f);
            return;
        }

        // A creature or other permanent of mine on the battlefield.
        CombatView combat = view.getCombat();
        boolean towardOpponent = oppSide || (onPlayer != null && onPlayer.getId() != me.getId());
        if (phase == PhaseType.COMBAT_DECLARE_ATTACKERS && myTurn) {
            boolean attacking = combat != null && combat.isAttacking(src.card);
            if (towardOpponent && multiplayer()) {
                // Pick who to attack first (Card-Forge's attack input switches its current defender).
                PlayerView defender = onPlayer != null && onPlayer.getId() != me.getId() ? onPlayer : opponentAt(mx);
                if (defender != null) duel.clickPlayer(defender);
                if (!attacking) duel.clickCard(src.card, 1);
            } else if (towardOpponent != attacking) {
                duel.clickCard(src.card, 1);
            }
            return;
        }
        if (phase == PhaseType.COMBAT_DECLARE_BLOCKERS && !myTurn) {
            Placed attacker = combat == null ? null : attackerAt(combat, mx, my);
            if (attacker != null) {
                String why = blockProblem(attacker.card, src.card);
                if (why != null) {
                    toast(why);
                    return;
                }
                duel.block(attacker.card, src.card);
                pendingBlocks.put(src.card.getId(), attacker.card.getId());
                watchBlock(src.card.getId(), attacker.card.getId());
                Theme.play(SoundEvents.SHIELD_BLOCK, 1.2f, 0.5f);
            } else if (pendingBlocks.remove(src.card.getId()) != null || (combat != null && combat.isBlocking(src.card))) {
                duel.clickCard(src.card, 3);
            }
            return;
        }
        // Dragging a creature at an opponent before combat: go to combat and attack with it. This never uses the
        // creature's own abilities (click the creature for those).
        boolean beforeCombat = phase == PhaseType.UPKEEP || phase == PhaseType.DRAW || phase == PhaseType.MAIN1
                || phase == PhaseType.COMBAT_BEGIN;
        CardStateView s = src.card.getCurrentState();
        if (myTurn && beforeCombat && towardOpponent && s != null && s.isCreature() && onCard == null) {
            if (src.card.isTapped() || src.card.isSick()) {
                duel.flashTime = System.currentTimeMillis();
                return;
            }
            if (!queuedAttack.add(src.card.getId())) queuedAttack.remove(src.card.getId());
            queuedTurn = view.getTurn();
            if (multiplayer()) {
                PlayerView defender = onPlayer != null && onPlayer.getId() != me.getId() ? onPlayer : opponentAt(mx);
                if (defender != null) queuedDefender = defender;
            }
            if (!queuedAttack.isEmpty() && view.getStack().isEmpty() && duel.okEnabled
                    && duel.prompt != null && duel.prompt.startsWith("Priority")) {
                duel.ok();
            }
            Theme.play(SoundEvents.ARMOR_EQUIP_IRON, 1.2f, 0.6f);
            return;
        }
        if (onCard != null || onPlayer != null) {
            // Activate an ability and aim it where it was dropped.
            duel.clickCard(src.card, 1);
            aimAt(onCard != null ? onCard.card : onPlayer);
        }
    }

    private void reorder(Placed src, PlayerView me, double mx) {
        List<Placed> hand = new ArrayList<>();
        for (Placed p : placed) if (p.zone == Zone.HAND) hand.add(p);
        int index = 0;
        for (Placed p : hand) {
            if (p.card.getId() != src.card.getId() && mx > p.x + p.w / 2) index++;
        }
        duel.reorderHand(src.card, index);
    }

    /** Remembers a drop target; it's clicked as soon as the engine asks for targets and it becomes valid. */
    private void aimAt(Object target) {
        pendingTarget = target;
        pendingUntil = System.currentTimeMillis() + 4000;
    }

    private void resolvePendingTarget() {
        if (pendingTarget == null) return;
        if (System.currentTimeMillis() > pendingUntil) {
            pendingTarget = null;
            return;
        }
        if (pendingTarget instanceof CardView c && duel.isSelectable(c)) {
            duel.clickCard(c, 1);
            pendingTarget = null;
        } else if (pendingTarget instanceof PlayerView p && duel.isHighlighted(p)) {
            duel.clickPlayer(p);
            pendingTarget = null;
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        ChoiceRequest req = duel.currentRequest();
        if (req != null && !peeking) {
            if (req.kind == ChoiceRequest.Kind.NUMBER) {
                numberValue = Math.max(req.min, Math.min(req.max, numberValue + (delta > 0 ? 1 : -1)));
            } else if (req.kind == ChoiceRequest.Kind.DISTRIBUTE && distRowAt(req, mx, my) >= 0) {
                nudge(req, distRowAt(req, mx, my), delta > 0 ? 1 : -1);
            } else {
                optionScroll = Math.max(0, Math.min(Math.max(0, req.labels.size() - 8), optionScroll - (int) Math.signum(delta)));
            }
            return true;
        }
        if (viewerOpen) {
            viewerScroll = Math.max(0, viewerScroll - (int) Math.signum(delta));
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_R && zoomCard != null) {
            // Turn the zoomed card a quarter turn, to read anything printed sideways or upside down.
            if (zoomTurnCard != zoomCard) {
                zoomTurnCard = zoomCard;
                zoomTurns = 0;
            }
            zoomTurns = (zoomTurns + 1) % 4;
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && viewerOpen) {
            viewerOpen = false;
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && showLog) {
            showLog = false;
            return true;
        }
        if (key == GLFW.GLFW_KEY_V && ArenaRenderer.has(table)) {
            toggleView();
            return true;
        }
        if (key == GLFW.GLFW_KEY_L) {
            showLog = !showLog;
            Theme.click();
            return true;
        }
        if (key == GLFW.GLFW_KEY_Z) {
            if (viewerOpen) viewerOpen = false;
            else openViewer(duel.me(), ZoneType.Graveyard);
            return true;
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            peeking = true;
            return true;
        }
        if (key == GLFW.GLFW_KEY_SPACE) {
            ChoiceRequest req = duel.currentRequest();
            if (req != null) {
                if (needsConfirm(req) && confirmAllowed(req)) answer(req);
            } else if (duel.okEnabled) {
                duel.ok();
                Theme.click();
            }
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_TAB) {
            peeking = false;
            return true;
        }
        return super.keyReleased(key, scan, mods);
    }

    /** Esc walks away from the table; the game waits, and right-clicking the table brings you back. */
    @Override
    public void onClose() {
        if (pendingRewards != null) {
            showRewards(pendingRewards);
            pendingRewards = null;
            return;
        }
        super.onClose();
    }

    private void showRewards(dev.mtgcraft.net.Packets.Rewards r) {
        minecraft.setScreen(new RewardsScreen(r));
    }
}

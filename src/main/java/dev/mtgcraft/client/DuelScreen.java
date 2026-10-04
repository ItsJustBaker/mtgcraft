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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Magic table. Everything is laid out fresh each frame from Card-Forge's game view; each card then glides
 * toward its spot, so draws, casts, attacks and deaths all animate without any per-action code.
 *
 * <p>Controls: drag a card from your hand onto the table to play it (or onto a creature/player to aim it); drag a
 * creature toward the opponent to attack; drag your creature onto an attacker to block; click anything to
 * select it; right-click to take a creature out of combat; Space for the main button; hold Tab to peek under
 * a question.
 */
public class DuelScreen extends Screen {
    private enum Zone { HAND, BATTLEFIELD, STACK, PILE, CHOICE }

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

    private final DuelGui duel;
    private final net.minecraft.core.BlockPos table;
    /** Opponents in turn order after me, each with their column; refreshed every frame. */
    private List<Column> columns = new ArrayList<>();
    private float oppCardW, oppCardH;
    /** Animated relative widths of the opponent columns, by player id. */
    private final Map<Integer, Float> columnWeights = new HashMap<>();
    /** Show the 3D battlefield in the world behind a see-through board (remembered between duels). */
    private static boolean arenaView = true;
    private final Map<Integer, Anim> anims = new HashMap<>();
    private List<Placed> placed = new ArrayList<>();
    private long lastFrame = System.currentTimeMillis();

    // layout
    private int sideW, boardW, rowX0, rowX1;
    private float cardW, cardH, handW, handH, midY, handTop;
    private float oppLandsY, oppCreY, myCreY, myLandsY;
    private int oppPillY, myPillY, zoomX, zoomY, zoomW, zoomH, promptY, buttonsY;

    // interaction
    private Placed pressed;
    private double pressX, pressY;
    private boolean dragging;
    private float dragDX, dragDY, dragTilt;
    private Placed hovered;
    private CardView zoomCard;
    private Object pendingTarget;
    private long pendingUntil;
    private boolean confirmConcede;
    private int lastHandSize = -1;

    // big hover preview
    private static final long PREVIEW_DELAY_MS = 220;
    private CardView choiceHover;
    private int previewId = -1;
    private long previewSince;

    // choice overlay
    private ChoiceRequest shownRequest;
    private final List<Integer> picked = new ArrayList<>();
    private int numberValue;
    private int optionScroll;
    private boolean peeking;

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
        int maxZoomH = height - zoomTop - 40 - 30 - 46;
        zoomW = sideW - 12;
        zoomH = (int) (zoomW * 88f / 63f);
        if (zoomH > maxZoomH) {
            zoomH = Math.max(40, maxZoomH);
            zoomW = (int) (zoomH * 63f / 88f);
        }
        zoomX = boardW + (sideW - zoomW) / 2;
        zoomY = zoomTop;
        promptY = zoomY + zoomH + 4;
        buttonsY = myPillY - 28;
    }

    // ------------------------------------------------------------------ frame

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        try {
            renderFrame(g, mouseX, mouseY);
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

    private void renderFrame(GuiGraphics g, int mouseX, int mouseY) {
        long now = System.currentTimeMillis();
        float dt = Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;

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

        hovered = (overlay || dragging) ? null : topCardAt(mouseX, mouseY);
        if (!overlay && !dragging && hovered != null) zoomCard = hovered.card;
        if (dragging && pressed != null) zoomCard = pressed.card;

        animate(dt, mouseX, mouseY);
        resolvePendingTarget();
        handSound(me);

        drawMiddleLine(g);
        if (!arena) drawPiles(g, me, opp);
        drawCombatLines(g, view);
        drawCards(g, mouseX, mouseY, now);
        drawStackLabel(g, view);
        drawSidebar(g, view, me, opp, mouseX, mouseY, now);
        choiceHover = null;
        if (overlay) {
            drawChoice(g, req, mouseX, mouseY, now);
        } else if (req != null) {
            g.drawCenteredString(font, "Release Tab to answer: " + Theme.ellipsize(font, req.message, boardW - 60),
                    boardW / 2, 4, Theme.GOLD);
        }
        if (duel.isOver() && req == null) {
            drawGameOver(g, view, me, mouseX, mouseY);
        }
        if (!overlay) drawViewToggle(g, mouseX, mouseY);
        drawBigPreview(g, overlay ? choiceHover : hovered == null ? null : hovered.card, mouseX, now);
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
        layoutStack(out, view);
        layoutHand(out, me);
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

    private void layoutStack(List<Placed> out, GameView view) {
        List<StackItemView> stack = new ArrayList<>((java.util.Collection<StackItemView>) view.getStack());
        int n = stack.size();
        if (n == 0) return;
        float w = cardW * 1.05f, h = cardH * 1.05f;
        float step = Math.min(w * 0.55f, (rowX1 - rowX0 - w) / Math.max(1, n));
        float cx = (rowX0 + rowX1) / 2f;
        float x = cx + (n - 1) * step / 2f - w / 2;
        // The top of the stack is index 0; draw it last (in front, rightmost).
        for (int i = n - 1; i >= 0; i--) {
            CardView src = stack.get(i).getSourceCard();
            if (src == null) continue;
            out.add(new Placed(src, Zone.STACK, false, false, x - i * step, midY - h / 2, w, h, 0));
        }
    }

    private void layoutHand(List<Placed> out, PlayerView me) {
        List<CardView> hand = me.getHand() == null ? List.of() : new ArrayList<>((java.util.Collection<CardView>) me.getHand());
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
            if (p == hovered && p.zone == Zone.HAND) {
                ty = height - p.h - 6;
                trot = 0;
                tscale = 1.12f;
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

    private void drawPile(GuiGraphics g, PlayerView p, float x, float libY, float gyY) {
        int lib = p.getZoneSize(ZoneType.Library);
        if (lib > 0) {
            for (int i = Math.min(3, lib) - 1; i >= 0; i--) {
                blitScaled(g, Theme.CARD_BACK, x + i * 1.2f, libY - i * 1.2f, cardW, cardH, 126, 176, 1f);
            }
        }
        label(g, String.valueOf(lib), x + cardW / 2, libY + cardH - 9);
        List<CardView> gy = p.getGraveyard() == null ? List.of() : new ArrayList<>((java.util.Collection<CardView>) p.getGraveyard());
        if (!gy.isEmpty()) {
            CardView top = gy.get(gy.size() - 1);
            drawCardFace(g, top, x, gyY, cardW, cardH, 0.85f);
            label(g, String.valueOf(gy.size()), x + cardW / 2, gyY + cardH - 9);
        } else {
            Theme.rounded(g, (int) x, (int) gyY, (int) cardW, (int) cardH, 0x30000000);
        }
    }

    private void label(GuiGraphics g, String s, float cx, float y) {
        int w = font.width(s) + 6;
        Theme.rounded(g, (int) (cx - w / 2f), (int) y - 1, w, 10, 0xC0000000);
        g.drawCenteredString(font, s, (int) cx, (int) y, Theme.TEXT);
    }

    private void drawCombatLines(GuiGraphics g, GameView view) {
        CombatView combat = view.getCombat();
        if (combat == null) return;
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

    private void drawCards(GuiGraphics g, int mx, int my, long now) {
        Placed draggedP = dragging ? pressed : null;
        // Order: battlefield, stack, hand, then whatever is hovered or dragged on top.
        for (Zone z : new Zone[]{Zone.BATTLEFIELD, Zone.STACK, Zone.HAND}) {
            for (Placed p : placed) {
                if (p.zone != z || p == hovered || (draggedP != null && p.card.getId() == draggedP.card.getId())) continue;
                drawPlaced(g, p, now);
            }
        }
        for (Map.Entry<Integer, Anim> e : anims.entrySet()) {
            Anim a = e.getValue();
            if (a.goneSince != 0 && a.card != null) {
                drawCardAt(g, a.card, a, a.hidden, false, now, false);
            }
        }
        if (hovered != null) drawPlaced(g, hovered, now);
        if (draggedP != null) {
            Anim a = anims.get(draggedP.card.getId());
            if (a != null) drawCardAt(g, draggedP.card, a, false, false, now, true);
        }
    }

    private void drawPlaced(GuiGraphics g, Placed p, long now) {
        Anim a = anims.get(p.card.getId());
        if (a == null) return;
        if (arenaActive() && p.zone == Zone.BATTLEFIELD && p != hovered) {
            // In arena view the battlefield lives in the world; keep faint 2D copies for clicking and dragging.
            float keep = a.alpha;
            a.alpha = keep * 0.3f;
            drawCardAt(g, p.card, a, p.hidden, true, now, false);
            a.alpha = keep;
            return;
        }
        drawCardAt(g, p.card, a, p.hidden, p.zone == Zone.BATTLEFIELD, now, false);
    }

    private boolean arenaActive() {
        return arenaView && ArenaRenderer.has(table);
    }

    private void drawViewToggle(GuiGraphics g, int mx, int my) {
        if (!ArenaRenderer.has(table)) return;
        Theme.button(g, font, arenaView ? "View: Arena (V)" : "View: Screen (V)", boardW - 92, 2, 88, 13, mx, my, true, arenaView);
    }

    private void toggleView() {
        arenaView = !arenaView;
        Theme.click();
    }

    private void drawCardAt(GuiGraphics g, CardView c, Anim a, boolean hidden, boolean onBattlefield, long now, boolean lifted) {
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
            if (onBattlefield) drawBadges(g, c, a.w, a.h);
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
        if (c.isAttacking()) return Theme.RED;
        if (duel.isHighlighted(c)) return 0xFF58A6FF;
        if (hovered != null && hovered.card.getId() == c.getId()) return 0xD0FFFFFF;
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

    private void drawCardFace(GuiGraphics g, CardView c, float x, float y, float w, float h, float alpha, boolean forceLarge) {
        CardStateView s = c.getCurrentState();
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

    /** Power/toughness, damage, counters and loyalty on battlefield cards. */
    private void drawBadges(GuiGraphics g, CardView c, float w, float h) {
        CardStateView s = c.getCurrentState();
        float ts = Math.max(0.5f, Math.min(1f, w / 60f));
        if (s.isCreature()) {
            int dmg = c.getDamage();
            String pt = s.getPower() + "/" + (s.getToughness() - dmg);
            badge(g, pt, w - 1, h - 1, ts, dmg > 0 ? 0xFFFF7A6E : Theme.TEXT, true);
        }
        if (s.isPlaneswalker() && s.getLoyalty() != null) {
            badge(g, s.getLoyalty(), w - 1, h - 1, ts, 0xFFFFE08A, true);
        }
        var counters = c.getCounters();
        if (counters != null && !counters.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (var type : counters.elementSet()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(type.getName()).append('×').append(counters.count(type));
            }
            badge(g, sb.toString(), 1, 1, ts * 0.85f, 0xFFB8F0FF, false);
        }
        if (c.isSick() && s.isCreature() && c.getController() != null && duel.isLocalPlayer(c.getController())) {
            var m = g.pose().last().pose();
            Theme.fillF(g, m, 0, 0, w, h, 0x28000000);
        }
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

    private void drawStackLabel(GuiGraphics g, GameView view) {
        int n = view.getStack().size();
        if (n == 0) return;
        StackItemView top = view.getStack().getFirst();
        String text = top.getText();
        int w = Math.min(rowX1 - rowX0, font.width(text) + 10);
        int cx = (rowX0 + rowX1) / 2;
        int y = (int) (midY + cardH * 0.55f + 4);
        Theme.rounded(g, cx - w / 2, y, w, 12, 0xD0101010);
        g.drawCenteredString(font, Theme.ellipsize(font, text, w - 8), cx, y + 2, Theme.GOLD);
    }

    // ------------------------------------------------------------------ drawing: sidebar

    private void drawSidebar(GuiGraphics g, GameView view, PlayerView me, PlayerView opp, int mx, int my, long now) {
        int x = boardW + 6, w = sideW - 12;
        if (multiplayer()) {
            drawTurnBanner(g, view, x, oppPillY, w);
            drawColumnTags(g, mx, my);
        } else {
            drawPill(g, opp, x, oppPillY, w, mx, my);
        }
        drawPhases(g, view, x, oppPillY + 39, w);

        if (zoomCard != null && duel.mayView(zoomCard)) {
            drawCardFace(g, zoomCard, zoomX, zoomY, zoomW, zoomH, 1f);
        } else {
            Theme.rounded(g, zoomX, zoomY, zoomW, zoomH, 0x30FFFFFF);
            g.drawCenteredString(font, "Hover a card", zoomX + zoomW / 2, zoomY + zoomH / 2 - 4, Theme.MUTED);
        }

        // prompt
        boolean shake = now - duel.flashTime < 300;
        int sx = shake ? (int) (Math.sin(now / 20.0) * 3) : 0;
        List<FormattedCharSequence> lines = font.split(Component.literal(cleanPrompt(duel.prompt)), w);
        int maxLines = Math.max(1, (buttonsY - promptY - 4) / 10);
        for (int i = 0; i < Math.min(maxLines, lines.size()); i++) {
            g.drawString(font, lines.get(i), x + sx, promptY + i * 10, Theme.TEXT);
        }

        int bw = (w - 4) / 2;
        Theme.button(g, font, duel.okLabel + " ␣", x, buttonsY, bw, 20, mx, my, duel.okEnabled, true);
        Theme.button(g, font, duel.cancelLabel, x + bw + 4, buttonsY, bw, 20, mx, my, duel.cancelEnabled, false);

        drawPill(g, me, x, myPillY, w, mx, my);
        String flag = confirmConcede ? "Concede?" : "⚑";
        int fw = font.width(flag) + 6;
        boolean fh = in(mx, my, x + w - fw - 2, myPillY + 21, fw, 11);
        Theme.rounded(g, x + w - fw - 2, myPillY + 21, fw, 11, fh || confirmConcede ? 0xE0803030 : 0x60000000);
        g.drawString(font, flag, x + w - fw + 1, myPillY + 23, Theme.TEXT, false);
        drawLog(g, view);
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

    /** Name, life and counts above each opponent's column; also the drop/click target for that player. */
    private void drawColumnTags(GuiGraphics g, int mx, int my) {
        PlayerView turn = duel.getGameView() == null ? null : duel.getGameView().getPlayerTurn();
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
            String counts = " ✋" + p.getZoneSize(ZoneType.Hand) + " ▤" + p.getZoneSize(ZoneType.Library);
            int lifeW = font.width(life) + 4;
            g.drawString(font, life, x + w - lifeW, y + 3, p.getLife() <= 5 ? Theme.RED : 0xFFFFFFFF);
            String name = Theme.ellipsize(font, p.getName(), w - lifeW - 6 - (w > 120 ? font.width(counts) : 0));
            g.drawString(font, name, x + 3, y + 3, out ? Theme.MUTED : Theme.TEXT);
            if (w > 120) g.drawString(font, counts, x + 3 + font.width(name), y + 3, Theme.MUTED);
        }
    }

    /** Card-Forge prompts start with a status line ("Priority: ... Stack: Empty"); keep only the useful part. */
    private static String cleanPrompt(String prompt) {
        if (prompt == null) return "";
        if (prompt.startsWith("Priority:")) {
            return prompt.contains("Stack: Empty") ? "Play a card, or press OK to move on." : "Respond, or press OK to let it resolve.";
        }
        return prompt.replace("  ", " ").trim();
    }

    private void drawPill(GuiGraphics g, PlayerView p, int x, int y, int w, int mx, int my) {
        boolean target = duel.isHighlighted(p);
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 34;
        int edge = target ? Theme.SELECT : hover ? 0xFFB0B5A8 : Theme.PANEL_EDGE;
        Theme.rounded(g, x - 1, y - 1, w + 2, 36, edge);
        Theme.rounded(g, x, y, w, 34, 0xF0182019);
        boolean turn = duel.getGameView() != null && duel.getGameView().getPlayerTurn() != null
                && duel.getGameView().getPlayerTurn().getId() == p.getId();
        Theme.disc(g, x + 13, y + 13, 9, turn ? Theme.GOLD : 0xFF3A403B);
        String initial = p.getName().isEmpty() ? "?" : p.getName().substring(0, 1).toUpperCase();
        g.drawCenteredString(font, initial, x + 13, y + 9, turn ? 0xFF2A2010 : Theme.TEXT);
        g.drawString(font, Theme.ellipsize(font, p.getName(), w - 60), x + 26, y + 4, Theme.TEXT);
        String life = String.valueOf(p.getLife());
        g.pose().pushPose();
        g.pose().translate(x + w - 4 - font.width(life) * 1.5f, y + 3, 0);
        g.pose().scale(1.5f, 1.5f, 1);
        g.drawString(font, life, 0, 0, p.getLife() <= 5 ? Theme.RED : 0xFFFFFFFF);
        g.pose().popPose();
        String sub = "Hand " + p.getZoneSize(ZoneType.Hand) + "  Lib " + p.getZoneSize(ZoneType.Library);
        if (p.getCounters() != null) {
            int poison = 0;
            for (var t : p.getCounters().elementSet()) if (t.getName().equalsIgnoreCase("poison")) poison = p.getCounters().count(t);
            if (poison > 0) sub += "  ☠" + poison;
        }
        g.drawString(font, Theme.ellipsize(font, sub, w - 30), x + 26, y + 14, Theme.MUTED);
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

    private static final PhaseType[][] PHASES = {
            {PhaseType.UNTAP, PhaseType.UPKEEP}, {PhaseType.DRAW}, {PhaseType.MAIN1}, {PhaseType.COMBAT_BEGIN},
            {PhaseType.COMBAT_DECLARE_ATTACKERS}, {PhaseType.COMBAT_DECLARE_BLOCKERS},
            {PhaseType.COMBAT_FIRST_STRIKE_DAMAGE, PhaseType.COMBAT_DAMAGE, PhaseType.COMBAT_END},
            {PhaseType.MAIN2}, {PhaseType.END_OF_TURN, PhaseType.CLEANUP}};
    private static final String[] PHASE_NAMES = {"UP", "DR", "M1", "BC", "ATK", "BLK", "DMG", "M2", "END"};

    private void drawPhases(GuiGraphics g, GameView view, int x, int y, int w) {
        PhaseType now = view.getPhase();
        float seg = w / (float) PHASES.length;
        for (int i = 0; i < PHASES.length; i++) {
            boolean active = false;
            for (PhaseType t : PHASES[i]) if (t == now) active = true;
            int sx = (int) (x + i * seg);
            Theme.rounded(g, sx, y, (int) seg - 1, 11, active ? Theme.GOLD : 0x40FFFFFF);
            g.pose().pushPose();
            g.pose().translate(sx + seg / 2f, y + 2.5f, 0);
            float fit = Math.min(0.7f, (seg - 3) / Math.max(1f, font.width(PHASE_NAMES[i])));
            g.pose().scale(fit, fit, 1);
            g.drawCenteredString(font, PHASE_NAMES[i], 0, 0, active ? 0xFF201808 : Theme.MUTED);
            g.pose().popPose();
        }
    }

    private void drawLog(GuiGraphics g, GameView view) {
        try {
            List<GameLogEntry> log = view.getGameLog().getLogEntries(null);
            int shown = 0;
            for (int i = 0; i < log.size() && shown < 3; i++, shown++) {
                String msg = log.get(i).message();
                int alpha = 200 - shown * 60;
                g.drawString(font, Theme.ellipsize(font, msg, boardW - rowX0 - 10), rowX0, (int) (midY - 30 - shown * 10),
                        (alpha << 24) | 0xD8E0D0, true);
            }
        } catch (RuntimeException ignored) {
            // log being appended to by the game thread
        }
    }

    // ------------------------------------------------------------------ choice overlay

    private void syncRequest(ChoiceRequest req) {
        if (req != shownRequest) {
            shownRequest = req;
            picked.clear();
            optionScroll = 0;
            numberValue = req == null ? 0 : req.max;
        }
    }

    private int[] choicePanel(ChoiceRequest req) {
        int pw = Math.min(boardW - 20, 440);
        int ph;
        if (req.kind == ChoiceRequest.Kind.NUMBER) ph = 110;
        else if (req.hasCards()) ph = (int) (cardH * 1.6f) + 90;
        else ph = Math.min(height - 40, 70 + Math.min(8, req.labels.size()) * 22 + 30);
        return new int[]{(boardW - pw) / 2, (height - ph) / 2, pw, ph};
    }

    private void drawChoice(GuiGraphics g, ChoiceRequest req, int mx, int my, long now) {
        g.fill(0, 0, boardW, height, 0x88000000);
        int[] p = choicePanel(req);
        int px = p[0], py = p[1], pw = p[2], ph = p[3];
        Theme.panel(g, px, py, pw, ph);
        String title = req.title.isEmpty() ? (req.subject != null ? req.subject.getName() : "Decision") : req.title;
        g.drawCenteredString(font, Theme.ellipsize(font, title, pw - 20), px + pw / 2, py + 8, Theme.GOLD);
        List<FormattedCharSequence> msg = font.split(Component.literal(req.message), pw - 20);
        for (int i = 0; i < Math.min(3, msg.size()); i++) {
            g.drawCenteredString(font, msg.get(i), px + pw / 2, py + 22 + i * 10, Theme.TEXT);
        }
        int contentY = py + 26 + Math.min(3, msg.size()) * 10;

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
            String label = req.max == 0 ? "Done" : picked.isEmpty() && req.min == 0 && req.kind != ChoiceRequest.Kind.NUMBER ? "None" : "Confirm";
            Theme.button(g, font, label, px + pw / 2 - 50, py + ph - 26, 100, 20, mx, my, ok, true);
        }
        g.drawString(font, "Hold Tab to see the board", px + 6, py + ph - 10, 0x80A9B5A8);
    }

    /** Single picks answer on click; everything else needs a Confirm. */
    private static boolean needsConfirm(ChoiceRequest req) {
        return !(req.kind == ChoiceRequest.Kind.PICK && req.min == 1 && req.max == 1);
    }

    private boolean confirmAllowed(ChoiceRequest req) {
        if (req.kind == ChoiceRequest.Kind.NUMBER) return true;
        return picked.size() >= req.min && picked.size() <= req.max;
    }

    private void answer(ChoiceRequest req) {
        if (req.kind == ChoiceRequest.Kind.NUMBER) {
            req.answer.complete(List.of(numberValue));
        } else {
            req.answer.complete(new ArrayList<>(picked));
        }
        Theme.click();
    }

    private boolean clickChoice(ChoiceRequest req, double mx, double my) {
        int[] p = choicePanel(req);
        int px = p[0], py = p[1], pw = p[2], ph = p[3];
        List<FormattedCharSequence> msg = font.split(Component.literal(req.message), pw - 20);
        int contentY = py + 26 + Math.min(3, msg.size()) * 10;

        if (needsConfirm(req) && in(mx, my, px + pw / 2 - 50, py + ph - 26, 100, 20)) {
            if (confirmAllowed(req)) answer(req);
            return true;
        }
        if (req.kind == ChoiceRequest.Kind.NUMBER) {
            if (in(mx, my, px + pw / 2 - 60, contentY + 6, 20, 18)) numberValue = Math.max(req.min, numberValue - 1);
            if (in(mx, my, px + pw / 2 + 40, contentY + 6, 20, 18)) numberValue = Math.min(req.max, numberValue + 1);
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

    private int[] gameOverPanel() {
        return new int[]{boardW / 2 - 90, height / 2 - 45, 180, 90};
    }

    private void drawGameOver(GuiGraphics g, GameView view, PlayerView me, int mx, int my) {
        g.fill(0, 0, boardW, height, 0x99000000);
        int[] p = gameOverPanel();
        Theme.panel(g, p[0], p[1], p[2], p[3]);
        String winner = view.getWinningPlayerName();
        String title = winner == null || winner.isEmpty() ? "Draw" : winner.equals(me.getName()) ? "Victory!" : "Defeat";
        g.pose().pushPose();
        g.pose().translate(p[0] + p[2] / 2f, p[1] + 10, 0);
        g.pose().scale(2, 2, 1);
        g.drawCenteredString(font, title, 0, 0, title.equals("Victory!") ? Theme.GOLD : Theme.TEXT);
        g.pose().popPose();
        Theme.button(g, font, "Back to the table", p[0] + 20, p[1] + 38, p[2] - 40, 18, mx, my, true, true);
        Theme.button(g, font, "Leave table", p[0] + 20, p[1] + 62, p[2] - 40, 18, mx, my, true, false);
    }

    // ------------------------------------------------------------------ input

    private Placed topCardAt(double mx, double my) {
        Placed best = null;
        int bestRank = -1;
        for (int i = 0; i < placed.size(); i++) {
            Placed p = placed.get(i);
            Anim a = anims.get(p.card.getId());
            if (a == null || !p.contains(mx, my, a)) continue;
            int rank = p.zone.ordinal() == Zone.HAND.ordinal() ? 2000 + i : p.zone == Zone.STACK ? 1000 + i : i;
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
        if (duel.isOver() && req == null) {
            int[] p = gameOverPanel();
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
        if (ArenaRenderer.has(table) && in(mx, my, boardW - 92, 2, 88, 13)) {
            toggleView();
            return true;
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
        if (in(mx, my, x + w - fw - 2, myPillY + 21, fw, 11)) {
            if (confirmConcede) duel.concedeGame();
            confirmConcede = !confirmConcede;
            Theme.click();
            return true;
        }
        confirmConcede = false;
        PlayerView pill = pillAt(mx, my);
        if (pill != null) {
            if (pill == duel.me() && clickMana(mx, my)) return true;
            duel.clickPlayer(pill);
            return true;
        }
        Placed p = topCardAt(mx, my);
        if (p != null) {
            if (button == 1) {
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
        return p.mine && (p.zone == Zone.HAND || p.zone == Zone.BATTLEFIELD);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        Placed src = pressed;
        boolean wasDragging = dragging;
        pressed = null;
        dragging = false;
        if (src == null) return super.mouseReleased(mx, my, button);
        if (!wasDragging) {
            duel.clickCard(src.card, 1);
            Theme.click();
            return true;
        }
        drop(src, mx, my);
        return true;
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
        boolean myTurn = view.getPlayerTurn() != null && view.getPlayerTurn().getId() == me.getId();
        PhaseType phase = view.getPhase();

        if (src.zone == Zone.HAND) {
            if (handArea && onPlayer == null && (onCard == null || onCard.zone == Zone.HAND)) {
                reorder(src, me, mx);
                return;
            }
            duel.clickCard(src.card, 1);
            aimAt(onCard != null && onCard.zone != Zone.HAND ? onCard.card : onPlayer);
            Theme.play(SoundEvents.BOOK_PUT, 1.2f, 0.7f);
            return;
        }

        // A creature or other permanent of mine on the battlefield.
        CombatView combat = view.getCombat();
        if (phase == PhaseType.COMBAT_DECLARE_ATTACKERS && myTurn) {
            boolean attacking = combat != null && combat.isAttacking(src.card);
            boolean towardOpponent = oppSide || (onPlayer != null && onPlayer.getId() != me.getId());
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
            if (onCard != null && combat != null && combat.isAttacking(onCard.card)) {
                duel.block(onCard.card, src.card);
            } else if (combat != null && combat.isBlocking(src.card)) {
                duel.clickCard(src.card, 3);
            }
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
        if (req != null) {
            if (req.kind == ChoiceRequest.Kind.NUMBER) {
                numberValue = Math.max(req.min, Math.min(req.max, numberValue + (delta > 0 ? 1 : -1)));
            } else {
                optionScroll = Math.max(0, Math.min(Math.max(0, req.labels.size() - 8), optionScroll - (int) Math.signum(delta)));
            }
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_V && ArenaRenderer.has(table)) {
            toggleView();
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
        super.onClose();
    }
}

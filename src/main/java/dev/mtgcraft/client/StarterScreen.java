package dev.mtgcraft.client;

import dev.mtgcraft.engine.Tribal;
import dev.mtgcraft.net.Net;
import dev.mtgcraft.net.Packets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The one-time starter deck choice for survival. Three ways in: pick two colours, pick a creature type you like
 * (angels, slimes, dragons...), or answer a few questions and get a matching deck.
 */
public class StarterScreen extends Screen {
    /** Colour bits, matching Card-Forge: W U B R G. */
    private static final int W = 1, U = 2, B = 4, R = 8, G = 16;
    private static final int[] PAIRS = {W | G, W | U, U | B, B | R, R | G, W | B, U | R, B | G, R | W, G | U};
    private static final String[] PAIR_NAMES = {"Selesnya", "Azorius", "Dimir", "Rakdos", "Gruul", "Orzhov", "Izzet",
            "Golgari", "Boros", "Simic"};
    private static final String[] PAIR_HINTS = {"go wide with friends", "control and flyers", "tricks and secrets",
            "fast and ruthless", "big and angry", "drain and outlast", "spells and inventions", "life from the graveyard",
            "attack, attack, attack", "grow and evolve"};

    /** Each question's answers, and the colours each answer leans to (W U B R G points). */
    private static final String[] QUESTIONS = {"How do you like to win?", "Pick a place to live", "What matters most?",
            "Pick a mob to bring along"};
    private static final String[][] ANSWERS = {
            {"A huge army of friends", "Outsmart them with tricks", "Drain them, whatever it costs", "Burn it all down", "Giant monsters"},
            {"A sunny village", "An island by the sea", "A dark swamp", "A volcano", "A deep forest"},
            {"Loyalty", "Knowledge", "Power", "Freedom", "Nature"},
            {"An allay", "A dolphin", "A wither skeleton", "A blaze", "A wolf"}};

    private enum Page { START, COLORS, TYPES, QUIZ, RESULT }

    private Page page = Page.START;
    private int question;
    private final int[] score = new int[5];
    private int result;

    public static void show(Packets.OfferStarter msg) {
        Minecraft.getInstance().setScreen(new StarterScreen());
    }

    public StarterScreen() {
        super(Component.literal("Choose your starter deck"));
    }

    // ------------------------------------------------------------------ layout

    private record Btn(String label, String hint, int x, int y, int w, int h, Runnable action, int mask, boolean primary) {}

    /** The buttons of the current page, laid out for the screen size. */
    private List<Btn> buttons() {
        List<Btn> out = new ArrayList<>();
        int pw = panelW(), px = (width - pw) / 2, top = panelY() + 34;
        switch (page) {
            case START -> {
                String[][] ways = {{"Pick colours", "Two colours, a legendary commander and 99 cards"},
                        {"Pick a creature type", "Angels, slimes, dragons, zombies..."},
                        {"Answer 4 questions", "We'll find a deck that fits you"}};
                Page[] to = {Page.COLORS, Page.TYPES, Page.QUIZ};
                for (int i = 0; i < 3; i++) {
                    Page p = to[i];
                    out.add(new Btn(ways[i][0], ways[i][1], px + 12, top + i * 34, pw - 24, 28, () -> go(p), 0, i == 0));
                }
            }
            case COLORS -> {
                int cw = (pw - 30) / 2;
                for (int i = 0; i < PAIRS.length; i++) {
                    int mask = PAIRS[i];
                    out.add(new Btn(PAIR_NAMES[i], PAIR_HINTS[i], px + 12 + (i % 2) * (cw + 6), top + (i / 2) * 26, cw, 22,
                            () -> choose(mask, ""), mask, false));
                }
                back(out, px, pw);
            }
            case TYPES -> {
                String[][] tribes = Tribal.TRIBES;
                int cols = 5, cw = (pw - 24 - (cols - 1) * 3) / cols;
                for (int i = 0; i < tribes.length; i++) {
                    String type = tribes[i][1];
                    out.add(new Btn(tribes[i][0], null, px + 12 + (i % cols) * (cw + 3), top + (i / cols) * 18, cw, 15,
                            () -> choose(0, type), 0, false));
                }
                back(out, px, pw);
            }
            case QUIZ -> {
                for (int i = 0; i < 5; i++) {
                    int pick = i;
                    out.add(new Btn(ANSWERS[question][i], null, px + 12, top + 12 + i * 22, pw - 24, 18, () -> answer(pick), 0, false));
                }
                back(out, px, pw);
            }
            case RESULT -> {
                out.add(new Btn("Take this deck", null, px + 12, top + 52, pw - 24, 22, () -> choose(result, ""), result, true));
                out.add(new Btn("Start over", null, px + 12, top + 80, pw - 24, 18, () -> go(Page.START), 0, false));
            }
        }
        return out;
    }

    private void back(List<Btn> out, int px, int pw) {
        out.add(new Btn("Back", null, px + pw / 2 - 40, panelY() + panelH() - 24, 80, 16, () -> go(Page.START), 0, false));
    }

    private int panelW() { return Math.min(width - 20, 320); }

    private int panelH() {
        return switch (page) {
            case START -> 148;
            case COLORS -> 34 + 5 * 26 + 30;
            case TYPES -> 34 + (Tribal.TRIBES.length + 4) / 5 * 18 + 30;
            case QUIZ -> 34 + 12 + 5 * 22 + 30;
            case RESULT -> 140;
        };
    }

    private int panelY() { return Math.max(4, height / 2 - panelH() / 2); }

    // ------------------------------------------------------------------ actions

    private void go(Page p) {
        page = p;
        if (p == Page.QUIZ) {
            question = 0;
            java.util.Arrays.fill(score, 0);
        }
    }

    private void answer(int pick) {
        // Strong lean to the answer's colour; earlier questions weigh a little more, to break ties.
        score[pick] += 4 - question / 2;
        if (++question < QUESTIONS.length) return;
        int first = 0;
        for (int i = 1; i < 5; i++) if (score[i] > score[first]) first = i;
        int second = first == 0 ? 1 : 0;
        for (int i = 0; i < 5; i++) if (i != first && score[i] > score[second]) second = i;
        result = (1 << first) | (1 << second);
        page = Page.RESULT;
    }

    private void choose(int colors, String tribe) {
        Net.toServer(new Packets.ChooseStarter(colors, tribe));
        onClose();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        Theme.drawFelt(g, width, height);
        int pw = panelW(), ph = panelH(), px = (width - pw) / 2, py = panelY();
        Theme.panel(g, px, py, pw, ph);
        String title = switch (page) {
            case START -> "Choose your starter deck";
            case COLORS -> "Pick two colours";
            case TYPES -> "What do you want to play?";
            case QUIZ -> QUESTIONS[question];
            case RESULT -> "Your deck";
        };
        g.drawCenteredString(font, Component.literal(title).withStyle(s -> s.withBold(true)), width / 2, py + 8, Theme.GOLD);
        String sub = switch (page) {
            case START -> "It comes in a Deck Box, with a Binder and a Duel Gauntlet.";
            case COLORS -> "A legend of those colours leads a 100-card deck.";
            case TYPES -> "A legend of that type leads a deck full of them.";
            case QUIZ -> "Question " + (question + 1) + " of " + QUESTIONS.length;
            case RESULT -> "Based on your answers";
        };
        g.drawCenteredString(font, sub, width / 2, py + 20, Theme.MUTED);
        if (page == Page.RESULT) {
            int idx = 0;
            for (int i = 0; i < PAIRS.length; i++) if (PAIRS[i] == result) idx = i;
            g.drawCenteredString(font, Component.literal(PAIR_NAMES[idx]).withStyle(s -> s.withBold(true)), width / 2, py + 42, Theme.TEXT);
            drawOrbs(g, result, width / 2 - 9, py + 62, false);
            g.drawCenteredString(font, PAIR_HINTS[idx], width / 2, py + 74, Theme.MUTED);
        }
        for (Btn b : buttons()) {
            boolean hover = Theme.button(g, font, b.mask != 0 && page == Page.COLORS ? "" : b.hint != null ? "" : b.label,
                    b.x, b.y, b.w, b.h, mx, my, true, b.primary);
            if (page == Page.COLORS) {
                drawOrbs(g, b.mask, b.x + 12, b.y + b.h / 2, hover);
                g.drawString(font, b.label, b.x + 34, b.y + 3, hover ? Theme.GOLD : Theme.TEXT);
                g.drawString(font, Theme.ellipsize(font, b.hint, b.w - 38), b.x + 34, b.y + 12, Theme.MUTED);
            } else if (b.hint != null) {
                g.drawCenteredString(font, b.label, b.x + b.w / 2, b.y + 5, hover ? Theme.GOLD : Theme.TEXT);
                g.drawCenteredString(font, Theme.ellipsize(font, b.hint, b.w - 8), b.x + b.w / 2, b.y + 16, Theme.MUTED);
            }
        }
    }

    /** One small mana orb per colour in the mask, left to right from x. */
    private void drawOrbs(GuiGraphics g, int mask, int x, int cy, boolean hover) {
        int n = 0;
        for (int i = 0; i < 5; i++) {
            if ((mask & (1 << i)) == 0) continue;
            Theme.manaOrb(g, x + n * 18, cy, 7, i, true, hover);
            n++;
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (Btn b : buttons()) {
            if (mx >= b.x && mx < b.x + b.w && my >= b.y && my < b.y + b.h) {
                Theme.click();
                b.action.run();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

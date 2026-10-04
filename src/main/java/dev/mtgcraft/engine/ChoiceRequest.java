package dev.mtgcraft.engine;

import forge.game.card.CardView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A question the engine is blocked on (pick cards, choose an ability, order triggers, pick a number).
 * The game or UI thread creates it and waits; the screen shows it and completes {@link #answer}.
 */
public final class ChoiceRequest {
    public enum Kind {
        /** Pick between {@link #min} and {@link #max} options. */
        PICK,
        /** Pick options one by one; the order they were picked in is the answer. */
        ORDER,
        /** Pick a number between {@link #min} and {@link #max}. The answer is a single value. */
        NUMBER
    }

    public final Kind kind;
    public final String title;
    public final String message;
    /** Text for each option; empty for NUMBER. */
    public final List<String> labels;
    /** Card for each option, or null where the option is not a card. Same size as labels. */
    public final List<CardView> cards;
    public final int min;
    public final int max;
    /** A card the question is about (shown beside the prompt), may be null. */
    public final CardView subject;
    /** Option indices for PICK/ORDER, or the chosen value for NUMBER. */
    public final CompletableFuture<List<Integer>> answer = new CompletableFuture<>();

    private ChoiceRequest(Kind kind, String title, String message, List<String> labels, List<CardView> cards,
                          int min, int max, CardView subject) {
        this.kind = kind;
        this.title = title == null ? "" : title;
        this.message = message == null ? "" : message;
        this.labels = Collections.unmodifiableList(labels);
        this.cards = Collections.unmodifiableList(cards);
        this.min = min;
        this.max = max;
        this.subject = subject;
    }

    public static ChoiceRequest pick(String title, String message, List<String> labels, List<CardView> cards,
                                     int min, int max, CardView subject) {
        return new ChoiceRequest(Kind.PICK, title, message, labels, padCards(cards, labels.size()), min, max, subject);
    }

    public static ChoiceRequest order(String title, String message, List<String> labels, List<CardView> cards,
                                      int min, int max, CardView subject) {
        return new ChoiceRequest(Kind.ORDER, title, message, labels, padCards(cards, labels.size()), min, max, subject);
    }

    public static ChoiceRequest number(String title, String message, int min, int max, CardView subject) {
        return new ChoiceRequest(Kind.NUMBER, title, message, List.of(), List.of(), min, max, subject);
    }

    public boolean hasCards() {
        for (CardView c : cards) {
            if (c != null) return true;
        }
        return false;
    }

    private static List<CardView> padCards(List<CardView> cards, int size) {
        List<CardView> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(cards != null && i < cards.size() ? cards.get(i) : null);
        }
        return out;
    }
}

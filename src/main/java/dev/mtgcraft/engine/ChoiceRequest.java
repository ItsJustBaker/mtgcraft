package dev.mtgcraft.engine;

import forge.game.card.CardView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

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
        NUMBER,
        /**
         * Split {@link #total} points (damage, counters...) between the options. The answer is one amount per option,
         * starting from {@link #initial}; {@link #check} says whether a split is allowed.
         */
        DISTRIBUTE
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
    /** DISTRIBUTE: the points to hand out, and the smallest amount each option may get. */
    public int total;
    public List<Integer> minEach = List.of();
    /** DISTRIBUTE: the suggested split the screen starts from. */
    public List<Integer> initial = List.of();
    /** DISTRIBUTE: a short note under each option (e.g. "lethal: 3"); may be empty. */
    public List<String> notes = List.of();
    /** DISTRIBUTE: null if a split is allowed, otherwise why not (shown to the player). */
    public Function<List<Integer>, String> check = v -> null;
    /** Option indices for PICK/ORDER, the chosen value for NUMBER, or the amounts for DISTRIBUTE. */
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

    public static ChoiceRequest distribute(String title, String message, List<String> labels, List<CardView> cards,
                                           int total, List<Integer> minEach, List<Integer> initial, List<String> notes,
                                           Function<List<Integer>, String> check, CardView subject) {
        ChoiceRequest r = new ChoiceRequest(Kind.DISTRIBUTE, title, message, labels, padCards(cards, labels.size()),
                0, total, subject);
        r.total = total;
        r.minEach = List.copyOf(minEach);
        r.initial = List.copyOf(initial);
        r.notes = notes == null ? List.of() : List.copyOf(notes);
        if (check != null) r.check = check;
        return r;
    }

    /** DISTRIBUTE: why this split isn't allowed, or null if it is. Checks the total and minimums first. */
    public String problem(List<Integer> values) {
        int sum = 0;
        for (int i = 0; i < values.size(); i++) {
            sum += values.get(i);
            if (i < minEach.size() && values.get(i) < minEach.get(i)) return "Each needs at least " + minEach.get(i) + ".";
        }
        if (sum != total) return sum < total ? (total - sum) + " left to assign." : "That's " + (sum - total) + " too many.";
        return check.apply(values);
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

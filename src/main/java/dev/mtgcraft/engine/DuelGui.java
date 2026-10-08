package dev.mtgcraft.engine;

import forge.LobbyPlayer;
import forge.deck.CardPool;
import forge.game.GameEntityView;
import forge.game.GameState;
import forge.game.card.CardView;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.gamemodes.match.NextGameDecision;
import forge.gamemodes.net.NetworkGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.trackable.TrackableCollection;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;

/**
 * The human player's view of a duel. Card-Forge calls in from the game thread (prompts, questions); the screen
 * polls the public state every frame and answers {@link ChoiceRequest}s. Nothing here blocks the render thread.
 */
public final class DuelGui extends NetworkGuiGame {
    private static volatile DuelGui active;

    /** The duel currently on the table, or null. */
    public static DuelGui active() { return active; }

    // Prompt bar state, written by the engine and read by the screen.
    public volatile String prompt = "";
    public volatile CardView promptCard;
    public volatile String okLabel = "OK";
    public volatile String cancelLabel = "Cancel";
    public volatile boolean okEnabled;
    public volatile boolean cancelEnabled;
    /** Time (ms) of the last rejected action, so the screen can shake the prompt. */
    public volatile long flashTime;
    public volatile boolean viewOpen;
    public volatile boolean finished;
    /** Set if the game couldn't start (e.g. a deck file failed to load). */
    public volatile String failed;

    /** Cards Card-Forge is showing for the current prompt (e.g. the cards being scried), or empty. */
    public volatile List<CardView> revealed = List.of();

    private final ConcurrentLinkedDeque<ChoiceRequest> requests = new ConcurrentLinkedDeque<>();

    public DuelGui() {
        active = this;
    }

    /** The question the screen should show now, or null. */
    public ChoiceRequest currentRequest() {
        ChoiceRequest r;
        if (isOver()) {
            // A question still open when the game ended (both players conceding at once) is moot, and would
            // otherwise sit over the board and hide the game-over panel for good.
            requests.forEach(q -> q.answer.cancel(false));
            requests.clear();
            return null;
        }
        while ((r = requests.peekFirst()) != null && r.answer.isDone()) {
            requests.remove(r);
        }
        return r;
    }

    public PlayerView me() {
        Set<PlayerView> local = getLocalPlayers();
        return local.isEmpty() ? null : local.iterator().next();
    }

    // ---- actions from the screen (all run on the engine UI thread) ----

    public void clickCard(CardView card, int button) {
        ForgeEngine.runOnUi(() -> {
            if (getGameController() != null) {
                getGameController().selectCard(card, null, new Trigger(button));
            }
        });
    }

    public void clickPlayer(PlayerView player) {
        ForgeEngine.runOnUi(() -> {
            if (getGameController() != null) {
                getGameController().selectPlayer(player, new Trigger(1));
            }
        });
    }

    public void ok() {
        ForgeEngine.runOnUi(() -> {
            if (getGameController() != null) getGameController().selectButtonOk();
        });
    }

    public void cancel() {
        ForgeEngine.runOnUi(() -> {
            if (getGameController() != null) getGameController().selectButtonCancel();
        });
    }

    /** Spends floating mana of one colour (a MagicColor byte) on the cost being paid. */
    public void useMana(byte color) {
        ForgeEngine.runOnUi(() -> {
            if (getGameController() != null) getGameController().useMana(color);
        });
    }

    public void reorderHand(CardView card, int index) {
        ForgeEngine.runOnUi(() -> {
            if (getGameController() != null) getGameController().reorderHand(card, index);
        });
    }

    public void concedeGame() {
        ForgeEngine.runOnUi(() -> {
            if (getGameController() != null) getGameController().concede();
        });
    }

    /** Selects the blocked attacker, then the blocker: what dragging a blocker onto an attacker means. */
    public void block(CardView attacker, CardView blocker) {
        ForgeEngine.runOnUi(() -> {
            if (getGameController() == null) return;
            getGameController().selectCard(attacker, null, new Trigger(1));
            getGameController().selectCard(blocker, null, new Trigger(1));
        });
    }

    /** True once the game has a result. */
    public boolean isOver() {
        return finished || (getGameView() != null && getGameView().isGameOver());
    }

    /** Leaves the table for good: concedes a running game, or closes a finished match. */
    public void leave() {
        if (active == this) {
            active = null;
        }
        requests.forEach(r -> r.answer.cancel(false));
        boolean over = isOver();
        ForgeEngine.runOnUi(() -> {
            if (getGameController() == null) return;
            if (over) {
                getGameController().nextGameDecision(NextGameDecision.QUIT);
            } else {
                getGameController().concede();
            }
        });
    }

    // ---- blocking questions ----

    /** Shows a question and waits for the answer; returns null if it was cancelled (duel left). */
    private List<Integer> ask(ChoiceRequest req) {
        requests.addLast(req);
        try {
            return req.answer.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | java.util.concurrent.CancellationException e) {
            return null;
        } finally {
            requests.remove(req);
        }
    }

    private static <T> String label(T item, FSerializableFunction<T, String> display) {
        if (display != null) return display.apply(item);
        if (item instanceof CardView c) return c.getName();
        if (item instanceof PlayerView p) return p.getName();
        if (item instanceof SpellAbilityView sa) return sa.toString();
        return String.valueOf(item);
    }

    private static CardView cardOf(Object item) {
        if (item instanceof CardView c) return c;
        if (item instanceof SpellAbilityView sa) return sa.getHostCard();
        return null;
    }

    private <T> List<T> pick(String title, String message, int min, int max, List<T> choices,
                             FSerializableFunction<T, String> display, CardView subject) {
        if (choices == null || choices.isEmpty() || max <= 0) {
            return new ArrayList<>();
        }
        max = Math.min(max, choices.size());
        min = Math.min(min, max);
        if (min == choices.size()) {
            return new ArrayList<>(choices);
        }
        List<String> labels = new ArrayList<>();
        List<CardView> cards = new ArrayList<>();
        for (T t : choices) {
            labels.add(label(t, display));
            cards.add(cardOf(t));
        }
        List<Integer> idx = ask(ChoiceRequest.pick(title, message, labels, cards, min, max, subject));
        List<T> out = new ArrayList<>();
        if (idx == null) {
            for (int i = 0; i < min; i++) out.add(choices.get(i));
            return out;
        }
        for (int i : idx) out.add(choices.get(i));
        return out;
    }

    private int askNumber(String message, int min, int max, CardView subject) {
        if (min >= max) return min;
        List<Integer> v = ask(ChoiceRequest.number("", message, min, max, subject));
        return v == null || v.isEmpty() ? min : v.get(0);
    }

    @Override
    public <T> List<T> getChoices(String message, int min, int max, List<T> choices, List<T> selected, FSerializableFunction<T, String> display) {
        if (min < 0 && max < 0) {
            // Card-Forge's way of saying "just show these".
            reveal(message, choices);
            return new ArrayList<>();
        }
        return pick("", message, min, max, choices, display, null);
    }

    @Override
    public <T> OrderResult<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax,
                                    List<T> sourceChoices, List<T> destChoices, CardView referenceCard,
                                    boolean sideboardingMode, boolean showRememberCheckbox) {
        List<T> result = new ArrayList<>();
        if (destChoices != null) result.addAll(destChoices);
        int n = sourceChoices.size();
        // "How many may stay behind": a negative bound means "any number" (e.g. scry: put any number on the bottom).
        int remainMin = remainingObjectsMin < 0 ? 0 : remainingObjectsMin;
        int remainMax = remainingObjectsMax < 0 ? n : remainingObjectsMax;
        int minPick = Math.max(0, Math.min(n, n - remainMax));
        int maxPick = Math.max(minPick, Math.min(n, n - remainMin));
        if (n <= 1 && minPick == n) {
            result.addAll(sourceChoices);
            return new OrderResult<>(result, false);
        }
        List<String> labels = new ArrayList<>();
        List<CardView> cards = new ArrayList<>();
        for (T t : sourceChoices) {
            labels.add(label(t, null));
            cards.add(cardOf(t));
        }
        if (minPick == n && new java.util.HashSet<>(labels).size() == 1) {
            // Ordering identical things (two copies of the same trigger) changes nothing; don't ask.
            result.addAll(sourceChoices);
            return new OrderResult<>(result, false);
        }
        List<Integer> idx = ask(ChoiceRequest.order(title, top, labels, cards, minPick, maxPick, referenceCard));
        if (idx == null) {
            result.addAll(sourceChoices.subList(0, minPick));
        } else {
            for (int i : idx) result.add(sourceChoices.get(i));
        }
        return new OrderResult<>(result, false);
    }

    @Override
    public boolean confirm(CardView c, String question, boolean defaultIsYes, List<String> options) {
        List<String> opts = options == null || options.size() < 2 ? List.of("Yes", "No") : options;
        List<Integer> idx = ask(ChoiceRequest.pick("", question, opts, null, 1, 1, c));
        return idx == null || idx.isEmpty() ? defaultIsYes : idx.get(0) == 0;
    }

    @Override
    public boolean showConfirmDialog(String message, String title, String yesButtonText, String noButtonText, boolean defaultYes) {
        List<Integer> idx = ask(ChoiceRequest.pick(title, message, List.of(yesButtonText, noButtonText), null, 1, 1, null));
        return idx == null || idx.isEmpty() ? defaultYes : idx.get(0) == 0;
    }

    @Override
    public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) {
        if (options == null || options.isEmpty()) return defaultOption;
        List<Integer> idx = ask(ChoiceRequest.pick(title, message, options, null, 1, 1, null));
        return idx == null || idx.isEmpty() ? defaultOption : idx.get(0);
    }

    @Override
    public String showInputDialog(String message, String title, FSkinProp icon, String initialInput, List<String> inputOptions, boolean isNumeric) {
        if (inputOptions == null || inputOptions.isEmpty()) {
            // No free text at the table: numbers go to the number picker, and type names to a list of every type.
            // (Returning nothing here used to make the card fizzle.)
            if (isNumeric) {
                int start = 0;
                try {
                    start = Integer.parseInt(initialInput == null ? "0" : initialInput.trim());
                } catch (NumberFormatException ignored) {
                    // no usable default
                }
                return String.valueOf(askNumber(message, Math.min(0, start), Math.max(999, start), null));
            }
            List<String> types = typeList(message + " " + title);
            if (!types.isEmpty()) {
                List<Integer> idx = ask(ChoiceRequest.pick(title, message, types, null, 1, 1, null));
                if (idx != null && !idx.isEmpty()) return types.get(idx.get(0));
            }
            return initialInput;
        }
        List<Integer> idx = ask(ChoiceRequest.pick(title, message, inputOptions, null, 1, 1, null));
        return idx == null || idx.isEmpty() ? initialInput : inputOptions.get(idx.get(0));
    }

    @Override
    public void message(String message, String title) {
        ask(ChoiceRequest.pick(title, message, List.of("OK"), null, 1, 1, null));
    }

    @Override
    public void showErrorDialog(String message, String title) {
        message(message, title);
    }

    @Override
    public <T> void reveal(String message, List<T> items) {
        if (items == null || items.isEmpty()) return;
        // Card-Forge's note about cards the AI plays badly is meant for deck builders, not for the duel.
        if (message != null && message.contains("play these cards well")) return;
        List<String> labels = new ArrayList<>();
        List<CardView> cards = new ArrayList<>();
        for (T t : items) {
            labels.add(label(t, null));
            cards.add(cardOf(t));
        }
        // max 0: display only, closed with the Done button.
        ask(ChoiceRequest.pick("", message, labels, cards, 0, 0, null));
    }

    /** Every creature type or card type, when a free-text question asks for one. */
    private static List<String> typeList(String question) {
        String q = question.toLowerCase(java.util.Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (q.contains("creature type")) out.addAll(forge.card.CardType.getAllCreatureTypes());
        else if (q.contains("card type")) for (forge.card.CardType.CoreType t : forge.card.CardType.CoreType.values()) out.add(t.name());
        else if (q.contains("land type")) out.addAll(forge.card.CardType.getAllLandTypes());
        java.util.Collections.sort(out);
        return out;
    }

    @Override
    public Integer getInteger(String message, int min, int max, boolean sortDesc) {
        return askNumber(message, min, max, null);
    }

    @Override
    public Integer getInteger(String message, int min, int max, int cutoff) {
        // The cutoff is where Card-Forge's own screens switch to an "Other..." text box; the number picker has no
        // such limit (it used to stop at 9, so big X costs couldn't be paid).
        return askNumber(message, min, Math.min(max, min + 999), null);
    }

    @Override
    public SpellAbilityView getAbilityToPlay(CardView hostCard, List<SpellAbilityView> abilities, ITriggerEvent triggerEvent) {
        if (abilities.isEmpty()) return null;
        if (abilities.size() == 1) return abilities.get(0);
        List<SpellAbilityView> chosen = pick("", "Choose what to do with " + hostCard.getName(), 0, 1, abilities, null, hostCard);
        return chosen.isEmpty() ? null : chosen.get(0);
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(String title, List<? extends GameEntityView> optionList, DelayedReveal delayedReveal, boolean isOptional) {
        if (delayedReveal != null && delayedReveal.getCards() != null && !delayedReveal.getCards().isEmpty()) {
            reveal(delayedReveal.getMessagePrefix(), new ArrayList<>(delayedReveal.getCards()));
        }
        List<GameEntityView> options = new ArrayList<>(optionList);
        if (options.isEmpty()) return null;
        if (options.size() == 1 && !isOptional) return options.get(0);
        List<GameEntityView> chosen = pick(title, title, isOptional ? 0 : 1, 1, options, null, null);
        return chosen.isEmpty() ? null : chosen.get(0);
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(String title, List<? extends GameEntityView> optionList, int min, int max, DelayedReveal delayedReveal) {
        if (delayedReveal != null && delayedReveal.getCards() != null && !delayedReveal.getCards().isEmpty()) {
            reveal(delayedReveal.getMessagePrefix(), new ArrayList<>(delayedReveal.getCards()));
        }
        return pick(title, title, min, max, new ArrayList<GameEntityView>(optionList), null, null);
    }

    /**
     * Moving cards to the top or bottom of a library (scry-like effects). The player clicks the cards to keep on top,
     * first click = top card; the rest go to the bottom.
     */
    @Override
    public List<CardView> manipulateCardList(String title, Iterable<CardView> cards, Iterable<CardView> manipulable,
                                             boolean toTop, boolean toBottom, boolean toAnywhere) {
        List<CardView> all = new ArrayList<>();
        cards.forEach(all::add);
        List<CardView> moving = new ArrayList<>();
        manipulable.forEach(moving::add);
        if (moving.isEmpty() || (!toTop && !toBottom)) return all;
        List<CardView> rest = new ArrayList<>(all);
        rest.removeAll(moving);
        List<String> labels = new ArrayList<>();
        for (CardView c : moving) labels.add(c.getName());
        List<CardView> top = new ArrayList<>(), bottom = new ArrayList<>();
        if (moving.size() == 1 && toTop && toBottom) {
            boolean keep = confirm(moving.get(0), "Put " + moving.get(0).getName() + " on top of your library?", true,
                    List.of("Top", "Bottom"));
            (keep ? top : bottom).addAll(moving);
        } else if (toTop) {
            String how = toBottom ? "Click the cards to keep on top (first click = top card). The rest go to the bottom."
                    : "Click the cards in order: the first one you click goes on top.";
            List<Integer> idx = ask(ChoiceRequest.order(title, how, labels, moving, toBottom ? 0 : moving.size(),
                    moving.size(), null));
            if (idx == null) return all;
            for (int i : idx) top.add(moving.get(i));
            for (CardView c : moving) if (!top.contains(c)) bottom.add(c);
        } else {
            List<Integer> idx = ask(ChoiceRequest.order(title, "Click the cards in order: the last one you click ends up at the very bottom.",
                    labels, moving, moving.size(), moving.size(), null));
            if (idx == null) return all;
            for (int i : idx) bottom.add(moving.get(i));
        }
        List<CardView> out = new ArrayList<>(top);
        out.addAll(rest);
        out.addAll(bottom);
        return out;
    }

    @Override
    public List<PaperCard> sideboard(CardPool sideboard, CardPool main, String message) {
        return main.toFlatList();
    }

    /** Lethal damage to each blocker in order; anything left goes to the player (trample) or the last blocker. */
    private static Map<CardView, Integer> autoCombatDamage(CardView attacker, List<CardView> blockers, int damage,
                                                           GameEntityView defender) {
        Map<CardView, Integer> out = new LinkedHashMap<>();
        int left = damage;
        for (CardView b : blockers) {
            int lethal = Math.max(0, Math.min(left, lethal(attacker, b)));
            out.put(b, lethal);
            left -= lethal;
        }
        if (left > 0) {
            if (defender != null) {
                out.put(null, left);
            } else if (!blockers.isEmpty()) {
                CardView last = blockers.get(blockers.size() - 1);
                out.merge(last, left, Integer::sum);
            }
        }
        return out;
    }

    /** Damage that kills a blocker: 1 from a deathtouch source. */
    private static int lethal(CardView source, CardView blocker) {
        int lethal = Math.max(0, blocker.getLethalDamage());
        boolean deathtouch = source != null && source.getCurrentState() != null && source.getCurrentState().hasDeathtouch();
        return deathtouch ? Math.min(1, lethal) : lethal;
    }

    /**
     * Splitting a creature's combat damage between its blockers (and, with trample, the player). Starts from the
     * usual split (lethal to each blocker, the rest through) and lets the player move points around.
     */
    @Override
    public Map<CardView, Integer> assignCombatDamage(CardView attacker, List<CardView> blockers, int damage,
                                                     GameEntityView defender, boolean overrideOrder, boolean maySkip) {
        Map<CardView, Integer> auto = autoCombatDamage(attacker, blockers, damage, defender);
        List<String> labels = new ArrayList<>();
        List<CardView> cards = new ArrayList<>();
        List<Integer> initial = new ArrayList<>(), mins = new ArrayList<>(), lethal = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (CardView b : blockers) {
            labels.add(b.getName());
            cards.add(b);
            initial.add(auto.getOrDefault(b, 0));
            mins.add(0);
            int l = lethal(attacker, b);
            lethal.add(l);
            notes.add("dies at " + l);
        }
        boolean toDefender = defender != null;
        if (toDefender) {
            labels.add(defender instanceof PlayerView p ? p.getName() : label(defender, null));
            cards.add(defender instanceof CardView c ? c : null);
            initial.add(auto.getOrDefault(null, 0));
            mins.add(0);
            notes.add("tramples over");
        }
        int nBlockers = blockers.size();
        java.util.function.Function<List<Integer>, String> check = v -> {
            if (!toDefender || overrideOrder || v.get(nBlockers) == 0) return null;
            for (int i = 0; i < nBlockers; i++) {
                if (v.get(i) < lethal.get(i)) return "Give every blocker lethal damage before trampling over.";
            }
            return null;
        };
        String name = attacker == null ? "Your creature" : attacker.getName();
        List<Integer> idx = ask(ChoiceRequest.distribute("Assign combat damage",
                name + " deals " + damage + " damage. Use + and − to split it.", labels, cards, damage, mins, initial,
                notes, check, attacker));
        if (idx == null || idx.size() != labels.size()) return auto;
        Map<CardView, Integer> out = new HashMap<>();
        for (int i = 0; i < nBlockers; i++) if (idx.get(i) > 0) out.put(blockers.get(i), idx.get(i));
        if (toDefender && idx.get(nBlockers) > 0) out.put(null, idx.get(nBlockers));
        return out;
    }

    /** Dividing damage, counters or shields between several targets, all on one screen. */
    @Override
    public Map<Object, Integer> assignGenericAmount(CardView effectSource, Map<Object, Integer> target, int amount,
                                                    boolean atLeastOne, String amountLabel) {
        List<Object> keys = new ArrayList<>(target.keySet());
        Map<Object, Integer> out = new LinkedHashMap<>();
        if (keys.isEmpty()) return out;
        int min = atLeastOne ? 1 : 0;
        List<String> labels = new ArrayList<>();
        List<CardView> cards = new ArrayList<>();
        List<Integer> mins = new ArrayList<>(), initial = new ArrayList<>();
        // Start from an even spread; the first targets get any odd points.
        int n = keys.size(), spare = Math.max(0, amount - min * n);
        for (int i = 0; i < n; i++) {
            Object k = keys.get(i);
            labels.add(label(k, null));
            cards.add(cardOf(k));
            mins.add(min);
            initial.add(min + spare / n + (i < spare % n ? 1 : 0));
        }
        String what = amountLabel == null || amountLabel.isEmpty() ? "points" : amountLabel.toLowerCase();
        List<Integer> v = keys.size() == 1 ? List.of(amount) : ask(ChoiceRequest.distribute(
                "Divide " + amount + " " + what, "Use + and − to split " + amount + " " + what + " between them.",
                labels, cards, amount, mins, initial, null, null, effectSource));
        if (v == null || v.size() != keys.size()) v = initial;
        for (int i = 0; i < keys.size(); i++) out.put(keys.get(i), v.get(i));
        return out;
    }

    /** Where the game pauses for the human when nothing is on the stack. */
    @Override
    public boolean isUiSetToSkipPhase(PlayerView playerTurn, PhaseType phase) {
        boolean mine = playerTurn != null && isLocalPlayer(playerTurn);
        if (mine) {
            return phase != PhaseType.MAIN1 && phase != PhaseType.MAIN2;
        }
        return phase != PhaseType.COMBAT_DECLARE_ATTACKERS && phase != PhaseType.END_OF_TURN;
    }

    // ---- display state ----

    @Override
    public void showPromptMessage(PlayerView playerView, String message, CardView card) {
        prompt = message == null ? "" : message;
        promptCard = card;
        promptAt = System.currentTimeMillis();
    }

    /** When the current prompt arrived (ms), to tell a real stop from one auto-pass is about to skip. */
    public volatile long promptAt;

    /** The connection to this duel is gone (logout, disconnect): forget it here without contacting the server. */
    public void abandon() {
        if (active == this) active = null;
        finished = true;
        requests.forEach(r -> r.answer.cancel(false));
    }

    @Override
    public void updateButtons(PlayerView owner, String label1, String label2, boolean enable1, boolean enable2, boolean focus1) {
        okLabel = label1;
        cancelLabel = label2;
        okEnabled = enable1;
        cancelEnabled = enable2;
    }

    @Override public void showRevealedCards(Iterable<CardView> cards) {
        List<CardView> list = new ArrayList<>();
        if (cards != null) cards.forEach(list::add);
        revealed = List.copyOf(list);
    }
    @Override public void hideRevealedCards() { revealed = List.of(); }
    @Override public void flashIncorrectAction() { flashTime = System.currentTimeMillis(); }
    @Override public void alertUser() { }
    @Override public void openView(TrackableCollection<PlayerView> myPlayers) { viewOpen = true; }
    @Override public void finishGame() { finished = true; }
    @Override public void afterGameEnd() { super.afterGameEnd(); finished = true; }
    @Override protected void updateCurrentPlayer(PlayerView player) { }
    @Override public GameState getGamestate() { return null; }
    @Override public void setCard(CardView card) { }
    @Override public void setPanelSelection(CardView hostCard) { }
    @Override public void setPlayerAvatar(LobbyPlayer player, IHasIcon ihi) { }
    @Override public void showCombat() { }

    /** Mouse info Card-Forge reads from a click: button 3 means right-click (e.g. remove from combat). */
    private record Trigger(int button) implements ITriggerEvent {
        @Override public int getButton() { return button; }
        @Override public int getX() { return 0; }
        @Override public int getY() { return 0; }
    }
}

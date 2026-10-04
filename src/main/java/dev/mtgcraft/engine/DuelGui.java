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

    private final ConcurrentLinkedDeque<ChoiceRequest> requests = new ConcurrentLinkedDeque<>();

    public DuelGui() {
        active = this;
    }

    /** The question the screen should show now, or null. */
    public ChoiceRequest currentRequest() {
        ChoiceRequest r;
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
        return pick("", message, min, max, choices, display, null);
    }

    @Override
    public <T> OrderResult<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax,
                                    List<T> sourceChoices, List<T> destChoices, CardView referenceCard,
                                    boolean sideboardingMode, boolean showRememberCheckbox) {
        List<T> result = new ArrayList<>();
        if (destChoices != null) result.addAll(destChoices);
        int n = sourceChoices.size();
        int minPick = Math.max(0, n - remainingObjectsMax);
        int maxPick = Math.max(minPick, n - remainingObjectsMin);
        if (remainingObjectsMin == 0 && remainingObjectsMax == 0) {
            minPick = maxPick = n;
        }
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
            // Free text entry isn't supported at the table; take Card-Forge's suggested value.
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
        List<String> labels = new ArrayList<>();
        List<CardView> cards = new ArrayList<>();
        for (T t : items) {
            labels.add(label(t, null));
            cards.add(cardOf(t));
        }
        // max 0: display only, closed with the Done button.
        ask(ChoiceRequest.pick("", message, labels, cards, 0, 0, null));
    }

    @Override
    public Integer getInteger(String message, int min, int max, boolean sortDesc) {
        return askNumber(message, min, max, null);
    }

    @Override
    public Integer getInteger(String message, int min, int max, int cutoff) {
        return askNumber(message, min, Math.min(max, cutoff), null);
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

    @Override
    public List<CardView> manipulateCardList(String title, Iterable<CardView> cards, Iterable<CardView> manipulable,
                                             boolean toTop, boolean toBottom, boolean toAnywhere) {
        List<CardView> out = new ArrayList<>();
        cards.forEach(out::add);
        return out;
    }

    @Override
    public List<PaperCard> sideboard(CardPool sideboard, CardPool main, String message) {
        return main.toFlatList();
    }

    /** Lethal damage to each blocker in order; anything left goes to the player (trample) or the last blocker. */
    @Override
    public Map<CardView, Integer> assignCombatDamage(CardView attacker, List<CardView> blockers, int damage,
                                                     GameEntityView defender, boolean overrideOrder, boolean maySkip) {
        Map<CardView, Integer> out = new HashMap<>();
        int left = damage;
        for (CardView b : blockers) {
            int lethal = Math.max(0, Math.min(left, b.getLethalDamage()));
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

    @Override
    public Map<Object, Integer> assignGenericAmount(CardView effectSource, Map<Object, Integer> target, int amount,
                                                    boolean atLeastOne, String amountLabel) {
        Map<Object, Integer> out = new LinkedHashMap<>();
        List<Object> keys = new ArrayList<>(target.keySet());
        int left = amount;
        for (int i = 0; i < keys.size(); i++) {
            Object k = keys.get(i);
            int stillNeeded = atLeastOne ? keys.size() - i - 1 : 0;
            int value;
            if (i == keys.size() - 1) {
                value = left;
            } else {
                int min = atLeastOne ? 1 : 0;
                value = askNumber(amountLabel + " to " + label(k, null) + " (" + left + " left)", min, left - stillNeeded, effectSource);
            }
            out.put(k, value);
            left -= value;
        }
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
    }

    @Override
    public void updateButtons(PlayerView owner, String label1, String label2, boolean enable1, boolean enable2, boolean focus1) {
        okLabel = label1;
        cancelLabel = label2;
        okEnabled = enable1;
        cancelEnabled = enable2;
    }

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

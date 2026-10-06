package dev.mtgcraft.engine.net;

import forge.ai.ComputerUtilCost;
import forge.ai.ComputerUtilMana;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.input.Input;
import forge.gamemodes.match.input.InputPassPriority;
import forge.player.PlayerControllerHuman;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Passes priority for a human player who has nothing they could do: no land to play and no spell or ability they
 * can both use right now and pay for. Decisions (blockers, targets, questions) are never skipped; only "you may
 * act now" prompts are.
 */
public final class AutoPass {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MTGCraft auto-pass");
        t.setDaemon(true);
        return t;
    });
    /** A short pause so the board can be seen before the game moves on. */
    private static final long DELAY_MS = 450;
    private static final ZoneType[] ZONES = {ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile,
            ZoneType.Command, ZoneType.Library};

    /** Players who turned auto-pass off (their client setting). Everyone else has it on. */
    private static final java.util.Set<java.util.UUID> OFF = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private AutoPass() {}

    /** A player's choice, sent by their client when it connects and whenever they change it. */
    public static void setEnabled(java.util.UUID player, boolean on) {
        if (on) OFF.remove(player);
        else OFF.add(player);
    }

    public static boolean enabled(java.util.UUID player) {
        return player == null || !OFF.contains(player);
    }

    /**
     * The phase bar's segments, in the order the client sends them as bits. A player can mark segments to skip: the
     * game then passes there for them (on every player's turn) unless something is on the stack.
     */
    public static final PhaseType[][] SEGMENTS = {
            {PhaseType.UNTAP, PhaseType.UPKEEP}, {PhaseType.DRAW}, {PhaseType.MAIN1}, {PhaseType.COMBAT_BEGIN},
            {PhaseType.COMBAT_DECLARE_ATTACKERS}, {PhaseType.COMBAT_DECLARE_BLOCKERS},
            {PhaseType.COMBAT_FIRST_STRIKE_DAMAGE, PhaseType.COMBAT_DAMAGE, PhaseType.COMBAT_END},
            {PhaseType.MAIN2}, {PhaseType.END_OF_TURN, PhaseType.CLEANUP}};
    private static final java.util.Map<java.util.UUID, Integer> SKIPS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Which phase-bar segments a player skips (bit i = {@link #SEGMENTS}[i]). */
    public static void setSkips(java.util.UUID player, int mask) {
        if (mask == 0) SKIPS.remove(player);
        else SKIPS.put(player, mask);
    }

    /** Hands the player's skipped phases to Card-Forge, which then passes there without asking. */
    private static void applySkips(PlayerControllerHuman controller, java.util.UUID player) {
        int mask = player == null ? 0 : SKIPS.getOrDefault(player, 0);
        var yields = controller.getYieldController();
        if (yields == null) return;
        for (Player turn : controller.getGame().getPlayers()) {
            for (int i = 0; i < SEGMENTS.length; i++) {
                boolean skip = (mask & (1 << i)) != 0;
                for (PhaseType ph : SEGMENTS[i]) {
                    if (yields.isSkippingPhase(turn.getView(), ph) != skip) yields.setSkipPhase(turn.getView(), ph, skip);
                }
            }
        }
    }

    /** Called when a prompt is shown to the player behind {@code controller}. */
    public static void onPrompt(PlayerControllerHuman controller, java.util.UUID player) {
        if (controller == null) return;
        try {
            applySkips(controller, player);
        } catch (RuntimeException ignored) {
            // the game is shutting down; nothing to skip
        }
        if (Boolean.getBoolean("mtgcraft.devDuel")) {
            try {
                System.out.println("[MTGCraft dev] prompt at " + controller.getGame().getPhaseHandler().getPhase()
                        + " turn of " + controller.getGame().getPhaseHandler().getPlayerTurn());
            } catch (RuntimeException ignored) {
                // no game yet
            }
        }
        Input input = controller.getInputQueue().getInput();
        List<Card> playable = null;
        if (input instanceof InputPassPriority) {
            try {
                playable = playable(controller.getPlayer());
            } catch (RuntimeException e) {
                // unsure: no highlight, and no auto-pass below
            }
        }
        // The cards this player can play right now glow on their screen (a highlight only, not a pick).
        try {
            var gui = controller.getGui();
            gui.clearWeaklySelectable();
            if (playable != null && !playable.isEmpty()) gui.setWeaklySelectable(playable.stream().map(Card::getView).toList());
        } catch (RuntimeException ignored) {
            // the game is shutting down
        }
        if (!enabled(player)) return;
        if (!(input instanceof InputPassPriority) || playable == null) return;
        if (stopsHere(controller, player)) return;
        if (!playable.isEmpty()) return;
        TIMER.schedule(() -> {
            if (controller.getInputQueue().getInput() == input) controller.selectButtonOk();
        }, DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /** Whether the player marked the current phase as a stop (bits 16+ of their mask): auto-pass never passes there. */
    private static boolean stopsHere(PlayerControllerHuman controller, java.util.UUID player) {
        int stops = player == null ? 0 : SKIPS.getOrDefault(player, 0) >>> 16;
        if (stops == 0) return false;
        PhaseType now = controller.getGame().getPhaseHandler().getPhase();
        for (int i = 0; i < SEGMENTS.length; i++) {
            if ((stops & (1 << i)) == 0) continue;
            for (PhaseType ph : SEGMENTS[i]) if (ph == now) return true;
        }
        return false;
    }

    /** Whether the player could play a land or use any spell or ability right now (and pay for it). */
    public static boolean hasMoves(Player p) {
        return !playable(p).isEmpty();
    }

    /** The cards with a land play, spell or ability the player could use right now (and pay for). */
    public static List<Card> playable(Player p) {
        List<Card> out = new java.util.ArrayList<>();
        for (ZoneType z : ZONES) {
            for (Card c : p.getCardsIn(z)) {
                for (SpellAbility sa : c.getAllPossibleAbilities(p, true)) {
                    if (sa.isManaAbility()) continue;
                    if (!sa.isLandAbility()) {
                        sa.setActivatingPlayer(p);
                        if (!ComputerUtilMana.canPayManaCost(sa, p, 0, false) || !ComputerUtilCost.canPayCost(sa, p, false)) continue;
                    }
                    out.add(c);
                    break;
                }
            }
        }
        return out;
    }
}

package dev.mtgcraft.engine.net;

import forge.ai.ComputerUtilCost;
import forge.ai.ComputerUtilMana;
import forge.game.card.Card;
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

    private AutoPass() {}

    /** Called when a prompt is shown to the player behind {@code controller}. */
    public static void onPrompt(PlayerControllerHuman controller) {
        if (controller == null) return;
        Input input = controller.getInputQueue().getInput();
        if (!(input instanceof InputPassPriority)) return;
        boolean moves;
        try {
            moves = hasMoves(controller.getPlayer());
        } catch (RuntimeException e) {
            return; // when unsure, let the player decide
        }
        if (moves) return;
        TIMER.schedule(() -> {
            if (controller.getInputQueue().getInput() == input) controller.selectButtonOk();
        }, DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /** Whether the player could play a land or use any spell or ability right now (and pay for it). */
    public static boolean hasMoves(Player p) {
        for (ZoneType z : ZONES) {
            for (Card c : p.getCardsIn(z)) {
                List<SpellAbility> abilities = c.getAllPossibleAbilities(p, true);
                for (SpellAbility sa : abilities) {
                    if (sa.isManaAbility()) continue;
                    if (sa.isLandAbility()) return true;
                    sa.setActivatingPlayer(p);
                    if (ComputerUtilMana.canPayManaCost(sa, p, 0, false) && ComputerUtilCost.canPayCost(sa, p, false)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}

package dev.mtgcraft.client;

import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.engine.ChoiceRequest;
import dev.mtgcraft.engine.DuelGui;
import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.combat.CombatView;
import forge.game.phase.PhaseType;
import forge.game.player.PlayerView;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The duel's sounds, played whether or not the cards are on screen (you can walk around a boss arena with them put
 * away): a bell when your turn starts, a soft close when it ends, a block bell when attackers are coming at you, and
 * a ping when the game asks you something. With the cards put away, the action bar also says what's waiting.
 */
@Mod.EventBusSubscriber(modid = MtgCraft.MODID, value = Dist.CLIENT)
public final class DuelAlerts {
    private static DuelGui tracked;
    private static int lastTurnPlayer = Integer.MIN_VALUE, blockAlertTurn = -1;
    private static ChoiceRequest alertedRequest;
    private static boolean wasWaitingOnMe;
    private static int nagTicks;

    private DuelAlerts() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        DuelGui duel = DuelGui.active();
        if (duel != tracked) {
            tracked = duel;
            lastTurnPlayer = Integer.MIN_VALUE;
            blockAlertTurn = -1;
            alertedRequest = null;
            wasWaitingOnMe = false;
        }
        if (duel == null || duel.isOver()) return;
        try {
            check(duel);
        } catch (RuntimeException notSyncedYet) {
            // game state still arriving
        }
    }

    private static void check(DuelGui duel) {
        GameView view = duel.getGameView();
        PlayerView me = duel.me();
        if (view == null || me == null || !duel.viewOpen) return;
        PlayerView turn = view.getPlayerTurn();
        int now = turn == null ? -1 : turn.getId();
        if (now != lastTurnPlayer) {
            if (lastTurnPlayer != Integer.MIN_VALUE) {
                if (now == me.getId()) {
                    Theme.play(SoundEvents.NOTE_BLOCK_BELL.value(), 1.0f, 1.0f);
                    Theme.play(SoundEvents.AMETHYST_BLOCK_CHIME, 1.2f, 1.0f);
                } else if (lastTurnPlayer == me.getId()) {
                    Theme.play(SoundEvents.BOOK_PUT, 0.7f, 1.0f);
                }
            }
            lastTurnPlayer = now;
        }
        boolean priority = duel.prompt != null && duel.prompt.startsWith("Priority");
        if (view.getPhase() == PhaseType.COMBAT_DECLARE_BLOCKERS && now != me.getId() && !priority && duel.okEnabled
                && defending(view, me) && blockAlertTurn != view.getTurn()) {
            blockAlertTurn = view.getTurn();
            Theme.play(SoundEvents.BELL_BLOCK, 1.3f, 1.0f);
        }
        ChoiceRequest req = duel.currentRequest();
        if (req != null && req != alertedRequest) {
            alertedRequest = req;
            Theme.play(SoundEvents.NOTE_BLOCK_PLING.value(), 1.6f, 0.8f);
        }

        // Cards put away (walking around the arena): say on the action bar when the game is waiting for you.
        Minecraft mc = Minecraft.getInstance();
        boolean waitingOnMe = req != null || duel.okEnabled || duel.cancelEnabled;
        if (!(mc.screen instanceof DuelScreen) && mc.player != null && waitingOnMe) {
            if (!wasWaitingOnMe) nagTicks = 0;
            if (nagTicks++ % 40 == 0) {
                String what = req != null ? "The duel has a question for you" : now == me.getId() ? "Your turn" : "Your move";
                mc.player.displayClientMessage(Component.literal(what + "! Right-click the air with the Duel Gauntlet to play.")
                        .withStyle(ChatFormatting.GOLD), true);
            }
        }
        wasWaitingOnMe = waitingOnMe;
    }

    /** Whether any attacker is coming at me (or my planeswalkers). */
    private static boolean defending(GameView view, PlayerView me) {
        CombatView combat = view.getCombat();
        if (combat == null) return false;
        for (CardView a : combat.getAttackers()) {
            GameEntityView d = combat.getDefender(a);
            if (d instanceof PlayerView p && p.getId() == me.getId()) return true;
            if (d instanceof CardView c && c.getController() != null && c.getController().getId() == me.getId()) return true;
        }
        return false;
    }
}

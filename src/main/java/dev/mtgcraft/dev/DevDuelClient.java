package dev.mtgcraft.dev;

import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.client.DuelScreen;
import dev.mtgcraft.engine.ChoiceRequest;
import dev.mtgcraft.engine.DuelGui;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.combat.CombatView;
import forge.game.phase.PhaseType;
import forge.game.player.PlayerView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Developer harness, client half (see {@link DevDuel}): while a duel screen is open, takes a screenshot every few
 * seconds and then answers whatever the game asks with a default, so the game moves on by itself.
 */
@Mod.EventBusSubscriber(modid = MtgCraft.MODID, value = Dist.CLIENT)
public final class DevDuelClient {
    private static final boolean ON = Boolean.getBoolean("mtgcraft.devDuel");
    private static int ticks, shots, hidden;
    /** -Dmtgcraft.devArena: keep the 3D arena view and also take shots without the duel screen. */
    private static final boolean ARENA = Boolean.getBoolean("mtgcraft.devArena");

    private DevDuelClient() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!ON || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (ARENA && hidden > 0) {
            // A clean shot of the 3D battlefield with the duel screen out of the way, then back to the cards.
            // Meanwhile walk forward, to check players can move around the arena.
            if (mc.player != null) {
                if (hidden == 12) walkFrom = mc.player.position();
                mc.options.keyUp.setDown(hidden > 2);
                if (hidden == 6) System.out.println("[MTGCraft dev] input forward=" + mc.player.input.forwardImpulse + " up=" + mc.player.input.up
                        + " keyUp=" + mc.options.keyUp.isDown() + " pos=" + mc.player.position());
                if (hidden == 1 && walkFrom != null) {
                    System.out.println("[MTGCraft dev] walked " + String.format("%.2f", mc.player.position().distanceTo(walkFrom))
                            + " blocks with the cards put away (flying=" + mc.player.getAbilities().flying + ")");
                }
            }
            if (hidden == 2 && mc.level != null && mc.player != null) {
                // Point the camera at the boss for the shot (alternate shots look at the field instead).
                net.minecraft.world.entity.Entity boss = null;
                for (var e : mc.level.entitiesForRendering()) {
                    if (e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon || e instanceof net.minecraft.world.entity.boss.wither.WitherBoss) boss = e;
                }
                if (boss != null && (shots / 3) % 2 == 0) {
                    net.minecraft.world.phys.Vec3 to = boss.position().subtract(mc.player.getEyePosition());
                    mc.player.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
                    mc.player.setXRot((float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z))) + 8);
                    System.out.println("[MTGCraft dev] boss at " + boss.position() + " yaw=" + boss.getYRot() + " player at " + mc.player.position());
                }
            }
            if (--hidden == 0) {
                Screenshot.grab(mc.gameDirectory, String.format("arena_%03d.png", shots++), mc.getMainRenderTarget(), msg -> {});
                dev.mtgcraft.client.ClientTables.reopenAny();
            }
            return;
        }
        if (!(mc.screen instanceof DuelScreen screen) || shots >= 120) return;
        if (++ticks % 50 != 0) return;
        if (ARENA && shots % 3 == 1) {
            mc.setScreen(null);
            hidden = 12;
            shots++;
            return;
        }
        DuelGui duel = DuelGui.active();
        Screenshot.grab(mc.gameDirectory, String.format("duel_%03d.png", shots++), mc.getMainRenderTarget(), msg -> {});
        if (Boolean.getBoolean("mtgcraft.devLose") && shots >= 10 && !winSent && duel != null && !duel.isOver()) {
            winSent = true;
            System.out.println("[MTGCraft dev] conceding to test the loss penalty");
            duel.concedeGame();
        }
        if (Boolean.getBoolean("mtgcraft.devWin") && shots >= 24 && !winSent && duel != null && !duel.isOver()
                && duel.prompt != null && duel.prompt.startsWith("Priority")) {
            winSent = true;
            // -Dmtgcraft.devWin: use the creative Win button, to check the victory path (drops, XP, rewards screen).
            System.out.println("[MTGCraft dev] pressing creative Win");
            dev.mtgcraft.net.Net.toServer(new dev.mtgcraft.net.Packets.CheatWin());
        }
        if (duel == null) return;
        // Every so often, show the zone viewer or the log for the next screenshot.
        if (shots % 9 == 3) { screen.keyPressed(GLFW.GLFW_KEY_Z, 0, 0); return; }
        if (shots % 9 == 4) { screen.keyPressed(GLFW.GLFW_KEY_Z, 0, 0); screen.keyPressed(GLFW.GLFW_KEY_L, 0, 0); return; }
        if (shots % 9 == 5) { screen.keyPressed(GLFW.GLFW_KEY_L, 0, 0); }
        if (shots == 2 && !ARENA) screen.keyPressed(GLFW.GLFW_KEY_V, 0, 0); // the 2D board shows more
        ChoiceRequest req = duel.currentRequest();
        if (req == null && act(duel)) return;
        if (req == null && duel.prompt != null && duel.prompt.contains("start this game") && duel.me() != null) {
            duel.clickPlayer(duel.me());
        } else if (req != null) {
            List<Integer> answer = new ArrayList<>();
            switch (req.kind) {
                case NUMBER -> answer.add(req.max);
                case DISTRIBUTE -> answer.addAll(req.initial);
                default -> {
                    int n = req.max == 0 ? 0 : Math.min(req.labels.size(), Math.max(1, req.min));
                    for (int i = 0; i < n; i++) answer.add(i);
                }
            }
            req.answer.complete(answer);
        } else if (duel.okEnabled) {
            duel.ok();
        } else if (duel.cancelEnabled) {
            duel.cancel(); // e.g. a spell it can't pay for
        }
    }

    private static int landTurn = -1, castTurn = -1;
    private static net.minecraft.world.phys.Vec3 walkFrom;
    private static boolean winSent;

    /** Plays like a beginner: a land, then a spell, attacks with everything, discards the first card. */
    private static boolean act(DuelGui duel) {
        GameView view = duel.getGameView();
        PlayerView me = duel.me();
        if (view == null || me == null || duel.prompt == null) return false;
        boolean myTurn = view.getPlayerTurn() != null && view.getPlayerTurn().getId() == me.getId();
        List<CardView> hand = new ArrayList<>();
        if (me.getHand() != null) me.getHand().forEach(hand::add);
        // Max 0 means Card-Forge is only highlighting playable cards, not asking for a pick.
        if (duel.isSelecting() && duel.getSelectionMax() > 0) {
            for (CardView c : hand) if (duel.isSelectable(c)) { duel.clickCard(c, 1); return true; }
        }
        boolean priority = duel.prompt.startsWith("Priority") && view.getStack().isEmpty();
        if (myTurn && priority && view.getPhase() == PhaseType.MAIN1) {
            if (landTurn != view.getTurn()) {
                landTurn = view.getTurn();
                for (CardView c : hand) if (c.getCurrentState().isLand()) { duel.clickCard(c, 1); return true; }
            }
            if (castTurn != view.getTurn()) {
                castTurn = view.getTurn();
                for (CardView c : hand) if (!c.getCurrentState().isLand()) { duel.clickCard(c, 1); return true; }
            }
        }
        if (myTurn && view.getPhase() == PhaseType.COMBAT_DECLARE_ATTACKERS && !duel.prompt.startsWith("Priority")) {
            CombatView combat = view.getCombat();
            boolean any = false;
            for (CardView c : me.getBattlefield()) {
                if (c.getCurrentState().isCreature() && !c.isTapped() && !c.isSick() && (combat == null || !combat.isAttacking(c))) {
                    duel.clickCard(c, 1);
                    any = true;
                }
            }
            return any;
        }
        return false;
    }
}

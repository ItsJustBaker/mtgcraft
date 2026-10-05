package dev.mtgcraft.test;

import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.engine.Cards;
import dev.mtgcraft.engine.ForgeEngine;
import dev.mtgcraft.engine.Packs;
import dev.mtgcraft.item.CardBag;
import dev.mtgcraft.item.CardItem;
import dev.mtgcraft.item.PackItem;
import dev.mtgcraft.net.Packets;
import dev.mtgcraft.server.CollectionOps;
import dev.mtgcraft.server.GauntletDuels;
import dev.mtgcraft.server.StarterKits;
import dev.mtgcraft.server.TableSessions;
import forge.deck.Deck;
import forge.item.PaperCard;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Server-side checks that run in Forge's GameTest server (gradlew runGameTestServer): packs, binders, deck boxes,
 * the starter kit and a full Duel Gauntlet challenge against real mobs. Each test first waits for Card-Forge.
 */
@GameTestHolder(MtgCraft.MODID)
@PrefixGameTestTemplate(false)
public class MtgGameTests {
    private static final int ENGINE_WAIT = 20_000;

    /**
     * The GameTest server ticks as fast as it can, so tick timeouts are a few seconds of real time. Card-Forge takes
     * longer than that to load, so the first check waits for it in real time (tests only).
     */
    private static boolean engineReady() {
        long until = System.currentTimeMillis() + 120_000;
        while (ForgeEngine.state() == ForgeEngine.State.LOADING || ForgeEngine.state() == ForgeEngine.State.IDLE) {
            if (System.currentTimeMillis() > until) break;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return ForgeEngine.state() == ForgeEngine.State.READY;
    }

    /** A fake player at the test arena (Forge 1.20.1's mock-player helper crashes, so Forge's FakePlayer is used). */
    private static ServerPlayer player(GameTestHelper h) {
        var profile = new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "Tester" + (int) (Math.random() * 1000));
        ServerPlayer p = net.minecraftforge.common.util.FakePlayerFactory.get(h.getLevel(), profile);
        var pos = h.absoluteVec(new net.minecraft.world.phys.Vec3(1.5, 1, 4.5));
        p.setPos(pos.x, pos.y, pos.z);
        dev.mtgcraft.server.DuelHosting.TEST_PLAYERS.put(p.getUUID(), p);
        return p;
    }

    private static int cardItems(Player p) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (s.getItem() instanceof CardItem) n += s.getCount();
        return n;
    }

    @GameTest(template = "arena", timeoutTicks = ENGINE_WAIT)
    public static void openThemedPack(GameTestHelper h) {
        h.assertTrue(engineReady(), "card engine not ready");
        h.succeedIf(() -> {
            ServerPlayer p = player(h);
            p.setItemInHand(InteractionHand.MAIN_HAND, PackItem.themed(Packs.Theme.UNDEAD, 1));
            p.getMainHandItem().use(h.getLevel(), p, InteractionHand.MAIN_HAND);
            h.assertTrue(p.getMainHandItem().isEmpty(), "pack was not used up");
            h.assertTrue(cardItems(p) == 15, "expected 15 cards from the pack, got " + cardItems(p));
        });
    }

    @GameTest(template = "arena", timeoutTicks = ENGINE_WAIT)
    public static void binderAndDeckBox(GameTestHelper h) {
        h.assertTrue(engineReady(), "card engine not ready");
        h.succeedIf(() -> {
            ServerPlayer p = player(h);
            List<Packs.Pull> pulls = Packs.openTheme(Packs.Theme.OVERWORLD);
            PackItem.giveCards(p, pulls);
            String key = Cards.key(pulls.get(1).card());
            boolean foil = pulls.get(1).foil();
            int before = cardItems(p);

            // File everything in a binder, then take one card back out.
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(MtgCraft.BINDER.get()));
            CollectionOps.handle(p, new Packets.CollectionOp(false, Packets.CollOp.BINDER_PUT_ALL, "", false, 0));
            ItemStack binder = p.getMainHandItem();
            h.assertTrue(cardItems(p) == 0, "cards left in inventory after filing: " + cardItems(p));
            h.assertTrue(CardBag.total(binder) == before, "binder holds " + CardBag.total(binder) + ", expected " + before);
            CollectionOps.handle(p, new Packets.CollectionOp(false, Packets.CollOp.BINDER_TAKE, key, foil, 1));
            h.assertTrue(cardItems(p) == 1, "taking a card out should give 1 card item");

            // Deck box: add that card (from the inventory) and 17 free basics, then remove the card again.
            ItemStack box = new ItemStack(MtgCraft.DECK_BOX.get());
            p.getInventory().add(binder.copy());
            p.setItemInHand(InteractionHand.MAIN_HAND, box);
            CollectionOps.handle(p, new Packets.CollectionOp(false, Packets.CollOp.DECK_ADD, key, foil, 1));
            CollectionOps.handle(p, new Packets.CollectionOp(false, Packets.CollOp.DECK_ADD, "Forest", false, 17));
            ItemStack deck = p.getMainHandItem();
            h.assertTrue(CardBag.total(deck) == 18, "deck should have 18 cards, has " + CardBag.total(deck));
            h.assertTrue(cardItems(p) == 0, "the added card should have left the inventory");
            CollectionOps.handle(p, new Packets.CollectionOp(false, Packets.CollOp.DECK_REMOVE, key, foil, 1));
            h.assertTrue(CardBag.total(deck) == 17, "removing should leave 17");
        });
    }

    @GameTest(template = "arena", timeoutTicks = ENGINE_WAIT)
    public static void starterKit(GameTestHelper h) {
        h.assertTrue(engineReady(), "card engine not ready");
        ServerPlayer[] p = {player(h)};
        StarterKits.choose(p[0], 6, "");
        h.succeedWhen(() -> {
            ItemStack box = ItemStack.EMPTY;
            for (ItemStack s : p[0].getInventory().items) if (s.is(MtgCraft.DECK_BOX.get())) box = s;
            h.assertTrue(!box.isEmpty(), "no deck box yet");
            h.assertTrue(CardBag.total(box) >= 40, "starter deck has " + CardBag.total(box) + " cards");
            boolean gauntlet = false, binder = false;
            for (ItemStack s : p[0].getInventory().items) {
                gauntlet |= s.is(MtgCraft.DUEL_GAUNTLET.get());
                binder |= s.is(MtgCraft.BINDER.get());
            }
            h.assertTrue(gauntlet && binder, "starter kit is missing the gauntlet or binder");
        });
    }

    /**
     * Challenge a zombie with two zombies and a skeleton nearby: all four mobs join (zombies on one team, the
     * skeleton on its own), everyone is frozen; then the player walks away (a forfeit), the game ends, the loss
     * penalty applies and the mobs move again.
     */
    @GameTest(template = "arena", timeoutTicks = 400_000)
    public static void gauntletChallenge(GameTestHelper h) {
        h.assertTrue(engineReady(), "card engine not ready");
        ServerPlayer p = player(h);
        Deck deck = Packs.themeDeck(Packs.Theme.VILLAGE, 6, "Test deck");
        ItemStack box = new ItemStack(MtgCraft.DECK_BOX.get());
        for (Map.Entry<PaperCard, Integer> e : deck.getMain()) CardBag.add(box, Cards.key(e.getKey()), false, e.getValue());
        p.getInventory().add(box);
        TableSessions.engineReady(p);
        Mob zombie = h.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(5, 1, 4));
        h.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(6, 1, 3));
        h.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(6, 1, 5));
        h.spawnWithNoFreeWill(EntityType.SKELETON, new BlockPos(7, 1, 7));
        // spawnWithNoFreeWill sets NoAI; give them their AI back so the duel freeze is what we observe.
        for (Mob m : h.getLevel().getEntitiesOfClass(Mob.class, zombie.getBoundingBox().inflate(10))) m.setNoAi(false);
        GauntletDuels.challenge(p, zombie);
        h.assertTrue(GauntletDuels.inDuel(p.getUUID()), "player should be in a duel (deck " + CardBag.total(box) + " cards)");
        h.assertTrue(GauntletDuels.inDuel(zombie.getUUID()) && zombie.isNoAi(), "zombie should be frozen in the duel");
        // Walk far away: a forfeit. The game must end, apply the loss and unfreeze the mobs.
        p.setPos(p.getX() + 100, p.getY(), p.getZ());
        h.succeedWhen(() -> {
            h.assertTrue(!GauntletDuels.inDuel(p.getUUID()), "duel still running");
            h.assertTrue(!GauntletDuels.inDuel(zombie.getUUID()), "zombie still marked in duel");
            h.assertTrue(!zombie.isNoAi(), "zombie should be unfrozen after the duel");
        });
    }

}

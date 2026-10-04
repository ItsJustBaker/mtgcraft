package dev.mtgcraft.server;

import dev.mtgcraft.MtgConfig;
import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.engine.Cards;
import dev.mtgcraft.engine.ForgeEngine;
import dev.mtgcraft.engine.Packs;
import dev.mtgcraft.item.CardBag;
import dev.mtgcraft.item.PackItem;
import dev.mtgcraft.net.Net;
import dev.mtgcraft.net.Packets;
import forge.deck.Deck;
import forge.deck.DeckgenUtil;
import forge.item.PaperCard;
import forge.model.FModel;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

/** The one-time starter: pick a two-colour deck, get it in a Deck Box with a Binder, a Duel Gauntlet and packs. */
public final class StarterKits {
    public static final String[] NAMES = {"Selesnya Pack (white/green)", "Azorius Skies (white/blue)",
            "Dimir Shadows (blue/black)", "Rakdos Fire (black/red)", "Gruul Beasts (red/green)"};
    private static final String[][] COLORS = {{"White", "Green"}, {"White", "Blue"}, {"Blue", "Black"},
            {"Black", "Red"}, {"Red", "Green"}};
    private static final String TAG = "mtgcraft_starter";

    private StarterKits() {}

    private static CompoundTag persisted(Player p) {
        CompoundTag data = p.getPersistentData();
        CompoundTag kept = data.getCompound(Player.PERSISTED_NBT_TAG);
        data.put(Player.PERSISTED_NBT_TAG, kept);
        return kept;
    }

    /** Called once the player's client has the card engine loaded. */
    public static void maybeOffer(ServerPlayer p) {
        if (!MtgConfig.STARTER_KIT.get() || persisted(p).getBoolean(TAG)) return;
        if (ForgeEngine.state() != ForgeEngine.State.READY) return;
        Net.toPlayer(p, new Packets.OfferStarter(List.of(NAMES)));
    }

    public static void choose(ServerPlayer p, int choice) {
        if (persisted(p).getBoolean(TAG) || choice < 0 || choice >= COLORS.length) return;
        if (ForgeEngine.state() != ForgeEngine.State.READY) return;
        persisted(p).putBoolean(TAG, true);
        var server = p.getServer();
        ForgeEngine.runOnUi(() -> {
            Deck deck = DeckgenUtil.buildColorDeck(List.of(COLORS[choice]),
                    FModel.getFormats().getModern().getFilterPrinted(), false);
            server.execute(() -> give(p, deck, NAMES[choice]));
        });
    }

    private static void give(ServerPlayer p, Deck deck, String name) {
        ItemStack box = new ItemStack(MtgCraft.DECK_BOX.get());
        for (Map.Entry<PaperCard, Integer> e : deck.getMain()) {
            CardBag.add(box, Cards.key(e.getKey()), false, e.getValue());
        }
        give(p, box);
        give(p, new ItemStack(MtgCraft.BINDER.get()));
        give(p, new ItemStack(MtgCraft.DUEL_GAUNTLET.get()));
        give(p, PackItem.themed(Packs.Theme.OVERWORLD, 3));
        p.displayClientMessage(Component.literal("Your starter deck: " + name + ". Right-click a mob with the Duel Gauntlet to duel!")
                .withStyle(ChatFormatting.GOLD), false);
    }

    private static void give(ServerPlayer p, ItemStack s) {
        if (!p.getInventory().add(s)) p.drop(s, false);
    }
}

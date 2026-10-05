package dev.mtgcraft.server;

import dev.mtgcraft.MtgConfig;
import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.engine.Cards;
import dev.mtgcraft.engine.ForgeEngine;
import dev.mtgcraft.engine.Packs;
import dev.mtgcraft.engine.Tribal;
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

/**
 * The one-time starter: pick a colour pair and get a 100-card Commander deck (a legendary commander of those colours
 * with a deck built around it) in a Deck Box, plus a Binder, a Duel Gauntlet and packs.
 */
public final class StarterKits {
    /** The five classic pairs offered first (more pairs come from the quiz). Names are for the offer packet. */
    public static final String[] NAMES = {"Selesnya Commander (white/green)", "Azorius Commander (white/blue)",
            "Dimir Commander (blue/black)", "Rakdos Commander (black/red)", "Gruul Commander (red/green)"};
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

    /**
     * @param colors bit mask of the deck's colours (W=1 U=2 B=4 R=8 G=16), used when {@code tribe} is empty
     * @param tribe  a creature type from {@link Tribal#TRIBES}, or empty
     */
    public static void choose(ServerPlayer p, int colors, String tribe) {
        if (persisted(p).getBoolean(TAG)) return;
        boolean byType = tribe != null && !tribe.isEmpty();
        if (byType ? !Tribal.known(tribe) : Integer.bitCount(colors & 0x1F) == 0 || Integer.bitCount(colors & 0x1F) > 2) return;
        if (ForgeEngine.state() != ForgeEngine.State.READY) return;
        persisted(p).putBoolean(TAG, true);
        var server = p.getServer();
        ForgeEngine.runOnUi(() -> {
            Deck deck;
            String name;
            if (byType) {
                deck = Tribal.commanderDeck(tribe, false);
                name = deck.getName();
            } else {
                byte mask = (byte) (colors & 0x1F);
                PaperCard commander = randomCommander(mask);
                deck = commander == null ? null
                        : DeckgenUtil.generateRandomCommanderDeck(commander, forge.deck.DeckFormat.Commander, false, false);
                if (deck == null) deck = DeckgenUtil.buildColorDeck(colorNames(mask), FModel.getFormats().getModern().getFilterPrinted(), false);
                name = (commander == null ? "" : commander.getName() + " ") + "(" + guild(mask) + ")";
            }
            Deck built = deck;
            server.execute(() -> give(p, built, name));
        });
    }

    private static List<String> colorNames(byte mask) {
        List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) if ((mask & forge.card.MagicColor.WUBRG[i]) != 0) out.add(dev.mtgcraft.engine.Duels.COLORS.get(i));
        return out;
    }

    /** "Boros, white/red" for a colour pair. */
    public static String guild(int mask) {
        String[] names = {"Azorius", "Dimir", "Rakdos", "Gruul", "Selesnya", "Orzhov", "Izzet", "Golgari", "Boros", "Simic"};
        int[] masks = {3, 6, 12, 24, 17, 5, 10, 20, 9, 18};
        String colors = String.join("/", colorNames((byte) mask)).toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < masks.length; i++) if (masks[i] == mask) return names[i] + ", " + colors;
        return colors;
    }

    /** A random legendary creature whose colours are exactly the pair (Modern-legal printings first). */
    private static PaperCard randomCommander(byte mask) {
        java.util.List<PaperCard> picks = new java.util.ArrayList<>();
        var modern = FModel.getFormats().getModern().getFilterPrinted();
        for (PaperCard pc : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
            var r = pc.getRules();
            if (!r.canBeCommander() || !r.getType().isCreature() || pc.getName().startsWith("A-")) continue;
            if (r.getColor().getColor() != mask || !modern.test(pc)) continue;
            picks.add(pc);
        }
        return picks.isEmpty() ? null : picks.get(new java.util.Random().nextInt(picks.size()));
    }

    private static void give(ServerPlayer p, Deck deck, String name) {
        ItemStack box = new ItemStack(MtgCraft.DECK_BOX.get());
        for (Map.Entry<PaperCard, Integer> e : deck.getMain()) {
            CardBag.add(box, Cards.key(e.getKey()), false, e.getValue());
        }
        if (deck.has(forge.deck.DeckSection.Commander)) {
            for (Map.Entry<PaperCard, Integer> e : deck.get(forge.deck.DeckSection.Commander)) {
                String key = Cards.key(e.getKey());
                CardBag.add(box, key, false, e.getValue());
                dev.mtgcraft.item.DeckBoxItem.setCommander(box, key);
            }
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

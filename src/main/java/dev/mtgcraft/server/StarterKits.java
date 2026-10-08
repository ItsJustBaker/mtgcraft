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
    /** Set once the player has been given their Starter Kit item. */
    private static final String TAG_KIT = "mtgcraft_starter_kit";
    /** Set while the player still has a free glove-or-disk pick waiting. */
    static final String TAG_STYLE = "mtgcraft_style_pick";

    /** The glove-or-disk pick from the starter menu (once). */
    public static int pickStyle(ServerPlayer p, boolean disk) {
        if (!persisted(p).getBoolean(TAG_STYLE)) return 0;
        persisted(p).remove(TAG_STYLE);
        give(p, new ItemStack(disk ? MtgCraft.DUEL_DISK.get() : MtgCraft.DUEL_GAUNTLET.get()));
        return 1;
    }

    private StarterKits() {}

    private static CompoundTag persisted(Player p) {
        CompoundTag data = p.getPersistentData();
        CompoundTag kept = data.getCompound(Player.PERSISTED_NBT_TAG);
        data.put(Player.PERSISTED_NBT_TAG, kept);
        return kept;
    }

    /** Called once the player's client has the card engine loaded. */
    public static void maybeOffer(ServerPlayer p) {
        if (persisted(p).getBoolean(TAG_STYLE)) {
            Net.toPlayer(p, new Packets.Prompt("Pick your dueling gear", "Both work the same. You can craft the other one later.",
                    List.of("Duel Gauntlet", "Duel Disk"), List.of("/mtgduel style glove", "/mtgduel style disk"), 0));
        }
        // No menu forced on anyone: new players get a Starter Kit item (once) and open it when they like.
        if (!MtgConfig.STARTER_KIT.get() || persisted(p).getBoolean(TAG) || persisted(p).getBoolean(TAG_KIT)) return;
        persisted(p).putBoolean(TAG_KIT, true);
        give(p, new net.minecraft.world.item.ItemStack(dev.mtgcraft.MtgCraft.STARTER_KIT.get()));
        p.displayClientMessage(Component.literal("You got an MTGCraft Starter Kit: right-click it to pick your first deck.")
                .withStyle(net.minecraft.ChatFormatting.GOLD), false);
    }

    /** Right-clicking a Starter Kit: show the starter deck menu. */
    public static void open(ServerPlayer p) {
        if (persisted(p).getBoolean(TAG)) {
            p.displayClientMessage(Component.literal("You already picked your starter deck."), true);
            return;
        }
        if (ForgeEngine.state() != ForgeEngine.State.READY) {
            p.displayClientMessage(Component.literal("The card engine is still loading..."), true);
            return;
        }
        Net.toPlayer(p, new Packets.OfferStarter(List.of(NAMES)));
    }

    /** Uses up one Starter Kit from the inventory; false if the player has none. */
    private static boolean useKit(ServerPlayer p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(dev.mtgcraft.MtgCraft.STARTER_KIT.get())) {
                inv.getItem(i).shrink(1);
                return true;
            }
        }
        return false;
    }

    /**
     * @param colors bit mask of the deck's colours (W=1 U=2 B=4 R=8 G=16), used when {@code tribe} is empty
     * @param tribe  a creature type from {@link Tribal#TRIBES}, or empty
     */
    public static void choose(ServerPlayer p, int colors, String tribe) {
        if (persisted(p).getBoolean(TAG)) return;
        boolean precon = "*precon".equals(tribe);
        boolean byType = !precon && tribe != null && !tribe.isEmpty();
        if (!precon && (byType ? !Tribal.known(tribe) : Integer.bitCount(colors & 0x1F) == 0 || Integer.bitCount(colors & 0x1F) > 2)) return;
        if (ForgeEngine.state() != ForgeEngine.State.READY) return;
        if (!useKit(p)) return;
        persisted(p).putBoolean(TAG, true);
        var server = p.getServer();
        ForgeEngine.runOnUi(() -> {
            Deck deck;
            String name;
            Deck real = precon ? realPrecon(-1) : byType ? null : realPrecon(colors & 0x1F);
            if (real != null) {
                deck = real;
                name = real.getName();
            } else if (byType) {
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

    /** Colour identity of each real Commander precon (worked out once). */
    private static java.util.Map<String, Integer> preconColors;

    /**
     * A real (Wizards-made) Commander precon: one whose colours are exactly {@code mask}, or any one for -1. These are
     * solid, tested decks, which makes better starters than generated ones. Null when none fits.
     */
    private static Deck realPrecon(int mask) {
        try {
            if (preconColors == null) {
                java.util.Map<String, Integer> m = new java.util.HashMap<>();
                for (String n : dev.mtgcraft.engine.Decks.commanderPrecons()) {
                    Deck d = dev.mtgcraft.engine.Decks.commanderPrecon(n);
                    if (d == null || d.getCommanders().isEmpty()) continue;
                    int c = 0;
                    for (PaperCard cmd : d.getCommanders()) c |= cmd.getRules().getColorIdentity().getColor();
                    m.put(n, c);
                }
                preconColors = m;
            }
            List<String> fits = new java.util.ArrayList<>();
            for (var e : preconColors.entrySet()) if (mask < 0 || e.getValue() == mask) fits.add(e.getKey());
            if (fits.isEmpty()) return null;
            return dev.mtgcraft.engine.Decks.commanderPrecon(fits.get(new java.util.Random().nextInt(fits.size())));
        } catch (RuntimeException e) {
            return null;
        }
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
        // Glove or Duel Disk: the player picks (see GauntletDuels: /mtgduel style).
        persisted(p).putBoolean(TAG_STYLE, true);
        Net.toPlayer(p, new Packets.Prompt("Pick your dueling gear", "Both work the same. You can craft the other one later.",
                List.of("Duel Gauntlet", "Duel Disk"), List.of("/mtgduel style glove", "/mtgduel style disk"), 0));
        give(p, PackItem.themed(Packs.Theme.OVERWORLD, 3));
        p.displayClientMessage(Component.literal("Your starter deck: " + name + ". Right-click a mob with the Duel Gauntlet to duel!")
                .withStyle(ChatFormatting.GOLD), false);
    }

    private static void give(ServerPlayer p, ItemStack s) {
        if (!p.getInventory().add(s)) p.drop(s, false);
    }
}

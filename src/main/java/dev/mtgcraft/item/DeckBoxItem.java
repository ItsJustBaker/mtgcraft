package dev.mtgcraft.item;

import dev.mtgcraft.engine.Cards;
import dev.mtgcraft.engine.Decks;
import forge.deck.Deck;
import forge.item.PaperCard;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import java.util.List;

/**
 * Holds one built deck (the real cards, moved in from your inventory or binders). Bring it along with a Duel
 * Gauntlet to challenge mobs. Survives death.
 */
public class DeckBoxItem extends Item {
    public static final int MIN_CARDS = 40;
    /** A Commander deck: the commander plus 99 cards. */
    public static final int COMMANDER_CARDS = 100;
    public static final String[] BASICS = {"Plains", "Island", "Swamp", "Mountain", "Forest"};
    private static final String TAG_COMMANDER = "Commander";

    /** The commander's card key, or null. */
    public static String commander(ItemStack box) {
        var tag = box.getTag();
        return tag == null || !tag.contains(TAG_COMMANDER) ? null : tag.getString(TAG_COMMANDER);
    }

    public static void setCommander(ItemStack box, String key) {
        if (key == null) {
            if (box.getTag() != null) box.getTag().remove(TAG_COMMANDER);
        } else {
            box.getOrCreateTag().putString(TAG_COMMANDER, key);
        }
    }

    public DeckBoxItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> dev.mtgcraft.client.DeckBuilderScreen.open(hand));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public Component getName(ItemStack stack) {
        int n = CardBag.total(stack);
        return n == 0 ? super.getName(stack) : Component.literal("Deck Box (" + n + " cards)");
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
        int n = CardBag.total(stack);
        lines.add(Component.literal(n >= MIN_CARDS ? "Ready to duel" : "Needs " + (MIN_CARDS - n) + " more cards to duel")
                .withStyle(n >= MIN_CARDS ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        String cmd = commander(stack);
        if (cmd != null) lines.add(Component.literal("Commander: " + Cards.name(cmd)).withStyle(ChatFormatting.GOLD));
        lines.add(Component.literal("Right-click to build").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** Free, unlimited basic lands: the deck box keys for them, as Card-Forge's default printings. */
    public static boolean isBasicKey(String key) {
        String name = Cards.name(key);
        for (String b : BASICS) if (b.equals(name)) return true;
        return false;
    }

    /** The deck as Card-Forge .dck text, for a duel. Needs the card engine. */
    public static String deckText(ItemStack box, String name) throws Exception {
        Deck deck = new Deck(name);
        String commander = commander(box);
        boolean placed = false;
        for (CardBag.Entry e : CardBag.entries(box)) {
            PaperCard pc = Cards.card(e.key());
            if (pc == null) continue;
            PaperCard card = e.foil() ? pc.getFoiled() : pc;
            int n = e.count();
            if (!placed && e.key().equals(commander)) {
                // One copy leads the deck from the command zone.
                deck.getOrCreate(forge.deck.DeckSection.Commander).add(card, 1);
                placed = true;
                n--;
            }
            if (n > 0) deck.getMain().add(card, n);
        }
        return Decks.toText(deck);
    }

    /** The first usable deck: a Deck Box with 40+ cards or a filled Universal Deck Box; offhand first. */
    public static ItemStack find(Player player) {
        ItemStack off = player.getOffhandItem();
        if (usable(off)) return off;
        for (ItemStack s : player.getInventory().items) if (usable(s)) return s;
        return ItemStack.EMPTY;
    }

    private static boolean usable(ItemStack s) {
        if (s.getItem() instanceof DeckBoxItem) return CardBag.total(s) >= MIN_CARDS;
        return s.getItem() instanceof UniversalDeckBoxItem && UniversalDeckBoxItem.choice(s) != null;
    }

    /** The deck in a usable box as a duel deck choice. */
    public static dev.mtgcraft.engine.DeckChoice choiceOf(ItemStack box, String name) throws Exception {
        if (box.getItem() instanceof UniversalDeckBoxItem) return UniversalDeckBoxItem.choice(box);
        return dev.mtgcraft.engine.DeckChoice.inline(deckText(box, name));
    }
}

package dev.mtgcraft.item;

import dev.mtgcraft.engine.DeckChoice;
import dev.mtgcraft.net.Packets;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * The Duel Familiar: an AI teammate. Right-click it while holding a deck box in the other hand to teach it that deck;
 * while it's in your inventory, it fights on your side in mob duels (one familiar per duel).
 */
public class FamiliarOrbItem extends Item {
    private static final String TAG_DECK = "FamiliarDeck";
    private static final String TAG_NAME = "FamiliarDeckName";

    public FamiliarOrbItem(Properties props) {
        super(props);
    }

    /** The familiar's deck, or null if it hasn't learned one. */
    public static DeckChoice deck(ItemStack orb) {
        if (orb.getTag() == null || !orb.getTag().contains(TAG_DECK)) return null;
        return Packets.decode(orb.getTag().getString(TAG_DECK));
    }

    /** The first loaded familiar the player carries, or EMPTY. */
    public static ItemStack find(Player p) {
        if (p.getOffhandItem().getItem() instanceof FamiliarOrbItem && deck(p.getOffhandItem()) != null) return p.getOffhandItem();
        for (ItemStack s : p.getInventory().items) if (s.getItem() instanceof FamiliarOrbItem && deck(s) != null) return s;
        return ItemStack.EMPTY;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack orb = player.getItemInHand(hand);
        ItemStack other = player.getItemInHand(hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        if (!level.isClientSide) {
            if (!DeckBoxItem.usableBox(other)) {
                player.displayClientMessage(Component.literal("Hold a ready deck box in your other hand to teach the familiar a deck."), true);
            } else {
                try {
                    DeckChoice c = DeckBoxItem.choiceOf(other, player.getGameProfile().getName() + "'s familiar");
                    orb.getOrCreateTag().putString(TAG_DECK, Packets.encode(c));
                    orb.getOrCreateTag().putString(TAG_NAME, other.getHoverName().getString());
                    player.displayClientMessage(Component.literal("Your familiar learned " + other.getHoverName().getString() + ".").withStyle(ChatFormatting.LIGHT_PURPLE), true);
                } catch (Exception e) {
                    player.displayClientMessage(Component.literal("The familiar couldn't read that deck."), true);
                }
            }
        }
        return InteractionResultHolder.sidedSuccess(orb, level.isClientSide);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return deck(stack) != null;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
        if (stack.getTag() != null && stack.getTag().contains(TAG_NAME)) {
            lines.add(Component.literal("Deck: " + stack.getTag().getString(TAG_NAME)).withStyle(ChatFormatting.LIGHT_PURPLE));
            lines.add(Component.literal("Fights on your side in mob duels").withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(Component.literal("Hold a deck box in your other hand and right-click to teach it a deck").withStyle(ChatFormatting.GRAY));
        }
    }
}

package dev.mtgcraft.item;

import dev.mtgcraft.engine.DeckChoice;
import dev.mtgcraft.net.Packets;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
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
 * A deck box that holds any deck without owning the cards: a precon, a colour-generated deck, or your own list
 * (pasted or from the decks folder). Works with the Duel Gauntlet just like a normal Deck Box.
 */
public class UniversalDeckBoxItem extends Item {
    private static final String TAG_DECK = "Deck";
    private static final String TAG_LABEL = "Label";

    public UniversalDeckBoxItem(Properties props) {
        super(props);
    }

    public static DeckChoice choice(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? null : Packets.decode(tag.getString(TAG_DECK));
    }

    public static void set(ItemStack stack, String encodedChoice, String label) {
        stack.getOrCreateTag().putString(TAG_DECK, encodedChoice);
        stack.getOrCreateTag().putString(TAG_LABEL, label);
    }

    public static String label(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? "" : tag.getString(TAG_LABEL);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> dev.mtgcraft.client.ClientHooks.pickUniversalDeck(hand));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public Component getName(ItemStack stack) {
        String label = label(stack);
        return label.isEmpty() ? super.getName(stack)
                : Component.literal("Universal Deck Box: " + label).withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal(choice(stack) == null ? "Empty: right-click to choose a deck" : "Ready to duel")
                .withStyle(choice(stack) == null ? ChatFormatting.YELLOW : ChatFormatting.GREEN));
        lines.add(Component.literal("Precons, generated decks or a pasted list").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return choice(stack) != null;
    }
}

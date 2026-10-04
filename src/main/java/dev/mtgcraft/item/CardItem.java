package dev.mtgcraft.item;

import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.engine.Cards;
import forge.item.PaperCard;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import java.util.List;
import java.util.function.Consumer;

/**
 * One physical Magic card. The printing lives in NBT ("Name|SET|number"), plus a copy of the name, rarity, type
 * and cost so tooltips work even before the card engine has loaded. Identical cards stack; foils shimmer.
 */
public class CardItem extends Item {
    public static final String TAG_CARD = "Card";
    public static final String TAG_FOIL = "Foil";
    private static final String TAG_NAME = "Name";
    private static final String TAG_RARITY = "Rarity";
    private static final String TAG_TYPE = "Type";
    private static final String TAG_COST = "Cost";

    public CardItem(Properties props) {
        super(props);
    }

    public static ItemStack of(PaperCard pc, boolean foil, int count) {
        ItemStack stack = new ItemStack(MtgCraft.CARD.get(), count);
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(TAG_CARD, Cards.key(pc));
        if (foil) tag.putBoolean(TAG_FOIL, true);
        tag.putString(TAG_NAME, pc.getName());
        tag.putString(TAG_RARITY, String.valueOf(Cards.rarity(pc)));
        tag.putString(TAG_TYPE, Cards.typeLine(pc));
        tag.putString(TAG_COST, Cards.manaCost(pc));
        return stack;
    }

    public static String key(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? null : tag.getString(TAG_CARD);
    }

    public static boolean foil(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.getBoolean(TAG_FOIL);
    }

    public static char rarity(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        String r = tag == null ? "" : tag.getString(TAG_RARITY);
        return r.isEmpty() ? 'C' : r.charAt(0);
    }

    public static ChatFormatting rarityColor(char r) {
        return switch (r) {
            case 'U' -> ChatFormatting.AQUA;
            case 'R' -> ChatFormatting.GOLD;
            case 'M' -> ChatFormatting.RED;
            case 'S' -> ChatFormatting.LIGHT_PURPLE;
            default -> ChatFormatting.WHITE;
        };
    }

    public static String rarityName(char r) {
        return switch (r) {
            case 'U' -> "Uncommon";
            case 'R' -> "Rare";
            case 'M' -> "Mythic Rare";
            case 'S' -> "Special";
            case 'L' -> "Basic Land";
            default -> "Common";
        };
    }

    @Override
    public Component getName(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_NAME)) return super.getName(stack);
        Component name = Component.literal(tag.getString(TAG_NAME)).withStyle(rarityColor(rarity(stack)));
        return foil(stack) ? Component.literal("✦ ").withStyle(ChatFormatting.LIGHT_PURPLE).append(name) : name;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return;
        if (!tag.getString(TAG_COST).isEmpty()) lines.add(Component.literal(tag.getString(TAG_COST)).withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal(tag.getString(TAG_TYPE)).withStyle(ChatFormatting.GRAY));
        String key = tag.getString(TAG_CARD);
        String[] parts = key.split("\\|");
        String set = parts.length > 1 ? parts[1] + (parts.length > 2 ? " #" + parts[2] : "") : "";
        lines.add(Component.literal(rarityName(rarity(stack)) + (set.isEmpty() ? "" : " · " + set))
                .withStyle(rarityColor(rarity(stack))));
        if (foil(stack)) lines.add(Component.literal("Foil").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.ITALIC));
    }

    /** Foils get the enchantment shimmer. */
    @Override
    public boolean isFoil(ItemStack stack) {
        return foil(stack);
    }

    @Override
    public Rarity getRarity(ItemStack stack) {
        return switch (rarity(stack)) {
            case 'U' -> Rarity.UNCOMMON;
            case 'R' -> Rarity.RARE;
            case 'M' -> Rarity.EPIC;
            default -> Rarity.COMMON;
        };
    }

    /** Draws the real card art in hand, on the ground and in item frames. */
    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return dev.mtgcraft.client.CardItemRenderer.get();
            }
        });
    }
}

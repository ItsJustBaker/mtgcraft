package dev.mtgcraft.item;

import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.engine.Packs;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * A sealed booster box. Right-click to crack it: every box holds 16 packs.
 */
public class BoxItem extends Item {
    public static final int PACKS = 16;

    private static int packs(ItemStack stack) {
        return PACKS;
    }

    public BoxItem(Properties props) {
        super(props);
    }

    public static ItemStack themed(Packs.Theme theme) {
        ItemStack s = new ItemStack(MtgCraft.BOOSTER_BOX.get());
        s.getOrCreateTag().putString(PackItem.TAG_THEME, theme.name());
        return s;
    }

    public static ItemStack ofSet(String setCode) {
        ItemStack s = new ItemStack(MtgCraft.BOOSTER_BOX.get());
        s.getOrCreateTag().putString(PackItem.TAG_SET, setCode);
        return s;
    }

    @Override
    public Component getName(ItemStack stack) {
        Packs.Theme t = PackItem.theme(stack);
        if (t != null) return Component.literal(t.isBox() ? t.label : t.label.replace(" Pack", "") + " Booster Box");
        String set = PackItem.set(stack);
        if (set == null) return super.getName(stack);
        String name = dev.mtgcraft.engine.ForgeEngine.state() == dev.mtgcraft.engine.ForgeEngine.State.READY
                ? Packs.setName(set) : set;
        return Component.literal(name + " Booster Box");
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal(packs(stack) + " sealed packs. Right-click to open.").withStyle(ChatFormatting.GRAY));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.success(stack);
        int count = packs(stack);
        ItemStack packs = new ItemStack(MtgCraft.BOOSTER_PACK.get(), count);
        // Only the theme or set carries over (not the box's stack size, name or other extras).
        if (PackItem.theme(stack) != null) packs.getOrCreateTag().putString(PackItem.TAG_THEME, PackItem.theme(stack).name());
        else if (PackItem.set(stack) != null) packs.getOrCreateTag().putString(PackItem.TAG_SET, PackItem.set(stack));
        if (!player.getAbilities().instabuild) stack.shrink(1);
        // Into the inventory, the rest at the player's feet (never lost when the inventory is full).
        net.minecraftforge.items.ItemHandlerHelper.giveItemToPlayer(player, packs);
        level.playSound(null, player.blockPosition(), SoundEvents.CHEST_OPEN, SoundSource.PLAYERS, 1f, 1.2f);
        player.displayClientMessage(Component.literal("Cracked open a box: " + count + " packs!").withStyle(ChatFormatting.GOLD), true);
        return InteractionResultHolder.consume(stack);
    }
}

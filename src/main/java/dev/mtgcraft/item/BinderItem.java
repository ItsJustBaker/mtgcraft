package dev.mtgcraft.item;

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

/** A card binder: page through your collection, file cards in, take them out to trade or frame. */
public class BinderItem extends Item {
    public BinderItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            // Shift + right-click: file every loose card in the inventory into this binder.
            if (!level.isClientSide) {
                int filed = 0;
                var inv = player.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack s = inv.getItem(i);
                    if (s.getItem() instanceof CardItem && CardItem.key(s) != null) {
                        CardBag.add(stack, CardItem.key(s), CardItem.foil(s), s.getCount());
                        filed += s.getCount();
                        inv.setItem(i, ItemStack.EMPTY);
                    }
                }
                player.displayClientMessage(Component.literal(filed == 0 ? "No loose cards to file."
                        : "Filed " + filed + " card" + (filed == 1 ? "" : "s") + " into the binder.").withStyle(ChatFormatting.GOLD), true);
                if (filed > 0) level.playSound(null, player.blockPosition(), net.minecraft.sounds.SoundEvents.BOOK_PAGE_TURN,
                        net.minecraft.sounds.SoundSource.PLAYERS, 1f, 1f);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> dev.mtgcraft.client.BinderScreen.open(hand));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
        int total = CardBag.total(stack);
        int unique = CardBag.entries(stack).size();
        lines.add(Component.literal(total + " cards (" + unique + " different)").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Right-click to open").withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.literal("Shift + right-click to file all loose cards").withStyle(ChatFormatting.DARK_GRAY));
    }
}

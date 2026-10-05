package dev.mtgcraft.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The Duel Gauntlet. Right-click a mob to challenge it to a duel with the deck in your Deck Box; nearby mobs join
 * in. Right-click the air during a duel to get back to the cards.
 */
public class GauntletItem extends Item {
    private static final String TAG_GROUP = "GroupFights";

    public GauntletItem(Properties props) {
        super(props);
    }

    /** Group fights: nearby mobs and friends join. Off by default (1v1 duels). */
    public static boolean groupFights(ItemStack stack) {
        if (!(stack.getItem() instanceof GauntletItem)) return false;
        if (stack.getTag() != null && stack.getTag().contains(TAG_GROUP)) return stack.getTag().getBoolean(TAG_GROUP);
        try {
            return dev.mtgcraft.MtgConfig.GROUP_FIGHTS.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (player.level().isClientSide) return InteractionResult.SUCCESS;
        if (target instanceof Mob mob && player instanceof ServerPlayer sp) {
            dev.mtgcraft.server.GauntletDuels.challenge(sp, mob);
        } else if (target instanceof ServerPlayer other && player instanceof ServerPlayer sp) {
            // Another player: join their mob duel, or challenge them to a friendly duel.
            dev.mtgcraft.server.GauntletDuels.invitePlayer(sp, other);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            // Shift + right-click in the air: switch between 1v1 duels and group fights.
            if (!level.isClientSide) {
                boolean group = !groupFights(held);
                held.getOrCreateTag().putBoolean(TAG_GROUP, group);
                player.displayClientMessage(Component.literal(group
                        ? "Group fights: nearby mobs join in too."
                        : "1v1 duels: just the mob you challenge (friends can still join).").withStyle(ChatFormatting.GOLD), true);
            }
            return InteractionResultHolder.sidedSuccess(held, level.isClientSide);
        }
        if (level.isClientSide) {
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> dev.mtgcraft.client.ClientTables::reopenAny);
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Right-click a mob to challenge it").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Right-click a player to duel or join them").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal(groupFights(stack) ? "Mode: group fights" : "Mode: 1v1 duels").withStyle(ChatFormatting.GOLD));
        lines.add(Component.literal("Shift + right-click the air to switch").withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.literal("Needs a Deck Box with 40+ cards").withStyle(ChatFormatting.DARK_GRAY));
    }
}

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

    /**
     * Mobs with their own right-click (villagers, traders, tameables, rideables) handle the click before the held
     * item is asked, so a gauntlet would open the trade menu instead of starting a duel. This runs first instead
     * (from an EntityInteract event) and keeps the mob's own interaction from going off at all.
     */
    public static boolean interceptEntityClick(Player player, net.minecraft.world.entity.Entity target, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof GauntletItem g) || !(target instanceof LivingEntity living)) return false;
        if (!(target instanceof Mob) && !(target instanceof Player)) return false;
        g.interactLivingEntity(stack, player, living, hand);
        return true;
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
        } else if (player instanceof ServerPlayer sp && !dev.mtgcraft.server.GauntletDuels.inDuel(sp.getUUID())) {
            // Aiming at a mob from a distance challenges it too: no need to walk into a boss's reach. Parts of
            // big multi-part bosses (the Ender Dragon, a Hydra) count as the boss.
            net.minecraft.world.entity.Entity aimed = aimedAt(sp, RANGE);
            if (aimed instanceof Mob mob) dev.mtgcraft.server.GauntletDuels.challenge(sp, mob);
            else if (aimed instanceof ServerPlayer other) dev.mtgcraft.server.GauntletDuels.invitePlayer(sp, other);
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    /** How far away a mob can be challenged by aiming at it. */
    public static final double RANGE = 32;

    /** The mob or player the player is looking at (not through walls), or null. */
    public static net.minecraft.world.entity.Entity aimedAt(Player p, double range) {
        net.minecraft.world.phys.Vec3 eye = p.getEyePosition(), look = p.getViewVector(1);
        net.minecraft.world.phys.Vec3 end = eye.add(look.scale(range));
        net.minecraft.world.phys.AABB box = p.getBoundingBox().expandTowards(look.scale(range)).inflate(2);
        var hit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(p.level(), p, eye, end, box,
                e -> e != p && !e.isSpectator() && e.isPickable());
        if (hit == null) return null;
        var wall = p.level().clip(new net.minecraft.world.level.ClipContext(eye, end, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, p));
        if (wall.getType() != net.minecraft.world.phys.HitResult.Type.MISS
                && eye.distanceToSqr(wall.getLocation()) < eye.distanceToSqr(hit.getLocation())) return null;
        net.minecraft.world.entity.Entity e = hit.getEntity();
        if (e instanceof net.minecraftforge.entity.PartEntity<?> part) e = part.getParent();
        return e;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Right-click a mob to challenge it").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Or aim at one up to 32 blocks away and right-click").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Right-click a player to duel or join them").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal(groupFights(stack) ? "Mode: group fights" : "Mode: 1v1 duels").withStyle(ChatFormatting.GOLD));
        lines.add(Component.literal("Shift + right-click the air to switch").withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.literal("Needs a Deck Box with 40+ cards").withStyle(ChatFormatting.DARK_GRAY));
    }
}

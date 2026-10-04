package dev.mtgcraft.server;

import dev.mtgcraft.MtgConfig;
import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.engine.ForgeEngine;
import dev.mtgcraft.engine.Packs;
import dev.mtgcraft.item.BoxItem;
import dev.mtgcraft.item.DeckBoxItem;
import dev.mtgcraft.item.PackItem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Cards in the world: mobs you beat in a duel always drop their themed pack, bosses drop their booster box, and
 * any hostile mob has a small chance to drop a pack. Your Deck Box is never lost on death.
 */
@Mod.EventBusSubscriber(modid = MtgCraft.MODID)
public final class DuelLoot {
    private static final Random RNG = new Random();
    /** Deck boxes held back from a player's death drops, returned on respawn. */
    private static final Map<UUID, List<ItemStack>> KEPT = new HashMap<>();

    private DuelLoot() {}

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void drops(LivingDropsEvent event) {
        LivingEntity e = event.getEntity();
        if (e instanceof ServerPlayer player) {
            keepDeckBoxes(player, event);
            return;
        }
        if (ForgeEngine.state() != ForgeEngine.State.READY || e instanceof EnderDragon) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer)) return;
        Packs.Theme theme = MobThemes.theme(e);
        List<ItemStack> loot = new ArrayList<>();
        boolean boss = MobThemes.isBoss(e);
        if (GauntletDuels.DEFEATED.remove(e.getUUID())) {
            loot.add(PackItem.themed(theme, 1));
            if (boss) loot.add(bossBox(theme));
            else if (RNG.nextFloat() < 0.25f) loot.add(PackItem.themed(theme, 1));
        } else if (boss) {
            loot.add(bossBox(theme));
        } else if (e instanceof Enemy) {
            if (RNG.nextDouble() < MtgConfig.PACK_DROP_CHANCE.get()) loot.add(PackItem.themed(theme, 1));
            if (RNG.nextDouble() < MtgConfig.SET_PACK_DROP_CHANCE.get()) {
                String set = Packs.randomSet();
                if (set != null) loot.add(PackItem.ofSet(set, 1));
            }
        }
        for (ItemStack s : loot) {
            event.getDrops().add(new ItemEntity(e.level(), e.getX(), e.getY() + 0.5, e.getZ(), s));
        }
    }

    /** Dragon and Wither get their own boxes; other bosses drop a box of their theme. */
    private static ItemStack bossBox(Packs.Theme theme) {
        return BoxItem.themed(theme);
    }

    /**
     * The Ender Dragon doesn't drop items the normal way, so its box appears above the exit portal when it dies.
     */
    @SubscribeEvent
    public static void dragonDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon) || !(dragon.level() instanceof ServerLevel level)) return;
        BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, BlockPos.ZERO);
        ItemEntity box = new ItemEntity(level, 0.5, top.getY() + 3, 0.5, BoxItem.themed(Packs.Theme.ENDER_DRAGON));
        box.setUnlimitedLifetime();
        box.setGlowingTag(true);
        level.addFreshEntity(box);
    }

    private static void keepDeckBoxes(ServerPlayer player, LivingDropsEvent event) {
        List<ItemStack> kept = new ArrayList<>();
        for (Iterator<ItemEntity> it = event.getDrops().iterator(); it.hasNext(); ) {
            ItemEntity drop = it.next();
            if (drop.getItem().getItem() instanceof DeckBoxItem) {
                kept.add(drop.getItem().copy());
                it.remove();
            }
        }
        if (!kept.isEmpty()) KEPT.computeIfAbsent(player.getUUID(), k -> new ArrayList<>()).addAll(kept);
    }

    @SubscribeEvent
    public static void respawn(PlayerEvent.PlayerRespawnEvent event) {
        List<ItemStack> kept = KEPT.remove(event.getEntity().getUUID());
        if (kept == null) return;
        for (ItemStack s : kept) {
            if (!event.getEntity().getInventory().add(s)) event.getEntity().drop(s, false);
        }
    }
}

package dev.mtgcraft.server;

import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.item.DeckBoxItem;
import dev.mtgcraft.item.GauntletItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * A player always keeps their Duel Gauntlet and the deck they duel with, so they can fight again right after dying.
 * The items leave the inventory at the very start of death (before grave mods such as ATM9's tombstone collect it),
 * wait in the player's persisted data (which survives death and logging out), and come back on respawn.
 */
@Mod.EventBusSubscriber(modid = MtgCraft.MODID)
public final class KeepOnDeath {
    private static final String TAG = "mtgcraft_kept";

    private KeepOnDeath() {}

    private static CompoundTag persisted(Player p) {
        CompoundTag data = p.getPersistentData();
        CompoundTag kept = data.getCompound(Player.PERSISTED_NBT_TAG);
        data.put(Player.PERSISTED_NBT_TAG, kept);
        return kept;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void died(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || event.isCanceled()) return;
        if (p.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) return;
        Inventory inv = p.getInventory();
        ItemStack deck = DeckBoxItem.find(p);
        ListTag kept = persisted(p).getList(TAG, Tag.TAG_COMPOUND);
        boolean gauntlet = false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            boolean keep = (!gauntlet && s.getItem() instanceof GauntletItem) || (s == deck);
            if (!keep) continue;
            if (s.getItem() instanceof GauntletItem) gauntlet = true;
            kept.add(s.save(new CompoundTag()));
            inv.setItem(i, ItemStack.EMPTY);
        }
        if (!kept.isEmpty()) persisted(p).put(TAG, kept);
    }

    @SubscribeEvent
    public static void respawned(PlayerEvent.PlayerRespawnEvent event) {
        giveBack(event.getEntity());
    }

    @SubscribeEvent
    public static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        // Logged out on the death screen: the items are still waiting.
        if (event.getEntity().isAlive()) giveBack(event.getEntity());
    }

    private static void giveBack(Player p) {
        CompoundTag data = persisted(p);
        if (!data.contains(TAG)) return;
        ListTag kept = data.getList(TAG, Tag.TAG_COMPOUND);
        data.remove(TAG);
        for (int i = 0; i < kept.size(); i++) {
            ItemStack s = ItemStack.of(kept.getCompound(i));
            if (!s.isEmpty() && !p.getInventory().add(s)) p.drop(s, false);
        }
    }
}

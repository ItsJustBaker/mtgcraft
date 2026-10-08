package dev.mtgcraft.server;

import dev.mtgcraft.MtgConfig;
import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.engine.Packs;
import dev.mtgcraft.item.PackItem;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.SetNbtFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraftforge.event.LootTableLoadEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Loot chests (vanilla and every mod's, e.g. ATM9's) can hold a themed booster pack. */
@Mod.EventBusSubscriber(modid = MtgCraft.MODID)
public final class ChestLoot {
    private ChestLoot() {}

    @SubscribeEvent
    public static void load(LootTableLoadEvent event) {
        if (!event.getName().getPath().startsWith("chests/")) return;
        double chance = MtgConfig.CHEST_PACK_CHANCE.get();
        if (chance <= 0) return;
        LootPool.Builder pool = LootPool.lootPool().name("mtgcraft_packs").setRolls(ConstantValue.exactly(1))
                .when(LootItemRandomChanceCondition.randomChance((float) chance));
        for (Packs.Theme t : Packs.Theme.values()) {
            pool.add(LootItem.lootTableItem(MtgCraft.BOOSTER_PACK.get())
                    .apply(SetNbtFunction.setTag(PackItem.themed(t, 1).getOrCreateTag())));
        }
        event.getTable().addPool(pool.build());
    }
}

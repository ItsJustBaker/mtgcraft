package dev.mtgcraft.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraftforge.common.ForgeSpawnEggItem;

/** Spawns a Village Champion (a villager with the Champion tag and name), anywhere, no village needed. */
public class ChampionEggItem extends ForgeSpawnEggItem {
    public ChampionEggItem(Properties props) {
        super(() -> EntityType.VILLAGER, 0xB8860B, 0x6A1B9A, props);
    }

    /** The spawned villager's data: the Champion tag, name and persistence (see Champions). */
    private static void champion(ItemStack stack) {
        CompoundTag e = stack.getOrCreateTagElement("EntityTag");
        ListTag tags = new ListTag();
        tags.add(StringTag.valueOf("mtgcraft_champion"));
        tags.add(StringTag.valueOf("mtgcraft_champion_checked"));
        e.put("Tags", tags);
        e.putString("CustomName", Component.Serializer.toJson(Component.literal("Village Champion")
                .withStyle(s -> s.withColor(net.minecraft.ChatFormatting.GOLD).withBold(true))));
        e.putBoolean("CustomNameVisible", true);
        e.putBoolean("PersistenceRequired", true);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        champion(ctx.getItemInHand());
        return super.useOn(ctx);
    }
}

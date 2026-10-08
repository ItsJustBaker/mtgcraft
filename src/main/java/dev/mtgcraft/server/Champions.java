package dev.mtgcraft.server;

import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.engine.Cards;
import dev.mtgcraft.engine.ForgeEngine;
import dev.mtgcraft.engine.Packs;
import dev.mtgcraft.item.CardItem;
import dev.mtgcraft.item.PackItem;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * One villager per village is its Champion: dueling them never costs your life, and beating them wins a rare card
 * and a pack (once a Minecraft day). The first win over any Champion also earns the World Cup, which unlocks
 * reward duels against other players.
 */
@Mod.EventBusSubscriber(modid = MtgCraft.MODID)
public final class Champions {
    private static final String TAG = "mtgcraft_champion";
    private static final String TAG_DAY = "mtgcraft_champion_day";
    private static final String TAG_CUP = "mtgcraft_world_cup";
    private static final double VILLAGE = 64;

    private Champions() {}

    public static boolean is(Entity e) {
        return e instanceof Villager && e.getTags().contains(TAG);
    }

    /** A villager joining the world becomes the Champion if no other Champion is in its village. */
    @SubscribeEvent
    public static void joined(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Villager v) || v.isBaby() || is(v)) return;
        if (v.getTags().contains(TAG + "_checked")) return;
        v.addTag(TAG + "_checked");
        List<Villager> near = event.getLevel().getEntitiesOfClass(Villager.class, v.getBoundingBox().inflate(VILLAGE), Champions::is);
        if (!near.isEmpty()) return;
        v.addTag(TAG);
        v.setCustomName(Component.literal("Village Champion").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        v.setCustomNameVisible(true);
        v.setPersistenceRequired();
    }

    private static CompoundTag persisted(Player p) {
        CompoundTag data = p.getPersistentData();
        CompoundTag kept = data.getCompound(Player.PERSISTED_NBT_TAG);
        data.put(Player.PERSISTED_NBT_TAG, kept);
        return kept;
    }

    public static boolean hasCup(Player p) {
        for (ItemStack s : p.getInventory().items) if (s.is(MtgCraft.WORLD_CUP.get())) return true;
        return p.getOffhandItem().is(MtgCraft.WORLD_CUP.get());
    }

    /** A player beat a Champion: a rare card and a pack (once a day), and the World Cup the first time. */
    public static void reward(ServerPlayer p) {
        long day = p.level().getDayTime() / 24000L;
        CompoundTag data = persisted(p);
        if (data.contains(TAG_DAY) && data.getLong(TAG_DAY) == day) {
            p.displayClientMessage(Component.literal("The Champion already gave you a prize today. Come back tomorrow!").withStyle(ChatFormatting.GOLD), false);
        } else {
            data.putLong(TAG_DAY, day);
            Packs.Theme[] themes = Packs.Theme.values();
            Packs.Theme theme = themes[p.getRandom().nextInt(themes.length)];
            give(p, PackItem.themed(theme, 1));
            if (ForgeEngine.state() == ForgeEngine.State.READY) {
                Packs.Pull best = null;
                for (Packs.Pull pull : Packs.openTheme(theme)) {
                    if (best == null || rank(Cards.rarity(pull.card())) > rank(Cards.rarity(best.card()))) best = pull;
                }
                if (best != null) give(p, CardItem.of(best.card(), best.foil(), 1));
            }
            p.displayClientMessage(Component.literal("The Champion bows and hands you a prize!").withStyle(ChatFormatting.GOLD), false);
        }
        if (!data.getBoolean(TAG_CUP)) {
            data.putBoolean(TAG_CUP, true);
            give(p, new ItemStack(MtgCraft.WORLD_CUP.get()));
            p.displayClientMessage(Component.literal("You won the MTG World Cup! Holding it lets you challenge players to reward duels.")
                    .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD), false);
        }
    }

    private static int rank(char r) {
        return switch (r) {
            case 'M' -> 4;
            case 'R' -> 3;
            case 'U' -> 2;
            default -> 1;
        };
    }

    private static void give(ServerPlayer p, ItemStack s) {
        if (!p.getInventory().add(s)) p.drop(s, false);
    }
}

package dev.mtgcraft.server;

import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.engine.Cards;
import dev.mtgcraft.engine.ForgeEngine;
import dev.mtgcraft.engine.Packs;
import dev.mtgcraft.item.CardItem;
import dev.mtgcraft.item.PackItem;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * One villager per village is its Champion: dueling them never costs your life, and beating them wins a Champion
 * Pack (sometimes a pack of your choice) and a rare card, once a Minecraft day; the first win also earns the World
 * Cup. Other villages' extra card-players are Village Duelists: safe to duel too, with their own look.
 * Champions are recorded per world, so a village keeps exactly one even when its villagers load at different times.
 */
@Mod.EventBusSubscriber(modid = MtgCraft.MODID)
public final class Champions {
    private static final String TAG = "mtgcraft_champion";
    private static final String TAG_DUELIST = "mtgcraft_duelist";
    private static final String TAG_CHECKED = "mtgcraft_champion_checked";
    private static final String TAG_TRADES = "mtgcraft_card_trades";
    private static final String TAG_DAY = "mtgcraft_champion_day";
    private static final String TAG_CUP = "mtgcraft_world_cup";
    private static final double VILLAGE = 96;

    private Champions() {}

    public static boolean is(Entity e) {
        return e instanceof Villager && e.getTags().contains(TAG);
    }

    public static boolean isDuelist(Entity e) {
        return e instanceof Villager && e.getTags().contains(TAG_DUELIST);
    }

    /** Champions and Duelists: dueling them is safe for both sides. */
    public static boolean spared(Entity e) {
        return is(e) || isDuelist(e);
    }

    /** Where each world's Champions live (saved with the world). */
    static final class Data extends SavedData {
        final Map<UUID, BlockPos> champions = new HashMap<>();

        static Data load(CompoundTag tag) {
            Data d = new Data();
            for (Tag t : tag.getList("champions", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) t;
                d.champions.put(c.getUUID("id"), BlockPos.of(c.getLong("pos")));
            }
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            for (Map.Entry<UUID, BlockPos> e : champions.entrySet()) {
                CompoundTag c = new CompoundTag();
                c.putUUID("id", e.getKey());
                c.putLong("pos", e.getValue().asLong());
                list.add(c);
            }
            tag.put("champions", list);
            return tag;
        }

        boolean otherNear(UUID self, BlockPos at) {
            for (Map.Entry<UUID, BlockPos> e : champions.entrySet()) {
                if (!e.getKey().equals(self) && e.getValue().distSqr(at) < VILLAGE * VILLAGE) return true;
            }
            return false;
        }
    }

    private static Data data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Data::load, Data::new, "mtgcraft_champions");
    }

    private static void makeChampion(Villager v) {
        v.removeTag(TAG_DUELIST);
        v.addTag(TAG);
        v.setCustomName(Component.literal("Village Champion").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        v.setCustomNameVisible(true);
        v.setPersistenceRequired();
    }

    private static void makeDuelist(Villager v) {
        v.removeTag(TAG);
        v.addTag(TAG_DUELIST);
        v.setCustomName(Component.literal("Village Duelist").withStyle(ChatFormatting.AQUA));
        v.setCustomNameVisible(true);
        v.setPersistenceRequired();
    }

    /** A villager joining the world: the village's Champion if it has none yet, sometimes a Duelist otherwise. */
    @SubscribeEvent
    public static void joined(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Villager v) || v.isBaby()) return;
        Data data = data(level);
        if (is(v)) {
            if (v.getTags().contains("mtgcraft_champion_egg")) {
                // Spawned on purpose from an egg: always a Champion.
                data.champions.put(v.getUUID(), v.blockPosition());
                data.setDirty();
                return;
            }
            // An extra Champion from before Champions were recorded (or spawned in a village that has one): a Duelist.
            if (data.otherNear(v.getUUID(), v.blockPosition()) && !data.champions.containsKey(v.getUUID())) {
                makeDuelist(v);
            } else if (!v.blockPosition().equals(data.champions.put(v.getUUID(), v.blockPosition()))) {
                data.setDirty();
            }
            return;
        }
        if (isDuelist(v) || v.getTags().contains(TAG_CHECKED)) return;
        v.addTag(TAG_CHECKED);
        if (!data.otherNear(v.getUUID(), v.blockPosition())) {
            makeChampion(v);
            data.champions.put(v.getUUID(), v.blockPosition());
            data.setDirty();
        } else if (v.getRandom().nextInt(6) == 0) {
            makeDuelist(v);
        }
    }

    /**
     * Champions and Duelists always trade cards, whatever their job (or none): the first right-click (without dueling
     * gear) gives them card-for-card trades and a booster, added to whatever they already sell.
     */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGH)
    public static void talkedTo(net.minecraftforge.event.entity.player.PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide() || !(event.getTarget() instanceof Villager v) || !spared(v)) return;
        if (event.getItemStack().getItem() instanceof dev.mtgcraft.item.GauntletItem) return;
        if (v.getTags().contains(TAG_TRADES) || ForgeEngine.state() != ForgeEngine.State.READY) return;
        var offers = v.getOffers();
        var rng = v.getRandom();
        for (var listing : new net.minecraft.world.entity.npc.VillagerTrades.ItemListing[]{
                BoosterTrades.cardForCard(false), BoosterTrades.cardForCard(false), BoosterTrades.cardForCard(true),
                BoosterTrades.booster(is(v) ? 6 : 8, 8)}) {
            var offer = listing.getOffer(v, rng);
            if (offer != null) offers.add(offer);
        }
        v.addTag(TAG_TRADES);
    }

    @SubscribeEvent
    public static void died(LivingDeathEvent event) {
        if (is(event.getEntity()) && event.getEntity().level() instanceof ServerLevel level) {
            Data data = data(level);
            if (data.champions.remove(event.getEntity().getUUID()) != null) data.setDirty();
        }
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

    /** A World Cup standing on a Magic Table near the player unlocks reward duels there. */
    public static boolean trophyNear(Player p) {
        BlockPos at = p.blockPosition();
        for (BlockPos b : BlockPos.betweenClosed(at.offset(-12, -6, -12), at.offset(12, 6, 12))) {
            if (p.level().getBlockState(b).is(MtgCraft.WORLD_CUP_BLOCK.get())
                    && p.level().getBlockState(b.below()).is(MtgCraft.MAGIC_TABLE.get())) return true;
        }
        return false;
    }

    /** Beat a Champion: a Champion Pack (1 in 10 a pack of your choice) and the best card of a pack, once a day; the Cup the first time. */
    public static void reward(ServerPlayer p) {
        long day = p.level().getDayTime() / 24000L;
        CompoundTag data = persisted(p);
        if (data.contains(TAG_DAY) && data.getLong(TAG_DAY) == day) {
            p.displayClientMessage(Component.literal("The Champion already gave you a prize today. Come back tomorrow!").withStyle(ChatFormatting.GOLD), false);
        } else {
            data.putLong(TAG_DAY, day);
            give(p, PackItem.themed(Packs.Theme.CHAMPION, 1));
            if (p.getRandom().nextInt(10) == 0) {
                give(p, PackItem.ticket(false));
                p.displayClientMessage(Component.literal("Lucky! A Booster Pick: open it to choose any pack.").withStyle(ChatFormatting.LIGHT_PURPLE), false);
            }
            if (ForgeEngine.state() == ForgeEngine.State.READY) {
                Packs.Pull best = null;
                for (Packs.Pull pull : Packs.openTheme(Packs.Theme.CHAMPION)) {
                    if (best == null || rank(Cards.rarity(pull.card())) > rank(Cards.rarity(best.card()))) best = pull;
                }
                if (best != null) give(p, CardItem.of(best.card(), best.foil(), 1));
            }
            p.displayClientMessage(Component.literal("The Champion bows and hands you a prize!").withStyle(ChatFormatting.GOLD), false);
        }
        if (!data.getBoolean(TAG_CUP)) {
            data.putBoolean(TAG_CUP, true);
            give(p, new ItemStack(MtgCraft.WORLD_CUP.get()));
            p.displayClientMessage(Component.literal("You won the MTG World Cup! Place it on a Magic Table (or carry it) to unlock reward duels against players.")
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

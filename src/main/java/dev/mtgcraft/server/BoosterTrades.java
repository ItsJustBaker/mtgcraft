package dev.mtgcraft.server;

import dev.mtgcraft.MtgConfig;
import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.engine.ForgeEngine;
import dev.mtgcraft.engine.Packs;
import dev.mtgcraft.item.BoxItem;
import dev.mtgcraft.item.PackItem;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraftforge.event.village.VillagerTradesEvent;
import net.minecraftforge.event.village.WandererTradesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Real set boosters for emeralds: wandering traders always carry one, and librarians sell boosters (and, once
 * they're masters, whole booster boxes). Each offer is for a random set, picked when the trade is made.
 */
@Mod.EventBusSubscriber(modid = MtgCraft.MODID)
public final class BoosterTrades {
    private BoosterTrades() {}

    @SubscribeEvent
    public static void wanderer(WandererTradesEvent event) {
        if (!enabled()) return;
        event.getGenericTrades().add(booster(6, 4));
        event.getRareTrades().add(booster(5, 6));
    }

    @SubscribeEvent
    public static void cardTrades(VillagerTradesEvent event) {
        if (!enabled()) return;
        if (event.getType() == VillagerProfession.LIBRARIAN || event.getType() == VillagerProfession.CLERIC
                || event.getType() == VillagerProfession.CARTOGRAPHER) {
            event.getTrades().get(1).add(cardForCard(false));
            event.getTrades().get(3).add(cardForCard(true));
        }
    }

    /**
     * The villager wants one particular card (plus a few emeralds) for one of theirs: a common or uncommon for an
     * uncommon, or (at higher levels) an uncommon for a rare or mythic. Both come from the same themed pack.
     */
    private static VillagerTrades.ItemListing cardForCard(boolean rare) {
        return (trader, random) -> {
            if (ForgeEngine.state() != ForgeEngine.State.READY) return null;
            Packs.Theme[] themes = Packs.Theme.values();
            java.util.List<Packs.Pull> pulls = Packs.openTheme(themes[random.nextInt(themes.length)]);
            Packs.Pull want = null, give = null;
            for (Packs.Pull p : pulls) {
                char r = dev.mtgcraft.engine.Cards.rarity(p.card());
                boolean low = r == 'C' || (rare && r == 'U');
                boolean high = rare ? r == 'R' || r == 'M' : r == 'U';
                if (low && want == null) want = p;
                else if (high && give == null && (want == null || !p.card().getName().equals(want.card().getName()))) give = p;
            }
            if (want == null || give == null) return null;
            return new MerchantOffer(dev.mtgcraft.item.CardItem.of(want.card(), false, 1), new ItemStack(Items.EMERALD, rare ? 6 : 2),
                    dev.mtgcraft.item.CardItem.of(give.card(), give.foil(), 1), 3, rare ? 15 : 5, 0.05f);
        };
    }

    @SubscribeEvent
    public static void villager(VillagerTradesEvent event) {
        if (!enabled() || event.getType() != VillagerProfession.LIBRARIAN) return;
        event.getTrades().get(2).add(booster(8, 6));
        event.getTrades().get(4).add(booster(7, 8));
        event.getTrades().get(5).add((trader, random) -> {
            String set = set();
            if (set == null) return null;
            return new MerchantOffer(new ItemStack(Items.EMERALD_BLOCK, 12), BoxItem.ofSet(set), 1, 30, 0.05f);
        });
    }

    private static VillagerTrades.ItemListing booster(int emeralds, int uses) {
        return (trader, random) -> {
            String set = set();
            if (set == null) return null;
            return new MerchantOffer(new ItemStack(Items.EMERALD, emeralds), PackItem.ofSet(set, 1), uses, 10, 0.05f);
        };
    }

    private static boolean enabled() {
        try {
            return MtgConfig.BOOSTER_TRADES.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    /** A random printable set, or null while the card engine is still loading (the trade is skipped then). */
    private static String set() {
        return ForgeEngine.state() == ForgeEngine.State.READY ? Packs.randomSet() : null;
    }
}

package dev.mtgcraft.dev;

import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.engine.Cards;
import dev.mtgcraft.engine.ForgeEngine;
import dev.mtgcraft.engine.Packs;
import dev.mtgcraft.item.CardBag;
import dev.mtgcraft.server.GauntletDuels;
import dev.mtgcraft.server.TableSessions;
import forge.deck.Deck;
import forge.item.PaperCard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;

/**
 * Developer harness, off unless the JVM runs with {@code -Dmtgcraft.devDuel=true}: the first player to join gets a
 * deck box and is challenged by a spider jockey (plus a zombie), so the duel screen can be checked without setup.
 */
@Mod.EventBusSubscriber(modid = MtgCraft.MODID)
public final class DevDuel {
    private static final boolean ON = Boolean.getBoolean("mtgcraft.devDuel");
    private static boolean started, poolsReported, tribesReported;
    private static int ticks;

    private DevDuel() {}

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (!ON || started || event.phase != TickEvent.Phase.END || ++ticks % 20 != 0) return;
        if (ForgeEngine.state() != ForgeEngine.State.READY) return;
        if (Boolean.getBoolean("mtgcraft.devPools") && !poolsReported) {
            // -PdevPools: how many cards each themed pack can draw from, and a deck built from each.
            poolsReported = true;
            for (Packs.Theme t : Packs.Theme.values()) {
                int[] n = Packs.poolSizes(t);
                int deck = Packs.themeDeck(t, 6, "x").getMain().countAll();
                System.out.println("[MTGCraft dev] pool " + t + " C" + n[0] + " U" + n[1] + " R" + n[2] + " M" + n[3]
                        + " pack=" + Packs.openTheme(t).size() + " deck=" + deck + " available=" + t.available());
            }
        }
        if (Boolean.getBoolean("mtgcraft.devTribes") && !tribesReported) {
            // -PdevTribes: how many creatures of the type end up in each creature-type deck.
            tribesReported = true;
            for (String[] t : dev.mtgcraft.engine.Tribal.TRIBES) {
                var d60 = dev.mtgcraft.engine.Tribal.deck(t[1], false);
                var d100 = dev.mtgcraft.engine.Tribal.commanderDeck(t[1], false);
                System.out.println("[MTGCraft dev] tribe " + t[0] + ": 60-card " + d60.getMain().countAll() + " cards, "
                        + typed(d60, t[1]) + " " + t[1] + "s | commander " + d100.getName() + " " + (d100.getMain().countAll()
                        + (d100.has(forge.deck.DeckSection.Commander) ? d100.get(forge.deck.DeckSection.Commander).countAll() : 0))
                        + " cards, " + typed(d100, t[1]) + " " + t[1] + "s");
            }
        }
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
            if (!TableSessions.isReady(p.getUUID())) continue;
            started = true;
            // -Dmtgcraft.devLose keeps survival so the loss penalty can be checked.
            if (!Boolean.getBoolean("mtgcraft.devLose")) p.setGameMode(GameType.CREATIVE);
            p.serverLevel().setDayTime(1000);
            if (Boolean.getBoolean("mtgcraft.devStarter")) {
                // Leave the player to the starter screen; also crack a set box to count its packs.
                p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, dev.mtgcraft.item.BoxItem.ofSet("DFT"));
                p.setGameMode(GameType.SURVIVAL);
                p.getMainHandItem().use(p.level(), p, net.minecraft.world.InteractionHand.MAIN_HAND);
                int packs = 0;
                for (var st : p.getInventory().items) if (st.is(MtgCraft.BOOSTER_PACK.get())) packs += st.getCount();
                System.out.println("[MTGCraft dev] box gave " + packs + " packs");
                return;
            }
            if (Boolean.getBoolean("mtgcraft.devPicker")) {
                p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, dev.mtgcraft.item.PackItem.ticket(false));
                p.getMainHandItem().use(p.level(), p, net.minecraft.world.InteractionHand.MAIN_HAND);
                return;
            }
            Deck deck = Packs.themeDeck(Packs.Theme.VILLAGE, 6, "Dev deck");
            ItemStack box = new ItemStack(MtgCraft.DECK_BOX.get());
            for (Map.Entry<PaperCard, Integer> e : deck.getMain()) CardBag.add(box, Cards.key(e.getKey()), false, e.getValue());
            p.getInventory().add(box);
            var level = p.serverLevel();
            var at = p.blockPosition().relative(p.getDirection(), 4);
            if (Boolean.getBoolean("mtgcraft.devHill")) {
                // A dirt hill beside the duel, to check the stage rises above terrain.
                var base = p.blockPosition().relative(p.getDirection().getClockWise(), 6);
                for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = 0; dy < 12 - Math.abs(dx) - Math.abs(dz); dy++) {
                    level.setBlockAndUpdate(base.offset(dx, dy, dz), net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState());
                }
            }
            String devMob = System.getProperty("mtgcraft.devMob");
            if (devMob != null && !devMob.isEmpty()) {
                // -Dmtgcraft.devMob=minecraft:ender_dragon (or any mob id): duel that instead of the jockey.
                var type = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(new net.minecraft.resources.ResourceLocation(devMob));
                var e = type == null ? null : type.spawn(level, p.blockPosition().relative(p.getDirection(), 14).above(4), MobSpawnType.COMMAND);
                if (e instanceof Mob mob) GauntletDuels.challenge(p, mob);
                return;
            }
            Mob spider = EntityType.SPIDER.spawn(level, at, MobSpawnType.COMMAND);
            Mob skeleton = EntityType.SKELETON.spawn(level, at, MobSpawnType.COMMAND);
            if (spider == null || skeleton == null) return;
            skeleton.startRiding(spider, true);
            EntityType.ZOMBIE.spawn(level, at.east(3), MobSpawnType.COMMAND);
            GauntletDuels.challenge(p, skeleton);
            return;
        }
    }

    private static int typed(forge.deck.Deck d, String type) {
        int n = 0;
        for (var e : d.getMain()) if (e.getKey().getRules().getType().hasCreatureType(type)) n += e.getValue();
        return n;
    }
}

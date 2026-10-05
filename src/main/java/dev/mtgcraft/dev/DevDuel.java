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
    private static boolean started;
    private static int ticks;

    private DevDuel() {}

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (!ON || started || event.phase != TickEvent.Phase.END || ++ticks % 20 != 0) return;
        if (ForgeEngine.state() != ForgeEngine.State.READY) return;
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
            if (!TableSessions.isReady(p.getUUID())) continue;
            started = true;
            p.setGameMode(GameType.CREATIVE);
            p.serverLevel().setDayTime(1000);
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
}

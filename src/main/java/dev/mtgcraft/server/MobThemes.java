package dev.mtgcraft.server;

import dev.mtgcraft.engine.Packs.Theme;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraftforge.common.Tags;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Which pack theme (and so which deck) a mob plays, and how strong its deck is. Works by registry name, so modded
 * mobs (ATM9's and others) get a sensible theme too.
 */
public final class MobThemes {
    private MobThemes() {}

    public static Theme theme(Entity e) {
        String id = id(e.getType());
        if (id.contains("ender_dragon")) return Theme.ENDER_DRAGON;
        if (id.contains("wither_skeleton")) return Theme.UNDEAD;
        if (id.contains("wither")) return Theme.WITHER;
        if (id.contains("warden") || id.contains("sculk")) return Theme.SCULK;
        if (has(id, "drowned", "guardian", "squid", "dolphin", "axolotl", "fish", "turtle", "shark", "crab")) return Theme.OCEAN;
        if (has(id, "zombie", "skeleton", "husk", "stray", "phantom", "ghost", "lich", "wraith", "mummy")) return Theme.UNDEAD;
        if (has(id, "spider", "silverfish", "bat", "rat")) return Theme.NIGHT;
        if (id.contains("creeper")) return Theme.CREEPER;
        if (has(id, "enderman", "endermite", "shulker", "end_")) return Theme.END;
        if (has(id, "blaze", "ghast", "piglin", "hoglin", "zoglin", "magma", "strider", "nether")) return Theme.NETHER;
        if (id.contains("witch")) return Theme.WITCH;
        if (has(id, "pillager", "vindicator", "evoker", "ravager", "illusioner", "vex", "illager")) return Theme.RAID;
        if (has(id, "villager", "golem", "trader")) return Theme.VILLAGE;
        if (e instanceof Enemy) {
            Theme[] dark = {Theme.UNDEAD, Theme.NIGHT, Theme.NETHER, Theme.WITCH};
            return dark[Math.floorMod(id.hashCode(), dark.length)];
        }
        return Theme.OVERWORLD;
    }

    /** How many themed packs its sealed-style deck is built from: more packs, better picks. */
    public static int packs(Entity e) {
        if (isBoss(e)) return 12;
        String id = id(e.getType());
        if (has(id, "witch", "enderman", "evoker", "ravager", "piglin_brute", "blaze", "elder_guardian")) return 8;
        return 6;
    }

    public static boolean isBoss(Entity e) {
        String id = id(e.getType());
        if (id.contains("wither_skeleton")) return false;
        return e.getType().is(Tags.EntityTypes.BOSSES) || has(id, "ender_dragon", "wither", "warden", "elder_guardian");
    }

    private static String id(EntityType<?> type) {
        ResourceLocation rl = ForgeRegistries.ENTITY_TYPES.getKey(type);
        return rl == null ? "" : rl.toString();
    }

    private static boolean has(String id, String... words) {
        for (String w : words) if (id.contains(w)) return true;
        return false;
    }
}

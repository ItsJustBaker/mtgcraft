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
        return switch (tier(e)) {
            case CRITTER -> 4;
            case COMMON -> 6;
            case TOUGH -> 8;
            case BOSS -> 12;
        };
    }

    /** How hard a mob is to duel. Critters and common mobs play quick duels; bosses play Archenemy. */
    public enum Tier {
        /** Cows, chickens, rabbits... a quick warm-up. */
        CRITTER,
        /** Zombies, skeletons, spiders, creepers. */
        COMMON,
        /** Ghasts, blazes, endermen, witches, illagers, golems... a real game. */
        TOUGH,
        /** The Wither, the Ender Dragon, the Warden, modded bosses: Archenemy. */
        BOSS
    }

    public static Tier tier(Entity e) {
        if (isBoss(e)) return Tier.BOSS;
        String id = id(e.getType());
        if (has(id, "ghast", "blaze", "enderman", "witch", "piglin_brute", "evoker", "ravager", "guardian",
                "wither_skeleton", "hoglin", "zoglin", "vindicator", "shulker", "golem", "illusioner", "breeze")) {
            return Tier.TOUGH;
        }
        // Modded mobs: judge by how much health they have.
        float hp = e instanceof net.minecraft.world.entity.LivingEntity l ? l.getMaxHealth() : 20;
        if (hp >= 40) return Tier.TOUGH;
        return e instanceof Enemy || hp > 30 ? Tier.COMMON : Tier.CRITTER;
    }

    /** A mob's starting life in a quick duel (critters and common mobs); 0 means the format's usual life. */
    public static int quickLife(Tier tier) {
        return switch (tier) {
            case CRITTER -> 6;
            case COMMON -> 12;
            default -> 0;
        };
    }

    /** Bosses from mods in All the Mods (Twilight Forest, Cataclysm, Bosses of Mass Destruction, Mowzie's...). */
    private static final String[] MODDED_BOSSES = {"naga", "lich", "hydra", "ur_ghast", "snow_queen", "minoshroom",
            "alpha_yeti", "knight_phantom", "ignis", "monstrosity", "leviathan", "ender_guardian", "harbinger", "scylla",
            "maledictus", "ancient_remnant", "obsidilith", "void_blossom", "piglich", "frostmaw", "wroughtnaut",
            "barako", "cornelia", "wither_storm", "dragon", "_boss", "boss_", "titan", "colossus", "overlord"};

    public static boolean isBoss(Entity e) {
        String id = id(e.getType());
        if (has(id, "wither_skeleton", "dragon_fly", "dragonfly", "baby_dragon")) return false;
        if (e.getType().is(Tags.EntityTypes.BOSSES) || has(id, "ender_dragon", "wither", "warden", "elder_guardian")) return true;
        if (!id.startsWith("minecraft:") && has(id, MODDED_BOSSES)) return true;
        // Anything else with a huge health pool is a boss too.
        return e instanceof net.minecraft.world.entity.LivingEntity l && l.getMaxHealth() >= 150;
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

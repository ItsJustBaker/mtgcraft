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

    /** Mob names that aren't a Magic creature type themselves. */
    private static final java.util.Map<String, String> TYPE_ALIASES = java.util.Map.ofEntries(
            java.util.Map.entry("cow", "Ox"), java.util.Map.entry("mooshroom", "Ox"), java.util.Map.entry("pig", "Boar"),
            java.util.Map.entry("hoglin", "Boar"), java.util.Map.entry("zoglin", "Boar"), java.util.Map.entry("chicken", "Bird"),
            java.util.Map.entry("parrot", "Bird"), java.util.Map.entry("donkey", "Horse"), java.util.Map.entry("mule", "Horse"),
            java.util.Map.entry("ocelot", "Cat"), java.util.Map.entry("bee", "Insect"), java.util.Map.entry("silverfish", "Insect"),
            java.util.Map.entry("endermite", "Insect"), java.util.Map.entry("husk", "Zombie"), java.util.Map.entry("drowned", "Zombie"),
            java.util.Map.entry("stray", "Skeleton"), java.util.Map.entry("creeper", "Elemental"), java.util.Map.entry("blaze", "Elemental"),
            java.util.Map.entry("breeze", "Elemental"), java.util.Map.entry("enderman", "Horror"), java.util.Map.entry("slime", "Ooze"),
            java.util.Map.entry("magma_cube", "Ooze"), java.util.Map.entry("ghast", "Spirit"), java.util.Map.entry("vex", "Spirit"),
            java.util.Map.entry("allay", "Faerie"), java.util.Map.entry("phantom", "Spirit"), java.util.Map.entry("axolotl", "Salamander"),
            java.util.Map.entry("tadpole", "Frog"), java.util.Map.entry("squid", "Squid"), java.util.Map.entry("glow_squid", "Squid"),
            java.util.Map.entry("cod", "Fish"), java.util.Map.entry("salmon", "Fish"), java.util.Map.entry("pufferfish", "Fish"),
            java.util.Map.entry("tropical_fish", "Fish"), java.util.Map.entry("guardian", "Fish"), java.util.Map.entry("elder_guardian", "Fish"),
            java.util.Map.entry("polar_bear", "Bear"), java.util.Map.entry("panda", "Bear"), java.util.Map.entry("villager", "Human"),
            java.util.Map.entry("wandering_trader", "Human"), java.util.Map.entry("pillager", "Human"), java.util.Map.entry("vindicator", "Human"),
            java.util.Map.entry("evoker", "Wizard"), java.util.Map.entry("illusioner", "Wizard"), java.util.Map.entry("witch", "Warlock"),
            java.util.Map.entry("ravager", "Beast"), java.util.Map.entry("piglin", "Boar"), java.util.Map.entry("piglin_brute", "Boar"),
            java.util.Map.entry("zombified_piglin", "Zombie"), java.util.Map.entry("iron_golem", "Golem"), java.util.Map.entry("snow_golem", "Golem"),
            java.util.Map.entry("shulker", "Horror"), java.util.Map.entry("warden", "Horror"), java.util.Map.entry("strider", "Beast"),
            java.util.Map.entry("sniffer", "Beast"), java.util.Map.entry("dolphin", "Whale"), java.util.Map.entry("bat", "Bat"));

    /**
     * The Magic creature type a mob stands for (spider: Spider, drowned: Zombie, a modded grizzly_bear: Bear), or null.
     * Tries the alias table, then each word of the mob's id from the last (so most modded mobs match too).
     */
    public static String creatureType(Entity e) {
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
        if (id == null) return null;
        String path = id.getPath();
        String alias = TYPE_ALIASES.get(path);
        if (alias != null) return alias;
        java.util.Collection<String> all = forge.card.CardType.getAllCreatureTypes();
        String[] words = path.split("_");
        for (int i = words.length - 1; i >= 0; i--) {
            String w = words[i];
            if (w.isEmpty()) continue;
            String cap = Character.toUpperCase(w.charAt(0)) + w.substring(1);
            if (all.contains(cap)) return cap;
            if (cap.endsWith("s") && all.contains(cap.substring(0, cap.length() - 1))) return cap.substring(0, cap.length() - 1);
        }
        return null;
    }

    public static Theme theme(Entity e) {
        String id = id(e.getType());
        int colon = id.indexOf(':');
        String ns = colon < 0 ? "" : id.substring(0, colon), path = id.substring(colon + 1);
        // Vanilla mobs promoted to elites or bosses by other mods (Apotheosis and the like) carry Champion packs.
        if (!isBoss(e) && marked(e) != null && Theme.CHAMPION.available()) return Theme.CHAMPION;
        if (ns.equals("minecraft")) {
            Theme exact = VANILLA.get(path);
            if (exact != null) return exact;
        } else {
            Theme modded = modded(ns, path);
            if (modded != null && modded.available()) return modded;
        }
        // Everything else (and modded mobs from other mods) by what the name suggests.
        if (id.contains("ender_dragon")) return Theme.ENDER_DRAGON;
        if (id.contains("wither_skeleton")) return Theme.UNDEAD;
        if (id.contains("wither")) return Theme.WITHER;
        if (id.contains("warden") || id.contains("sculk")) return Theme.SCULK;
        if (has(id, "angel", "seraph", "valkyrie", "fairy", "pixie", "spirit")) return Theme.CELESTIAL;
        if (has(id, "drowned", "guardian", "squid", "dolphin", "axolotl", "fish", "turtle", "shark", "crab")) return Theme.OCEAN;
        if (has(id, "skeleton", "archer")) return Theme.ARCHER;
        if (has(id, "zombie", "husk", "stray", "ghost", "lich", "wraith", "mummy")) return Theme.UNDEAD;
        if (has(id, "spider", "bat", "rat")) return Theme.NIGHT;
        if (id.contains("creeper")) return Theme.CREEPER;
        if (has(id, "enderman", "endermite", "end_")) return Theme.END;
        if (has(id, "piglin", "hoglin", "pig")) return Theme.PIGLIN;
        if (has(id, "blaze", "fire", "flame", "magma")) return Theme.INFERNO;
        if (has(id, "ghast", "nether")) return Theme.NETHER;
        if (has(id, "slime", "ooze")) return Theme.SLIME;
        if (has(id, "bee", "wasp", "hornet")) return Theme.HIVE;
        if (has(id, "golem", "construct", "robot")) return Theme.GOLEM;
        if (has(id, "frost", "ice", "snow", "yeti", "penguin")) return Theme.FROST;
        if (has(id, "mushroom", "fung", "shroom")) return Theme.MUSHROOM;
        if (has(id, "horse", "deer", "moose")) return Theme.STABLE;
        if (has(id, "wolf", "fox", "bear", "boar")) return Theme.WILD;
        if (has(id, "dino", "raptor", "rex", "fossil")) return Theme.ANCIENT;
        if (id.contains("witch")) return Theme.WITCH;
        if (has(id, "pillager", "vindicator", "evoker", "ravager", "illusioner", "illager", "bandit", "pirate")) return Theme.RAID;
        if (has(id, "villager", "trader")) return Theme.VILLAGE;
        if (e instanceof Enemy) {
            Theme[] dark = {Theme.UNDEAD, Theme.NIGHT, Theme.NETHER, Theme.WITCH, Theme.CAVE, Theme.SOUL};
            return dark[Math.floorMod(id.hashCode(), dark.length)];
        }
        return Theme.OVERWORLD;
    }

    /** Vanilla mobs by their exact id. */
    private static final java.util.Map<String, Theme> VANILLA = new java.util.HashMap<>();

    private static void map(Theme t, String... paths) {
        for (String p : paths) VANILLA.put(p, t);
    }

    static {
        map(Theme.CELESTIAL, "allay", "vex", "phantom");
        map(Theme.HIVE, "bee");
        map(Theme.GOLEM, "iron_golem");
        map(Theme.PIGLIN, "piglin", "piglin_brute", "hoglin", "zoglin", "zombified_piglin");
        map(Theme.INFERNO, "blaze", "magma_cube");
        map(Theme.SOUL, "ghast");
        map(Theme.NETHER, "strider");
        map(Theme.SLIME, "slime");
        map(Theme.MONUMENT, "guardian", "elder_guardian");
        map(Theme.FROST, "stray", "polar_bear", "snow_golem", "goat");
        map(Theme.DESERT, "husk", "camel");
        map(Theme.JUNGLE, "ocelot", "parrot", "panda");
        map(Theme.SWAMP, "frog", "tadpole");
        map(Theme.WILD, "wolf", "fox");
        map(Theme.FARM, "cow", "pig", "chicken", "sheep");
        map(Theme.STABLE, "horse", "donkey", "mule", "llama", "trader_llama", "skeleton_horse", "zombie_horse");
        map(Theme.CAVE, "cave_spider", "silverfish", "glow_squid", "bat");
        map(Theme.ANCIENT, "sniffer", "turtle", "armadillo");
        map(Theme.MUSHROOM, "mooshroom");
        map(Theme.END_CITY, "shulker");
        map(Theme.ARCHER, "skeleton");
        map(Theme.UNDEAD, "zombie", "zombie_villager", "wither_skeleton", "drowned");
        map(Theme.OCEAN, "squid", "dolphin", "axolotl", "cod", "salmon", "pufferfish", "tropical_fish");
        map(Theme.NIGHT, "spider", "endermite");
        map(Theme.END, "enderman");
        map(Theme.CREEPER, "creeper");
        map(Theme.WITCH, "witch");
        map(Theme.RAID, "pillager", "vindicator", "evoker", "ravager", "illusioner");
        map(Theme.VILLAGE, "villager", "wandering_trader", "cat");
        map(Theme.SCULK, "warden");
        map(Theme.WITHER, "wither");
        map(Theme.ENDER_DRAGON, "ender_dragon");
        map(Theme.OVERWORLD, "rabbit");
        map(Theme.RODENT, "silverfish", "endermite");
        map(Theme.SPIDER, "spider", "cave_spider");
        map(Theme.PHANTOM, "phantom");
        map(Theme.LUSH, "frog", "axolotl", "tadpole");
        map(Theme.TRAIL, "sniffer", "camel");
    }

    /** All the Mods mobs: each mod gets its own pack (only when installed, see {@link Theme#available()}). */
    private static Theme modded(String ns, String path) {
        return switch (ns) {
            case "twilightforest" -> path.contains("naga") ? Theme.NAGA
                    : path.contains("hydra") ? Theme.HYDRA
                    : has(path, "snow", "yeti", "ice", "winter") ? Theme.FROST
                    : has(path, "lich", "wraith", "skeleton", "phantom") ? Theme.UNDEAD
                    : Theme.TWILIGHT;
            case "cataclysm" -> has(path, "leviathan", "deepling", "coral", "lionfish", "abyss", "scylla") ? Theme.ABYSS : Theme.CATACLYSM;
            case "aquamirae" -> Theme.ABYSS;
            case "alexsmobs" -> Theme.WILDLIFE;
            case "mowziesmobs" -> Theme.MOWZIE;
            case "ars_nouveau" -> Theme.ARCANE;
            case "botania" -> Theme.MANA;
            case "undergarden" -> Theme.UNDERGARDEN;
            case "deeperdarker" -> Theme.OTHERSIDE;
            case "occultism", "bloodmagic" -> Theme.OCCULT;
            case "born_in_chaos_v1" -> Theme.CHAOS;
            case "irons_spellbooks" -> Theme.SPELLBOOK;
            case "ad_astra" -> Theme.STARBOUND;
            case "iceandfire" -> Theme.DRAGONFIRE;
            case "aether" -> Theme.AETHER;
            case "blue_skies" -> Theme.SKIES;
            case "allthemodium" -> Theme.ALLTHEMODIUM;
            case "endermanoverhaul" -> Theme.ENDERMEN;
            case "eidolon" -> Theme.NECRO;
            case "naturalist" -> Theme.SAFARI;
            case "quark" -> Theme.QUARK;
            case "forbidden_arcanus" -> Theme.FORBIDDEN;
            case "voidscape" -> Theme.VOID;
            case "aquaculture" -> Theme.ANGLER;
            case "mythicbotany" -> Theme.ALFHEIM;
            case "productivebees" -> Theme.HIVE;
            case "ars_elemental" -> Theme.ARCANE;
            default -> null;
        };
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
        Tier marked = marked(e);
        if (marked != null) return marked;
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

    /**
     * Ordinary mobs that other mods promoted: Apotheosis bosses and elites, Champions, Infernal Mobs and similar keep a
     * marker in the mob's saved data or its tags. A "boss" marker makes a boss; "elite", "champion", "miniboss" or
     * "infernal" make it tough. Null when there's no marker (its max health still counts, see tier()).
     */
    private static Tier marked(Entity e) {
        Tier found = null;
        java.util.List<String> keys = new java.util.ArrayList<>(e.getTags());
        net.minecraft.nbt.CompoundTag data = e.getPersistentData();
        for (String k : data.getAllKeys()) {
            net.minecraft.nbt.Tag t = data.get(k);
            // A numeric flag that's 0 means "no"; anything else counts as set.
            if (t instanceof net.minecraft.nbt.NumericTag n && n.getAsInt() == 0) continue;
            keys.add(k);
        }
        for (String k : keys) {
            String l = k.toLowerCase(java.util.Locale.ROOT);
            if (has(l, "miniboss", "mini_boss", "elite", "champion", "infernal")) {
                if (found == null) found = Tier.TOUGH;
            } else if (l.contains("boss")) {
                return Tier.BOSS;
            }
        }
        return found;
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
        if (marked(e) == Tier.BOSS) return true;
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

package dev.mtgcraft.engine;

import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.deck.Deck;
import forge.gamemodes.limited.SealedDeckBuilder;
import forge.item.BoosterPack;
import forge.item.PaperCard;
import forge.model.FModel;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Booster packs: real set boosters (Card-Forge's own print runs) and Minecraft-themed packs whose real cards are
 * picked to match a mob or place. Themes also give every mob a matching deck.
 */
public final class Packs {
    private Packs() {}

    /** A pull from a pack: the card and whether it's foil. */
    public record Pull(PaperCard card, boolean foil) {}

    public enum Theme {
        OVERWORLD("Overworld Pack", "GW", new String[]{"Beast", "Wolf", "Cat", "Horse", "Bird", "Bear", "Ox", "Boar", "Elk", "Rabbit", "Squirrel", "Fox", "Goat", "Sheep", "Llama", "Frog", "Turtle"}, new String[]{}),
        CREEPER("Creeper Pack", "RG", new String[]{"Elemental", "Plant", "Fungus", "Saproling"}, new String[]{"damage to each creature", "damage to each player", "explode", "sacrifice it"}),
        UNDEAD("Undead Pack", "BW", new String[]{"Zombie", "Skeleton", "Spirit", "Wraith", "Mummy"}, new String[]{"from your graveyard to the battlefield"}),
        NIGHT("Night Pack", "BG", new String[]{"Spider", "Insect", "Bat", "Rat", "Werewolf"}, new String[]{"deathtouch"}),
        NETHER("Nether Pack", "BR", new String[]{"Demon", "Devil", "Imp", "Phoenix", "Hellion", "Goblin", "Boar", "Salamander"}, new String[]{"lava", "inferno"}),
        END("End Pack", "UB", new String[]{"Eldrazi", "Horror", "Shapeshifter", "Dragon"}, new String[]{"phase out", "exile target creature"}),
        OCEAN("Ocean Pack", "U", new String[]{"Fish", "Merfolk", "Kraken", "Octopus", "Leviathan", "Squid", "Turtle", "Serpent", "Whale", "Crab", "Jellyfish"}, new String[]{"islandwalk"}),
        VILLAGE("Village Pack", "WG", new String[]{"Human", "Peasant", "Citizen", "Advisor", "Golem", "Cat"}, new String[]{"Food token"}),
        RAID("Raid Pack", "BR", new String[]{"Pirate", "Barbarian", "Berserker", "Rogue", "Warrior"}, new String[]{"Raid —"}),
        WITCH("Witch Pack", "UB", new String[]{"Warlock", "Wizard", "Shaman", "Druid"}, new String[]{"potion", "-1/-1 counter"}),
        SCULK("Sculk Pack", "BG", new String[]{"Horror", "Nightmare", "Shade"}, new String[]{"can't be blocked"}),
        ENDER_DRAGON("Ender Dragon Box", "B", new String[]{"Dragon"}, new String[]{}),
        WITHER("Wither Box", "B", new String[]{"Skeleton", "Zombie", "Horror", "Spirit"}, new String[]{"destroy target creature"}),

        // ---- more vanilla themes (new ones always go at the end: saved packs and item models use this order)
        CELESTIAL("Celestial Pack", "W", new String[]{"Angel", "Archon", "Spirit", "Cleric", "Faerie"}, new String[]{"gain life", "lifelink"}),
        HIVE("Hive Pack", "G", new String[]{"Insect", "Bee"}, new String[]{"Insect token", "flying"}),
        GOLEM("Golem Pack", "W", new String[]{"Golem", "Construct", "Assembly-Worker", "Myr"}, new String[]{"artifact creature"}),
        PIGLIN("Piglin Pack", "R", new String[]{"Boar", "Goblin", "Orc", "Berserker"}, new String[]{"Treasure token", "gold"}),
        INFERNO("Inferno Pack", "R", new String[]{"Elemental", "Phoenix", "Salamander", "Efreet"}, new String[]{"damage to any target"}),
        SOUL("Soul Valley Pack", "BU", new String[]{"Spirit", "Specter", "Shade", "Wraith"}, new String[]{"exile target card from a graveyard"}),
        SLIME("Slime Pack", "G", new String[]{"Ooze"}, new String[]{"+1/+1 counter", "proliferate"}),
        MONUMENT("Monument Pack", "U", new String[]{"Fish", "Serpent", "Leviathan", "Wall"}, new String[]{"tap target creature", "doesn't untap"}),
        FROST("Frost Pack", "UW", new String[]{"Yeti", "Bear", "Giant"}, new String[]{"snow", "freeze", "doesn't untap"}),
        DESERT("Desert Pack", "WR", new String[]{"Mummy", "Camel", "Jackal", "Scorpion", "Snake", "Sphinx", "Djinn", "Nomad"}, new String[]{"desert", "exert"}),
        JUNGLE("Jungle Pack", "G", new String[]{"Cat", "Ape", "Monkey", "Bird", "Dinosaur", "Panda"}, new String[]{"explore"}),
        SWAMP("Swamp Pack", "BG", new String[]{"Frog", "Leech", "Horror", "Lizard"}, new String[]{"swampwalk"}),
        WILD("Wild Pack", "RG", new String[]{"Wolf", "Fox", "Werewolf", "Bear"}, new String[]{"fights"}),
        FARM("Farm Pack", "GW", new String[]{"Ox", "Boar", "Sheep", "Bird", "Peasant", "Rabbit"}, new String[]{"Food token"}),
        STABLE("Stable Pack", "W", new String[]{"Horse", "Unicorn", "Knight", "Pegasus"}, new String[]{"vigilance"}),
        CAVE("Deep Cave Pack", "BR", new String[]{"Dwarf", "Kobold", "Gnome", "Bat", "Mole"}, new String[]{"Cave"}),
        ANCIENT("Ancient Pack", "G", new String[]{"Dinosaur", "Turtle", "Plant", "Treefolk"}, new String[]{"fossil", "discover"}),
        MUSHROOM("Mushroom Pack", "BG", new String[]{"Fungus", "Saproling"}, new String[]{"Saproling token"}),
        END_CITY("End City Pack", "UW", new String[]{"Construct", "Shapeshifter", "Illusion"}, new String[]{"phase out", "exile it, then return"}),
        ARCHER("Archer Pack", "WG", new String[]{"Archer", "Skeleton", "Ranger"}, new String[]{"reach"}),

        // ---- All the Mods 9 add-on: only drop and show up when their mod is installed
        TWILIGHT("Twilight Forest Pack", "GU", new String[]{"Elf", "Faerie", "Treefolk", "Dryad"}, new String[]{"flash"}, "twilightforest"),
        NAGA("Naga Pack", "BG", new String[]{"Naga", "Snake", "Serpent"}, new String[]{"deathtouch"}, "twilightforest"),
        HYDRA("Hydra Pack", "RG", new String[]{"Hydra"}, new String[]{"X +1/+1 counters"}, "twilightforest"),
        CATACLYSM("Cataclysm Pack", "BR", new String[]{"Golem", "Construct", "Elemental", "Demon"}, new String[]{"destroy all"}, "cataclysm"),
        ABYSS("Abyss Pack", "UB", new String[]{"Leviathan", "Kraken", "Horror", "Octopus"}, new String[]{"draw a card"}, "cataclysm", "aquamirae"),
        WILDLIFE("Wildlife Pack", "GR", new String[]{"Ape", "Elephant", "Crocodile", "Hippo", "Rhino", "Snake", "Lizard", "Kangaroo", "Bird"}, new String[]{"trample"}, "alexsmobs"),
        MOWZIE("Mowzie's Pack", "RW", new String[]{"Warrior", "Shaman", "Giant"}, new String[]{"double strike"}, "mowziesmobs"),
        ARCANE("Arcane Pack", "U", new String[]{"Wizard", "Faerie", "Spirit"}, new String[]{"instant or sorcery"}, "ars_nouveau"),
        MANA("Mana Pack", "GW", new String[]{"Faerie", "Dryad", "Elemental"}, new String[]{"add one mana"}, "botania"),
        UNDERGARDEN("Undergarden Pack", "BG", new String[]{"Fungus", "Horror", "Kobold"}, new String[]{"mill"}, "undergarden"),
        OTHERSIDE("Otherside Pack", "B", new String[]{"Horror", "Shade", "Nightmare"}, new String[]{"can't block"}, "deeperdarker"),
        OCCULT("Occult Pack", "BR", new String[]{"Demon", "Djinn", "Efreet", "Cleric"}, new String[]{"sacrifice a creature", "pay life"}, "occultism", "bloodmagic"),
        CHAOS("Chaos Pack", "BR", new String[]{"Scarecrow", "Zombie", "Horror", "Spirit"}, new String[]{"each opponent loses"}, "born_in_chaos_v1"),
        SPELLBOOK("Spellbook Pack", "UR", new String[]{"Wizard", "Warlock", "Shaman"}, new String[]{"magecraft", "copy target instant"}, "irons_spellbooks"),
        STARBOUND("Starbound Pack", "UW", new String[]{"Alien", "Construct", "Robot"}, new String[]{"Spacecraft", "Planet"}, "ad_astra"),
        CHAMPION("Champion Pack", "RW", new String[]{"Knight", "Soldier", "Warrior"}, new String[]{"Equip"}, "apotheosis"),
        DRAGONFIRE("Dragonfire Pack", "R", new String[]{"Dragon", "Wyvern", "Hydra", "Gorgon", "Cyclops"}, new String[]{}, "iceandfire"),
        AETHER("Aether Pack", "W", new String[]{"Angel", "Bird", "Pegasus", "Spirit"}, new String[]{"flying"}, "aether"),
        SKIES("Skies Pack", "UW", new String[]{"Spirit", "Elemental", "Bird"}, new String[]{"flying"}, "blue_skies"),
        ALLTHEMODIUM("Allthemodium Pack", "BR", new String[]{"Golem", "Demon", "Dragon"}, new String[]{"indestructible"}, "allthemodium"),
        // 0.3.0: new themes go at the end, so saved packs (stored by name) and the pack art order stay as they were.
        RODENT("Rodent Pack", "BG", new String[]{"Rat", "Squirrel", "Rabbit", "Mouse", "Weasel", "Otter"}, new String[]{"rat"}),
        SPIDER("Spider Pack", "BG", new String[]{"Spider", "Insect"}, new String[]{"reach", "deathtouch"}),
        PHANTOM("Phantom Pack", "WU", new String[]{"Spirit", "Specter", "Bat", "Bird"}, new String[]{"flying"}),
        LUSH("Lush Pack", "GU", new String[]{"Frog", "Salamander", "Fish", "Plant"}, new String[]{"untap"}),
        TRAIL("Trail Pack", "GW", new String[]{"Camel", "Beast", "Dinosaur"}, new String[]{"discover", "explore"}),
        ENDERMEN("Enderman Pack", "UB", new String[]{"Horror", "Shapeshifter", "Eldrazi"}, new String[]{"exile"}, "endermanoverhaul"),
        NECRO("Eidolon Pack", "BW", new String[]{"Wraith", "Zombie", "Spirit", "Cleric"}, new String[]{"sacrifice"}, "eidolon"),
        SAFARI("Naturalist Pack", "GR", new String[]{"Elephant", "Cat", "Bear", "Snake", "Lizard", "Bird"}, new String[]{"fight"}, "naturalist"),
        QUARK("Quark Pack", "UG", new String[]{"Crab", "Elemental", "Dog", "Wraith"}, new String[]{"scry"}, "quark"),
        FORBIDDEN("Forbidden Pack", "BU", new String[]{"Demon", "Spirit", "Wizard"}, new String[]{"pay life"}, "forbidden_arcanus"),
        VOID("Voidscape Pack", "UB", new String[]{"Horror", "Eldrazi", "Spirit"}, new String[]{"exile", "void"}, "voidscape"),
        ANGLER("Angler Pack", "UG", new String[]{"Fish", "Octopus", "Crab", "Turtle", "Merfolk"}, new String[]{"islandwalk"}, "aquaculture"),
        ALFHEIM("Alfheim Pack", "GW", new String[]{"Elf", "Faerie", "Dryad", "Treefolk"}, new String[]{"add one mana"}, "mythicbotany");

        public final String label;
        final String colors;
        final String[] types;
        final String[] hints;
        /** For add-on themes: the mods that bring the mobs (any one is enough). Null for vanilla themes. */
        public final String[] mod;

        Theme(String label, String colors, String[] types, String[] hints, String... mods) {
            this.label = label;
            this.colors = colors;
            this.types = types;
            this.hints = hints;
            this.mod = mods.length == 0 ? null : mods;
        }

        /** Whether this theme is in play: vanilla themes always are; add-on themes when one of their mods is installed. */
        public boolean available() {
            if (mod == null) return true;
            for (String m : mod) if (net.minecraftforge.fml.ModList.get() != null && net.minecraftforge.fml.ModList.get().isLoaded(m)) return true;
            return false;
        }

        /** "For <mod>" note for add-on packs' tooltips. */
        public String modName() {
            return mod == null ? "" : "All the Mods add-on (" + String.join(", ", mod) + ")";
        }

        public boolean isBox() {
            return this == ENDER_DRAGON || this == WITHER;
        }
    }

    /** Sets whose boosters can drop as "set packs" (only those Card-Forge can actually print are used). */
    public static final String[] SET_POOL = {
            "M10", "M11", "M12", "M13", "M14", "M15", "ORI", "M19", "M20", "M21", "ZEN", "SOM", "ISD", "RTR", "THS",
            "KTK", "BFZ", "SOI", "KLD", "AKH", "XLN", "DOM", "GRN", "RNA", "WAR", "ELD", "THB", "IKO", "ZNR", "KHM",
            "STX", "AFR", "MID", "VOW", "NEO", "SNC", "DMU", "BRO", "ONE", "MOM", "WOE", "LCI", "MKM", "OTJ", "BLB",
            "DSK", "FDN"};

    private static final Random RNG = new Random();
    private static final Map<Theme, Map<CardRarity, List<PaperCard>>> POOLS = new EnumMap<>(Theme.class);
    private static List<String> validSets;

    // ------------------------------------------------------------------ set boosters

    /**
     * Every set Card-Forge can print a booster for (expansions, core sets, Masters sets and Universes Beyond
     * crossovers like Spider-Man, Avatar or Final Fantasy), oldest first. Digital-only sets are left out.
     */
    public static synchronized List<String> boosterSets() {
        if (validSets == null) {
            List<CardEdition> eds = new ArrayList<>();
            for (CardEdition ed : FModel.getMagicDb().getEditions()) {
                if (ed.getType() == CardEdition.Type.ONLINE || ed.getType() == CardEdition.Type.CUSTOM_SET) continue;
                try {
                    if (ed.getRandomBoosterKind() == null) continue;
                } catch (RuntimeException broken) {
                    continue;
                }
                eds.add(ed);
            }
            eds.sort(java.util.Comparator.comparing(CardEdition::getDate).thenComparing(CardEdition::getCode));
            List<String> ok = new ArrayList<>();
            for (CardEdition ed : eds) ok.add(ed.getCode());
            validSets = ok;
        }
        return validSets;
    }

    public static String randomSet() {
        List<String> sets = boosterSets();
        return sets.isEmpty() ? null : sets.get(RNG.nextInt(sets.size()));
    }

    public static String setName(String code) {
        CardEdition ed = FModel.getMagicDb().getEditions().get(code);
        return ed == null ? code : ed.getName();
    }

    /** A real booster from a set, as Card-Forge prints it. Foils are already marked by Card-Forge. */
    public static List<Pull> openSet(String code) {
        CardEdition ed = FModel.getMagicDb().getEditions().get(code);
        BoosterPack pack = ed == null ? null : BoosterPack.fromSet(ed);
        List<Pull> out = new ArrayList<>();
        if (pack == null) return out;
        for (PaperCard pc : pack.getCards()) out.add(new Pull(pc, pc.isFoil()));
        return out;
    }

    // ------------------------------------------------------------------ themed packs

    /** 10 commons, 3 uncommons, a rare (1 in 7 a mythic) and a land; 1 in 6 packs swaps a common for a foil. */
    public static List<Pull> openTheme(Theme theme) {
        Map<CardRarity, List<PaperCard>> pool = pool(theme);
        List<Pull> out = new ArrayList<>();
        addRandom(out, pool.get(CardRarity.Common), 10);
        addRandom(out, pool.get(CardRarity.Uncommon), 3);
        boolean mythic = RNG.nextInt(7) == 0 && !pool.get(CardRarity.MythicRare).isEmpty();
        addRandom(out, pool.get(mythic ? CardRarity.MythicRare : CardRarity.Rare), 1);
        PaperCard land = basicLand(theme);
        if (land != null) out.add(new Pull(land, false));
        if (RNG.nextInt(6) == 0 && !out.isEmpty()) {
            List<PaperCard> any = new ArrayList<>();
            for (List<PaperCard> l : pool.values()) any.addAll(l);
            if (!any.isEmpty()) out.set(0, new Pull(any.get(RNG.nextInt(any.size())), true));
        }
        return out;
    }

    /** A boss box: 12 packs' worth plus two extra rares. */
    public static List<List<Pull>> openBox(Theme theme) {
        List<List<Pull>> packs = new ArrayList<>();
        for (int i = 0; i < 12; i++) packs.add(openTheme(theme));
        return packs;
    }

    private static void addRandom(List<Pull> out, List<PaperCard> from, int n) {
        if (from == null || from.isEmpty()) return;
        for (int i = 0; i < n; i++) out.add(new Pull(from.get(RNG.nextInt(from.size())), false));
    }

    private static PaperCard basicLand(Theme theme) {
        String name = switch (theme.colors.charAt(RNG.nextInt(theme.colors.length()))) {
            case 'W' -> "Plains";
            case 'U' -> "Island";
            case 'B' -> "Swamp";
            case 'R' -> "Mountain";
            default -> "Forest";
        };
        return FModel.getMagicDb().getCommonCards().getCard(name);
    }

    /** Every card that fits a theme, grouped by rarity. Built once per theme (a scan of the unique cards). */
    /** Cards per rarity in a theme's pool (common, uncommon, rare, mythic), for the dev harness. */
    public static int[] poolSizes(Theme theme) {
        Map<CardRarity, List<PaperCard>> p = pool(theme);
        return new int[]{p.get(CardRarity.Common).size(), p.get(CardRarity.Uncommon).size(), p.get(CardRarity.Rare).size(),
                p.get(CardRarity.MythicRare).size()};
    }

    private static synchronized Map<CardRarity, List<PaperCard>> pool(Theme theme) {
        Map<CardRarity, List<PaperCard>> cached = POOLS.get(theme);
        if (cached != null) return cached;
        Map<CardRarity, List<PaperCard>> strict = scan(theme, true);
        // Small themes: allow any colour, as long as type or text matches.
        Map<CardRarity, List<PaperCard>> pool = strict.get(CardRarity.Common).size() < 20 ? scan(theme, false) : strict;
        POOLS.put(theme, pool);
        return pool;
    }

    private static Map<CardRarity, List<PaperCard>> scan(Theme theme, boolean colorLimited) {
        Map<CardRarity, List<PaperCard>> out = new EnumMap<>(CardRarity.class);
        for (CardRarity r : new CardRarity[]{CardRarity.Common, CardRarity.Uncommon, CardRarity.Rare, CardRarity.MythicRare}) {
            out.put(r, new ArrayList<>());
        }
        byte allowed = colorMask(theme.colors);
        for (PaperCard pc : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
            CardRules rules = pc.getRules();
            if (rules == null || rules.getType() == null || rules.getType().isLand()) continue;
            if (!paperCard(pc)) continue;
            if (!fits(theme, rules)) continue;
            if (colorLimited) {
                ColorSet cs = rules.getColor();
                if (cs != null && !cs.isColorless() && (cs.getColor() & ~allowed) != 0) continue;
            }
            List<PaperCard> bucket = out.get(pc.getRarity());
            if (bucket != null) bucket.add(pc);
        }
        // Boxes lean on their headline type: make sure rares/mythics aren't empty.
        if (out.get(CardRarity.Rare).isEmpty()) out.get(CardRarity.Rare).addAll(out.get(CardRarity.Uncommon));
        if (out.get(CardRarity.Uncommon).isEmpty()) out.get(CardRarity.Uncommon).addAll(out.get(CardRarity.Common));
        if (out.get(CardRarity.Common).isEmpty()) out.get(CardRarity.Common).addAll(out.get(CardRarity.Uncommon));
        return out;
    }

    /** Skips digital-only (Alchemy "A-" rebalances, online sets) and silver-border joke cards. */
    private static boolean paperCard(PaperCard pc) {
        if (pc.getName().startsWith("A-")) return false;
        CardEdition ed = FModel.getMagicDb().getEditions().get(pc.getEdition());
        return ed == null || (ed.getType() != CardEdition.Type.FUNNY && ed.getType() != CardEdition.Type.ONLINE);
    }

    private static boolean fits(Theme theme, CardRules rules) {
        for (String t : theme.types) {
            if (rules.getType().hasCreatureType(t)) return true;
        }
        if (theme.hints.length > 0) {
            String text = rules.getOracleText() == null ? "" : rules.getOracleText().toLowerCase(Locale.ROOT);
            for (String h : theme.hints) {
                if (text.contains(h.toLowerCase(Locale.ROOT))) return true;
            }
        }
        return false;
    }

    private static byte colorMask(String colors) {
        byte m = 0;
        for (char c : colors.toCharArray()) {
            m |= switch (c) {
                case 'W' -> MagicColor.WHITE;
                case 'U' -> MagicColor.BLUE;
                case 'B' -> MagicColor.BLACK;
                case 'R' -> MagicColor.RED;
                default -> MagicColor.GREEN;
            };
        }
        return m;
    }

    // ------------------------------------------------------------------ themed decks for mobs

    /**
     * A 40-card deck built the way a sealed player would: open {@code packs} themed packs and let Card-Forge's
     * limited deck builder pick the best cards and lands. Bosses get more packs, so stronger decks.
     */
    public static Deck themeDeck(Theme theme, int packs, String name) {
        return themeDeck(theme, packs, name, false);
    }

    /** As above; with {@code commander}, a legendary creature from the theme leads the deck. */
    public static Deck themeDeck(Theme theme, int packs, String name, boolean commander) {
        return themeDeck(theme, packs, name, commander, 0);
    }

    /**
     * As above; a non-Commander deck is grown to {@code library} cards when that's more than 40, so a mob draws
     * from a library as big as its opponent's. A tight 40-card sealed deck finds its best cards far more often than
     * a 100-card one, which made mobs much faster than the players they fought. The extra cards are the rest of
     * the opened pool (and more packs if needed) in the deck's colours, with lands kept at the same ratio.
     */
    public static Deck themeDeck(Theme theme, int packs, String name, boolean commander, int library) {
        if (commander) {
            // A full 100-card Commander deck, built by Card-Forge around a legendary creature of the theme.
            Deck sample = themeDeck(theme, packs, name, false);
            PaperCard cmd = pickCommander(theme, sample);
            if (cmd != null) {
                Deck full = forge.deck.DeckgenUtil.generateRandomCommanderDeck(cmd, forge.deck.DeckFormat.Commander, true, false);
                if (full != null) {
                    full.setName(name);
                    return full;
                }
            }
            Deck random = forge.deck.DeckgenUtil.generateCommanderDeck(true, forge.game.GameType.Commander);
            random.setName(name);
            return random;
        }
        List<PaperCard> pool = new ArrayList<>();
        for (int i = 0; i < packs; i++) {
            for (Pull p : openTheme(theme)) {
                if (!p.card().getRules().getType().isBasicLand()) pool.add(p.card());
            }
        }
        Deck deck = new SealedDeckBuilder(pool).buildDeck();
        deck.setName(name);
        if (library > deck.getMain().countAll()) growDeck(deck, theme, pool, library);
        return deck;
    }

    /** Grows a sealed deck to {@code library} cards: same land ratio, the rest from on-colour pool cards. */
    private static void growDeck(Deck deck, Theme theme, List<PaperCard> pool, int library) {
        var main = deck.getMain();
        int total = main.countAll(), lands = 0;
        byte colors = 0;
        List<PaperCard> basics = new ArrayList<>();
        for (PaperCard pc : main.toFlatList()) {
            CardRules r = pc.getRules();
            if (r.getType().isLand()) {
                lands++;
                if (r.getType().isBasicLand()) basics.add(pc);
            } else if (r.getColor() != null) {
                colors |= r.getColor().getColor();
            }
        }
        if (total == 0) return;
        int addLands = Math.max(0, Math.round((float) library * lands / total) - lands);
        int addSpells = library - total - addLands;

        // Spare cards: what the deck builder left out of the pool, then more packs, in the deck's colours.
        List<PaperCard> spare = new ArrayList<>();
        java.util.Map<String, Integer> used = new java.util.HashMap<>();
        for (PaperCard pc : main.toFlatList()) used.merge(pc.getName(), 1, Integer::sum);
        for (PaperCard pc : pool) {
            if (used.merge(pc.getName(), -1, Integer::sum) >= 0) continue;
            if (fitsColors(pc, colors)) spare.add(pc);
        }
        for (int tries = 0; spare.size() < addSpells && tries < 12; tries++) {
            for (Pull p : openTheme(theme)) {
                if (!p.card().getRules().getType().isLand() && fitsColors(p.card(), colors)) spare.add(p.card());
            }
        }
        java.util.Collections.shuffle(spare, RNG);
        for (int i = 0; i < addSpells && i < spare.size(); i++) main.add(spare.get(i), 1);
        // Anything still missing (a tiny pool) is made up with basic lands.
        int missing = library - main.countAll();
        for (int i = 0; i < missing && !basics.isEmpty(); i++) main.add(basics.get(i % basics.size()), 1);
    }

    private static boolean fitsColors(PaperCard pc, byte colors) {
        CardRules r = pc.getRules();
        if (r.getType().isLand()) return false;
        ColorSet cs = r.getColor();
        return cs == null || (cs.getColor() & ~colors) == 0;
    }

    /** A legendary creature of the theme in the deck's colours (any colour if none fits), rarest first. */
    private static PaperCard pickCommander(Theme theme, Deck deck) {
        byte deckColors = 0;
        for (PaperCard pc : deck.getMain().toFlatList()) {
            ColorSet cs = pc.getRules().getColor();
            if (cs != null && !pc.getRules().getType().isLand()) deckColors |= cs.getColor();
        }
        List<PaperCard> fits = new ArrayList<>(), any = new ArrayList<>();
        for (List<PaperCard> bucket : pool(theme).values()) {
            for (PaperCard pc : bucket) {
                if (!pc.getRules().canBeCommander() || !pc.getRules().getType().isCreature()) continue;
                any.add(pc);
                ColorSet cs = pc.getRules().getColor();
                if (cs == null || (cs.getColor() & ~deckColors) == 0) fits.add(pc);
            }
        }
        List<PaperCard> from = !fits.isEmpty() ? fits : any;
        if (from.isEmpty()) return null;
        from.sort((a, b) -> Integer.compare(rank(b), rank(a)));
        int top = Math.min(from.size(), 6);
        return from.get(RNG.nextInt(top));
    }

    private static int rank(PaperCard pc) {
        return switch (pc.getRarity()) {
            case MythicRare -> 4;
            case Rare -> 3;
            case Uncommon -> 2;
            default -> 1;
        };
    }
}

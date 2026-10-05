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
        WITHER("Wither Box", "B", new String[]{"Skeleton", "Zombie", "Horror", "Spirit"}, new String[]{"destroy target creature"});

        public final String label;
        final String colors;
        final String[] types;
        final String[] hints;

        Theme(String label, String colors, String[] types, String[] hints) {
            this.label = label;
            this.colors = colors;
            this.types = types;
            this.hints = hints;
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
        return deck;
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

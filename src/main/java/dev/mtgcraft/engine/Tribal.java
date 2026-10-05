package dev.mtgcraft.engine;

import forge.card.CardEdition;
import forge.card.CardRules;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckgenUtil;
import forge.item.PaperCard;
import forge.model.FModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Decks built around a creature type ("I want angels"): Card-Forge builds a normal deck, then its creatures are
 * swapped for creatures of the chosen type. Commander decks are led by a legend of that type.
 */
public final class Tribal {
    private Tribal() {}

    /** The creature types players can pick from: label, then the Magic creature type. */
    public static final String[][] TRIBES = {
            {"Angels", "Angel"}, {"Slimes", "Ooze"}, {"Dragons", "Dragon"}, {"Zombies", "Zombie"},
            {"Vampires", "Vampire"}, {"Elves", "Elf"}, {"Goblins", "Goblin"}, {"Merfolk", "Merfolk"},
            {"Wizards", "Wizard"}, {"Knights", "Knight"}, {"Dinosaurs", "Dinosaur"}, {"Cats", "Cat"},
            {"Spiders", "Spider"}, {"Beasts", "Beast"}, {"Elementals", "Elemental"}, {"Pirates", "Pirate"},
            {"Insects", "Insect"}, {"Demons", "Demon"}, {"Spirits", "Spirit"}, {"Faeries", "Faerie"},
            {"Soldiers", "Soldier"}, {"Wolves", "Wolf"}, {"Birds", "Bird"}, {"Skeletons", "Skeleton"}};

    private static final Random RNG = new Random();

    public static boolean known(String type) {
        for (String[] t : TRIBES) if (t[1].equals(type)) return true;
        return false;
    }

    public static String label(String type) {
        for (String[] t : TRIBES) if (t[1].equals(type)) return t[0];
        return type;
    }

    /** A 100-card Commander deck led by a legend of the type, its creatures swapped for that type where possible. */
    public static Deck commanderDeck(String type, boolean forAi) {
        PaperCard cmd = commander(type);
        Deck deck = cmd == null ? null : DeckgenUtil.generateRandomCommanderDeck(cmd, DeckFormat.Commander, forAi, false);
        if (deck == null) return DeckgenUtil.generateCommanderDeck(forAi, forge.game.GameType.Commander);
        byte identity = cmd.getRules().getColorIdentity().getColor();
        swapCreatures(deck.getMain(), type, identity, 1, true, cmd);
        deck.setName(cmd.getName() + " (" + label(type) + ")");
        return deck;
    }

    /** A 60-card deck in the type's most common colours, with as many creatures of the type as fit. */
    public static Deck deck(String type, boolean forAi) {
        int[] count = new int[5];
        for (PaperCard pc : candidates(type, (byte) 0x1F, false)) {
            ColorSet cs = pc.getRules().getColor();
            for (int i = 0; i < 5; i++) if (cs.hasAnyColor(MagicColor.WUBRG[i])) count[i]++;
        }
        int first = 0, second = -1;
        for (int i = 1; i < 5; i++) if (count[i] > count[first]) first = i;
        for (int i = 0; i < 5; i++) if (i != first && (second < 0 || count[i] > count[second])) second = i;
        List<String> colors = new ArrayList<>();
        colors.add(Duels.COLORS.get(first));
        byte mask = MagicColor.WUBRG[first];
        // A second colour only when the type really lives there too.
        if (second >= 0 && count[second] * 4 >= count[first]) {
            colors.add(Duels.COLORS.get(second));
            mask |= MagicColor.WUBRG[second];
        }
        Deck deck = DeckgenUtil.buildColorDeck(colors, FModel.getFormats().getModern().getFilterPrinted(), forAi);
        swapCreatures(deck.getMain(), type, mask, 2, false, null);
        deck.setName(label(type) + " deck");
        return deck;
    }

    /** A legendary creature of the type; ones whose text also cares about the type come first. */
    private static PaperCard commander(String type) {
        List<PaperCard> both = new ArrayList<>(), typed = new ArrayList<>(), mentions = new ArrayList<>();
        String word = type.toLowerCase(Locale.ROOT);
        for (PaperCard pc : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
            CardRules r = pc.getRules();
            if (r == null || !r.canBeCommander() || !r.getType().isCreature() || !paper(pc)) continue;
            if (!DeckFormat.Commander.isLegalCommander(r) || !DeckFormat.Commander.isLegalCard(pc)) continue;
            boolean has = r.getType().hasCreatureType(type);
            String text = r.getOracleText() == null ? "" : r.getOracleText().toLowerCase(Locale.ROOT);
            boolean says = text.contains(word);
            if (has && says) both.add(pc);
            else if (has) typed.add(pc);
            else if (says) mentions.add(pc);
        }
        List<PaperCard> from = !both.isEmpty() ? both : !typed.isEmpty() ? typed : mentions;
        return from.isEmpty() ? null : from.get(RNG.nextInt(from.size()));
    }

    /** Paper cards with the creature type whose colours fit {@code mask}. */
    private static List<PaperCard> candidates(String type, byte mask, boolean commanderLegal) {
        List<PaperCard> out = new ArrayList<>();
        for (PaperCard pc : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
            CardRules r = pc.getRules();
            if (r == null || !r.getType().isCreature() || !r.getType().hasCreatureType(type) || !paper(pc)) continue;
            if (r.getType().isLand() || r.getManaCost() == null || r.getManaCost().getCMC() > 7) continue;
            ColorSet id = commanderLegal ? r.getColorIdentity() : r.getColor();
            if (!id.hasNoColorsExcept(mask)) continue;
            if (commanderLegal && !DeckFormat.Commander.isLegalCard(pc)) continue;
            out.add(pc);
        }
        return out;
    }

    /** Replaces creatures that aren't of the type with ones that are, {@code copies} of each. */
    private static void swapCreatures(CardPool main, String type, byte mask, int copies, boolean commanderLegal, PaperCard skip) {
        List<PaperCard> pool = candidates(type, mask, commanderLegal);
        pool.removeIf(pc -> skip != null && pc.getName().equals(skip.getName()));
        pool.removeIf(pc -> main.contains(pc));
        Collections.shuffle(pool, RNG);
        List<PaperCard> off = new ArrayList<>();
        for (Map.Entry<PaperCard, Integer> e : main) {
            CardRules r = e.getKey().getRules();
            if (r.getType().isCreature() && !r.getType().isLand() && !r.getType().hasCreatureType(type)) {
                for (int i = 0; i < e.getValue(); i++) off.add(e.getKey());
            }
        }
        Collections.shuffle(off, RNG);
        int next = 0;
        for (int i = 0; i + copies <= off.size() && next < pool.size(); i += copies) {
            for (int c = 0; c < copies; c++) main.remove(off.get(i + c), 1);
            main.add(pool.get(next++), copies);
        }
        // Too few creatures in the generated deck: swap some other spells too, up to a third of the deck.
        int target = main.countAll() / 3, have = 0;
        for (Map.Entry<PaperCard, Integer> e : main) if (e.getKey().getRules().getType().hasCreatureType(type)) have += e.getValue();
        if (have >= target || next >= pool.size()) return;
        List<PaperCard> spells = new ArrayList<>();
        for (Map.Entry<PaperCard, Integer> e : main) {
            var t = e.getKey().getRules().getType();
            if (!t.isLand() && !t.isCreature() && !e.getKey().getName().equals(skip == null ? "" : skip.getName())) {
                for (int i = 0; i < e.getValue(); i++) spells.add(e.getKey());
            }
        }
        Collections.shuffle(spells, RNG);
        for (int i = 0; i + copies <= spells.size() && next < pool.size() && have < target; i += copies) {
            for (int c = 0; c < copies; c++) main.remove(spells.get(i + c), 1);
            main.add(pool.get(next++), copies);
            have += copies;
        }
    }

    private static boolean paper(PaperCard pc) {
        if (pc.getName().startsWith("A-")) return false;
        CardEdition ed = FModel.getMagicDb().getEditions().get(pc.getEdition());
        return ed == null || (ed.getType() != CardEdition.Type.FUNNY && ed.getType() != CardEdition.Type.ONLINE);
    }

}

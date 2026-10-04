package dev.mtgcraft.engine;

import forge.deck.Deck;
import forge.deck.DeckRecognizer;
import forge.deck.DeckSection;
import forge.deck.io.DeckSerializer;
import forge.localinstance.properties.ForgeConstants;
import forge.util.FileSection;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Where decks come from: Card-Forge's 505 preconstructed decks, and the player's own decks in
 * {@code <game dir>/mtgcraft/decks}. Own decks can be Card-Forge .dck files or plain decklists
 * (.txt/.dec, the text MTG Arena / MTGO / most deck sites export).
 */
public final class Decks {
    private Decks() {}

    public static Path userDir() {
        return ForgeEngine.dataDir().resolve("decks");
    }

    /** Names of the preconstructed decks, sorted. */
    public static List<String> precons() {
        return listNames(Path.of(ForgeConstants.QUEST_PRECON_DIR), ".dck");
    }

    public static Deck precon(String name) {
        return DeckSerializer.fromFile(new File(ForgeConstants.QUEST_PRECON_DIR, name + ".dck"));
    }

    /** Names of Card-Forge's Commander precon decks (100 cards with a commander), sorted. */
    public static List<String> commanderPrecons() {
        return listNames(Path.of(ForgeConstants.COMMANDER_PRECON_DIR), ".dck");
    }

    public static Deck commanderPrecon(String name) {
        return DeckSerializer.fromFile(new File(ForgeConstants.COMMANDER_PRECON_DIR, name + ".dck"));
    }

    /** True for a proper Commander deck: a commander and 100 cards in all. */
    public static boolean isCommanderReady(Deck deck) {
        if (deck == null || !deck.has(forge.deck.DeckSection.Commander)) return false;
        int total = deck.getMain().countAll() + deck.get(forge.deck.DeckSection.Commander).countAll();
        return total >= 100;
    }

    /** File names (with extension) of the player's own decks, sorted. */
    public static List<String> userDecks() {
        Path dir = userDir();
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            files.map(p -> p.getFileName().toString())
                    .filter(n -> {
                        String l = n.toLowerCase(Locale.ROOT);
                        return l.endsWith(".dck") || l.endsWith(".txt") || l.endsWith(".dec");
                    })
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(out::add);
        } catch (IOException ignored) {
        }
        return out;
    }

    public static Deck userDeck(String fileName) throws IOException {
        Path file = userDir().resolve(fileName);
        if (fileName.toLowerCase(Locale.ROOT).endsWith(".dck")) {
            return DeckSerializer.fromFile(file.toFile());
        }
        String name = fileName.replaceFirst("\\.[^.]+$", "");
        return parse(Files.readString(file), name).deck;
    }

    /** A parsed decklist plus the lines Card-Forge didn't recognise as cards it knows. */
    public record Parsed(Deck deck, int cards, List<String> unknown) {}

    public static Parsed parse(String text, String fallbackName) {
        DeckRecognizer recognizer = new DeckRecognizer();
        List<DeckRecognizer.Token> tokens = recognizer.parseCardList(text.split("\\R"));
        Deck deck = new Deck(fallbackName);
        int count = 0;
        List<String> unknown = new ArrayList<>();
        for (DeckRecognizer.Token t : tokens) {
            if (t.getType() == DeckRecognizer.TokenType.DECK_NAME && !t.getText().isBlank()) {
                deck.setName(t.getText().trim());
            } else if (t.isCardTokenForDeck() && t.getCard() != null) {
                DeckSection section = t.getTokenSection() == null ? DeckSection.Main : t.getTokenSection();
                deck.getOrCreate(section).add(t.getCard(), t.getQuantity());
                if (section == DeckSection.Main) count += t.getQuantity();
            } else if (t.getType() == DeckRecognizer.TokenType.UNKNOWN_CARD
                    || t.getType() == DeckRecognizer.TokenType.UNSUPPORTED_CARD) {
                unknown.add(t.getText());
            }
        }
        return new Parsed(deck, count, unknown);
    }

    /** Parses a pasted decklist and saves it as a .dck in the decks folder. Returns what was saved. */
    public static Parsed importText(String text) throws IOException {
        Parsed parsed = parse(text, "Imported deck");
        if (parsed.cards == 0) return parsed;
        Files.createDirectories(userDir());
        String base = parsed.deck.getName().replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (base.isEmpty()) base = "Imported deck";
        Path file = userDir().resolve(base + ".dck");
        for (int i = 2; Files.exists(file); i++) {
            file = userDir().resolve(base + " " + i + ".dck");
        }
        parsed.deck.setName(file.getFileName().toString().replaceFirst("\\.dck$", ""));
        DeckSerializer.writeDeck(parsed.deck, file.toFile());
        return parsed;
    }

    /** A deck as .dck text, for sending a player's own deck to the server. */
    public static String toText(Deck deck) throws IOException {
        File tmp = File.createTempFile("mtgcraft", ".dck");
        try {
            DeckSerializer.writeDeck(deck, tmp);
            return Files.readString(tmp.toPath());
        } finally {
            tmp.delete();
        }
    }

    public static Deck fromText(String dckText) {
        return DeckSerializer.fromSections(FileSection.parseSections(List.of(dckText.split("\\R"))));
    }

    private static List<String> listNames(Path dir, String ext) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(ext))
                    .map(n -> n.substring(0, n.length() - ext.length()))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(out::add);
        } catch (IOException ignored) {
        }
        return out;
    }
}

package dev.mtgcraft.engine;

import forge.deck.Deck;
import forge.deck.DeckRecognizer;
import forge.deck.DeckSection;
import forge.deck.io.DeckSerializer;
import forge.localinstance.properties.ForgeConstants;
import forge.util.FileSection;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
            // Read it ourselves: Card-Forge reads as UTF-8 only, and older files were saved in the Windows encoding.
            return fromText(readText(file));
        }
        String name = fileName.replaceFirst("\\.[^.]+$", "");
        return parse(readText(file), name).deck;
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
        Files.writeString(file, toText(parsed.deck), StandardCharsets.UTF_8);
        return parsed;
    }

    /**
     * A deck as .dck text, for sending a player's own deck to the server. Built in memory: Card-Forge's file writer
     * uses the computer's default encoding (not UTF-8 on Windows), which broke card names like "Lim-Dûl" or "Æther"
     * ("Couldn't read your deck: Input length = 1").
     */
    public static String toText(Deck deck) throws IOException {
        try {
            java.lang.reflect.Method serialize = DeckSerializer.class.getDeclaredMethod("serializeDeck", Deck.class);
            serialize.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> lines = (List<String>) serialize.invoke(null, deck);
            return String.join(System.lineSeparator(), lines) + System.lineSeparator();
        } catch (ReflectiveOperationException | RuntimeException noSuchMethod) {
            // A Card-Forge version without it: write a temp file and read it back in the encoding it was written in.
            File tmp = File.createTempFile("mtgcraft", ".dck");
            try {
                DeckSerializer.writeDeck(deck, tmp);
                return Files.readString(tmp.toPath(), Charset.defaultCharset());
            } finally {
                tmp.delete();
            }
        }
    }

    /** A text file as UTF-8 if it is valid UTF-8, otherwise as Windows-1252 (Notepad's old default, Card-Forge's on Windows). */
    public static String readText(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException notUtf8) {
            return new String(bytes, Charset.forName("windows-1252"));
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

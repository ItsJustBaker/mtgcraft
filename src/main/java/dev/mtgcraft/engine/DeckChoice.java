package dev.mtgcraft.engine;

import forge.deck.Deck;
import forge.deck.DeckgenUtil;
import forge.item.PaperCard;
import forge.model.FModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

/** One side's deck pick in the lobby: generated from colours, a preconstructed deck, or the player's own. */
public record DeckChoice(Kind kind, List<String> colors, String name) {
    public enum Kind { COLORS, PRECON, USER, INLINE, THEME, CMD_PRECON }

    public static DeckChoice colors(List<String> colors) { return new DeckChoice(Kind.COLORS, List.copyOf(colors), null); }
    public static DeckChoice precon(String name) { return new DeckChoice(Kind.PRECON, List.of(), name); }
    public static DeckChoice user(String fileName) { return new DeckChoice(Kind.USER, List.of(), fileName); }
    /** A whole deck carried as .dck text (a remote player's own deck); {@code name} holds the text. */
    public static DeckChoice inline(String dckText) { return new DeckChoice(Kind.INLINE, List.of(), dckText); }
    /** A Commander precon by name, or a random 100-card commander deck when {@code name} is null. */
    public static DeckChoice commanderPrecon(String name) { return new DeckChoice(Kind.CMD_PRECON, List.of(), name); }
    /** A mob's deck: built from {@code packs} packs of a theme, sealed-style. {@code colors} holds the pack count. */
    public static DeckChoice theme(Packs.Theme theme, int packs) { return theme(theme, packs, false); }
    /** With {@code commander}, the themed deck gets a legendary commander from its theme. */
    public static DeckChoice theme(Packs.Theme theme, int packs, boolean commander) {
        return new DeckChoice(Kind.THEME, commander ? List.of(String.valueOf(packs), "C") : List.of(String.valueOf(packs)), theme.name());
    }

    /** Builds the deck. Runs on the engine thread; a null precon name means a random one. */
    Deck build(boolean forAi) throws Exception {
        switch (kind) {
            case PRECON -> {
                String n = name;
                if (n == null) {
                    List<String> all = Decks.precons();
                    n = all.get(new Random().nextInt(all.size()));
                }
                return Decks.precon(n);
            }
            case USER -> {
                return Decks.userDeck(name);
            }
            case INLINE -> {
                return Decks.fromText(name);
            }
            case CMD_PRECON -> {
                if (name == null) return DeckgenUtil.generateCommanderDeck(forAi, forge.game.GameType.Commander);
                return Decks.commanderPrecon(name);
            }
            case THEME -> {
                Packs.Theme t = Packs.Theme.valueOf(name);
                int packs = colors.isEmpty() ? 6 : Integer.parseInt(colors.get(0));
                boolean commander = colors.contains("C");
                return Packs.themeDeck(t, packs, t.label.replace(" Pack", "").replace(" Box", "") + " deck", commander);
            }
            default -> {
                // Generated from Modern-legal printings, so no joke or digital-only cards.
                Predicate<PaperCard> modern = FModel.getFormats().getModern().getFilterPrinted();
                if (colors.isEmpty()) return DeckgenUtil.getRandomColorDeck(modern, forAi);
                return DeckgenUtil.buildColorDeck(new ArrayList<>(colors), modern, forAi);
            }
        }
    }
}

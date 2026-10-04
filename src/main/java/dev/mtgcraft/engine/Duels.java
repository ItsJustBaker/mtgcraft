package dev.mtgcraft.engine;

import forge.deck.Deck;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gui.GuiBase;
import forge.player.GamePlayerUtil;

import java.util.ArrayList;
import java.util.List;

/** Starts duels against the Card-Forge AI. */
public final class Duels {
    public static final List<String> COLORS = List.of("White", "Blue", "Black", "Red", "Green");

    private Duels() {}

    /** Starts a game. Returns at once; the gui fills in once the game thread is running. */
    public static DuelGui start(DeckChoice mine, DeckChoice theirs, String aiName) {
        DuelGui previous = DuelGui.active();
        if (previous != null) {
            previous.leave();
        }
        DuelGui gui = new DuelGui();
        ForgeEngine.runOnUi(() -> {
            Deck myDeck, aiDeck;
            try {
                myDeck = mine.build(false);
                aiDeck = theirs.build(true);
            } catch (Exception e) {
                gui.failed = "Couldn't load deck: " + e.getMessage();
                return;
            }
            RegisteredPlayer human = new RegisteredPlayer(myDeck).setPlayer(GamePlayerUtil.getGuiPlayer());
            RegisteredPlayer ai = new RegisteredPlayer(aiDeck).setPlayer(GamePlayerUtil.createAiPlayer(aiName, 1));
            List<RegisteredPlayer> players = new ArrayList<>(List.of(human, ai));
            HostedMatch match = GuiBase.getInterface().hostMatch();
            match.startMatch(GameType.Constructed, null, players, human, gui);
        });
        return gui;
    }

    /** Colour-based decks for both sides (empty list = random colours). */
    public static DuelGui start(List<String> myColors, List<String> aiColors, String aiName) {
        return start(DeckChoice.colors(myColors), DeckChoice.colors(aiColors), aiName);
    }
}

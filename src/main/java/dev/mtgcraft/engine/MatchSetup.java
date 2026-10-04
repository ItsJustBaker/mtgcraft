package dev.mtgcraft.engine;

import forge.game.GameType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** What a table is about to play: the mode and 2-4 seats, each an AI or a human with a deck and a team. */
public final class MatchSetup {
    public static final int MIN_SEATS = 2;
    public static final int MAX_SEATS = 4;

    public enum Mode {
        FREE_FOR_ALL("Free-for-all", "Everyone for themselves. Last player standing wins.", EnumSet.noneOf(GameType.class), false),
        TEAMS("Teams", "Pick a team for each seat. A team wins when every other team is out.", EnumSet.noneOf(GameType.class), true),
        COMMANDER("Commander", "100-card singleton decks led by a legendary commander. 40 life.", EnumSet.of(GameType.Commander), true),
        BRAWL("Brawl", "Commander with 60-card decks from recent sets.", EnumSet.of(GameType.Brawl), true),
        OATHBREAKER("Oathbreaker", "A planeswalker commander and its signature spell.", EnumSet.of(GameType.Oathbreaker), true),
        PLANECHASE("Planechase", "Roll the planar die and travel between wild planes.", EnumSet.of(GameType.Planechase), true),
        ARCHENEMY("Archenemy", "Seat 1 is the Archenemy: 40 life and a scheme deck. Everyone else teams up.", EnumSet.of(GameType.Archenemy), false),
        MOMIR("Momir Basic", "Basic lands only. Discard a land to make a random creature.", EnumSet.of(GameType.MomirBasic), true),
        MOJHOSTO("MoJhoSto", "Momir plus random instants and sorceries. Pure chaos.", EnumSet.of(GameType.MoJhoSto), true);

        public final String label;
        public final String description;
        public final Set<GameType> variants;
        /** Whether teams can be set per seat (Archenemy decides teams itself). */
        public final boolean allowsTeams;

        Mode(String label, String description, Set<GameType> variants, boolean allowsTeams) {
            this.label = label;
            this.description = description;
            this.variants = variants;
            this.allowsTeams = allowsTeams;
        }

        public boolean isCommanderStyle() {
            return variants.contains(GameType.Commander) || variants.contains(GameType.Brawl)
                    || variants.contains(GameType.Oathbreaker);
        }

        public boolean autoDecks() {
            return variants.contains(GameType.MomirBasic) || variants.contains(GameType.MoJhoSto);
        }
    }

    public static final class Seat {
        public boolean ai;
        public String name;
        /** The Minecraft player in a human seat; null for AI or an open seat. */
        public UUID player;
        public DeckChoice deck;
        /** 0 means "no team" (everyone is their own team). */
        public int team;

        public Seat(boolean ai, String name, UUID player, DeckChoice deck, int team) {
            this.ai = ai;
            this.name = name;
            this.player = player;
            this.deck = deck;
            this.team = team;
        }

        public boolean isOpen() {
            return !ai && player == null;
        }
    }

    public Mode mode = Mode.FREE_FOR_ALL;
    public final List<Seat> seats = new ArrayList<>();

    /** The team each seat actually plays on (Archenemy and free-for-all decide it themselves). */
    public int effectiveTeam(int seatIndex) {
        if (mode == Mode.ARCHENEMY) return seatIndex == 0 ? 1 : 2;
        if (mode == Mode.FREE_FOR_ALL) return seatIndex + 1;
        int t = seats.get(seatIndex).team;
        return t > 0 ? t : 100 + seatIndex;
    }
}

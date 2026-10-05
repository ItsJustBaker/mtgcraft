package dev.mtgcraft.server;

import dev.mtgcraft.engine.DeckChoice;
import dev.mtgcraft.engine.MatchSetup;
import dev.mtgcraft.net.Packets;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** One Magic Table's lobby and, while a game runs, its live seats. Server main thread only (except seats). */
final class TableSession {
    final ResourceKey<Level> dimension;
    final BlockPos pos;
    UUID host;
    final MatchSetup setup = new MatchSetup();
    /** Players with this table's screen open; they get state updates. */
    final Set<UUID> viewers = new LinkedHashSet<>();
    /** The running game's tunnels, or null. */
    DuelHosting.Handle live;
    volatile boolean running;
    String message = "";

    TableSession(ResourceKey<Level> dimension, BlockPos pos, UUID host, String hostName) {
        this.dimension = dimension;
        this.pos = pos;
        this.host = host;
        setup.seats.add(new MatchSetup.Seat(false, hostName, host, DeckChoice.colors(List.of()), 1));
        setup.seats.add(new MatchSetup.Seat(true, aiName(1), null, DeckChoice.colors(List.of()), 2));
    }

    static String aiName(int seat) {
        return "Forge AI " + seat;
    }

    int seatOf(UUID player) {
        for (int i = 0; i < setup.seats.size(); i++) {
            if (player.equals(setup.seats.get(i).player)) return i;
        }
        return -1;
    }

    Packets.TableState state(Set<UUID> readyPlayers) {
        List<Packets.SeatView> seats = new ArrayList<>();
        for (MatchSetup.Seat s : setup.seats) {
            boolean ready = s.ai || (s.player != null && readyPlayers.contains(s.player));
            seats.add(new Packets.SeatView(s.ai, s.name, s.player, deckLabel(s.deck), s.team, ready));
        }
        return new Packets.TableState(pos, host, setup.mode.ordinal(), running, message, seats);
    }

    private String deckLabel(DeckChoice c) {
        if (setup.mode.autoDecks()) return "Auto-generated";
        if (setup.mode.isCommanderStyle() && (c == null || c.kind() == DeckChoice.Kind.COLORS || c.kind() == DeckChoice.Kind.PRECON)) {
            return "Random commander deck";
        }
        if (c == null) return "Random colours";
        return switch (c.kind()) {
            case COLORS -> c.colors().isEmpty() ? "Random colours" : String.join("/", c.colors()) + " (generated)";
            case PRECON -> c.name() == null ? "Random precon" : c.name();
            case THEME -> "Themed deck";
            case TRIBE -> dev.mtgcraft.engine.Tribal.label(c.name()) + " deck";
            case CMD_PRECON -> c.name() == null ? "Random commander deck" : c.name();
            case USER -> c.name();
            case INLINE -> {
                String text = c.name() == null ? "" : c.name();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^Name=(.*)$").matcher(text);
                yield m.find() ? m.group(1).trim() : "Own deck";
            }
        };
    }
}

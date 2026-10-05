package dev.mtgcraft.net;

import dev.mtgcraft.engine.DeckChoice;
import dev.mtgcraft.server.TableSessions;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** All packets. Server-bound ones go to {@link TableSessions}; client-bound ones to the client's ClientTables. */
public final class Packets {
    private Packets() {}

    // ------------------------------------------------------------------ duel byte tunnel (both directions)

    /** A chunk of Card-Forge protocol bytes for one duel seat. */
    public record Tunnel(int session, byte[] data) {
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(session);
            buf.writeByteArray(data);
        }

        public static Tunnel read(FriendlyByteBuf buf) {
            return new Tunnel(buf.readVarInt(), buf.readByteArray());
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer sender = ctx.get().getSender();
            if (sender != null) {
                TableSessions.onTunnel(sender, this);
            } else {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> dev.mtgcraft.client.ClientTables.onTunnel(this));
            }
            ctx.get().setPacketHandled(true);
        }
    }

    /**
     * Tells a client that a duel it's seated in is starting; its traffic uses {@code session}. {@code gauntlet} is
     * true for a Duel Gauntlet fight (no table to go back to afterwards).
     */
    public record DuelStart(int session, BlockPos table, boolean gauntlet) {
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(session);
            buf.writeBlockPos(table);
            buf.writeBoolean(gauntlet);
        }

        public static DuelStart read(FriendlyByteBuf buf) {
            return new DuelStart(buf.readVarInt(), buf.readBlockPos(), buf.readBoolean());
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> dev.mtgcraft.client.ClientTables.onDuelStart(this));
            ctx.get().setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------------ 3D arena

    /** Who sits in a seat: the seat's player name (as in the game) and the entity it belongs to (-1: none). */
    public record ArenaSeat(String name, int entity) {}

    /** Where a duel's 3D battlefield is drawn, keyed like the duel itself. */
    public record Arena(BlockPos key, double x, double y, double z, float radius, boolean boss, List<ArenaSeat> seats) {
        public void write(FriendlyByteBuf buf) {
            buf.writeBlockPos(key);
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeDouble(z);
            buf.writeFloat(radius);
            buf.writeBoolean(boss);
            buf.writeVarInt(seats.size());
            for (ArenaSeat s : seats) {
                buf.writeUtf(s.name);
                buf.writeInt(s.entity);
            }
        }

        public static Arena read(FriendlyByteBuf buf) {
            BlockPos key = buf.readBlockPos();
            double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
            float radius = buf.readFloat();
            boolean boss = buf.readBoolean();
            int n = buf.readVarInt();
            List<ArenaSeat> seats = new ArrayList<>();
            for (int i = 0; i < n; i++) seats.add(new ArenaSeat(buf.readUtf(), buf.readInt()));
            return new Arena(key, x, y, z, radius, boss, seats);
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> dev.mtgcraft.client.ArenaRenderer.put(this));
        }
    }

    // ------------------------------------------------------------------ booster packs

    /** One card pulled from a pack, as the opening animation needs it. */
    public record Pull(String card, boolean foil, char rarity) {}

    /** A pack was opened; the cards are already in the inventory, this plays the reveal. */
    public record PackOpened(String title, String wrapper, List<Pull> cards) {
        public void write(FriendlyByteBuf buf) {
            buf.writeUtf(title);
            buf.writeUtf(wrapper);
            buf.writeVarInt(cards.size());
            for (Pull p : cards) {
                buf.writeUtf(p.card);
                buf.writeBoolean(p.foil);
                buf.writeChar(p.rarity);
            }
        }

        public static PackOpened read(FriendlyByteBuf buf) {
            String title = buf.readUtf();
            String wrapper = buf.readUtf();
            int n = buf.readVarInt();
            List<Pull> cards = new ArrayList<>();
            for (int i = 0; i < n; i++) cards.add(new Pull(buf.readUtf(), buf.readBoolean(), buf.readChar()));
            return new PackOpened(title, wrapper, cards);
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> dev.mtgcraft.client.PackOpeningScreen.show(this));
        }
    }

    // ------------------------------------------------------------------ binders and deck boxes

    public enum CollOp { BINDER_PUT, BINDER_PUT_ALL, BINDER_TAKE, DECK_ADD, DECK_REMOVE, DECK_COMMANDER }

    /** Move cards between the inventory and the binder or deck box held in a hand. */
    public record CollectionOp(boolean offhand, CollOp op, String card, boolean foil, int count) {
        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(offhand);
            buf.writeEnum(op);
            buf.writeUtf(card);
            buf.writeBoolean(foil);
            buf.writeVarInt(count);
        }

        public static CollectionOp read(FriendlyByteBuf buf) {
            return new CollectionOp(buf.readBoolean(), buf.readEnum(CollOp.class), buf.readUtf(), buf.readBoolean(), buf.readVarInt());
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer p = ctx.get().getSender();
            if (p != null) dev.mtgcraft.server.CollectionOps.handle(p, this);
        }
    }

    // ------------------------------------------------------------------ popups

    /** Creative mode only: win the duel you're in right now. */
    public record CheatWin() {
        public void write(FriendlyByteBuf buf) {}
        public static CheatWin read(FriendlyByteBuf buf) { return new CheatWin(); }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer p = ctx.get().getSender();
            if (p != null) dev.mtgcraft.server.DuelHosting.cheatWin(p);
        }
    }

    /**
     * A small popup with buttons (duel invites, join requests). Each button runs a command as the player, e.g.
     * {@code /mtgduel accept Steve}. {@code seconds} is how long the offer lasts (0: no timer).
     */
    public record Prompt(String title, String body, List<String> buttons, List<String> commands, int seconds) {
        public void write(FriendlyByteBuf buf) {
            buf.writeUtf(title);
            buf.writeUtf(body);
            buf.writeVarInt(buttons.size());
            for (int i = 0; i < buttons.size(); i++) {
                buf.writeUtf(buttons.get(i));
                buf.writeUtf(commands.get(i));
            }
            buf.writeVarInt(seconds);
        }

        public static Prompt read(FriendlyByteBuf buf) {
            String title = buf.readUtf(), body = buf.readUtf();
            int n = buf.readVarInt();
            List<String> buttons = new ArrayList<>(), commands = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                buttons.add(buf.readUtf());
                commands.add(buf.readUtf());
            }
            return new Prompt(title, body, buttons, commands, buf.readVarInt());
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> dev.mtgcraft.client.PromptScreen.show(this));
        }
    }

    /** What a player won from a duel, for the rewards screen. */
    public record Rewards(String title, String subtitle, List<net.minecraft.world.item.ItemStack> items) {
        public void write(FriendlyByteBuf buf) {
            buf.writeUtf(title);
            buf.writeUtf(subtitle);
            buf.writeVarInt(items.size());
            for (var s : items) buf.writeItem(s);
        }

        public static Rewards read(FriendlyByteBuf buf) {
            String title = buf.readUtf(), sub = buf.readUtf();
            int n = buf.readVarInt();
            List<net.minecraft.world.item.ItemStack> items = new ArrayList<>();
            for (int i = 0; i < n; i++) items.add(buf.readItem());
            return new Rewards(title, sub, items);
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> dev.mtgcraft.client.RewardsScreen.show(this));
        }
    }

    // ------------------------------------------------------------------ starter deck

    public record OfferStarter(List<String> names) {
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(names.size());
            for (String n : names) buf.writeUtf(n);
        }

        public static OfferStarter read(FriendlyByteBuf buf) {
            int n = buf.readVarInt();
            List<String> names = new ArrayList<>();
            for (int i = 0; i < n; i++) names.add(buf.readUtf());
            return new OfferStarter(names);
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> dev.mtgcraft.client.StarterScreen.show(this));
        }
    }

    public record ChooseStarter(int choice) {
        public void write(FriendlyByteBuf buf) { buf.writeVarInt(choice); }
        public static ChooseStarter read(FriendlyByteBuf buf) { return new ChooseStarter(buf.readVarInt()); }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer p = ctx.get().getSender();
            if (p != null) dev.mtgcraft.server.StarterKits.choose(p, choice);
        }
    }

    /** Fill the Universal Deck Box in a hand with a deck choice. */
    public record SetUniversalDeck(boolean offhand, String choice, String label) {
        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(offhand);
            buf.writeUtf(choice, 1 << 16);
            buf.writeUtf(label);
        }

        public static SetUniversalDeck read(FriendlyByteBuf buf) {
            return new SetUniversalDeck(buf.readBoolean(), buf.readUtf(1 << 16), buf.readUtf());
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer p = ctx.get().getSender();
            if (p == null) return;
            var stack = p.getItemInHand(offhand ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND);
            if (stack.getItem() instanceof dev.mtgcraft.item.UniversalDeckBoxItem) {
                dev.mtgcraft.item.UniversalDeckBoxItem.set(stack, choice, label);
            }
        }
    }

    // ------------------------------------------------------------------ settings

    /** One editable server setting: key, label, current value, the values it cycles through. */
    public record Setting(String key, String label, String value, List<String> options) {}

    public record SettingsRequest() {
        public void write(FriendlyByteBuf buf) { }
        public static SettingsRequest read(FriendlyByteBuf buf) { return new SettingsRequest(); }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer p = ctx.get().getSender();
            if (p != null) dev.mtgcraft.server.ServerSettings.send(p);
        }
    }

    public record SettingsSet(String key, String value) {
        public void write(FriendlyByteBuf buf) { buf.writeUtf(key); buf.writeUtf(value); }
        public static SettingsSet read(FriendlyByteBuf buf) { return new SettingsSet(buf.readUtf(), buf.readUtf()); }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer p = ctx.get().getSender();
            if (p != null) dev.mtgcraft.server.ServerSettings.set(p, key, value);
        }
    }

    public record SettingsState(boolean canEdit, List<Setting> settings) {
        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(canEdit);
            buf.writeVarInt(settings.size());
            for (Setting s : settings) {
                buf.writeUtf(s.key());
                buf.writeUtf(s.label());
                buf.writeUtf(s.value());
                buf.writeVarInt(s.options().size());
                for (String o : s.options()) buf.writeUtf(o);
            }
        }

        public static SettingsState read(FriendlyByteBuf buf) {
            boolean canEdit = buf.readBoolean();
            int n = buf.readVarInt();
            List<Setting> out = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String key = buf.readUtf(), label = buf.readUtf(), value = buf.readUtf();
                int m = buf.readVarInt();
                List<String> opts = new ArrayList<>();
                for (int j = 0; j < m; j++) opts.add(buf.readUtf());
                out.add(new Setting(key, label, value, opts));
            }
            return new SettingsState(canEdit, out);
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> dev.mtgcraft.client.SettingsScreen.onState(this));
        }
    }

    // ------------------------------------------------------------------ table lobby

    /** The player right-clicked a table (or reopened its screen). */
    public record OpenTable(BlockPos table) {
        public void write(FriendlyByteBuf buf) { buf.writeBlockPos(table); }
        public static OpenTable read(FriendlyByteBuf buf) { return new OpenTable(buf.readBlockPos()); }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer p = ctx.get().getSender();
            if (p != null) TableSessions.open(p, table);
        }
    }

    public enum Op {
        SET_MODE, ADD_SEAT, REMOVE_SEAT, SEAT_AI, SEAT_OPEN, SIT, STAND, SET_TEAM, SET_DECK, START, CLOSE
    }

    /** A lobby change. {@code seat}, {@code number} and {@code text} mean different things per op. */
    public record EditTable(BlockPos table, Op op, int seat, int number, String text) {
        public void write(FriendlyByteBuf buf) {
            buf.writeBlockPos(table);
            buf.writeEnum(op);
            buf.writeVarInt(seat);
            buf.writeVarInt(number);
            buf.writeUtf(text, 1 << 16);
        }

        public static EditTable read(FriendlyByteBuf buf) {
            return new EditTable(buf.readBlockPos(), buf.readEnum(Op.class), buf.readVarInt(), buf.readVarInt(), buf.readUtf(1 << 16));
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer p = ctx.get().getSender();
            if (p != null) TableSessions.edit(p, this);
        }
    }

    /** The client finished loading Card-Forge, so it can be dealt in. */
    public record EngineReady() {
        public void write(FriendlyByteBuf buf) { }
        public static EngineReady read(FriendlyByteBuf buf) { return new EngineReady(); }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer p = ctx.get().getSender();
            if (p != null) TableSessions.engineReady(p);
        }
    }

    /** One seat as everyone at the table sees it. */
    public record SeatView(boolean ai, String name, UUID player, String deck, int team, boolean ready) {
        public boolean open() { return !ai && player == null; }
    }

    /** The whole lobby, sent to everyone looking at a table whenever it changes. */
    public record TableState(BlockPos table, UUID host, int mode, boolean running, String message, List<SeatView> seats) {
        public void write(FriendlyByteBuf buf) {
            buf.writeBlockPos(table);
            buf.writeUUID(host);
            buf.writeVarInt(mode);
            buf.writeBoolean(running);
            buf.writeUtf(message);
            buf.writeVarInt(seats.size());
            for (SeatView s : seats) {
                buf.writeBoolean(s.ai);
                buf.writeUtf(s.name);
                buf.writeBoolean(s.player != null);
                if (s.player != null) buf.writeUUID(s.player);
                buf.writeUtf(s.deck);
                buf.writeVarInt(s.team);
                buf.writeBoolean(s.ready);
            }
        }

        public static TableState read(FriendlyByteBuf buf) {
            BlockPos pos = buf.readBlockPos();
            UUID host = buf.readUUID();
            int mode = buf.readVarInt();
            boolean running = buf.readBoolean();
            String message = buf.readUtf();
            int n = buf.readVarInt();
            List<SeatView> seats = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                boolean ai = buf.readBoolean();
                String name = buf.readUtf();
                UUID player = buf.readBoolean() ? buf.readUUID() : null;
                seats.add(new SeatView(ai, name, player, buf.readUtf(), buf.readVarInt(), buf.readBoolean()));
            }
            return new TableState(pos, host, mode, running, message, seats);
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> dev.mtgcraft.client.ClientTables.onState(this));
        }
    }

    // ------------------------------------------------------------------ deck choices as text

    /** Packs a deck choice into one string: kind, then colours, then the name or deck text. */
    public static String encode(DeckChoice c) {
        if (c == null) return "";
        return c.kind().name() + "\u0001" + String.join(",", c.colors()) + "\u0001" + (c.name() == null ? "" : c.name());
    }

    public static DeckChoice decode(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] parts = s.split("\u0001", 3);
        DeckChoice.Kind kind = DeckChoice.Kind.valueOf(parts[0]);
        List<String> colors = parts.length > 1 && !parts[1].isEmpty() ? List.of(parts[1].split(",")) : List.of();
        String name = parts.length > 2 && !parts[2].isEmpty() ? parts[2] : null;
        return new DeckChoice(kind, colors, name);
    }
}

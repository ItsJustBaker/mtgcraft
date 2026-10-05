package dev.mtgcraft.server;

import dev.mtgcraft.MtgConfig;
import dev.mtgcraft.net.Net;
import dev.mtgcraft.net.Packets;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * The server settings anyone can see from a Magic Table, and the world host or an operator can change there.
 * They are the same values as config/mtgcraft-common.toml.
 */
public final class ServerSettings {
    private static final String[] CHANCES = {"0", "0.01", "0.02", "0.04", "0.08", "0.15", "0.3"};

    private ServerSettings() {}

    /** The single-player owner, or an operator (permission level 2). */
    public static boolean canEdit(ServerPlayer p) {
        return p.hasPermissions(2) || p.getServer().isSingleplayerOwner(p.getGameProfile());
    }

    private static List<Packets.Setting> current() {
        List<Packets.Setting> out = new ArrayList<>();
        out.add(new Packets.Setting("duelMode", "Duel rules", MtgConfig.DUEL_MODE.get().name(), List.of("COMMANDER", "CLASSIC")));
        out.add(new Packets.Setting("lossPenalty", "Losing a duel", MtgConfig.LOSS_PENALTY.get().name(), List.of("DEATH", "DAMAGE", "NONE")));
        out.add(new Packets.Setting("groupFights", "Gauntlet default", String.valueOf(MtgConfig.GROUP_FIGHTS.get()), List.of("false", "true")));
        out.add(new Packets.Setting("themedPackChance", "Themed pack drops", trim(MtgConfig.PACK_DROP_CHANCE.get()), List.of(CHANCES)));
        out.add(new Packets.Setting("setPackChance", "Set pack drops", trim(MtgConfig.SET_PACK_DROP_CHANCE.get()), List.of(CHANCES)));
        out.add(new Packets.Setting("starterKit", "Starter deck for new players", String.valueOf(MtgConfig.STARTER_KIT.get()), List.of("true", "false")));
        return out;
    }

    private static String trim(double v) {
        String s = String.valueOf(v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    public static void send(ServerPlayer p) {
        Net.toPlayer(p, new Packets.SettingsState(canEdit(p), current()));
    }

    public static void set(ServerPlayer p, String key, String value) {
        if (!canEdit(p)) {
            send(p);
            return;
        }
        try {
            switch (key) {
                case "duelMode" -> MtgConfig.DUEL_MODE.set(MtgConfig.DuelMode.valueOf(value));
                case "lossPenalty" -> MtgConfig.LOSS_PENALTY.set(MtgConfig.LossPenalty.valueOf(value));
                case "groupFights" -> MtgConfig.GROUP_FIGHTS.set(Boolean.parseBoolean(value));
                case "themedPackChance" -> MtgConfig.PACK_DROP_CHANCE.set(Math.max(0, Math.min(1, Double.parseDouble(value))));
                case "setPackChance" -> MtgConfig.SET_PACK_DROP_CHANCE.set(Math.max(0, Math.min(1, Double.parseDouble(value))));
                case "starterKit" -> MtgConfig.STARTER_KIT.set(Boolean.parseBoolean(value));
                default -> { }
            }
            MtgConfig.SPEC.save();
        } catch (IllegalArgumentException ignored) {
            // a bad value from a modified client; keep the old one
        }
        send(p);
    }
}

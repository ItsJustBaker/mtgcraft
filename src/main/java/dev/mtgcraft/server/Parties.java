package dev.mtgcraft.server;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.net.Net;
import dev.mtgcraft.net.Packets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Parties and holo battles, all through pop-up menus (the commands behind them are only what the buttons send).
 * Party members anywhere on the server can join each other's mob duels: they're saved where they stand, beamed in
 * with a ghostly glow, and sent back when the duel ends (before any loss penalty, so a grave lands where they were).
 */
@Mod.EventBusSubscriber(modid = MtgCraft.MODID)
public final class Parties {
    private static final String TAG_HOME = "mtgcraft_holo_home";
    /** member -> leader (the leader maps to themself). */
    private static final Map<UUID, UUID> LEADER = new HashMap<>();
    /** invitee -> who invited them. */
    private static final Map<UUID, UUID> INVITES = new HashMap<>();

    private Parties() {}

    static Set<UUID> partyOf(UUID id) {
        UUID leader = LEADER.get(id);
        Set<UUID> out = new HashSet<>();
        if (leader == null) return out;
        for (Map.Entry<UUID, UUID> e : LEADER.entrySet()) if (e.getValue().equals(leader)) out.add(e.getKey());
        return out;
    }

    static boolean together(ServerPlayer a, ServerPlayer b) {
        return partyOf(a.getUUID()).contains(b.getUUID());
    }

    /** Party members who aren't close by but could beam into this player's duel. */
    static List<ServerPlayer> holoCandidates(ServerPlayer host, List<ServerPlayer> nearby) {
        List<ServerPlayer> out = new ArrayList<>();
        for (UUID id : partyOf(host.getUUID())) {
            if (id.equals(host.getUUID())) continue;
            ServerPlayer p = host.getServer().getPlayerList().getPlayer(id);
            if (p == null || nearby.contains(p) || !p.isAlive() || GauntletDuels.inDuel(id)) continue;
            out.add(p);
        }
        return out;
    }

    static void offerHolo(ServerPlayer to, ServerPlayer host, Mob target) {
        String h = host.getGameProfile().getName();
        Net.toPlayer(to, new Packets.Prompt("Holo battle!", h + " is dueling " + target.getDisplayName().getString()
                + ". Beam in as a hologram? You'll come right back here afterwards.",
                List.of("Beam in", "No thanks"), List.of("/mtgparty holo " + h, ""), 10));
    }

    /** Beams a party member into the host's duel lobby; they're sent home if they can't join after all. */
    private static int holo(ServerPlayer p, ServerPlayer host) {
        if (!together(p, host)) return 0;
        if (!GauntletDuels.lobbyOpenFor(host, p)) {
            p.displayClientMessage(Component.literal("That duel already started."), true);
            return 0;
        }
        CompoundTag home = new CompoundTag();
        home.putString("dim", p.level().dimension().location().toString());
        home.putDouble("x", p.getX());
        home.putDouble("y", p.getY());
        home.putDouble("z", p.getZ());
        home.putFloat("yaw", p.getYRot());
        home.putFloat("pitch", p.getXRot());
        persisted(p).put(TAG_HOME, home);
        p.teleportTo(host.serverLevel(), host.getX() + 1.5, host.getY(), host.getZ() + 1.5, host.getYRot(), 0);
        p.addEffect(new MobEffectInstance(MobEffects.GLOWING, 20 * 60 * 30, 0, false, false));
        GauntletDuels.join(p, host);
        if (!GauntletDuels.joinedLobby(host, p)) sendHome(p);
        else Quests.award(p, "holo");
        return 1;
    }

    /** A hologram goes back to where it came from (no-op for everyone else). */
    public static void sendHome(ServerPlayer p) {
        CompoundTag data = persisted(p);
        if (!data.contains(TAG_HOME)) return;
        CompoundTag home = data.getCompound(TAG_HOME);
        data.remove(TAG_HOME);
        ServerLevel level = p.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(home.getString("dim"))));
        if (level == null) level = p.getServer().overworld();
        p.teleportTo(level, home.getDouble("x"), home.getDouble("y"), home.getDouble("z"), home.getFloat("yaw"), home.getFloat("pitch"));
        p.removeEffect(MobEffects.GLOWING);
    }

    private static CompoundTag persisted(Player p) {
        CompoundTag data = p.getPersistentData();
        CompoundTag kept = data.getCompound(Player.PERSISTED_NBT_TAG);
        data.put(Player.PERSISTED_NBT_TAG, kept);
        return kept;
    }

    @SubscribeEvent
    public static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        // Logged out mid holo battle: back home.
        if (event.getEntity() instanceof ServerPlayer p && !GauntletDuels.inDuel(p.getUUID())) sendHome(p);
    }

    @SubscribeEvent
    public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        INVITES.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mtgparty")
                .then(Commands.literal("invite").then(Commands.argument("player", StringArgumentType.word())
                        .executes(c -> other(c.getSource().getPlayer(), StringArgumentType.getString(c, "player"), Parties::invite))))
                .then(Commands.literal("accept").then(Commands.argument("player", StringArgumentType.word())
                        .executes(c -> other(c.getSource().getPlayer(), StringArgumentType.getString(c, "player"), Parties::accept))))
                .then(Commands.literal("holo").then(Commands.argument("player", StringArgumentType.word())
                        .executes(c -> other(c.getSource().getPlayer(), StringArgumentType.getString(c, "player"), Parties::holo))))
                .then(Commands.literal("leave").executes(c -> leave(c.getSource().getPlayer()))));
    }

    private interface Action {
        int run(ServerPlayer me, ServerPlayer other);
    }

    private static int other(ServerPlayer me, String name, Action a) {
        if (me == null) return 0;
        ServerPlayer o = me.getServer().getPlayerList().getPlayerByName(name);
        if (o == null || o == me) {
            me.displayClientMessage(Component.literal(name + " isn't online."), false);
            return 0;
        }
        return a.run(me, o);
    }

    private static int invite(ServerPlayer me, ServerPlayer o) {
        if (together(me, o)) {
            me.displayClientMessage(Component.literal("You're already in a party together."), true);
            return 0;
        }
        INVITES.put(o.getUUID(), me.getUUID());
        String n = me.getGameProfile().getName();
        Net.toPlayer(o, new Packets.Prompt("Party invite", n + " invites you to their party. Party members can beam into each other's duels from anywhere.",
                List.of("Join", "No thanks"), List.of("/mtgparty accept " + n, ""), 60));
        me.displayClientMessage(Component.literal("Party invite sent to " + o.getGameProfile().getName() + "."), true);
        return 1;
    }

    private static int accept(ServerPlayer me, ServerPlayer o) {
        if (!o.getUUID().equals(INVITES.remove(me.getUUID()))) {
            me.displayClientMessage(Component.literal("That party invite has expired."), true);
            return 0;
        }
        leave(me);
        UUID leader = LEADER.getOrDefault(o.getUUID(), o.getUUID());
        LEADER.put(o.getUUID(), leader);
        LEADER.put(me.getUUID(), leader);
        for (UUID id : partyOf(me.getUUID())) {
            ServerPlayer p = me.getServer().getPlayerList().getPlayer(id);
            if (p != null) p.displayClientMessage(Component.literal(me.getGameProfile().getName() + " joined the party!").withStyle(ChatFormatting.GOLD), false);
        }
        return 1;
    }

    static int leave(ServerPlayer me) {
        if (me == null || LEADER.remove(me.getUUID()) == null) return 0;
        // If the leader left, another member leads.
        UUID was = me.getUUID(), next = null;
        for (Map.Entry<UUID, UUID> e : LEADER.entrySet()) if (e.getValue().equals(was) && next == null) next = e.getKey();
        if (next != null) for (Map.Entry<UUID, UUID> e : LEADER.entrySet()) if (e.getValue().equals(was)) e.setValue(next);
        // A party of one is no party.
        if (next != null && partyOf(next).size() < 2) LEADER.remove(next);
        me.displayClientMessage(Component.literal("You left the party."), true);
        return 1;
    }
}

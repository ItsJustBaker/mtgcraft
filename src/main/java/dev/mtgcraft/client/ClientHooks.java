package dev.mtgcraft.client;

import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.engine.ForgeEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraftforge.fml.loading.FMLPaths;

public final class ClientHooks {
    private ClientHooks() {}

    /** Starts loading Card-Forge on this client (no-op if it's already loading or loaded). */
    public static void startEngine() {
        ForgeEngine.start(FMLPaths.GAMEDIR.get().resolve("mtgcraft"),
                () -> MtgCraft.class.getResourceAsStream("/mtgcraft_data/res.zip"),
                Minecraft.getInstance().getUser().getName());
    }

    /** Choose the deck held by a Universal Deck Box. */
    public static void pickUniversalDeck(net.minecraft.world.InteractionHand hand) {
        startEngine();
        Minecraft mc = Minecraft.getInstance();
        if (ForgeEngine.state() != ForgeEngine.State.READY) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("Cards are still loading..."), true);
            return;
        }
        mc.setScreen(new DeckPickerScreen(null, "Universal Deck Box", true, choice -> {
            String label = switch (choice.kind()) {
                case PRECON -> choice.name() == null ? "Random precon" : choice.name();
                case CMD_PRECON -> choice.name() == null ? "Random commander deck" : choice.name();
                case INLINE -> {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^Name=(.*)$").matcher(choice.name());
                    yield m.find() ? m.group(1).trim() : "Own deck";
                }
                default -> choice.colors().isEmpty() ? "Random colours" : String.join("/", choice.colors());
            };
            dev.mtgcraft.net.Net.toServer(new dev.mtgcraft.net.Packets.SetUniversalDeck(
                    hand == net.minecraft.world.InteractionHand.OFF_HAND, dev.mtgcraft.net.Packets.encode(choice), label));
        }));
    }

    /** Right-click on a Magic Table. */
    public static void openTable(BlockPos pos) {
        startEngine();
        ClientTables.open(pos.immutable());
    }
}

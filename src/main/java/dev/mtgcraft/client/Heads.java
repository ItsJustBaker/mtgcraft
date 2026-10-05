package dev.mtgcraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;

/**
 * Small portraits for the duel screen: a player's skin face, or the head of the mob they're dueling (its vanilla
 * mob head when there is one, otherwise the mob itself, shrunk to fit).
 */
public final class Heads {
    private static final Map<EntityType<?>, ItemStack> MOB_HEADS = Map.of(
            EntityType.ZOMBIE, new ItemStack(Items.ZOMBIE_HEAD),
            EntityType.SKELETON, new ItemStack(Items.SKELETON_SKULL),
            EntityType.STRAY, new ItemStack(Items.SKELETON_SKULL),
            EntityType.WITHER_SKELETON, new ItemStack(Items.WITHER_SKELETON_SKULL),
            EntityType.CREEPER, new ItemStack(Items.CREEPER_HEAD),
            EntityType.ENDER_DRAGON, new ItemStack(Items.DRAGON_HEAD),
            EntityType.PIGLIN, new ItemStack(Items.PIGLIN_HEAD));

    private Heads() {}

    /** Draws {@code name}'s portrait in a {@code size}-pixel square at (x, y). Returns false if there's none. */
    public static boolean draw(GuiGraphics g, BlockPos duelKey, String name, int x, int y, int size) {
        Minecraft mc = Minecraft.getInstance();
        Entity e = ArenaRenderer.entityFor(duelKey, name);
        // A player: their skin's face (also for table games, where there's no arena).
        PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(name);
        if (e instanceof Player || (e == null && info != null)) {
            if (info == null) return false;
            PlayerFaceRenderer.draw(g, info.getSkinLocation(), x, y, size);
            return true;
        }
        if (!(e instanceof LivingEntity mob)) return false;
        // A jockey: show the rider.
        Entity face = mob;
        for (Entity p : mob.getIndirectPassengers()) if (p instanceof LivingEntity) face = p;
        ItemStack head = MOB_HEADS.get(face.getType());
        if (head != null) {
            g.pose().pushPose();
            g.pose().translate(x, y, 0);
            g.pose().scale(size / 16f, size / 16f, 1);
            g.renderItem(head, 0, 0);
            g.pose().popPose();
            return true;
        }
        if (!(face instanceof LivingEntity living)) return false;
        // Any other mob: draw it small, clipped to the square, looking out of the screen.
        g.enableScissor(x, y, x + size, y + size);
        float h = Math.max(0.4f, living.getBbHeight());
        int scale = (int) Math.max(4, size * 1.6f / Math.max(h, living.getBbWidth()));
        int feetY = (int) (y + Math.min(size + h * scale * 0.35f, h * scale));
        try {
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, x + size / 2, feetY, scale,
                    x + size / 2f, feetY - h * scale * 0.8f, living);
        } catch (RuntimeException modelTrouble) {
            // a modded mob that can't render in a GUI: no portrait
        }
        g.disableScissor();
        return true;
    }
}

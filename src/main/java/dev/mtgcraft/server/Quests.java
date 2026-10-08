package dev.mtgcraft.server;

import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/** The MTGCraft quest tab (Advancements, key L): the duel-based quests are granted from here. */
public final class Quests {
    private static final String TAG_WINS = "mtgcraft_duel_wins";

    private Quests() {}

    public static void award(ServerPlayer p, String quest) {
        Advancement adv = p.getServer().getAdvancements().getAdvancement(new ResourceLocation("mtgcraft", "quests/" + quest));
        if (adv == null) return;
        AdvancementProgress progress = p.getAdvancements().getOrStartProgress(adv);
        if (progress.isDone()) return;
        for (String c : progress.getRemainingCriteria()) p.getAdvancements().award(adv, c);
    }

    /** A mob duel was won: first win, ten wins, and boss quests. */
    public static void won(ServerPlayer p, boolean boss) {
        CompoundTag data = p.getPersistentData();
        CompoundTag kept = data.getCompound(Player.PERSISTED_NBT_TAG);
        data.put(Player.PERSISTED_NBT_TAG, kept);
        int wins = kept.getInt(TAG_WINS) + 1;
        kept.putInt(TAG_WINS, wins);
        award(p, "first_win");
        if (wins >= 10) award(p, "ten_wins");
        if (boss) award(p, "boss");
    }
}

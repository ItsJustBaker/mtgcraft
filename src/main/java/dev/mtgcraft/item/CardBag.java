package dev.mtgcraft.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Cards stored inside an item (binder pages, a deck box) as NBT: a list of (card key, foil, count). Each method
 * reads and writes the stack's tag directly, so it always reflects what the item really holds.
 */
public final class CardBag {
    private static final String TAG = "Cards";

    public record Entry(String key, boolean foil, int count) {}

    private CardBag() {}

    public static List<Entry> entries(ItemStack stack) {
        List<Entry> out = new ArrayList<>();
        CompoundTag tag = stack.getTag();
        if (tag == null) return out;
        ListTag list = tag.getList(TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            out.add(new Entry(e.getString("k"), e.getBoolean("f"), e.getInt("n")));
        }
        return out;
    }

    public static int total(ItemStack stack) {
        int n = 0;
        for (Entry e : entries(stack)) n += e.count();
        return n;
    }

    public static int count(ItemStack stack, String key, boolean foil) {
        for (Entry e : entries(stack)) if (e.key().equals(key) && e.foil() == foil) return e.count();
        return 0;
    }

    public static void add(ItemStack stack, String key, boolean foil, int n) {
        if (n <= 0) return;
        ListTag list = stack.getOrCreateTag().getList(TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            if (e.getString("k").equals(key) && e.getBoolean("f") == foil) {
                e.putInt("n", e.getInt("n") + n);
                stack.getOrCreateTag().put(TAG, list);
                return;
            }
        }
        CompoundTag e = new CompoundTag();
        e.putString("k", key);
        if (foil) e.putBoolean("f", true);
        e.putInt("n", n);
        list.add(e);
        stack.getOrCreateTag().put(TAG, list);
    }

    /** Removes up to n copies; returns how many were removed. */
    public static int remove(ItemStack stack, String key, boolean foil, int n) {
        CompoundTag tag = stack.getTag();
        if (tag == null || n <= 0) return 0;
        ListTag list = tag.getList(TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            if (e.getString("k").equals(key) && e.getBoolean("f") == foil) {
                int have = e.getInt("n");
                int take = Math.min(have, n);
                if (have - take <= 0) list.remove(i);
                else e.putInt("n", have - take);
                tag.put(TAG, list);
                return take;
            }
        }
        return 0;
    }

    public static void clear(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null) tag.remove(TAG);
    }
}

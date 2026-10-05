package dev.mtgcraft.item;

import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.engine.Cards;
import dev.mtgcraft.engine.ForgeEngine;
import dev.mtgcraft.engine.Packs;
import dev.mtgcraft.net.Net;
import dev.mtgcraft.net.Packets;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * A sealed booster: either a Minecraft-themed pack (real cards picked to match a mob or place, in a Minecraft-art
 * wrapper) or a real set booster. Right-click to tear it open: the cards go into your inventory and the opening
 * plays on screen.
 */
public class PackItem extends Item {
    public static final String TAG_THEME = "Theme";
    public static final String TAG_SET = "Set";
    /** A Booster Pick ticket: "PACK" or "BOX". Right-click to choose any set. */
    public static final String TAG_PICK = "Pick";

    public PackItem(Properties props) {
        super(props);
    }

    public static ItemStack themed(Packs.Theme theme, int count) {
        ItemStack s = new ItemStack(MtgCraft.BOOSTER_PACK.get(), count);
        s.getOrCreateTag().putString(TAG_THEME, theme.name());
        return s;
    }

    public static ItemStack ofSet(String setCode, int count) {
        ItemStack s = new ItemStack(MtgCraft.BOOSTER_PACK.get(), count);
        s.getOrCreateTag().putString(TAG_SET, setCode);
        return s;
    }

    /** A Booster Pick ticket (a prize from bosses): the player picks the set. {@code box}: a whole booster box. */
    public static ItemStack ticket(boolean box) {
        ItemStack s = new ItemStack(MtgCraft.BOOSTER_PACK.get());
        s.getOrCreateTag().putString(TAG_PICK, box ? "BOX" : "PACK");
        return s;
    }

    /** "PACK", "BOX" or null. */
    public static String pick(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null || !tag.contains(TAG_PICK) ? null : tag.getString(TAG_PICK);
    }

    /** Swaps a Booster Pick ticket the player holds for the chosen set's pack or box. */
    public static void redeem(ServerPlayer player, boolean box, String set) {
        if (ForgeEngine.state() != ForgeEngine.State.READY || !Packs.boosterSets().contains(set)) return;
        String kind = box ? "BOX" : "PACK";
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = player.getItemInHand(hand);
            if (!held.is(MtgCraft.BOOSTER_PACK.get()) || !kind.equals(pick(held))) continue;
            held.shrink(1);
            ItemStack prize = box ? BoxItem.ofSet(set) : ofSet(set, 1);
            if (!player.getInventory().add(prize)) player.drop(prize, false);
            player.displayClientMessage(Component.literal("Got a " + Packs.setName(set) + (box ? " Booster Box!" : " Booster!"))
                    .withStyle(ChatFormatting.GOLD), true);
            player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.4f);
            return;
        }
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return pick(stack) != null || super.isFoil(stack);
    }

    public static Packs.Theme theme(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_THEME)) return null;
        try {
            return Packs.Theme.valueOf(tag.getString(TAG_THEME));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String set(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null || !tag.contains(TAG_SET) ? null : tag.getString(TAG_SET);
    }

    /** Wrapper art id: a theme name, or "SET" for real set boosters. */
    public static String wrapper(ItemStack stack) {
        Packs.Theme t = theme(stack);
        return t != null ? t.name() : "SET";
    }

    @Override
    public Component getName(ItemStack stack) {
        String pick = pick(stack);
        if (pick != null) return Component.literal("BOX".equals(pick) ? "Booster Box Pick" : "Booster Pick").withStyle(ChatFormatting.GOLD);
        Packs.Theme t = theme(stack);
        if (t != null) return Component.literal(t.label);
        String set = set(stack);
        if (set != null) {
            String name = ForgeEngine.state() == ForgeEngine.State.READY ? Packs.setName(set) : set;
            return Component.literal(name + " Booster");
        }
        return super.getName(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
        String pick = pick(stack);
        if (pick != null) {
            lines.add(Component.literal("BOX".equals(pick) ? "Right-click to choose a booster box from any set"
                    : "Right-click to choose a booster from any set").withStyle(ChatFormatting.GRAY));
            return;
        }
        Packs.Theme mod = theme(stack);
        if (mod != null && mod.mod != null) lines.add(Component.literal(mod.modName()).withStyle(ChatFormatting.BLUE, ChatFormatting.ITALIC));
        lines.add(Component.literal("Right-click to open").withStyle(ChatFormatting.GRAY));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.success(stack);
        ServerPlayer sp = (ServerPlayer) player;
        if (ForgeEngine.state() != ForgeEngine.State.READY) {
            sp.displayClientMessage(Component.literal("The card engine is still loading..."), true);
            return InteractionResultHolder.fail(stack);
        }
        String pick = pick(stack);
        if (pick != null) {
            List<String> codes = new ArrayList<>(Packs.boosterSets()), names = new ArrayList<>();
            for (String c : codes) names.add(Packs.setName(c));
            Net.toPlayer(sp, new Packets.SetPicker("BOX".equals(pick), codes, names));
            return InteractionResultHolder.consume(stack);
        }
        Packs.Theme t = theme(stack);
        String set = set(stack);
        if (t == null && set == null) {
            set = Packs.randomSet();
            if (set == null) return InteractionResultHolder.fail(stack);
        }
        List<Packs.Pull> pulls = t != null ? Packs.openTheme(t) : Packs.openSet(set);
        if (pulls.isEmpty()) {
            sp.displayClientMessage(Component.literal("This pack turned out empty."), true);
            return InteractionResultHolder.fail(stack);
        }
        String title = t != null ? t.label : Packs.setName(set) + " Booster";
        String wrapper = wrapper(stack);
        if (!player.getAbilities().instabuild) stack.shrink(1);
        giveCards(sp, pulls);
        List<Packets.Pull> shown = new ArrayList<>();
        for (Packs.Pull p : pulls) shown.add(new Packets.Pull(Cards.key(p.card()), p.foil(), Cards.rarity(p.card())));
        Net.toPlayer(sp, new Packets.PackOpened(title, wrapper, shown));
        level.playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1f, 0.8f);
        return InteractionResultHolder.consume(stack);
    }

    /** Puts cards in the inventory, stacking duplicates; what doesn't fit drops at the player's feet. */
    public static void giveCards(ServerPlayer player, List<Packs.Pull> pulls) {
        for (Packs.Pull p : pulls) {
            ItemStack card = CardItem.of(p.card(), p.foil(), 1);
            if (!player.getInventory().add(card)) player.drop(card, false);
        }
    }
}

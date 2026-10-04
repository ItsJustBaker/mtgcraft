package dev.mtgcraft.server;

import dev.mtgcraft.engine.Cards;
import dev.mtgcraft.engine.ForgeEngine;
import dev.mtgcraft.item.BinderItem;
import dev.mtgcraft.item.CardBag;
import dev.mtgcraft.item.CardItem;
import dev.mtgcraft.item.DeckBoxItem;
import dev.mtgcraft.net.Packets;
import forge.item.PaperCard;
import forge.model.FModel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Moves physical cards between the inventory, binders and deck boxes. Every move is checked here on the server, so
 * a card is always in exactly one place.
 */
public final class CollectionOps {
    private CollectionOps() {}

    public static void handle(ServerPlayer player, Packets.CollectionOp msg) {
        InteractionHand hand = msg.offhand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack holder = player.getItemInHand(hand);
        int n = Math.max(1, Math.min(64 * 36, msg.count()));
        switch (msg.op()) {
            case BINDER_PUT -> {
                if (!(holder.getItem() instanceof BinderItem)) return;
                int moved = takeFromInventory(player, msg.card(), msg.foil(), n, holder);
                CardBag.add(holder, msg.card(), msg.foil(), moved);
            }
            case BINDER_PUT_ALL -> {
                if (!(holder.getItem() instanceof BinderItem)) return;
                Inventory inv = player.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack s = inv.getItem(i);
                    if (s.getItem() instanceof CardItem && CardItem.key(s) != null) {
                        CardBag.add(holder, CardItem.key(s), CardItem.foil(s), s.getCount());
                        inv.setItem(i, ItemStack.EMPTY);
                    }
                }
            }
            case BINDER_TAKE -> {
                if (!(holder.getItem() instanceof BinderItem)) return;
                int took = CardBag.remove(holder, msg.card(), msg.foil(), n);
                give(player, msg.card(), msg.foil(), took);
            }
            case DECK_ADD -> {
                if (!(holder.getItem() instanceof DeckBoxItem)) return;
                if (DeckBoxItem.isBasicKey(msg.card())) {
                    String key = basicKey(Cards.name(msg.card()));
                    if (key != null) CardBag.add(holder, key, false, n);
                    return;
                }
                int moved = takeFromInventory(player, msg.card(), msg.foil(), n, holder);
                if (moved < n) moved += takeFromBinders(player, msg.card(), msg.foil(), n - moved);
                CardBag.add(holder, msg.card(), msg.foil(), moved);
            }
            case DECK_COMMANDER -> {
                if (!(holder.getItem() instanceof DeckBoxItem)) return;
                String current = DeckBoxItem.commander(holder);
                if (msg.card().equals(current)) {
                    DeckBoxItem.setCommander(holder, null);
                } else {
                    PaperCard pc = Cards.card(msg.card());
                    if (pc != null && pc.getRules().canBeCommander() && CardBag.count(holder, msg.card(), msg.foil()) == 0) {
                        // Picked from the collection: move one copy into the deck first.
                        int moved = takeFromInventory(player, msg.card(), msg.foil(), 1, holder);
                        if (moved == 0) moved = takeFromBinders(player, msg.card(), msg.foil(), 1);
                        CardBag.add(holder, msg.card(), msg.foil(), moved);
                    }
                    if (CardBag.count(holder, msg.card(), msg.foil()) == 0) return;
                    if (pc != null && pc.getRules().canBeCommander()) DeckBoxItem.setCommander(holder, msg.card());
                    else player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                            "Only legendary creatures (and some planeswalkers) can lead a deck."), true);
                }
            }
            case DECK_REMOVE -> {
                if (!(holder.getItem() instanceof DeckBoxItem)) return;
                int took = CardBag.remove(holder, msg.card(), msg.foil(), n);
                if (msg.card().equals(DeckBoxItem.commander(holder)) && CardBag.count(holder, msg.card(), msg.foil()) == 0) {
                    DeckBoxItem.setCommander(holder, null);
                }
                if (DeckBoxItem.isBasicKey(msg.card())) return; // basics are free; they just vanish
                ItemStack binder = firstBinder(player);
                if (binder.isEmpty()) give(player, msg.card(), msg.foil(), took);
                else CardBag.add(binder, msg.card(), msg.foil(), took);
            }
        }
        player.getInventory().setChanged();
    }

    /** The printing used for free basic lands (Card-Forge's default for that name). */
    private static String basicKey(String name) {
        if (ForgeEngine.state() != ForgeEngine.State.READY) return null;
        PaperCard pc = FModel.getMagicDb().getCommonCards().getCard(name);
        return pc == null ? null : Cards.key(pc);
    }

    private static int takeFromInventory(ServerPlayer player, String key, boolean foil, int n, ItemStack except) {
        Inventory inv = player.getInventory();
        int moved = 0;
        for (int i = 0; i < inv.getContainerSize() && moved < n; i++) {
            ItemStack s = inv.getItem(i);
            if (s == except || !(s.getItem() instanceof CardItem)) continue;
            if (!key.equals(CardItem.key(s)) || CardItem.foil(s) != foil) continue;
            int take = Math.min(n - moved, s.getCount());
            s.shrink(take);
            moved += take;
        }
        return moved;
    }

    private static int takeFromBinders(ServerPlayer player, String key, boolean foil, int n) {
        int moved = 0;
        for (ItemStack s : player.getInventory().items) {
            if (moved >= n) break;
            if (s.getItem() instanceof BinderItem) moved += CardBag.remove(s, key, foil, n - moved);
        }
        ItemStack off = player.getOffhandItem();
        if (moved < n && off.getItem() instanceof BinderItem) moved += CardBag.remove(off, key, foil, n - moved);
        return moved;
    }

    private static ItemStack firstBinder(ServerPlayer player) {
        for (ItemStack s : player.getInventory().items) if (s.getItem() instanceof BinderItem) return s;
        ItemStack off = player.getOffhandItem();
        return off.getItem() instanceof BinderItem ? off : ItemStack.EMPTY;
    }

    /** Gives card items, built from the key (falls back to a bare stack if the engine isn't loaded). */
    static void give(ServerPlayer player, String key, boolean foil, int count) {
        if (count <= 0) return;
        PaperCard pc = Cards.card(key);
        while (count > 0) {
            int n = Math.min(64, count);
            ItemStack stack;
            if (pc != null) {
                stack = CardItem.of(pc, foil, n);
            } else {
                stack = new ItemStack(dev.mtgcraft.MtgCraft.CARD.get(), n);
                stack.getOrCreateTag().putString(CardItem.TAG_CARD, key);
                if (foil) stack.getOrCreateTag().putBoolean(CardItem.TAG_FOIL, true);
            }
            if (!player.getInventory().add(stack)) player.drop(stack, false);
            count -= n;
        }
    }
}

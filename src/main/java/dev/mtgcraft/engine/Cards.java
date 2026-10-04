package dev.mtgcraft.engine;

import forge.card.CardRarity;
import forge.card.CardRules;
import forge.item.PaperCard;
import forge.model.FModel;

/**
 * Identifies one printing of a card as a short string, "Name|SET|collectorNumber", for item NBT and packets.
 * Lookups need Card-Forge loaded ({@link ForgeEngine.State#READY}).
 */
public final class Cards {
    private Cards() {}

    public static String key(PaperCard pc) {
        return pc.getName() + "|" + pc.getEdition() + "|" + pc.getCollectorNumber();
    }

    /** The printing for a key, or the newest printing of that name if the exact one is unknown; null if none. */
    public static PaperCard card(String key) {
        if (key == null || ForgeEngine.state() != ForgeEngine.State.READY) return null;
        String[] p = key.split("\\|", 3);
        var db = FModel.getMagicDb().getCommonCards();
        PaperCard pc = null;
        try {
            if (p.length == 3) pc = db.getCard(p[0], p[1], p[2]);
            if (pc == null && p.length >= 2) pc = db.getCard(p[0], p[1]);
            if (pc == null) pc = db.getCard(p[0]);
        } catch (RuntimeException ignored) {
        }
        return pc;
    }

    public static String name(String key) {
        int bar = key == null ? -1 : key.indexOf('|');
        return bar < 0 ? String.valueOf(key) : key.substring(0, bar);
    }

    /** The image key Card-Forge's image cache and fetcher understand. */
    public static String imageKey(PaperCard pc) {
        return pc.getImageKey(false);
    }

    /** One-letter rarity: C, U, R, M, S (special) or L (land). */
    public static char rarity(PaperCard pc) {
        CardRarity r = pc.getRarity();
        if (r == null) return 'C';
        return switch (r) {
            case Uncommon -> 'U';
            case Rare -> 'R';
            case MythicRare -> 'M';
            case Special -> 'S';
            case BasicLand -> 'L';
            default -> 'C';
        };
    }

    public static String typeLine(PaperCard pc) {
        CardRules r = pc.getRules();
        return r.getType() == null ? "" : r.getType().toString();
    }

    public static String manaCost(PaperCard pc) {
        CardRules r = pc.getRules();
        return r.getManaCost() == null ? "" : r.getManaCost().toString();
    }
}

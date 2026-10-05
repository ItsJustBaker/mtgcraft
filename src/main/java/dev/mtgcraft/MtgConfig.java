package dev.mtgcraft;

import net.minecraftforge.common.ForgeConfigSpec;

/** Server-side settings (config/mtgcraft-common.toml). */
public final class MtgConfig {
    public enum LossPenalty { DEATH, DAMAGE, NONE }
    public enum DuelMode { COMMANDER, CLASSIC }

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.EnumValue<LossPenalty> LOSS_PENALTY;
    public static final ForgeConfigSpec.EnumValue<DuelMode> DUEL_MODE;
    public static final ForgeConfigSpec.DoubleValue PACK_DROP_CHANCE;
    public static final ForgeConfigSpec.DoubleValue SET_PACK_DROP_CHANCE;
    public static final ForgeConfigSpec.BooleanValue STARTER_KIT;
    public static final ForgeConfigSpec.BooleanValue GROUP_FIGHTS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("duels");
        LOSS_PENALTY = b.comment("What happens when you lose a Duel Gauntlet challenge. DEATH: you die (your Deck Box is kept).",
                        "DAMAGE: you take 5 hearts of damage. NONE: nothing happens.")
                .defineEnum("lossPenalty", LossPenalty.DEATH);
        DUEL_MODE = b.comment("Rules for Duel Gauntlet challenges. COMMANDER: casual Commander (40 life, commanders, any deck size).",
                        "CLASSIC: 20 life, no commanders. Bosses always fight as the Archenemy.")
                .defineEnum("duelMode", DuelMode.COMMANDER);
        GROUP_FIGHTS = b.comment("Default Duel Gauntlet mode. false: 1v1 duels. true: nearby mobs and friends join.",
                        "Each gauntlet can be switched with shift + right-click in the air.")
                .define("groupFights", false);
        b.pop();
        b.push("drops");
        PACK_DROP_CHANCE = b.comment("Chance a hostile mob killed by a player drops a themed booster pack.")
                .defineInRange("themedPackChance", 0.04, 0, 1);
        SET_PACK_DROP_CHANCE = b.comment("Chance a hostile mob killed by a player drops a real set booster.")
                .defineInRange("setPackChance", 0.006, 0, 1);
        b.pop();
        b.push("survival");
        STARTER_KIT = b.comment("Offer new players a starter deck (Deck Box, Binder, Duel Gauntlet and a few packs).")
                .define("starterKit", true);
        b.pop();
        SPEC = b.build();
    }

    private MtgConfig() {}
}

package dev.mtgcraft;

import net.minecraftforge.common.ForgeConfigSpec;

/** Per-player settings (config/mtgcraft-client.toml). */
public final class MtgClientConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue DUEL_INTRO;
    public static final ForgeConfigSpec.BooleanValue ARENA_VIEW;
    public static final ForgeConfigSpec.BooleanValue AUTO_PASS;
    public static final ForgeConfigSpec.IntValue SKIP_PHASES;
    public static final ForgeConfigSpec.IntValue STOP_PHASES;
    public static final ForgeConfigSpec.BooleanValue HIGHLIGHT_PLAYABLE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        DUEL_INTRO = b.comment("Play the duel intro (sound, camera shake, letterbox) when a duel starts.")
                .define("duelIntro", true);
        ARENA_VIEW = b.comment("Start duels in the 3D arena view (true) or on the 2D table screen (false).")
                .define("arenaView", true);
        AUTO_PASS = b.comment("Pass automatically when you have nothing you can play (you still decide blocks, targets and questions).")
                .define("autoPass", true);
        SKIP_PHASES = b.comment("Phases the game always passes through for you (bits of the phase bar, left to right). Click the phase bar in a duel to change.")
                .defineInRange("skipPhases", 0, 0, 511);
        STOP_PHASES = b.comment("Phases where auto-pass always stops for you, even with nothing to play (bits of the phase bar). Click the phase bar in a duel to change.")
                .defineInRange("stopPhases", 0, 0, 511);
        HIGHLIGHT_PLAYABLE = b.comment("Make the cards you can play right now glow green.")
                .define("highlightPlayable", true);
        SPEC = b.build();
    }

    private MtgClientConfig() {}
}

package dev.mtgcraft;

import net.minecraftforge.common.ForgeConfigSpec;

/** Per-player settings (config/mtgcraft-client.toml). */
public final class MtgClientConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue DUEL_INTRO;
    public static final ForgeConfigSpec.BooleanValue ARENA_VIEW;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        DUEL_INTRO = b.comment("Play the duel intro (sound, camera shake, letterbox) when a duel starts.")
                .define("duelIntro", true);
        ARENA_VIEW = b.comment("Start duels in the 3D arena view (true) or on the 2D table screen (false).")
                .define("arenaView", true);
        SPEC = b.build();
    }

    private MtgClientConfig() {}
}

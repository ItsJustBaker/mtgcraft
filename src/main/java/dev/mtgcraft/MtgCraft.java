package dev.mtgcraft;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(MtgCraft.MODID)
public class MtgCraft {
    public static final String MODID = "mtgcraft";

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredRegister<net.minecraft.sounds.SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, MODID);

    /** Played as a duel begins. Players can swap in their own clip (see client.PersonalSounds). */
    public static final RegistryObject<net.minecraft.sounds.SoundEvent> DUEL_START = SOUNDS.register("duel_start",
            () -> net.minecraft.sounds.SoundEvent.createVariableRangeEvent(new net.minecraft.resources.ResourceLocation(MODID, "duel_start")));

    public static final RegistryObject<Block> MAGIC_TABLE = BLOCKS.register("magic_table",
            () -> new MagicTableBlock(BlockBehaviour.Properties.copy(Blocks.CRAFTING_TABLE).noOcclusion()));
    public static final RegistryObject<Item> MAGIC_TABLE_ITEM = ITEMS.register("magic_table",
            () -> new BlockItem(MAGIC_TABLE.get(), new Item.Properties()));

    public static final RegistryObject<Item> CARD = ITEMS.register("card",
            () -> new dev.mtgcraft.item.CardItem(new Item.Properties()));
    public static final RegistryObject<Item> BOOSTER_PACK = ITEMS.register("booster_pack",
            () -> new dev.mtgcraft.item.PackItem(new Item.Properties()));
    public static final RegistryObject<Item> BOOSTER_BOX = ITEMS.register("booster_box",
            () -> new dev.mtgcraft.item.BoxItem(new Item.Properties().stacksTo(16)));

    public static final RegistryObject<Item> BINDER = ITEMS.register("binder",
            () -> new dev.mtgcraft.item.BinderItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> DECK_BOX = ITEMS.register("deck_box",
            () -> new dev.mtgcraft.item.DeckBoxItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<Item> UNIVERSAL_DECK_BOX = ITEMS.register("universal_deck_box",
            () -> new dev.mtgcraft.item.UniversalDeckBoxItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> DUEL_GAUNTLET = ITEMS.register("duel_gauntlet",
            () -> new dev.mtgcraft.item.GauntletItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> STARTER_KIT = ITEMS.register("starter_kit",
            () -> new dev.mtgcraft.item.StarterKitItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> WORLD_CUP = ITEMS.register("world_cup",
            () -> new Item(new Item.Properties().stacksTo(1).rarity(net.minecraft.world.item.Rarity.EPIC).fireResistant()));
    public static final RegistryObject<Item> FAMILIAR_ORB = ITEMS.register("familiar_orb",
            () -> new dev.mtgcraft.item.FamiliarOrbItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> DUEL_DISK = ITEMS.register("duel_disk",
            () -> new dev.mtgcraft.item.DuelDiskItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> CHAMPION_EGG = ITEMS.register("champion_spawn_egg",
            () -> new dev.mtgcraft.item.ChampionEggItem(new Item.Properties()));

    /** The mod's own creative tab. */
    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("mtgcraft", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.mtgcraft"))
            .icon(() -> MAGIC_TABLE_ITEM.get().getDefaultInstance())
            .displayItems((params, output) -> {
                output.accept(MAGIC_TABLE_ITEM.get());
                output.accept(BINDER.get());
                output.accept(DECK_BOX.get());
                output.accept(UNIVERSAL_DECK_BOX.get());
                output.accept(DUEL_GAUNTLET.get());
                output.accept(STARTER_KIT.get());
                output.accept(WORLD_CUP.get());
                output.accept(FAMILIAR_ORB.get());
                output.accept(DUEL_DISK.get());
                output.accept(CHAMPION_EGG.get());
                output.accept(dev.mtgcraft.item.PackItem.ticket(false));
                output.accept(dev.mtgcraft.item.PackItem.ticket(true));
                for (dev.mtgcraft.engine.Packs.Theme t : dev.mtgcraft.engine.Packs.Theme.values()) {
                    if (!t.available()) continue;
                    if (t.isBox()) output.accept(dev.mtgcraft.item.BoxItem.themed(t));
                    else output.accept(dev.mtgcraft.item.PackItem.themed(t, 1));
                }
                for (dev.mtgcraft.engine.Packs.Theme t : dev.mtgcraft.engine.Packs.Theme.values()) {
                    if (t.available() && !t.isBox()) output.accept(dev.mtgcraft.item.BoxItem.themed(t));
                }
            })
            .build());

    /** Real Magic set boosters: a pack and a box for every set the mod prints. */
    public static final RegistryObject<CreativeModeTab> BOOSTERS_TAB = TABS.register("boosters", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.mtgcraft.boosters"))
            .icon(() -> dev.mtgcraft.item.PackItem.ofSet("NEO", 1))
            .withTabsBefore(TAB.getKey())
            .displayItems((params, output) -> {
                // Every printable set, oldest to newest, once the card engine is loaded (until then, a short list).
                java.util.List<String> sets = dev.mtgcraft.engine.ForgeEngine.state() == dev.mtgcraft.engine.ForgeEngine.State.READY
                        ? dev.mtgcraft.engine.Packs.boosterSets() : java.util.List.of(dev.mtgcraft.engine.Packs.SET_POOL);
                for (String set : sets) {
                    output.accept(dev.mtgcraft.item.PackItem.ofSet(set, 1));
                    output.accept(dev.mtgcraft.item.BoxItem.ofSet(set));
                }
            })
            .build());

    public MtgCraft() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(bus);
        ITEMS.register(bus);
        TABS.register(bus);
        SOUNDS.register(bus);
        dev.mtgcraft.net.Net.register();
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                net.minecraftforge.fml.config.ModConfig.Type.COMMON, MtgConfig.SPEC);
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                net.minecraftforge.fml.config.ModConfig.Type.CLIENT, MtgClientConfig.SPEC);
        bus.addListener(this::addToCreativeTab);
    }

    private void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(MAGIC_TABLE_ITEM);
        }
    }
}

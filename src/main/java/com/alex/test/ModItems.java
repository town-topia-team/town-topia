package com.alex.test;

import com.alex.test.village.tokens.VillageTokenItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Mod item registry. */
public final class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, DynamicVillages.MODID);

    public static final DeferredHolder<Item, Item> LUMBERJACK_TOKEN =
            ITEMS.register("lumberjack_token", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> GUARD_TOKEN =
            ITEMS.register("guard_token", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> CAPTAIN_TOKEN =
            ITEMS.register("captain_token", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> GUARD_POST =
            ITEMS.register("guard_post", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> TOWN_HALL =
            ITEMS.register("town_hall", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> TWO_BED_HOUSE =
            ITEMS.register("2_bed_house", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> FOUR_BED_HOUSE =
            ITEMS.register("4_bed_house", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> SIX_BED_HOUSE =
            ITEMS.register("6_bed_house", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> STORAGE_ROOM =
            ITEMS.register("storage_room", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    // Building tokens for the crafting/food economy professions.
    public static final DeferredHolder<Item, Item> KITCHEN =
            ITEMS.register("kitchen", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> BUTCHERY =
            ITEMS.register("butchery", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> FORGE =
            ITEMS.register("forge", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> MINE =
            ITEMS.register("mine", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> RANCH =
            ITEMS.register("ranch", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    // Profession tokens assign or switch a resident's job on right-click.
    public static final DeferredHolder<Item, Item> FARMER_TOKEN =
            ITEMS.register("farmer_token", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> RANCHER_TOKEN =
            ITEMS.register("rancher_token", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> COOK_TOKEN =
            ITEMS.register("cook_token", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> BLACKSMITH_TOKEN =
            ITEMS.register("blacksmith_token", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> BUTCHER_TOKEN =
            ITEMS.register("butcher_token", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> MINER_TOKEN =
            ITEMS.register("miner_token", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> BUILDER_TOKEN =
            ITEMS.register("builder_token", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> BARD_TOKEN =
            ITEMS.register("bard_token", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> CLERIC_TOKEN =
            ITEMS.register("cleric_token", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> DRUID_TOKEN =
            ITEMS.register("druid_token", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> ENCHANTER_TOKEN =
            ITEMS.register("enchanter_token", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> TEACHER_TOKEN =
            ITEMS.register("teacher_token", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> TRADESMAN_TOKEN =
            ITEMS.register("tradesman_token", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> ARCHER_TOKEN =
            ITEMS.register("archer_token", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> MAGE_TOKEN =
            ITEMS.register("mage_token", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    // Buildings for the TekTopia-parity professions.
    public static final DeferredHolder<Item, Item> TAVERN =
            ITEMS.register("tavern", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> LIBRARY =
            ITEMS.register("library", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> SCHOOL =
            ITEMS.register("school", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> STALL =
            ITEMS.register("stall", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> BARRACKS =
            ITEMS.register("barracks", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> ARCHERY_RANGE =
            ITEMS.register("archery_range", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> WATCHTOWER =
            ITEMS.register("watchtower", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    /** The mages' room: a study station, bookshelves and an amethyst crystal (MageTowerBuilding). */
    public static final DeferredHolder<Item, Item> MAGE_TOWER =
            ITEMS.register("mage_tower", () -> new VillageTokenItem(new Item.Properties().stacksTo(1)));

    /** Starter guide, given once on first join; opens as a one-page vanilla book. */
    public static final DeferredHolder<Item, Item> GUIDE_BOOK =
            ITEMS.register("guide_book", () -> new GuideBookItem(new Item.Properties().stacksTo(1)));

    /** Raid trophies (see RaidTrophyItem): hung in the Town Hall, they add fame. */
    public static final DeferredHolder<Item, Item> WARBAND_TROPHY =
            ITEMS.register("warband_trophy", () -> new com.alex.test.village.raid.RaidTrophyItem(
                    com.alex.test.village.raid.RaidFaction.WARBAND, new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item, Item> HORDE_TROPHY =
            ITEMS.register("horde_trophy", () -> new com.alex.test.village.raid.RaidTrophyItem(
                    com.alex.test.village.raid.RaidFaction.HORDE, new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item, Item> RIDER_TROPHY =
            ITEMS.register("rider_trophy", () -> new com.alex.test.village.raid.RaidTrophyItem(
                    com.alex.test.village.raid.RaidFaction.RIDERS, new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item, Item> RESCUE_TROPHY =
            ITEMS.register("rescue_trophy", () -> new com.alex.test.village.raid.RaidTrophyItem(
                    com.alex.test.village.raid.RaidFaction.KIDNAPPERS, new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item, Item> OMEN_TROPHY =
            ITEMS.register("omen_trophy", () -> new com.alex.test.village.raid.RaidTrophyItem(
                    com.alex.test.village.raid.RaidFaction.CHALLENGE, new Item.Properties().stacksTo(1)));
    /** Left where the Illager Warlord falls; it survives lava. */
    public static final DeferredHolder<Item, Item> WARLORD_HELM =
            ITEMS.register("warlord_helm", () -> new com.alex.test.village.raid.warlord.WarlordHelmItem(
                    new Item.Properties().stacksTo(1).fireResistant()));

    /** Given by the Builder's desk to place a blueprint in the world; gone once the spot is confirmed. */
    public static final DeferredHolder<Item, Item> BUILDER_PLAN =
            ITEMS.register("builder_plan", () -> new com.alex.test.village.villagers.builder.plan.BuilderPlanItem(
                    new Item.Properties().stacksTo(1)));

    /**
     * The Blacksmith's hammer: drawn in its hand while it forges, mends or breaks down
     * at the anvil (HeldItemGeoLayer). A prop only; never given out or put in a pack.
     */
    public static final DeferredHolder<Item, Item> SMITH_HAMMER =
            ITEMS.register("smith_hammer", () -> new Item(new Item.Properties().stacksTo(1)));

    /**
     * The Cook's wooden spoon and knife: drawn in its hand at the work clips that use them
     * (HeldItemGeoLayer). Props only, like the hammer; never given out or put in a pack.
     */
    public static final DeferredHolder<Item, Item> COOK_SPOON =
            ITEMS.register("cook_spoon", () -> new Item(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> COOK_KNIFE =
            ITEMS.register("cook_knife", () -> new Item(new Item.Properties().stacksTo(1)));

    /**
     * The Blacksmith's tongs and the hot bar in them: drawn in its left hand while it draws out a bar
     * at the anvil (client SmithTongs, models from tools/art/SmithTongsModel.java). Props only, like the
     * hammer; never given out or put in a pack.
     */
    public static final DeferredHolder<Item, Item> SMITH_TONGS =
            ITEMS.register("smith_tongs", () -> new Item(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> SMITH_BAR =
            ITEMS.register("smith_bar", () -> new Item(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, Item> WOODEN_CHAIR =
            ITEMS.register("wooden_chair", () -> new BlockItem(ModBlocks.WOODEN_CHAIR.get(), new Item.Properties()));

    public static final DeferredHolder<Item, Item> TALL_WOODEN_CHAIR =
            ITEMS.register("tall_wooden_chair", () -> new BlockItem(ModBlocks.TALL_WOODEN_CHAIR.get(), new Item.Properties()));

    public static final Map<String, DeferredHolder<Item, Item>> WOODEN_CHAIR_VARIANTS =
            registerChairItems("wooden_chair", wood -> ModBlocks.WOODEN_CHAIR_VARIANTS.get(wood).get());

    public static final Map<String, DeferredHolder<Item, Item>> SMALL_CHAIR_VARIANTS =
            registerChairItems("small_wooden_chair", wood -> ModBlocks.SMALL_CHAIR_VARIANTS.get(wood).get());

    public static final DeferredHolder<Item, Item> ZALITH_PIANO =
            ITEMS.register("zalith_piano", () -> new BlockItem(ModBlocks.ZALITH_PIANO.get(), new Item.Properties()));

    public static final DeferredHolder<Item, Item> ZALITH_PIANO_BENCH =
            ITEMS.register("zalith_piano_bench", () -> new BlockItem(ModBlocks.ZALITH_PIANO_BENCH.get(), new Item.Properties()));

    /** The other 10 woods, piano then bench per wood, so the creative tab keeps each pair together. */
    private static final List<Map<String, DeferredHolder<Item, Item>>> PIANO_VARIANT_ITEMS = registerPianoItems();
    public static final Map<String, DeferredHolder<Item, Item>> ZALITH_PIANO_VARIANTS = PIANO_VARIANT_ITEMS.get(0);
    public static final Map<String, DeferredHolder<Item, Item>> ZALITH_PIANO_BENCH_VARIANTS = PIANO_VARIANT_ITEMS.get(1);

    private static List<Map<String, DeferredHolder<Item, Item>>> registerPianoItems() {
        Map<String, DeferredHolder<Item, Item>> pianos = new LinkedHashMap<>();
        Map<String, DeferredHolder<Item, Item>> benches = new LinkedHashMap<>();
        for (String wood : ModBlocks.CHAIR_WOODS) {
            pianos.put(wood, ITEMS.register(wood + "_zalith_piano",
                    () -> new BlockItem(ModBlocks.ZALITH_PIANO_VARIANTS.get(wood).get(), new Item.Properties())));
            benches.put(wood, ITEMS.register(wood + "_zalith_piano_bench",
                    () -> new BlockItem(ModBlocks.ZALITH_PIANO_BENCH_VARIANTS.get(wood).get(), new Item.Properties())));
        }
        return List.of(Map.copyOf(pianos), Map.copyOf(benches));
    }

    private static Map<String, DeferredHolder<Item, Item>> registerChairItems(
            String suffix, Function<String, ? extends Block> blockForWood) {
        Map<String, DeferredHolder<Item, Item>> items = new LinkedHashMap<>();
        for (String wood : ModBlocks.CHAIR_WOODS) {
            items.put(wood, ITEMS.register(wood + "_" + suffix,
                    () -> new BlockItem(blockForWood.apply(wood), new Item.Properties())));
        }
        return Map.copyOf(items);
    }

    private ModItems() {}

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}

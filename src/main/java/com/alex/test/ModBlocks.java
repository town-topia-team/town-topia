package com.alex.test;

import com.alex.test.block.WoodenChairBlock;
import com.alex.test.block.TallWoodenChairBlock;
import com.alex.test.block.TravelerCampfireBlock;
import com.alex.test.block.ZalithPianoBenchBlock;
import com.alex.test.block.ZalithPianoBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Placeable blocks provided by the mod. */
public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, DynamicVillages.MODID);

    /** Oak keeps its existing IDs; these woods add matching variants of both chairs. */
    public static final List<String> CHAIR_WOODS = List.of(
            "spruce", "birch", "jungle", "acacia", "dark_oak",
            "mangrove", "cherry", "bamboo", "crimson", "warped");

    /** Chairs that never burn, like their planks. */
    private static final Set<String> FIREPROOF_CHAIR_WOODS = Set.of("crimson", "warped");

    public static final DeferredHolder<Block, WoodenChairBlock> WOODEN_CHAIR =
            BLOCKS.register("wooden_chair", () -> new WoodenChairBlock(chairProperties("oak")));

    public static final DeferredHolder<Block, TallWoodenChairBlock> TALL_WOODEN_CHAIR =
            BLOCKS.register("tall_wooden_chair", () -> new TallWoodenChairBlock(chairProperties("oak")));

    public static final Map<String, DeferredHolder<Block, WoodenChairBlock>> WOODEN_CHAIR_VARIANTS =
            registerWoodenChairVariants();
    public static final Map<String, DeferredHolder<Block, TallWoodenChairBlock>> SMALL_CHAIR_VARIANTS =
            registerSmallChairVariants();

    /** The campfire travelers light when they camp overnight; never an item. */
    public static final DeferredHolder<Block, TravelerCampfireBlock> TRAVELER_CAMPFIRE =
            BLOCKS.register("traveler_campfire", () -> new TravelerCampfireBlock(
                    BlockBehaviour.Properties.ofFullCopy(Blocks.CAMPFIRE)
                            .noLootTable()
                            .explosionResistance(1200.0F)
                            .pushReaction(PushReaction.BLOCK)));

    /** ZalithYT's piano (oak): decoration, 2x2, each part draws its own quarter. */
    public static final DeferredHolder<Block, ZalithPianoBlock> ZALITH_PIANO =
            BLOCKS.register("zalith_piano", () -> new ZalithPianoBlock(pianoProperties(2.5F)));

    /** The piano's bench (oak): 2 wide, seat in the middle; never a villager seat (not a WoodenChairBlock). */
    public static final DeferredHolder<Block, ZalithPianoBenchBlock> ZALITH_PIANO_BENCH =
            BLOCKS.register("zalith_piano_bench", () -> new ZalithPianoBenchBlock(pianoProperties(2.0F)));

    /** The chairs' other 10 woods (round 5, 10-24): {@code <wood>_zalith_piano} and {@code <wood>_zalith_piano_bench}. */
    public static final Map<String, DeferredHolder<Block, ZalithPianoBlock>> ZALITH_PIANO_VARIANTS =
            registerPianoVariants("zalith_piano", () -> new ZalithPianoBlock(pianoProperties(2.5F)));
    public static final Map<String, DeferredHolder<Block, ZalithPianoBenchBlock>> ZALITH_PIANO_BENCH_VARIANTS =
            registerPianoVariants("zalith_piano_bench", () -> new ZalithPianoBenchBlock(pianoProperties(2.0F)));

    /** Lava-lit also makes the chair burn in fire, see {@link WoodenChairBlock#getFlammability}. */
    private static BlockBehaviour.Properties chairProperties(String wood) {
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .strength(2.0F)
                .sound(SoundType.WOOD)
                .noOcclusion();
        return FIREPROOF_CHAIR_WOODS.contains(wood) ? properties : properties.ignitedByLava();
    }

    /** Every wood the same; no wood burns (the lock keeps the piano whole, PianoLock). */
    private static BlockBehaviour.Properties pianoProperties(float strength) {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .strength(strength)
                .explosionResistance(3600000.0F)   // like bedrock: the lock (PianoLock) also keeps blasts off
                .sound(SoundType.WOOD)
                .noOcclusion()
                .forceSolidOn()                    // the bench is too low to count as solid: water and lava would wash it away
                .pushReaction(PushReaction.BLOCK);
    }

    private static <B extends Block> Map<String, DeferredHolder<Block, B>> registerPianoVariants(
            String suffix, Supplier<B> block) {
        Map<String, DeferredHolder<Block, B>> variants = new LinkedHashMap<>();
        for (String wood : CHAIR_WOODS) {
            variants.put(wood, BLOCKS.register(wood + "_" + suffix, block));
        }
        return Map.copyOf(variants);
    }

    private static Map<String, DeferredHolder<Block, WoodenChairBlock>> registerWoodenChairVariants() {
        Map<String, DeferredHolder<Block, WoodenChairBlock>> variants = new LinkedHashMap<>();
        for (String wood : CHAIR_WOODS) {
            variants.put(wood, BLOCKS.register(wood + "_wooden_chair",
                    () -> new WoodenChairBlock(chairProperties(wood))));
        }
        return Map.copyOf(variants);
    }

    private static Map<String, DeferredHolder<Block, TallWoodenChairBlock>> registerSmallChairVariants() {
        Map<String, DeferredHolder<Block, TallWoodenChairBlock>> variants = new LinkedHashMap<>();
        for (String wood : CHAIR_WOODS) {
            variants.put(wood, BLOCKS.register(wood + "_small_wooden_chair",
                    () -> new TallWoodenChairBlock(chairProperties(wood))));
        }
        return Map.copyOf(variants);
    }

    private ModBlocks() {}

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }
}

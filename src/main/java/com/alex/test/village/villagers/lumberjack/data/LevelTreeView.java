package com.alex.test.village.villagers.lumberjack.data;

import com.alex.test.DynamicVillages;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * The real world as {@link TreeJudge} sees it. One view per judgement: it
 * remembers the chunks and block kinds it has looked at, so a big tree reads
 * each chunk once. Never loads a chunk; a block in an unloaded one is
 * {@link TreeView.Kind#UNLOADED}.
 */
public final class LevelTreeView implements TreeView {

    /** Blocks only builds have (planks, glass, cobblestone, torches...): {@code tree_build_marks}. */
    public static final TagKey<Block> BUILD_MARKS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "tree_build_marks"));
    /** Blocks trees grow on (dirt kinds, mangrove roots): {@code tree_soil}. Modpacks add theirs. */
    public static final TagKey<Block> SOIL = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "tree_soil"));

    /** One number per wood: logs that share a tree type's log set share the number. */
    private static final Map<Set<Block>, Integer> FAMILY_IDS = new ConcurrentHashMap<>();
    private static final Map<Integer, List<TreeTypeRegistry.TreeType>> FAMILY_TYPES = new ConcurrentHashMap<>();
    private static final Map<Long, Boolean> LEAF_FITS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_FAMILY = new AtomicInteger();

    private final ServerLevel level;
    private final Predicate<BlockPos> protectedPosition;
    private final Map<Long, LevelChunk> chunks = new HashMap<>();
    private final Map<BlockState, Cell> cells = new IdentityHashMap<>();

    public LevelTreeView(ServerLevel level, Predicate<BlockPos> protectedPosition) {
        this.level = level;
        this.protectedPosition = protectedPosition;
    }

    @Override
    public Cell at(int x, int y, int z) {
        BlockState state = stateAt(x, y, z);
        if (state == null) return Cell.UNLOADED;
        return cells.computeIfAbsent(state, LevelTreeView::cellFor);
    }

    /** The block at a spot, or null when its chunk is not loaded. */
    public BlockState stateAt(int x, int y, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        long key = (long) cx << 32 | (cz & 0xFFFFFFFFL);
        LevelChunk chunk = chunks.get(key);
        if (chunk == null) {
            if (!level.hasChunk(cx, cz)) return null;
            chunk = level.getChunk(cx, cz);
            chunks.put(key, chunk);
        }
        if (level.isOutsideBuildHeight(y)) return Blocks.VOID_AIR.defaultBlockState();
        return chunk.getBlockState(new BlockPos(x, y, z));
    }

    @Override
    public boolean keepOut(int x, int y, int z) {
        return protectedPosition.test(new BlockPos(x, y, z));
    }

    @Override
    public boolean leafFits(int logFamily, int leafFamily) {
        if (logFamily < 0) return false;
        long key = (long) logFamily << 32 | (leafFamily & 0xFFFFFFFFL);
        return LEAF_FITS.computeIfAbsent(key, ignored -> {
            Block leaf = BuiltInRegistries.BLOCK.byId(leafFamily);
            return FAMILY_TYPES.getOrDefault(logFamily, List.of()).stream()
                    .anyMatch(type -> type.leaves().contains(leaf));
        });
    }

    @Override
    public boolean soil(int x, int y, int z) {
        BlockState state = stateAt(x, y, z);
        return state != null && state.is(SOIL);
    }

    private static Cell cellFor(BlockState state) {
        boolean solid = state.blocksMotion();
        if (state.isAir()) return new Cell(Kind.AIR, -1, false, false);
        Block block = state.getBlock();
        if (state.is(BlockTags.LOGS)) {
            Kind kind = isBarkOrStripped(block) ? Kind.BARK_OR_STRIPPED : Kind.PLAIN_LOG;
            return new Cell(kind, familyOf(block), TreeStructure.isUpright(state), solid);
        }
        if (state.is(BlockTags.LEAVES)) {
            Kind kind = TreeStructure.isNaturalLeaf(state) ? Kind.NATURAL_LEAF : Kind.PLACED_LEAF;
            return new Cell(kind, BuiltInRegistries.BLOCK.getId(block), false, solid);
        }
        if (state.is(BUILD_MARKS)) return new Cell(Kind.BUILT, -1, false, solid);
        return new Cell(solid ? Kind.GROUND : Kind.OTHER, -1, false, solid);
    }

    /** The wood number of a log, -1 for a log no tree type grows. */
    public static int familyOf(Block log) {
        List<TreeTypeRegistry.TreeType> types = TreeTypeRegistry.candidatesForLog(log);
        if (types.isEmpty()) return -1;
        return FAMILY_IDS.computeIfAbsent(types.get(0).logs(), ignored -> {
            int id = NEXT_FAMILY.getAndIncrement();
            FAMILY_TYPES.put(id, types);
            return id;
        });
    }

    /**
     * Stripped logs and all-bark blocks: no tree grows them, builders do.
     * By name, like {@link TreeTypeRegistry} finds wood families, so modded
     * woods named the vanilla way are covered too.
     */
    public static boolean isBarkOrStripped(Block block) {
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        return path.startsWith("stripped_") || path.endsWith("_wood") || path.endsWith("_hyphae");
    }
}

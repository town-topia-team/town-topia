package com.alex.test.village.villagers.druid.behavior;

import com.alex.test.village.NavGraph;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.building.Building;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Finding the village's saplings and growing them, one stage at a time.
 *
 * <p>Growth goes through vanilla's own {@link SaplingBlock#advanceTree}, the
 * call bonemeal makes: a sapling has two stages, the first only marks it
 * ready and the second grows the tree. Going through vanilla means the tree
 * is a real one of the right kind and shape, and it fires the grow event the
 * village already listens to, so the lumberjack learns about the new tree
 * the moment it stands.
 */
final class DruidSaplings {

    /** Saplings that grow one big tree when four stand in a square. */
    private static final Set<Block> TWO_BY_TWO = Set.of(
            Blocks.SPRUCE_SAPLING, Blocks.JUNGLE_SAPLING, Blocks.DARK_OAK_SAPLING);

    /** Saplings with no single-block tree at all: a lone one never grows. */
    private static final Set<Block> SQUARE_ONLY = Set.of(Blocks.DARK_OAK_SAPLING);

    enum Growth { STAGED, TREE, BLOCKED, GONE }

    /**
     * One tree's worth of sapling: a single one, or a 2x2 square keyed by its
     * north-west corner.
     */
    record Target(BlockPos base, boolean twoByTwo) {

        Vec3 centre() {
            double offset = twoByTwo ? 1.0 : 0.5;
            return new Vec3(base.getX() + offset, base.getY() + 0.3, base.getZ() + offset);
        }

        /** The tiles touching the sapling, or touching the square's sides. */
        List<BlockPos> sides() {
            if (!twoByTwo) {
                List<BlockPos> sides = new ArrayList<>(4);
                for (Direction d : Direction.Plane.HORIZONTAL) sides.add(base.relative(d));
                return sides;
            }
            int x = base.getX(), y = base.getY(), z = base.getZ();
            return List.of(
                    new BlockPos(x - 1, y, z), new BlockPos(x - 1, y, z + 1),
                    new BlockPos(x + 2, y, z), new BlockPos(x + 2, y, z + 1),
                    new BlockPos(x, y, z - 1), new BlockPos(x + 1, y, z - 1),
                    new BlockPos(x, y, z + 2), new BlockPos(x + 1, y, z + 2));
        }
    }

    private DruidSaplings() {}

    /**
     * Every sapling standing on the village's walkable ground, outside its
     * buildings. A sapling has no collision, so its cell is an ordinary nav
     * node's feet cell; a potted one is a flower pot, not a sapling, and is
     * never seen.
     *
     * <p>This used to read the block at every nav node, a hundred and sixty
     * thousand reads on generated terrain and nearly 30 ms of server thread,
     * to find perhaps a dozen saplings. Now it reads the blocks only in chunk
     * sections whose palette lists a sapling. A palette can list a block the
     * section no longer holds, but never leaves out one it does, so a section
     * it rules out really has none. Each sapling found then has to be a nav
     * node's feet cell, which is the old rule turned around.
     */
    static List<BlockPos> scan(Activity.Context ctx) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null || graph.isEmpty()) return List.of();
        ServerLevel level = ctx.level();
        List<BlockPos> found = new ArrayList<>();
        int minCX = SectionPos.blockToSectionCoord(graph.getBoundsMinX());
        int maxCX = SectionPos.blockToSectionCoord(graph.getBoundsMaxX());
        int minCZ = SectionPos.blockToSectionCoord(graph.getBoundsMinZ());
        int maxCZ = SectionPos.blockToSectionCoord(graph.getBoundsMaxZ());
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                // Never load a chunk just to look at it.
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection section = sections[i];
                    if (section == null || section.hasOnlyAir()
                            || !section.maybeHas(DruidSaplings::isSapling)) continue;
                    int baseX = SectionPos.sectionToBlockCoord(cx);
                    int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
                    int baseZ = SectionPos.sectionToBlockCoord(cz);
                    for (int ly = 0; ly < 16; ly++) {
                        for (int lz = 0; lz < 16; lz++) {
                            for (int lx = 0; lx < 16; lx++) {
                                if (!isSapling(section.getBlockState(lx, ly, lz))) continue;
                                int x = baseX + lx, y = baseY + ly, z = baseZ + lz;
                                if (graph.nodeAt(x, y, z) == null) continue;
                                BlockPos feet = new BlockPos(x, y, z);
                                if (insideAnyBuilding(ctx, feet)) continue;
                                found.add(feet);
                            }
                        }
                    }
                }
            }
        }
        return found;
    }

    private static boolean isSapling(BlockState state) {
        return state.getBlock() instanceof SaplingBlock;
    }

    /** The tree job the sapling at {@code pos} belongs to, or null if it can never grow. */
    @Nullable
    static Target targetAt(ServerLevel level, BlockPos pos) {
        Block block = level.getBlockState(pos).getBlock();
        if (!(block instanceof SaplingBlock)) return null;
        if (TWO_BY_TWO.contains(block)) {
            for (int ox = -1; ox <= 0; ox++) {
                for (int oz = -1; oz <= 0; oz++) {
                    BlockPos corner = pos.offset(ox, 0, oz);
                    if (isSquare(level, corner, block)) return new Target(corner, true);
                }
            }
        }
        if (SQUARE_ONLY.contains(block)) return null;
        return new Target(pos, false);
    }

    /** Whether the saplings this target was made from are all still there. */
    static boolean stillThere(ServerLevel level, Target target) {
        Block block = level.getBlockState(target.base()).getBlock();
        if (!(block instanceof SaplingBlock)) return false;
        return !target.twoByTwo() || isSquare(level, target.base(), block);
    }

    /**
     * Whether a tree stands where the target's sapling was: a log on its tile,
     * or a mangrove's roots (its trunk starts a few blocks up). For a sapling
     * that vanished mid-dance, so the druid can tell vanilla grew it.
     */
    static boolean treeStandsAt(ServerLevel level, Target target) {
        BlockState state = level.getBlockState(target.base());
        return state.is(BlockTags.LOGS) || state.is(Blocks.MANGROVE_ROOTS) || state.is(Blocks.MUDDY_MANGROVE_ROOTS);
    }

    /** Whether the next stage grown is the one that makes the tree (the sapling is already marked ready). */
    static boolean nextStageMakesTree(ServerLevel level, Target target) {
        BlockState state = level.getBlockState(target.base());
        return state.getBlock() instanceof SaplingBlock && state.getValue(SaplingBlock.STAGE) == 1;
    }

    /**
     * Grows {@code target} by up to {@code stages} stages.
     *
     * @return {@link Growth#TREE} once it is a tree, {@link Growth#BLOCKED}
     *         when it was ready but vanilla found no room to grow it
     */
    static Growth grow(ServerLevel level, Target target, int stages) {
        BlockPos pos = target.base();
        for (int i = 0; i < stages; i++) {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof SaplingBlock sapling)) {
                return i == 0 ? Growth.GONE : Growth.TREE;
            }
            boolean growsTree = state.getValue(SaplingBlock.STAGE) == 1;
            // For a square, vanilla looks for the 2x2 around the sapling it is
            // given, so the corner stands for all four.
            sapling.advanceTree(level, pos, state, level.random);
            if (growsTree) {
                return level.getBlockState(pos).getBlock() instanceof SaplingBlock
                        ? Growth.BLOCKED : Growth.TREE;
            }
        }
        return Growth.STAGED;
    }

    private static boolean isSquare(ServerLevel level, BlockPos corner, Block block) {
        return level.getBlockState(corner).is(block)
                && level.getBlockState(corner.east()).is(block)
                && level.getBlockState(corner.south()).is(block)
                && level.getBlockState(corner.south().east()).is(block);
    }

    private static boolean insideAnyBuilding(Activity.Context ctx, BlockPos pos) {
        for (Building building : ctx.mgr().getBuildings()) {
            if (building.isInRoom(pos)) return true;
        }
        return false;
    }
}

package com.alex.test.village.building;

import com.alex.test.DynamicVillages;
import com.alex.test.block.WoodenChairBlock;
import com.alex.test.village.stats.CrowdingBalance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How much room a building gives the villagers inside it, for overcrowding.
 *
 * <p>Space is the room's floor tiles plus its furniture: a bed, chest or
 * bookshelf takes floor away from walking but is still part of the home, the
 * way the TekTopia rule counts it. Each tile is worth one, or
 * {@link CrowdingBalance#LOW_CEILING_WEIGHT} when its headroom is two blocks
 * or less, measured from what the villager actually stands on (a carpet, a
 * slab) to the underside of what is overhead (a top slab leaves 2.5).
 *
 * <p>Furniture only counts where it stands inside the room: next to one of the
 * room's tiles at the same height, with the room or a solid block behind it.
 * A bookshelf that <em>is</em> the wall has the outdoors behind it and is left
 * out, so a library walled in bookshelves does not count its walls as floor.
 * The two halves of a bed count together once either does.
 */
public final class BuildingSpace {
    private BuildingSpace() {}

    /** Blocks that count as floor space although nobody can stand in them. */
    public static final TagKey<Block> FURNITURE = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "furniture"));

    private static final double EPSILON = 1e-4;

    /** A measured room. {@code space} already has the low-ceiling weighting applied. */
    public record Result(float space, int floorTiles, int lowTiles, int furnitureTiles) {
        public static final Result NONE = new Result(0f, 0, 0, 0);

        public int fits() { return CrowdingBalance.fits(space); }
    }

    /**
     * Measures the room made of {@code tiles}.
     *
     * @param ceilings ceiling heights the room scan already read, keyed by feet
     *                 position; any tile missing from it is read here
     */
    public static Result measure(ServerLevel level, Set<BlockPos> tiles, Map<Long, Integer> ceilings) {
        if (tiles.isEmpty()) return Result.NONE;
        float space = 0f;
        int floor = 0, low = 0, furniture = 0;
        Set<BlockPos> lowTiles = new HashSet<>();
        List<BlockPos> floorTiles = new ArrayList<>();

        for (BlockPos tile : tiles) {
            // Standing on a bed or a chest is not floor: the furniture under it counts instead.
            if (isFurniture(level.getBlockState(tile.below()))) continue;
            boolean cramped = isLowCeiling(level, tile, ceilings);
            floorTiles.add(tile);
            floor++;
            if (cramped) {
                low++;
                lowTiles.add(tile);
                space += CrowdingBalance.LOW_CEILING_WEIGHT;
            } else {
                space += 1f;
            }
        }

        Set<BlockPos> counted = new HashSet<>();
        for (BlockPos tile : floorTiles) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos pos = tile.relative(dir);
                if (tiles.contains(pos) || counted.contains(pos)) continue;
                BlockState state = level.getBlockState(pos);
                if (!isFurniture(state) || !standsInside(level, tiles, pos, dir)) continue;
                boolean cramped = lowTiles.contains(tile);
                counted.add(pos.immutable());
                space += cramped ? CrowdingBalance.LOW_CEILING_WEIGHT : 1f;
                furniture++;
                if (cramped) low++;
                if (state.getBlock() instanceof BedBlock) {
                    BlockPos other = pos.relative(BedBlock.getConnectedDirection(state));
                    if (!tiles.contains(other) && counted.add(other.immutable())
                            && isFurniture(level.getBlockState(other))) {
                        space += cramped ? CrowdingBalance.LOW_CEILING_WEIGHT : 1f;
                        furniture++;
                        if (cramped) low++;
                    }
                }
            }
        }
        return new Result(space, floor, low, furniture);
    }

    public static boolean isFurniture(BlockState state) {
        return state.is(FURNITURE) || state.getBlock() instanceof WoodenChairBlock;
    }

    /**
     * Whether furniture at {@code pos}, reached from a room tile going
     * {@code dir}, stands inside the room: behind it is more room, more
     * furniture, or a wall -- anything but open air.
     */
    private static boolean standsInside(ServerLevel level, Set<BlockPos> tiles, BlockPos pos, Direction dir) {
        BlockPos behind = pos.relative(dir);
        if (tiles.contains(behind)) return true;
        BlockState state = level.getBlockState(behind);
        return isFurniture(state) || isSolid(level, behind, state);
    }

    /** Two blocks of headroom or less over this tile. */
    static boolean isLowCeiling(ServerLevel level, BlockPos tile, Map<Long, Integer> ceilings) {
        Integer cached = ceilings.get(tile.asLong());
        int roofY = cached != null ? cached : RoomScanner.findCeilingY(level, tile);
        if (roofY == RoomScanner.NO_CEILING) return false;
        BlockPos roof = new BlockPos(tile.getX(), roofY, tile.getZ());
        VoxelShape roofShape = level.getBlockState(roof).getCollisionShape(level, roof);
        double underside = roofY + (roofShape.isEmpty() ? 0.0 : roofShape.min(Direction.Axis.Y));
        double headroom = underside - standY(level, tile);
        return headroom <= CrowdingBalance.LOW_CEILING_HEADROOM + EPSILON;
    }

    /**
     * The height a villager stands at on this tile: on top of a carpet or snow
     * layer in the tile itself, else on top of the block below (half a block
     * down on a bottom slab).
     */
    private static double standY(ServerLevel level, BlockPos tile) {
        VoxelShape inTile = level.getBlockState(tile).getCollisionShape(level, tile);
        if (!inTile.isEmpty() && inTile.max(Direction.Axis.Y) < 1.0) {
            return tile.getY() + inTile.max(Direction.Axis.Y);
        }
        BlockPos below = tile.below();
        VoxelShape under = level.getBlockState(below).getCollisionShape(level, below);
        if (!under.isEmpty()) return below.getY() + Math.min(1.0, under.max(Direction.Axis.Y));
        return tile.getY();
    }

    private static boolean isSolid(ServerLevel level, BlockPos pos, BlockState state) {
        return state.blocksMotion()
                || (!state.isAir() && !state.getCollisionShape(level, pos).isEmpty());
    }
}

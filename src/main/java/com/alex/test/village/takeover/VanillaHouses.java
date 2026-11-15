package com.alex.test.village.takeover;

import com.alex.test.DynamicVillages;
import com.alex.test.village.NavGraph;
import com.alex.test.village.VillageManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the houses of a world-generated village and gets them ready for tokens.
 *
 * <p>Vanilla houses are close to what a token needs, but not always: many desert
 * and savanna houses have an open doorway with no door, and a room that stays
 * open to the outside is a room no token accepts. So each house is flooded from
 * the inside along its floor, and every place the flood gets out through a one
 * block doorway (wall on both sides, a block over the top) is where a door
 * would close it. Doors are only planned when they close the house completely;
 * a house that stays open, such as a workshop missing a wall, is left alone.
 *
 * <p>This is a quick look, not the real room check: the token still goes
 * through the usual registration, which has the final say and explains itself
 * in chat if it disagrees.
 */
final class VanillaHouses {

    /** Floor tiles one flood may cover; vanilla houses are far smaller. */
    private static final int MAX_ROOM_TILES = 256;
    /** Smallest floor worth a token. */
    private static final int MIN_ROOM_TILES = 4;
    /** How far above head height a roof may sit and still count as a roof. */
    private static final int MAX_ROOF_GAP = 10;
    /** How far past its piece a house's floor may reach (porches, door steps). */
    private static final int PIECE_MARGIN = 2;
    /** Doors one house may be given before it counts as too open to fix. */
    private static final int MAX_NEW_DOORS = 3;
    /** How far back from where the flood got out the wall line may be (roof overhangs). */
    private static final int MAX_DOORWAY_STEP_BACK = 2;
    /** Pieces without beds bigger than this are streets and fields, not rooms. */
    private static final int MAX_BEDLESS_SPAN = 20;
    /** Collision this low at foot height is a carpet or a snow layer, not furniture. */
    private static final double LOW_SHAPE = 0.1875;

    private VanillaHouses() {}

    /** A door the house will get, and which way its outside lies. */
    record NewDoor(BlockPos pos, Direction out) {}

    /**
     * One house that closes up, with or without new doors.
     *
     * @param door     lower half of the door its token hangs by
     * @param out      from the room through that door to the outside
     * @param tiles    floor tiles at door height reached from inside
     * @param beds     beds standing in that room
     * @param newDoors doors still to be placed before the room is closed
     */
    record House(BlockPos door, Direction out, Set<BlockPos> tiles, int beds,
                 List<NewDoor> newDoors, Block doorBlock) {

        /** True when {@code pos} is this house's door, floor, or right beside the floor. */
        boolean touches(BlockPos pos) {
            if (door.equals(pos) || tiles.contains(pos)) return true;
            for (NewDoor planned : newDoors) {
                if (planned.pos().equals(pos)) return true;
            }
            for (Direction side : Direction.Plane.HORIZONTAL) {
                if (tiles.contains(pos.relative(side))) return true;
            }
            return false;
        }
    }

    /**
     * An abandoned village: the generator's zombie variant, with zombie
     * villagers in its houses. Its pieces come from the {@code zombie} pools
     * (for example {@code village/plains/zombie/houses/...}).
     */
    static boolean isAbandoned(StructureStart start) {
        for (StructurePiece piece : start.getPieces()) {
            if (piece instanceof PoolElementStructurePiece pool
                    && pool.getElement().toString().contains("/zombie/")) return true;
        }
        return false;
    }

    /** Whether the village's map reaches this house: its door or the floor just beside it. */
    static boolean onMap(@Nullable NavGraph graph, House house) {
        return graph != null && graph.nearest(house.door(), 1, 1) != null;
    }

    /** Every house in the village that is, or can be made, a closed room. */
    static List<House> survey(ServerLevel level, StructureStart start) {
        List<House> houses = new ArrayList<>();
        Set<BlockPos> taken = new HashSet<>();
        for (StructurePiece piece : start.getPieces()) {
            BoundingBox box = piece.getBoundingBox();
            if (!isLoaded(level, box)) continue;
            Contents contents = readPiece(level, box);
            if (contents.seed() == null || taken.contains(contents.seed())) continue;
            House house = closeUp(level, box, contents);
            if (house == null || overlaps(house.tiles(), taken)) continue;
            taken.addAll(house.tiles());
            houses.add(house);
        }
        return houses;
    }

    /**
     * Places the doors the survey planned.
     *
     * @param village told about each door so its paths update; null before founding
     * @return how many doors went in
     */
    static int placeDoors(ServerLevel level, House house, @Nullable VillageManager village) {
        int placed = 0;
        for (NewDoor door : house.newDoors()) {
            // The world may have changed since the survey.
            if (!replaceable(level, door.pos()) || !replaceable(level, door.pos().above())) continue;
            BlockState lower = house.doorBlock().defaultBlockState()
                    .setValue(DoorBlock.FACING, door.out())
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                    .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
                    .setValue(DoorBlock.OPEN, false)
                    .setValue(DoorBlock.POWERED, false);
            level.setBlock(door.pos(), lower, Block.UPDATE_ALL);
            level.setBlock(door.pos().above(),
                    lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
            // Placed straight into the world, so no placement event tells the paths.
            if (village != null) village.noteBlockChanged(door.pos());
            placed++;
        }
        return placed;
    }

    /**
     * Hangs {@code token} in a new item frame where the token search will find
     * this door: over it, or beside its top half, outside first, and only in a
     * spot with nothing else in it.
     *
     * <p>Like a player: the empty frame goes up first, then the token goes in.
     * A frame that arrives already holding a Town Hall token founds the village
     * inside its own join event, before the frame is in the world, and the new
     * village's search for its Town Hall frame then comes up empty.
     *
     * @return the frame, already in the world, or null when no spot holds a frame
     */
    @Nullable
    static ItemFrame hangToken(ServerLevel level, BlockPos door, Direction out, ItemStack token) {
        Direction outside = outsideOf(level, door, out);
        // Why each outside spot was turned down, for the one line logged if the
        // token has to go inside or nowhere.
        StringBuilder refused = new StringBuilder();
        // Every spot on the outside first, then the inside as a last resort.
        for (Direction facing : new Direction[]{outside, outside.getOpposite()}) {
            for (BlockPos support : supportSpots(door, facing)) {
                BlockPos framePos = support.relative(facing);
                boolean isOutside = facing == outside;
                // A frame would happily share its block with a torch, lantern or sign
                // (none of them block it), and end up drawn right through it.
                if (!level.getBlockState(framePos).isAir()) {
                    if (isOutside) refused.append(" ").append(framePos.toShortString()).append(" is ")
                            .append(level.getBlockState(framePos).getBlock().getDescriptionId()).append(";");
                    continue;
                }
                ItemFrame frame = new ItemFrame(level, framePos, facing);
                // Any block with a full face will do, a stair included: it is the same
                // test a player's frame goes through.
                if (!frame.survives()) {
                    if (isOutside) refused.append(" ").append(framePos.toShortString()).append(" on ")
                            .append(level.getBlockState(support).getBlock().getDescriptionId())
                            .append(" will not hold;");
                    continue;
                }
                if (!level.addFreshEntity(frame)) continue;
                frame.setItem(token.copyWithCount(1), false);
                if (!isOutside) {
                    DynamicVillages.LOGGER.info("[Takeover] Door {}: no spot on the outside, hung the "
                            + "token inside at {}.{}", door.toShortString(), framePos.toShortString(), refused);
                }
                return frame;
            }
        }
        DynamicVillages.LOGGER.info("[Takeover] Door {}: no spot for the token on either side.{}",
                door.toShortString(), refused);
        return null;
    }

    /**
     * Where the token may hang for a door, best first. Every spot is one the
     * token search pairs with the door: a block over it, beside it, or the
     * block over the opening one step out (a door set back in an alcove).
     * A lintel may be a stair, a slab or a block; any of them will do.
     *
     * <p>Over the door and beside its top half come first, as they always have.
     * A door set back in a doorway often has no free air at either, so next the
     * pillar right beside the door at floor level, then the beam over the
     * opening in front of it.
     */
    private static List<BlockPos> supportSpots(BlockPos door, Direction facing) {
        BlockPos sideA = door.relative(facing.getClockWise());
        BlockPos sideB = door.relative(facing.getCounterClockWise());
        BlockPos front = door.relative(facing);
        return List.of(door.above(2), sideA.above(), sideB.above(),
                sideA, sideB, front.above(2),
                door.above(3), sideA.above(2), sideB.above(2), sideA.above(3), sideB.above(3),
                front.above(3));
    }

    /** How far to look along each side of a door for open sky. */
    private static final int SKY_LOOK = 6;
    /** Past this many steps to open sky the far side is not "right outside". */
    private static final int NEAR_SKY = 4;

    /**
     * Which way the outside of a door lies. The survey's answer, unless the
     * other side is clearly the street: the frame hangs on the outside, and a
     * door recessed into its wall makes a porch that looks like a room.
     */
    private static Direction outsideOf(ServerLevel level, BlockPos door, Direction out) {
        int outReach = stepsToSky(level, door, out);
        int inReach = stepsToSky(level, door, out.getOpposite());
        if (inReach < outReach && inReach <= NEAR_SKY) {
            DynamicVillages.LOGGER.info("[Takeover] Door {}: open sky is {} steps {} and {} steps {}; hanging the "
                            + "token on the {} side.", door.toShortString(), outReach, out,
                    inReach, out.getOpposite(), out.getOpposite());
            return out.getOpposite();
        }
        return out;
    }

    /**
     * Steps from the door, straight along {@code dir}, to the first tile that
     * is open and has no roof; a wall or furniture first, or no sky within
     * {@link #SKY_LOOK} steps, counts as {@code Integer.MAX_VALUE}.
     */
    private static int stepsToSky(ServerLevel level, BlockPos door, Direction dir) {
        for (int step = 1; step <= SKY_LOOK; step++) {
            BlockPos tile = door.relative(dir, step);
            if (!isOpen(level, tile)) return Integer.MAX_VALUE;
            if (!isRoofed(level, tile)) return step;
        }
        return Integer.MAX_VALUE;
    }

    /**
     * A floor tile for the storage chest: against a wall, away from the doors,
     * preferring a corner far from the front door.
     */
    @Nullable
    static BlockPos chestSpot(ServerLevel level, House house) {
        Set<BlockPos> doors = new HashSet<>();
        doors.add(house.door());
        for (NewDoor planned : house.newDoors()) doors.add(planned.pos());

        BlockPos best = null;
        int bestWalls = 0;
        double bestDistance = -1;
        for (BlockPos tile : house.tiles()) {
            if (besideAny(tile, doors) || !replaceable(level, tile)) continue;
            if (!level.getBlockState(tile.below()).isFaceSturdy(level, tile.below(), Direction.UP)) continue;
            int walls = 0;
            Direction firstWall = null;
            boolean opposite = false;
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos next = tile.relative(side);
                if (house.tiles().contains(next) || !isSolid(level, next)) continue;
                if (firstWall != null && side == firstWall.getOpposite()) opposite = true;
                if (firstWall == null) firstWall = side;
                walls++;
            }
            // Walls on opposite sides mean a corridor: a chest there blocks it.
            if (walls == 0 || (walls == 2 && opposite)) continue;
            double distance = tile.distSqr(house.door());
            if (walls > bestWalls || (walls == bestWalls && distance > bestDistance)) {
                best = tile;
                bestWalls = walls;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** A chest at {@code spot} with its lid opening into the room. */
    static BlockState chestState(House house, BlockPos spot) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (house.tiles().contains(spot.relative(side))) {
                return Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, side);
            }
        }
        return Blocks.CHEST.defaultBlockState();
    }

    // =========================================================================
    // Reading a piece
    // =========================================================================

    private record Contents(@Nullable BlockPos seed, List<BlockPos> bedHeads, Block doorBlock) {}

    private static Contents readPiece(ServerLevel level, BoundingBox box) {
        List<BlockPos> bedHeads = new ArrayList<>();
        Map<Block, Integer> planks = new HashMap<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    BlockState state = level.getBlockState(at.set(x, y, z));
                    if (state.getBlock() instanceof BedBlock) {
                        if (state.getValue(BedBlock.PART) == BedPart.HEAD) bedHeads.add(at.immutable());
                    } else if (state.is(BlockTags.PLANKS)) {
                        planks.merge(state.getBlock(), 1, Integer::sum);
                    }
                }
            }
        }
        BlockPos seed;
        if (!bedHeads.isEmpty()) {
            seed = bedSeed(level, bedHeads.get(0));
        } else if (box.getXSpan() <= MAX_BEDLESS_SPAN && box.getZSpan() <= MAX_BEDLESS_SPAN) {
            seed = roofedSeed(level, box);
        } else {
            seed = null;
        }
        return new Contents(seed, bedHeads, doorFor(planks));
    }

    /** A covered floor tile beside the bed. */
    @Nullable
    private static BlockPos bedSeed(ServerLevel level, BlockPos head) {
        BlockPos foot = head.relative(level.getBlockState(head).getValue(BedBlock.FACING).getOpposite());
        for (BlockPos part : new BlockPos[]{head, foot}) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos next = part.relative(side);
                if (isOpen(level, next) && isRoofed(level, next)) return next;
            }
        }
        return null;
    }

    /** The covered floor tile nearest the middle of a piece with no bed. */
    @Nullable
    private static BlockPos roofedSeed(ServerLevel level, BoundingBox box) {
        BlockPos center = box.getCenter();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    at.set(x, y, z);
                    double distance = at.distSqr(center);
                    if (distance >= bestDistance) continue;
                    if (!isOpen(level, at) || isEmptyShape(level, at.below())) continue;
                    if (!isRoofed(level, at)) continue;
                    best = at.immutable();
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    /** The door matching the planks the house is mostly built from; oak if none. */
    private static Block doorFor(Map<Block, Integer> planks) {
        Block most = null;
        int count = 0;
        for (Map.Entry<Block, Integer> entry : planks.entrySet()) {
            if (entry.getValue() > count) {
                most = entry.getKey();
                count = entry.getValue();
            }
        }
        if (most == null) return Blocks.OAK_DOOR;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(most);
        if (!id.getPath().endsWith("_planks")) return Blocks.OAK_DOOR;
        Block door = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(
                id.getNamespace(), id.getPath().replace("_planks", "_door")));
        return door instanceof DoorBlock ? door : Blocks.OAK_DOOR;
    }

    // =========================================================================
    // Closing a house
    // =========================================================================

    private record Leak(BlockPos from, Direction dir) {}

    private record DoorSide(BlockPos pos, Direction out) {}

    private record Flood(Set<BlockPos> tiles, List<Leak> leaks, List<DoorSide> doors) {}

    /** Floods the house, planning a door at each doorway it leaks through, until it is closed. */
    @Nullable
    private static House closeUp(ServerLevel level, BoundingBox box, Contents contents) {
        BlockPos seed = contents.seed();
        BoundingBox limit = box.inflatedBy(PIECE_MARGIN);
        List<NewDoor> planned = new ArrayList<>();
        for (int round = 0; ; round++) {
            Flood flood = flood(level, seed, limit, planned);
            if (flood == null) return null;
            if (flood.leaks().isEmpty()) {
                if (flood.tiles().size() < MIN_ROOM_TILES) return null;
                DoorSide front = frontDoor(level, flood.doors());
                if (front == null) return null;
                return new House(front.pos(), front.out(), flood.tiles(),
                        countBeds(level, contents.bedHeads(), flood.tiles()),
                        List.copyOf(planned), contents.doorBlock());
            }
            if (round >= MAX_NEW_DOORS) return null;
            NewDoor doorway = null;
            for (Leak leak : flood.leaks()) {
                doorway = findDoorway(level, flood.tiles(), leak, seed);
                if (doorway != null) break;
            }
            if (doorway == null) return null;
            planned.add(doorway);
        }
    }

    /**
     * Walks the floor at the seed's height. Stops at walls, furniture and doors
     * (planned ones too); a step onto open sky, or out past the piece, is a leak.
     *
     * @return null when the floor is too big to be one house
     */
    @Nullable
    private static Flood flood(ServerLevel level, BlockPos seed, BoundingBox limit,
                               List<NewDoor> planned) {
        Set<BlockPos> plannedDoors = new HashSet<>();
        for (NewDoor door : planned) plannedDoors.add(door.pos());

        Set<BlockPos> tiles = new LinkedHashSet<>();
        List<Leak> leaks = new ArrayList<>();
        Map<BlockPos, DoorSide> doors = new LinkedHashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        tiles.add(seed);
        queue.add(seed);
        while (!queue.isEmpty()) {
            BlockPos at = queue.poll();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos next = at.relative(dir);
                if (tiles.contains(next)) continue;
                if (plannedDoors.contains(next) || isDoorBase(level.getBlockState(next))) {
                    doors.putIfAbsent(next, new DoorSide(next, dir));
                    continue;
                }
                if (!isOpen(level, next)) continue;
                if (!limit.isInside(next) || !isRoofed(level, next)) {
                    leaks.add(new Leak(at, dir));
                    continue;
                }
                if (tiles.size() >= MAX_ROOM_TILES) return null;
                tiles.add(next);
                queue.add(next);
            }
        }
        return new Flood(tiles, leaks, List.copyOf(doors.values()));
    }

    /**
     * Where a leak's wall line is: the leak tile itself, or a step or two back
     * when the roof hangs out over the doorstep.
     */
    @Nullable
    private static NewDoor findDoorway(ServerLevel level, Set<BlockPos> tiles, Leak leak, BlockPos seed) {
        Direction out = leak.dir();
        BlockPos candidate = leak.from();
        for (int back = 0; back <= MAX_DOORWAY_STEP_BACK; back++) {
            if (!tiles.contains(candidate) || candidate.equals(seed)) return null;
            if (isDoorway(level, candidate, out, tiles)) return new NewDoor(candidate, out);
            candidate = candidate.relative(out.getOpposite());
        }
        return null;
    }

    /** One block wide, two tall, wall on both sides, a block over the top, floor under it. */
    private static boolean isDoorway(ServerLevel level, BlockPos pos, Direction out, Set<BlockPos> tiles) {
        if (!tiles.contains(pos.relative(out.getOpposite()))) return false;
        if (!isOpen(level, pos.relative(out))) return false;
        if (!replaceable(level, pos) || !replaceable(level, pos.above())) return false;
        if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) return false;
        if (!isSolid(level, pos.above(2))) return false;
        for (Direction side : new Direction[]{out.getClockWise(), out.getCounterClockWise()}) {
            BlockPos wall = pos.relative(side);
            if (!isSolid(level, wall) || !isSolid(level, wall.above())) return false;
        }
        return true;
    }

    /** The door that opens onto open sky, else any door out of the room. */
    @Nullable
    private static DoorSide frontDoor(ServerLevel level, List<DoorSide> doors) {
        DoorSide fallback = null;
        for (DoorSide door : doors) {
            BlockPos beyond = door.pos().relative(door.out());
            if (!isOpen(level, beyond)) continue;
            if (!isRoofed(level, beyond)) return door;
            if (fallback == null) fallback = door;
        }
        return fallback;
    }

    /** Beds with the head or foot beside the room's floor. */
    private static int countBeds(ServerLevel level, List<BlockPos> bedHeads, Set<BlockPos> tiles) {
        int beds = 0;
        for (BlockPos head : bedHeads) {
            BlockState state = level.getBlockState(head);
            if (!(state.getBlock() instanceof BedBlock)) continue;
            BlockPos foot = head.relative(state.getValue(BedBlock.FACING).getOpposite());
            if (besideAny(head, tiles) || besideAny(foot, tiles)) beds++;
        }
        return beds;
    }

    // =========================================================================
    // Block tests
    // =========================================================================

    /** Room to stand: nothing solid at the feet (a carpet is fine) or the head, and no water. */
    private static boolean isOpen(ServerLevel level, BlockPos pos) {
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        if (!feet.getFluidState().isEmpty() || !head.getFluidState().isEmpty()) return false;
        VoxelShape feetShape = feet.getCollisionShape(level, pos);
        if (!feetShape.isEmpty() && feetShape.max(Direction.Axis.Y) > LOW_SHAPE) return false;
        return head.getCollisionShape(level, pos.above()).isEmpty();
    }

    /** Something solid somewhere over the head. */
    private static boolean isRoofed(ServerLevel level, BlockPos pos) {
        for (int dy = 2; dy <= 2 + MAX_ROOF_GAP; dy++) {
            if (isSolid(level, pos.above(dy))) return true;
        }
        return false;
    }

    private static boolean isSolid(ServerLevel level, BlockPos pos) {
        return !isEmptyShape(level, pos);
    }

    private static boolean isEmptyShape(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** Air, grass, snow or a carpet: something a door or chest may simply replace. */
    private static boolean replaceable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.canBeReplaced() || state.is(BlockTags.WOOL_CARPETS);
    }

    private static boolean isDoorBase(BlockState state) {
        return state.getBlock() instanceof DoorBlock
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
    }

    private static boolean besideAny(BlockPos pos, Set<BlockPos> set) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (set.contains(pos.relative(side))) return true;
        }
        return false;
    }

    private static boolean overlaps(Set<BlockPos> tiles, Set<BlockPos> taken) {
        for (BlockPos tile : tiles) {
            if (taken.contains(tile)) return true;
        }
        return false;
    }

    /** Never load or generate a chunk just to look at a house. */
    private static boolean isLoaded(ServerLevel level, BoundingBox box) {
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) return false;
            }
        }
        return true;
    }
}

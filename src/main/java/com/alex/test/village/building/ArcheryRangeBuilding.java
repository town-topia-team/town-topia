package com.alex.test.village.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Archery Range: a fenced, gated yard where archers practise and make arrows.
 * Created like a ranch, by hanging the Archery Range token on the fence beside
 * the gate.
 *
 * <p>Inside it recognises target blocks, fletching tables and hay bales. Each
 * target becomes one shooting lane when the yard has open floor 6 to 12 blocks
 * in front of it with a clear shot; the standing spot of every lane is worked
 * out here, on each scan, so the archers only have to claim one. Lanes never
 * cross: a spot in another lane's line of fire is not used.
 *
 * <p>Furniture is looked for in the columns beside the floor tiles, from the
 * floor up to {@value #FURNITURE_ABOVE} blocks above it, not only at foot level
 * like {@link #isFunctionalBlockAccessibleFromRoom}: a target usually sits on
 * a hay bale at chest height.
 */
public final class ArcheryRangeBuilding extends EnclosureBuilding {

    public static final String SAVED_TYPE = "archery_range";
    /** The same token beside a door: a walled, roofed room instead of a fenced yard. */
    public static final String ROOM_SAVED_TYPE = "archery_range_room";
    /** Arrows a craft makes at a fletching table; a crafting table makes vanilla's four. */
    public static final int ARROWS_PER_FLETCH = 6;

    /** Distance from a target, along the ground, to where its archer stands. */
    public static final double MIN_LANE = 6.0D;
    public static final double MAX_LANE = 12.0D;
    private static final double IDEAL_LANE = 9.0D;
    /** The standing spot may be this many blocks above or below the target. */
    private static final int LANE_DY = 4;
    /** Furniture counts from the floor up to this many blocks above it. */
    private static final int FURNITURE_ABOVE = 3;
    /** Eye height of a standing villager, for the clear-shot check. */
    private static final double EYE = 1.6D;
    /** Another lane's standing spot or line of fire this close is in the way. */
    private static final double LANE_CLEARANCE = 1.2D;
    /** Best-scoring candidate spots tried for a clear shot, per target. */
    private static final int SHOT_CHECKS = 24;
    /** From a target's centre to just inside the face being shot at. */
    private static final double FACE_INSET = 0.48D;

    /**
     * One shooting lane: a target, the floor tile its archer shoots from, and
     * the tile beside the target where it stands to pull its arrows out.
     */
    public record Lane(BlockPos target, BlockPos stand, BlockPos pull) {}

    private final List<BlockPos> targets = new ArrayList<>();
    private final List<Lane> lanes = new ArrayList<>();
    private final List<BlockPos> fletchingTables = new ArrayList<>();
    private int hayBales;

    /** A walled room behind a door (true) or a fenced yard behind a gate (false). */
    private final boolean indoor;

    public ArcheryRangeBuilding(UUID itemFrameId, BlockPos gatePos, BlockPos itemFramePos,
                                Direction interiorDirection, Set<BlockPos> initialTiles) {
        this(itemFrameId, gatePos, itemFramePos, interiorDirection, initialTiles, false);
    }

    /** {@code indoor}: the token hangs beside a door, and the range is the walled room behind it. */
    public ArcheryRangeBuilding(UUID itemFrameId, BlockPos entrancePos, BlockPos itemFramePos,
                                Direction interiorDirection, Set<BlockPos> initialTiles, boolean indoor) {
        super(itemFrameId, entrancePos, itemFramePos, interiorDirection, initialTiles);
        this.indoor = indoor;
    }

    public boolean isIndoor() {
        return indoor;
    }

    /** An indoor range is scanned like any room; a yard by its fence. */
    @Override
    protected Set<BlockPos> scanRoomBlocks(ServerLevel level, com.alex.test.village.NavGraph navGraph,
                                           java.util.Collection<BlockPos> rivalEntries) {
        if (!indoor) return super.scanRoomBlocks(level, navGraph, rivalEntries);
        return RoomScanner.scanDetailed(level, navGraph, doorPos, interiorDirection, rivalEntries).tiles();
    }

    @Override
    protected boolean scanFunctionalBlocks(ServerLevel level) {
        targets.clear();
        lanes.clear();
        fletchingTables.clear();
        hayBales = 0;
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos tile : navMeshBlocks) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos column = tile.relative(dir);
                for (int dy = 0; dy <= FURNITURE_ABOVE; dy++) {
                    BlockPos pos = column.above(dy);
                    if (!seen.add(pos) || !level.isLoaded(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    if (state.is(Blocks.TARGET)) {
                        targets.add(pos.immutable());
                    } else if (state.is(Blocks.FLETCHING_TABLE)) {
                        // A table is worked from the floor beside it, so only at foot level.
                        if (dy == 0) fletchingTables.add(pos.immutable());
                    } else if (state.is(Blocks.HAY_BLOCK)) {
                        hayBales++;
                    }
                }
            }
        }
        targets.sort(Comparator.<BlockPos>comparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getY));
        planLanes(level);
        functionalBlocks.addAll(targets);
        functionalBlocks.addAll(fletchingTables);
        return !functionalBlocks.isEmpty();
    }

    /** Gives each target the best free standing spot, if it has one. */
    private void planLanes(ServerLevel level) {
        for (BlockPos target : targets) {
            Vec3 centre = Vec3.atCenterOf(target);
            List<Candidate> candidates = new ArrayList<>();
            for (BlockPos tile : navMeshBlocks) {
                double dx = tile.getX() + 0.5D - centre.x;
                double dz = tile.getZ() + 0.5D - centre.z;
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist < MIN_LANE || dist > MAX_LANE) continue;
                if (Math.abs(tile.getY() - target.getY()) > LANE_DY) continue;
                if (standsOnFurniture(level, tile)) continue;
                if (inAnotherLane(tile, target)) continue;
                // Straight out from a face shoots best: the sideways offset from
                // the lines through the target's middle costs most.
                double side = Math.min(Math.abs(dx), Math.abs(dz));
                candidates.add(new Candidate(tile, Math.abs(dist - IDEAL_LANE) * 2.0D + side * 3.0D));
            }
            candidates.sort(Comparator.comparingDouble(Candidate::score));
            int checks = 0;
            for (Candidate candidate : candidates) {
                if (checks++ >= SHOT_CHECKS) break;
                if (!level.isLoaded(candidate.tile())) continue;
                Vec3 eye = new Vec3(candidate.tile().getX() + 0.5D, candidate.tile().getY() + EYE,
                        candidate.tile().getZ() + 0.5D);
                if (hasClearShot(level, eye, target)) {
                    BlockPos pull = pullSpot(level, target, aimPoint(target, eye));
                    if (pull != null) lanes.add(new Lane(target, candidate.tile(), pull));
                    break;
                }
            }
        }
    }

    /**
     * The yard scan counts the top of a lone hay bale or target as floor, since
     * a villager could step up on it; nobody should shoot or pull arrows from there.
     */
    private static boolean standsOnFurniture(ServerLevel level, BlockPos tile) {
        // Unloaded: not known, so not used (reading it would load the chunk).
        if (!level.isLoaded(tile.below())) return true;
        BlockState below = level.getBlockState(tile.below());
        return below.is(Blocks.TARGET) || below.is(Blocks.HAY_BLOCK) || below.is(Blocks.FLETCHING_TABLE);
    }

    /**
     * The floor tile beside {@code target}, at or below it, nearest the middle
     * of the face that is shot at ({@code face}): in front, where the arrows stick.
     */
    @Nullable
    private BlockPos pullSpot(ServerLevel level, BlockPos target, Vec3 face) {
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos tile : navMeshBlocks) {
            if (Math.abs(tile.getX() - target.getX()) > 1 || Math.abs(tile.getZ() - target.getZ()) > 1) continue;
            if (tile.getY() > target.getY()) continue;
            if (standsOnFurniture(level, tile)) continue;
            double d = face.distanceToSqr(tile.getX() + 0.5D, tile.getY() + 1.0D, tile.getZ() + 0.5D);
            if (d < bestSq) {
                bestSq = d;
                best = tile;
            }
        }
        return best;
    }

    private record Candidate(BlockPos tile, double score) {}

    /**
     * Whether {@code tile} stands in, or its shot crosses, a lane already
     * planned: either standing spot too close to the other line of fire, or
     * the two lines crossing in an X somewhere in the middle of the yard.
     */
    private boolean inAnotherLane(BlockPos tile, BlockPos target) {
        double tx = tile.getX() + 0.5D;
        double tz = tile.getZ() + 0.5D;
        double gx = target.getX() + 0.5D;
        double gz = target.getZ() + 0.5D;
        for (Lane lane : lanes) {
            double sx = lane.stand().getX() + 0.5D;
            double sz = lane.stand().getZ() + 0.5D;
            double lx = lane.target().getX() + 0.5D;
            double lz = lane.target().getZ() + 0.5D;
            if (distanceToSegment(tx, tz, sx, sz, lx, lz) < LANE_CLEARANCE) return true;
            if (distanceToSegment(sx, sz, tx, tz, gx, gz) < LANE_CLEARANCE) return true;
            if (segmentsCross(tx, tz, gx, gz, sx, sz, lx, lz)) return true;
        }
        return false;
    }

    /** Whether the line a-b and the line c-d cross each other, seen from above. */
    private static boolean segmentsCross(double ax, double az, double bx, double bz,
                                         double cx, double cz, double dx, double dz) {
        double c1 = side(cx, cz, dx, dz, ax, az);
        double c2 = side(cx, cz, dx, dz, bx, bz);
        double c3 = side(ax, az, bx, bz, cx, cz);
        double c4 = side(ax, az, bx, bz, dx, dz);
        // Touching or lying along each other is the distance checks' business.
        return c1 * c2 < 0.0D && c3 * c4 < 0.0D;
    }

    /** Which side of the line a-b the point p is on: the sign of the cross product. */
    private static double side(double ax, double az, double bx, double bz, double px, double pz) {
        return (bx - ax) * (pz - az) - (bz - az) * (px - ax);
    }

    private static double distanceToSegment(double px, double pz, double ax, double az, double bx, double bz) {
        double vx = bx - ax;
        double vz = bz - az;
        double lenSq = vx * vx + vz * vz;
        double t = lenSq <= 0.0D ? 0.0D : Math.clamp(((px - ax) * vx + (pz - az) * vz) / lenSq, 0.0D, 1.0D);
        double cx = ax + vx * t - px;
        double cz = az + vz * t - pz;
        return Math.sqrt(cx * cx + cz * cz);
    }

    /** Nothing solid between {@code eye} and the face of {@code target} it looks at. */
    public static boolean hasClearShot(ServerLevel level, Vec3 eye, BlockPos target) {
        HitResult hit = level.clip(new ClipContext(eye, aimPoint(target, eye),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.BLOCK && ((BlockHitResult) hit).getBlockPos().equals(target);
    }

    /**
     * The middle of the face of {@code target} that {@code eye} looks at, just
     * inside the block. The block's own centre is half a block behind that
     * face, so a shot at the centre from an angle lands off the middle.
     */
    public static Vec3 aimPoint(BlockPos target, Vec3 eye) {
        Vec3 centre = Vec3.atCenterOf(target);
        Direction face = Direction.getNearest(eye.x - centre.x, eye.y - centre.y, eye.z - centre.z);
        return centre.add(face.getStepX() * FACE_INSET, face.getStepY() * FACE_INSET,
                face.getStepZ() * FACE_INSET);
    }

    /**
     * Whether an arrow lying at {@code pos} can be picked up from the range's
     * own floor: within a block, sideways, of a floor tile, from a block below
     * it to four above (stuck in the fence or a target). The range's outer box
     * was used, and in a yard that is not a plain rectangle the box takes in
     * ground outside the fence: an archer pushed against the fence for each
     * arrow out there.
     */
    public boolean isWithinReachOfFloor(BlockPos pos) {
        BlockPos.MutableBlockPos tile = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int up = -1; up <= 4; up++) {
                    tile.set(pos.getX() + dx, pos.getY() - up, pos.getZ() + dz);
                    if (navMeshBlocks.contains(tile)) return true;
                }
            }
        }
        return false;
    }

    /** Every target block in the yard, lane or not, in a stable order. */
    public List<BlockPos> getTargets() {
        return List.copyOf(targets);
    }

    /** The targets an archer can shoot at, with where to stand for each. */
    public List<Lane> getLanes() {
        return List.copyOf(lanes);
    }

    @Nullable
    public Lane laneFor(BlockPos target) {
        for (Lane lane : lanes) {
            if (lane.target().equals(target)) return lane;
        }
        return null;
    }

    /** Targets with no standing spot: too close to the fence, blocked, or crossed by another lane. */
    public int targetsWithoutLane() {
        return targets.size() - lanes.size();
    }

    public List<BlockPos> getFletchingTables() {
        return List.copyOf(fletchingTables);
    }

    public boolean hasFletchingTable() {
        return !fletchingTables.isEmpty();
    }

    public int getHayBales() {
        return hayBales;
    }

    /** Changes whenever what the player would be told about the yard changes. */
    public String summaryKey() {
        return lanes.size() + "/" + targetsWithoutLane() + "/" + fletchingTables.size();
    }
}

package com.alex.test.village.building;

import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Abstract base for all buildings in the village system.
 *
 * <p>A building is created when a player places a recognised item in an item frame
 * that is mounted near a door.  A BFS room scan using the {@link NavGraph} determines
 * which navigable floor tiles are "inside" the building, and a type-specific scan
 * records optional functional blocks (beds, chests, etc.).
 *
 * <h3>isInRoom contract</h3>
 * The bounding-box test errs toward <em>false positives</em>: a position that is
 * technically outside the room may be reported as inside, but a position that is
 * genuinely inside will never be reported as outside.  This is intentional — it
 * keeps the check cheap while still catching every relevant block-change event.
 */
public abstract class Building {

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    /** UUID of the ItemFrame entity that created this building. */
    protected final UUID itemFrameId;

    /** Block position of the item frame entity. */
    protected final BlockPos itemFramePos;

    /** Block position of the door's lower half. */
    protected final BlockPos doorPos;

    /** Direction pointing FROM the door INTO the room interior. */
    protected final Direction interiorDirection;

    // -------------------------------------------------------------------------
    // Room state (mutable — rebuilt on block changes)
    // -------------------------------------------------------------------------

    /** Whether the building still has a valid room, enclosure or marker shape. */
    protected boolean isValid = false;

    /** Kept separately so an intact building can recover when its route is repaired. */
    private boolean accessValid = true;

    /**
     * feetY-level block positions of every NavGraph node that lies inside the room.
     * Cleared and repopulated by {@link #rebuild}.
     */
    protected final Set<BlockPos> navMeshBlocks = new LinkedHashSet<>();

    /**
     * Positions of type-specific functional blocks found inside the room
     * (e.g. bed heads for a house, chests for a storage building).
     * Cleared and repopulated by {@link #scanFunctionalBlocks}.
     */
    protected final Set<BlockPos> functionalBlocks = new LinkedHashSet<>();

    /** Room space for overcrowding, measured with every rebuild; see {@link BuildingSpace}. */
    private BuildingSpace.Result space = BuildingSpace.Result.NONE;

    /** Ceiling heights from the last room scan, kept only until the space is measured. */
    private java.util.Map<Long, Integer> scanCeilings = java.util.Map.of();

    // -------------------------------------------------------------------------
    // Bounding box (encompasses navMeshBlocks — used by isInRoom)
    // -------------------------------------------------------------------------

    protected int minX, maxX, minY, maxY, minZ, maxZ;

    // =========================================================================
    // Constructor
    // =========================================================================

    protected Building(UUID itemFrameId, BlockPos doorPos, BlockPos itemFramePos,
                       Direction interiorDirection,
                       Set<BlockPos> initialNavMeshBlocks) {
        this.itemFrameId       = itemFrameId;
        this.doorPos           = doorPos;
        this.itemFramePos      = itemFramePos;
        this.interiorDirection = interiorDirection;
        navMeshBlocks.addAll(initialNavMeshBlocks);
        computeBounds();
    }

    // =========================================================================
    // Bounding box
    // =========================================================================

    /** Whether {@link #computeBounds} has ever seen a non-empty room. */
    private boolean boundsEverComputed;

    /**
     * Recompute the AABB that encloses all nav-mesh block positions.
     *
     * <p>When a scan comes back empty the previous box is <em>kept</em>, because
     * this box is also what decides whether a block change is "near" this
     * building and so worth re-checking. Collapsing it to the door column made
     * a broken building unrecoverable: flood a farm and its scan finds no
     * tiles, the box shrinks to the gate, and draining the water five blocks
     * away is then a change that touches nothing the village is watching. The
     * farm stays invalid forever and the only cure is re-hanging the token.
     * A stale box costs nothing -- validity still requires a real room scan --
     * and it is the only way a building can hear about being fixed.
     */
    protected final void computeBounds() {
        if (navMeshBlocks.isEmpty()) {
            if (boundsEverComputed) return;
            // Never had a room to begin with: fall back to the door column.
            minX = maxX = doorPos.getX();
            minY = maxY = doorPos.getY();
            minZ = maxZ = doorPos.getZ();
            return;
        }
        boundsEverComputed = true;
        minX = minY = minZ = Integer.MAX_VALUE;
        maxX = maxY = maxZ = Integer.MIN_VALUE;
        for (BlockPos p : navMeshBlocks) {
            if (p.getX() < minX) minX = p.getX();
            if (p.getX() > maxX) maxX = p.getX();
            if (p.getY() < minY) minY = p.getY();
            if (p.getY() > maxY) maxY = p.getY();
            if (p.getZ() < minZ) minZ = p.getZ();
            if (p.getZ() > maxZ) maxZ = p.getZ();
        }
    }


    /**
     * Returns true when a functional block (chest, bed, crafting table, etc.) is
     * actually reachable from this building's scanned room tiles.
     *
     * <p>This is stricter than the room AABB. In stacked apartments the AABB can
     * overlap the room above/below, so scanning only by bounds can accidentally
     * bind a storage room to bedroom beds, or a bedroom to storage chests.
     */
    protected final boolean isFunctionalBlockAccessibleFromRoom(BlockPos blockPos) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (navMeshBlocks.contains(blockPos.relative(dir))) return true;
        }
        return false;
    }

    /** A block inside this room's box with one of the room's floor tiles beside it. */
    public final boolean reachesFromRoom(BlockPos blockPos) {
        return isInRoom(blockPos) && isFunctionalBlockAccessibleFromRoom(blockPos);
    }

    /**
     * Best walkable room tile beside {@code blockPos}, or {@code null} if that
     * block is not accessible from this building's own nav mesh.
     */
    @Nullable
    public final BlockPos getWalkTargetBeside(BlockPos blockPos, NavGraph graph, BlockPos from) {
        if (blockPos == null || graph == null) return null;
        return getWalkTargetBesideAny(Set.of(blockPos), graph, from);
    }

    /** Same as {@link #getWalkTargetBeside}, but accepts multi-block furniture such as beds. */
    @Nullable
    protected final BlockPos getWalkTargetBesideAny(Collection<BlockPos> blockPositions,
                                                    NavGraph graph,
                                                    BlockPos from) {
        return getWalkTargetBesideAny(blockPositions, graph, from, tile -> true);
    }

    /**
     * The room tile beside any of {@code blockPositions} nearest to {@code from}
     * that {@code usable} accepts, trying them nearest first.
     *
     * <p>Only the nearest tile used to be tried. A bed with a tile boxed in by
     * a chair and a cabinet at its head, approached from that side, then had
     * no way in at all, though the tile beside its foot was open, and its
     * owner slept in the Town Hall every night.
     */
    @Nullable
    protected final BlockPos getWalkTargetBesideAny(Collection<BlockPos> blockPositions,
                                                    NavGraph graph,
                                                    BlockPos from,
                                                    Predicate<BlockPos> usable) {
        if (blockPositions == null || blockPositions.isEmpty() || graph == null) return null;

        BlockPos origin = from != null ? from : doorPos;
        List<NavNode> candidates = new ArrayList<>();

        for (BlockPos blockPos : blockPositions) {
            if (blockPos == null) continue;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos candidate = blockPos.relative(dir);
                if (!navMeshBlocks.contains(candidate)) continue;

                // Exact node lookup is important: nearest(..., yRadius) may snap to
                // the top of a chest/crafting table or to the same X/Z on another floor.
                NavNode node = graph.nodeAt(candidate.getX(), candidate.getY(), candidate.getZ());
                if (node == null || candidates.contains(node)) continue;
                candidates.add(node);
            }
        }

        candidates.sort(Comparator.comparingDouble(node -> node.feetPos().distSqr(origin)));
        for (NavNode node : candidates) {
            if (usable.test(node.feetPos())) return node.feetPos();
        }
        return null;
    }

    /**
     * Room tile to work {@code blockPos} from: one beside it when there is one,
     * otherwise the closest room tile within {@code maxRadius} blocks (XZ) on
     * roughly the same floor. For furniture boxed in on every side, where
     * standing a step further back beats refusing to use it at all.
     */
    @Nullable
    public final BlockPos getWalkTargetNear(BlockPos blockPos, NavGraph graph,
                                            BlockPos from, int maxRadius) {
        BlockPos beside = getWalkTargetBeside(blockPos, graph, from);
        if (beside != null) return beside;
        if (blockPos == null || graph == null) return null;

        BlockPos origin = from != null ? from : doorPos;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        double bestFromDist = Double.MAX_VALUE;
        for (BlockPos candidate : navMeshBlocks) {
            int dx = candidate.getX() - blockPos.getX();
            int dz = candidate.getZ() - blockPos.getZ();
            if (Math.abs(dx) > maxRadius || Math.abs(dz) > maxRadius) continue;
            if (Math.abs(candidate.getY() - blockPos.getY()) > 1) continue;
            if (dx == 0 && dz == 0) continue;
            NavNode node = graph.nodeAt(candidate.getX(), candidate.getY(), candidate.getZ());
            if (node == null || !node.isRoutable()) continue;

            double d = dx * dx + dz * dz;
            double fromDist = candidate.distSqr(origin);
            if (d < bestDist || (d == bestDist && fromDist < bestFromDist)) {
                bestDist = d;
                bestFromDist = fromDist;
                best = candidate;
            }
        }
        return best;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Returns {@code true} if {@code pos} falls within the axis-aligned bounding
     * box that encompasses the room's nav-mesh tiles.
     *
     * <p>This may return {@code true} for positions just outside the room walls
     * (false positive) but will <em>never</em> return {@code false} for a position
     * that is genuinely inside the room.
     */
    public boolean isInRoom(BlockPos pos) {
        return pos.getX() >= minX && pos.getX() <= maxX
            && pos.getY() >= minY && pos.getY() <= maxY
            && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    /** The room's box, one block down for sleepers lying on a bed, as an entity search area. */
    public net.minecraft.world.phys.AABB roomBox() {
        return new net.minecraft.world.phys.AABB(minX, minY - 1, minZ, maxX + 1, maxY + 2, maxZ + 1);
    }

    /**
     * Whether a block change at {@code pos} could change what the room scan
     * finds: within 1 block (XZ) of the room AABB, and anywhere up to the
     * highest ceiling the scan looks for.
     *
     * <p>Wider than {@link #isNearRoom} on purpose. A vaulted or gabled room
     * can have its roof ten blocks up, and changing it must trigger a rescan;
     * but protecting everything that high from miners would be far too much.
     */
    public boolean mayAffectRoom(BlockPos pos) {
        return pos.getX() >= minX - 1 && pos.getX() <= maxX + 1
            && pos.getY() >= minY - 1 && pos.getY() <= maxY + RoomScanner.MAX_CEILING_DIST
            && pos.getZ() >= minZ - 1 && pos.getZ() <= maxZ + 1;
    }

    /**
     * Whether this room's box comes within {@code marginXZ} blocks sideways
     * and {@code marginY} blocks up or down of {@code other}'s.
     *
     * <p>Two rooms that share floor space, or meet at a doorway, always pass:
     * their tiles touch. Used to tell which neighbours a new or removed door
     * can change, so only those are rescanned.
     */
    public boolean roomBoxNear(Building other, int marginXZ, int marginY) {
        return minX - marginXZ <= other.maxX && other.minX <= maxX + marginXZ
            && minY - marginY <= other.maxY && other.minY <= maxY + marginY
            && minZ - marginXZ <= other.maxZ && other.minZ <= maxZ + marginXZ;
    }

    /**
     * Returns {@code true} if {@code pos} is within 1 block (XZ) or up to 3
     * blocks (Y upward) of the room AABB.
     *
     * <p>Used for dirty-rebuild triggering: catches changes to room walls (1 block
     * outside the navmesh horizontally) and ceiling (up to 3 blocks above feetY).
     */
    public boolean isNearRoom(BlockPos pos) {
        return pos.getX() >= minX - 1 && pos.getX() <= maxX + 1
            && pos.getY() >= minY - 1 && pos.getY() <= maxY + 3
            && pos.getZ() >= minZ - 1 && pos.getZ() <= maxZ + 1;
    }

    /** Low corner of the box {@link #isNearRoom} covers: the room with its walls, floor and roof. */
    public BlockPos shellMin() {
        return new BlockPos(minX - 1, minY - 1, minZ - 1);
    }

    /** High corner (inclusive) of the box {@link #isNearRoom} covers. */
    public BlockPos shellMax() {
        return new BlockPos(maxX + 1, maxY + 3, maxZ + 1);
    }

    /**
     * Re-runs the room BFS and functional-block scan after a block change.
     * Updates {@link #isValid} and returns the new validity state.
     *
     * <p>A building stays registered while its room or enclosure still exists.
     * Type-specific blocks are discovered as optional features and no longer
     * decide whether the building itself is valid.
     */
    public final boolean rebuild(ServerLevel level, NavGraph navGraph) {
        return rebuild(level, navGraph, java.util.List.of());
    }

    /**
     * As above, told which tiles the other buildings' doors open onto.
     *
     * <p>Rooms that share a wall with a doorway in it are one connected floor,
     * so without this each of a pair of attached houses scans both and counts
     * the other's beds as its own. {@link RoomScanner} divides shared space
     * between the doors that reach it.
     */
    public final boolean rebuild(ServerLevel level, NavGraph navGraph,
                                 java.util.Collection<BlockPos> rivalEntries) {
        Set<BlockPos> newBlocks = scanRoomBlocks(level, navGraph, rivalEntries);
        navMeshBlocks.clear();
        if (newBlocks != null) navMeshBlocks.addAll(newBlocks);
        computeBounds();

        functionalBlocks.clear();
        scanFunctionalBlocks(level);
        isValid = !navMeshBlocks.isEmpty();
        measureSpace(level);
        return isValid();
    }

    /**
     * Runs the functional-block scan without re-running the room BFS.
     * Called once after initial construction.  Updates {@link #isValid}.
     *
     * <p>As above, validity requires a room; furniture is recorded as optional
     * functionality for villagers and the Mayor's building overview.
     */
    public final boolean validate(ServerLevel level) {
        functionalBlocks.clear();
        scanFunctionalBlocks(level);
        isValid = !navMeshBlocks.isEmpty();
        measureSpace(level);
        return isValid();
    }

    private void measureSpace(ServerLevel level) {
        space = countsCrowding() && !navMeshBlocks.isEmpty()
                ? BuildingSpace.measure(level, navMeshBlocks, scanCeilings)
                : BuildingSpace.Result.NONE;
        scanCeilings = java.util.Map.of();
    }

    /**
     * Whether villagers inside can feel crowded. Off for everything without a
     * proper room -- farms, ranches, mines, open-air stalls -- and for the
     * tavern, where a crowd is the point.
     */
    public boolean countsCrowding() {
        return false;
    }

    /**
     * Beds villagers live in here, no more than may be claimed: what the
     * crowding warnings compare with the room's space. Only a house has them:
     * barracks defenders sleep in shifts and Town Hall beds are a stopgap, so
     * neither fills every bed at once.
     */
    public int livingBeds() {
        return 0;
    }

    /** A short name for messages: the class name without "Building", split into words. */
    public String getDisplayName() {
        String simple = getClass().getSimpleName();
        if (simple.endsWith("Building")) simple = simple.substring(0, simple.length() - "Building".length());
        return simple.replaceAll("(?<=[a-z])(?=[A-Z])", " ");
    }

    /** The room's measured space; {@link BuildingSpace.Result#NONE} when crowding does not apply. */
    public BuildingSpace.Result getSpace() {
        return space;
    }

    /**
     * Re-discovers this building's interior tiles during {@link #rebuild}.
     *
     * <p>Walled, roofed rooms use the default {@link RoomScanner} BFS from the
     * door. Open-air enclosures (farms, ranches) override this with a fence-
     * bounded scan — they have a gate instead of a door and no ceiling at all.
     */
    protected Set<BlockPos> scanRoomBlocks(ServerLevel level, NavGraph navGraph,
                                           java.util.Collection<BlockPos> rivalEntries) {
        RoomScanner.ScanResult scan = RoomScanner.scanDetailed(level, navGraph, doorPos,
                interiorDirection, rivalEntries);
        scanCeilings = scan.ceilings();
        return scan.tiles();
    }

    /** The tile this building's door opens onto — its identity to a room scan. */
    public BlockPos entryTile() {
        return doorPos.relative(interiorDirection);
    }

    // =========================================================================
    // Abstract — type-specific logic
    // =========================================================================

    /**
     * Scan the room AABB for type-specific blocks (beds, chests, etc.).
     * Populate {@link #functionalBlocks}. The return value describes whether the
     * building has its traditional recommended feature set; registration no
     * longer depends on it.
     *
     * <p>Implementations should use {@code minX−1 … maxX+1} etc. to catch blocks
     * sitting just outside the nav-mesh but still inside the room walls.
     */
    protected abstract boolean scanFunctionalBlocks(ServerLevel level);

    // =========================================================================
    // Getters
    // =========================================================================

    public UUID       getItemFrameId()       { return itemFrameId; }
    public BlockPos   getItemFramePos()      { return itemFramePos; }
    public BlockPos   getDoorPos()           { return doorPos; }
    public Direction  getInteriorDirection() { return interiorDirection; }
    public boolean    isValid()              { return isValid && accessValid; }
    public boolean    isShapeValid()         { return isValid; }
    public boolean    isAccessValid()        { return accessValid; }
    public void       setAccessValid(boolean reachable) { accessValid = reachable; }
    public Set<BlockPos> getNavMeshBlocks()  { return navMeshBlocks; }
    public Set<BlockPos> getFunctionalBlocks(){ return functionalBlocks; }
}

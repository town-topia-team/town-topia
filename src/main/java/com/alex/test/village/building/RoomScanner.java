package com.alex.test.village.building;

import com.alex.test.DynamicVillages;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.chat.RegistrationFailure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

import javax.annotation.Nullable;
import java.util.*;

/**
 * BFS room scanner that discovers which {@link NavGraph} nodes lie inside a walled room.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>The first interior block ({@code doorBase + interiorDirection}) must be navigable
 *       AND have a solid ceiling within {@value #MAX_CEILING_DIST} blocks above it.
 *       If not, the room is considered invalid and {@code null} is returned.
 *       Ceiling heights may be negative — see {@link #NO_CEILING}.</li>
 *   <li>BFS through the {@link NavNode#neighbors} graph, starting from the navnode
 *       nearest that first interior block <em>and</em> from the entry tile of every
 *       other building sharing the space.</li>
 *   <li>A navnode joins the room when it stays on the entry floor, under a roof
 *       that does not jump across a doorway or low passage (see
 *       {@link #MIN_OPEN_HEADROOM}), and this door reaches it in strictly fewer
 *       steps than any other
 *       door does. BFS stops at nodes that fail the floor/roof check, which keeps
 *       stacked floors, balconies and covered outdoor paths out.</li>
 *   <li>Inside doors are walked through. A door that belongs to a building
 *       token — this one's or another's — is never. Whatever lies behind an
 *       inside door is dropped again if it reaches open sky (a back door onto
 *       the garden), and a tile goes to the building that reaches it through
 *       the fewest inside doors, so a bedroom with its own token is its own.</li>
 *   <li>The one way off a floor is something a player builds on purpose to go
 *       between floors: a ladder or a staircase. See {@link #scan(ServerLevel,
 *       NavGraph, BlockPos, Direction, Collection)} for the storey rules.</li>
 * </ol>
 */
public final class  RoomScanner {

    /**
     * Safety valve on the room BFS, not a size limit.
     *
     * <p>It used to be 50, and it truncated: a hall bigger than that kept the
     * fifty tiles nearest its door and silently dropped the rest, so a chest
     * against the far wall was never counted — and because the same truncated
     * bounds decide whether a later block change is worth re-scanning, it could
     * never announce itself either. A nine-by-nine storage room did not
     * validate and nothing said why.
     *
     * <p>Rooms are now effectively unlimited: four thousand floor tiles is a
     * sixty-four-block square. Reaching this number means the scan has escaped
     * into open terrain, which is a failure and is reported as one rather than
     * quietly becoming a very large building.
     */
    public static final int MAX_ROOM_NODES = 4096;

    /** Number of blocks above a floor tile to search for a solid ceiling. */
    public static final int MAX_CEILING_DIST = 30;

    /** Maximum vertical drift from the entry floor that still counts as the same room. */
    private static final int MAX_ENTRY_FLOOR_DELTA = 1;

    /**
     * Largest change in ceiling height between two neighbouring tiles of the
     * entry floor. A gable or vaulted roof rises one block per step, so it is
     * followed all the way to the ridge; a door lintel, an archway or a lower
     * porch roof is a bigger jump, and the room stops there.
     */
    private static final int MAX_ROOF_STEP = 1;

    /**
     * Headroom at which the ceiling may change freely between two tiles.
     *
     * <p>What keeps a porch out of the house is the doorway, not the roof: a
     * door's own top half reads as a ceiling one block up, and a plain
     * two-high opening as two. Between tiles that both have at least this much
     * room, a ceiling may jump by any amount, which is what lets the scan pass
     * from under a loft or gallery into the double-height hall beside it.
     * A step through anything lower is still held to {@link #MAX_ROOF_STEP}.
     */
    private static final int MIN_OPEN_HEADROOM = 3;

    /**
     * How far a new storey's floor must be from the one the climb started on,
     * and from the entry floor. One block of floor plus two of headroom is the
     * lowest real storey; anything closer is a porch step or a split level,
     * and those stay out exactly as they always have.
     */
    private static final int MIN_STOREY_RISE = 3;

    /** Longest ladder or staircase the scan will follow between two floors. */
    private static final int MAX_TRANSIT_STEPS = 32;

    /** Most floor changes along any one path from the door. */
    private static final int MAX_STOREY_CHANGES = 4;

    /**
     * How far outside the entry floor's footprint another storey may reach.
     * Allows for the usual one-block jetty on an upper floor, and stops an
     * upstairs door onto a hillside from pulling the woods into the house.
     */
    private static final int FOOTPRINT_MARGIN = 2;

    /**
     * Sentinel returned by {@link #findCeilingY} when a column has no ceiling.
     *
     * <p>Deliberately not {@code -1}: since 1.18 the world extends to y=-64, so
     * {@code -1} is a perfectly ordinary ceiling height. Using it as "not found"
     * made every room whose ceiling sits below y=0 — any underground build, and
     * every room in a superflat world — scan as if it were open sky.
     */
    static final int NO_CEILING = Integer.MIN_VALUE;

    /** Owner id for the door being scanned. */
    private static final int OURS = 0;

    /** Owner id for a tile equally near two doors, which belongs to neither. */
    private static final int CONTESTED = -1;

    private RoomScanner() {}

    /**
     * @param ceilings the ceiling height the scan read over each tile it
     *                 visited, keyed by feet position; handed on so measuring
     *                 the room's space does not read every column again
     */
    public record ScanResult(@Nullable Set<BlockPos> tiles, @Nullable RegistrationFailure failure,
                             Map<Long, Integer> ceilings) {
        public ScanResult(@Nullable Set<BlockPos> tiles, @Nullable RegistrationFailure failure) {
            this(tiles, failure, Map.of());
        }

        public boolean found() { return tiles != null && !tiles.isEmpty(); }
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Scan the room entered by stepping from {@code doorBase} in
     * {@code interiorDirection}, with no other buildings to share space with.
     *
     * @param level             the server world
     * @param navGraph          the village nav graph
     * @param doorBase          block-pos of the door's <em>lower</em> half
     * @param interiorDirection direction pointing from the door into the room
     * @return set of feetY-level {@link BlockPos} inside the room, or {@code null}
     *         if the entry point is not navigable, has no ceiling, or the scan
     *         escaped into open ground
     */
    @Nullable
    public static Set<BlockPos> scan(ServerLevel level, NavGraph navGraph,
                                     BlockPos doorBase, Direction interiorDirection) {
        return scan(level, navGraph, doorBase, interiorDirection, List.of());
    }

    /**
     * As above, but aware of the other buildings sharing this space.
     *
     * <p>Two houses in a terrace, with a doorway in the party wall, are one
     * connected floor as far as a flood fill is concerned — so each used to
     * scan both rooms, and the one with no beds in it validated on its
     * neighbour's. The village then believed it had twice the housing it had
     * built, which is what decides whether there is room for another villager.
     *
     * <p>The fix is to let the doors divide the space between them: a tile
     * belongs to whichever door reaches it in fewest steps, and a tile equally
     * near two doors belongs to neither. That is order-independent — the same
     * answer whichever building registered first — and it does the obvious
     * thing with a corridor between two rooms.
     *
     * <h3>Storeys</h3>
     * A two-storey house is one building, but its upper floor fails every check
     * that keeps the room on the entry floor, so it used to be dropped at the
     * foot of the ladder. The scan now carries a storey with every tile, and
     * may change storey only by climbing:
     * <ul>
     *   <li>Stepping onto a ladder rung, onto a stair block the current
     *       storey will not take, or up or down any step that leaves the
     *       storey's floor band (a staircase of plain blocks and slabs),
     *       starts a <em>transit</em>. Transit tiles need a
     *       roof over them — an outside ladder up to the roof has none — are
     *       followed for at most {@value #MAX_TRANSIT_STEPS} steps, and are never
     *       part of the room: nobody should pick a ladder rung to stand on.</li>
     *   <li>The first plain floor tile off a transit starts a new storey if it
     *       is at least {@value #MIN_STOREY_RISE} blocks from both the storey it
     *       came from and the entry floor, and has a roof — and, off a
     *       staircase of plain blocks, is floor rather than another step
     *       (see {@link #isFloor}). Porch steps, split
     *       levels and flat rooftops all fail that. Basements count the same way
     *       as upper floors.</li>
     *   <li>Other storeys keep the floor band but not the entry floor's roof
     *       continuity check — an attic may have beams or a chimney breast.
     *       What keeps them in the house instead is the footprint: nothing off
     *       the entry floor may lie more than {@value #FOOTPRINT_MARGIN} blocks
     *       outside that door's share of the entry floor.</li>
     *   <li>The entry floor is scanned on its own first, exactly as it always
     *       was. If that floor has no ladder or stair on it the answer stands;
     *       if following the storeys escapes into open ground, the entry floor
     *       is kept alone. Another floor can therefore add tiles, but can never
     *       cost a building its registration.</li>
     * </ul>
     *
     * @param rivalEntries the first interior tile of every OTHER building's
     *                     door; empty when nothing else is registered yet
     */
    @Nullable
    public static Set<BlockPos> scan(ServerLevel level, NavGraph navGraph,
                                     BlockPos doorBase, Direction interiorDirection,
                                     Collection<BlockPos> rivalEntries) {
        return scanDetailed(level, navGraph, doorBase, interiorDirection, rivalEntries).tiles();
    }

    /** The regular room scan with a player-facing reason when it cannot register. */
    public static ScanResult scanDetailed(ServerLevel level, NavGraph navGraph,
                                          BlockPos doorBase, Direction interiorDirection,
                                          Collection<BlockPos> rivalEntries) {
        if (navGraph == null || navGraph.isEmpty()) {
            DynamicVillages.LOGGER.info(
                    "[RoomScanner] Entry rejected: no village NavGraph; door={} interior={}",
                    doorBase.toShortString(), interiorDirection);
            return new ScanResult(null, RegistrationFailure.PATH_GRAPH_UNAVAILABLE);
        }

        // Both passes read the same columns; there is no need to read them twice.
        Map<Long, Integer> ceilings = new HashMap<>();
        Set<Long> registeredDoors = registeredDoors(level, doorBase, rivalEntries);

        Pass floorPass = new Pass(false, Map.of(), ceilings, registeredDoors);
        Set<BlockPos> entryFloor = runPass(level, navGraph, doorBase, interiorDirection,
                rivalEntries, floorPass);
        if (floorPass.overflowed) {
            DynamicVillages.LOGGER.warn(
                    "[RoomScanner] Room scan from door {} passed {} tiles without finding "
                    + "a boundary, so it has escaped into open ground. No room registered.",
                    doorBase.toShortString(), MAX_ROOM_NODES);
            return new ScanResult(null, RegistrationFailure.ROOM_TOO_LARGE);
        }
        if (entryFloor == null || entryFloor.isEmpty()) {
            return new ScanResult(null, floorPass.failure != null
                    ? floorPass.failure : RegistrationFailure.ROOM_NOT_ASSIGNED);
        }

        // No ladder or stair anywhere on the floor: there is no other storey
        // to reach, and a second pass would find exactly the same tiles.
        if (!floorPass.sawConnector) return new ScanResult(entryFloor, null, ceilings);

        Pass storeyPass = new Pass(true, floorPass.footprints, ceilings, registeredDoors);
        Set<BlockPos> allFloors = runPass(level, navGraph, doorBase, interiorDirection,
                rivalEntries, storeyPass);
        if (storeyPass.overflowed) {
            DynamicVillages.LOGGER.info(
                    "[RoomScanner] Floors above or below door {} escaped into open ground; "
                    + "registered the entry floor only.", doorBase.toShortString());
            return new ScanResult(entryFloor, null, ceilings);
        }
        if (allFloors == null || allFloors.isEmpty()) return new ScanResult(entryFloor, null, ceilings);
        return new ScanResult(allFloors, null, ceilings);
    }

    // =========================================================================
    // BFS
    // =========================================================================

    /**
     * One multi-source BFS from this door and every rival's.
     *
     * @return this door's tiles, or {@code null} when there is no room (in
     *         which case {@link Pass#overflowed} says whether it escaped)
     */
    @Nullable
    private static Set<BlockPos> runPass(ServerLevel level, NavGraph navGraph,
                                         BlockPos doorBase, Direction interiorDirection,
                                         Collection<BlockPos> rivalEntries, Pass pass) {
        // First block on the interior side of the door.
        BlockPos firstInterior = doorBase.relative(interiorDirection);

        // Must have a ceiling (confirms this is inside a room, not open air).
        // The roof is then followed tile by tile, so a far-away balcony or
        // upper floor cannot become a fake ceiling for this building.
        int entryRoofY = ceilingY(level, firstInterior, pass.ceilings);
        if (entryRoofY == NO_CEILING) {
            pass.failure = RegistrationFailure.ROOM_NO_CEILING;
            logEntryFailure(level, navGraph, doorBase, interiorDirection, firstInterior,
                    "no ceiling", entryRoofY);
            return null;
        }

        // Find the starting navnode at the actual entry floor. X/Z-only snapping
        // can bind stacked apartments to the room below.
        NavNode startNode = navGraph.nearest(firstInterior, 0, MAX_ENTRY_FLOOR_DELTA);
        if (startNode == null) {
            NavGraph.StandingCandidate candidate = NavGraph.standingCandidate(level, firstInterior);
            pass.failure = !candidate.surface() ? RegistrationFailure.ROOM_BAD_FLOOR
                    : !candidate.headroom() ? RegistrationFailure.ROOM_BLOCKED_HEADROOM
                    : !candidate.ledgeSafe() ? RegistrationFailure.ROOM_UNSAFE_LEDGE
                    : RegistrationFailure.ROOM_UNREACHABLE;
            logEntryFailure(level, navGraph, doorBase, interiorDirection, firstInterior,
                    "no entry NavNode", entryRoofY);
            return null;
        }
        Storey ourEntry = Storey.entry(startNode.feetY);

        // One search, several sources: ours and every rival door's. Each node
        // records who reached it, through how many inside doors and in how
        // many steps, so the frontier between two buildings falls out of the
        // wavefront instead of needing a second pass. Fewest doors wins before
        // fewest steps: a bedroom with its own token keeps its bedroom even
        // when the house around it could walk in through a second door.
        Map<Long, Claim> claims = new HashMap<>();
        PriorityQueue<Queued> queue = new PriorityQueue<>(
                (a, b) -> a.claim().compareRank(b.claim()));

        Claim ourStart = Claim.start(OURS, ourEntry, entryRoofY);
        claims.put(nodeKey(startNode), ourStart);
        queue.add(new Queued(startNode, ourStart));

        int rivalId = OURS;
        for (BlockPos entry : rivalEntries) {
            rivalId++;
            NavNode rivalStart = navGraph.nearest(entry, 0, MAX_ENTRY_FLOOR_DELTA);
            if (rivalStart == null) continue;
            long key = nodeKey(rivalStart);
            if (claims.containsKey(key)) continue; // same tile as ours: same door
            // A rival floods its own floor under its own roof, so an apartment
            // with its own door upstairs keeps its rooms even when a ladder
            // from this house reaches them. Farm gates and mine mouths have no
            // roof; those keep the floor being scanned, as they always did.
            int rivalRoofY = ceilingY(level, entry, pass.ceilings);
            Claim rivalClaim = rivalRoofY == NO_CEILING
                    ? Claim.start(rivalId, ourEntry, entryRoofY)
                    : Claim.start(rivalId, Storey.entry(rivalStart.feetY), rivalRoofY);
            claims.put(key, rivalClaim);
            queue.add(new Queued(rivalStart, rivalClaim));
        }

        int expanded = 0;
        while (!queue.isEmpty()) {
            Queued queued = queue.poll();
            NavNode cur = queued.node();
            Claim claim = claims.get(nodeKey(cur));
            // Superseded by a better claim, or contested since it was queued.
            if (claim != queued.claim() || claim.owner() == CONTESTED) continue;
            // Already known to be outdoors: no need to wander further out.
            if (pass.isOutdoors(claim.segment())) continue;

            if (++expanded > MAX_ROOM_NODES) {
                pass.overflowed = true;
                pass.failure = RegistrationFailure.ROOM_TOO_LARGE;
                return null;
            }

            for (NavNode nb : cur.neighbours()) {
                if (isConnector(nb) || leavesFloor(claim.storey(), cur, nb)) {
                    pass.sawConnector = true;
                }
                // Behind an inside door and able to walk out under open sky:
                // that door led outdoors, not into another room.
                if (claim.segment() != 0
                        && ceilingY(level, nb.feetPos(), pass.ceilings) == NO_CEILING) {
                    pass.leaky.set(claim.segment());
                }
                Claim step = step(level, claim, cur, nb, pass);
                if (step == null) {
                    continue; // boundary: other floor, other roof, or open sky
                }
                long key = nodeKey(nb);
                Claim existing = claims.get(key);

                if (existing == null || step.compareRank(existing) < 0) {
                    claims.put(key, step);
                    queue.add(new Queued(nb, step));
                } else if (step.compareRank(existing) == 0
                        && existing.owner() != claim.owner()
                        && existing.owner() != CONTESTED) {
                    // Equally near two doors: it belongs to neither, and nothing
                    // expands through it, so the two rooms cannot bleed together.
                    claims.put(key, existing.contested());
                }
            }
        }

        Set<BlockPos> roomBlocks = new LinkedHashSet<>();
        Map<Integer, Footprint> footprints = new HashMap<>();
        for (Map.Entry<Long, Claim> entry : claims.entrySet()) {
            Claim claim = entry.getValue();
            if (claim.owner() == CONTESTED || claim.inTransit()) continue;
            if (pass.isOutdoors(claim.segment())) continue;
            BlockPos pos = BlockPos.of(entry.getKey());
            if (claim.storey().isEntry()) {
                footprints.merge(claim.owner(), Footprint.of(pos), Footprint::union);
            }
            if (claim.owner() == OURS) roomBlocks.add(pos);
        }
        pass.footprints = footprints;

        if (roomBlocks.isEmpty()) {
            pass.failure = !pass.leaky.isEmpty()
                    ? RegistrationFailure.ROOM_OPEN_TO_OUTSIDE
                    : RegistrationFailure.ROOM_NOT_ASSIGNED;
            DynamicVillages.LOGGER.info(
                    "[RoomScanner] Entry rejected: no claimed room tiles; door={} interior={} "
                    + "startNode={} roofY={} expanded={} rivals={}",
                    doorBase.toShortString(), interiorDirection, startNode.feetPos().toShortString(),
                    entryRoofY, expanded, rivalEntries.size());
            return null;
        }
        return roomBlocks;
    }

    private static void logEntryFailure(ServerLevel level, NavGraph navGraph,
                                        BlockPos doorBase, Direction interiorDirection,
                                        BlockPos firstInterior, String reason, int roofY) {
        NavNode nearby = navGraph.nearest(firstInterior, 4, 4);
        DynamicVillages.LOGGER.info(
                "[RoomScanner] Entry rejected: {}; door={} interior={} firstTile={} "
                + "floor={} feet={} head={} roofY={} roof={} nearbyNode={} "
                + "columnHasNode={} candidate={} graphVersion={} inBounds={}",
                reason, doorBase.toShortString(), interiorDirection, firstInterior.toShortString(),
                level.getBlockState(firstInterior.below()).getBlock(),
                level.getBlockState(firstInterior).getBlock(),
                level.getBlockState(firstInterior.above()).getBlock(), roofY,
                roofY == NO_CEILING ? "none"
                        : level.getBlockState(new BlockPos(firstInterior.getX(), roofY,
                                firstInterior.getZ())).getBlock(),
                nearby == null ? "none" : nearby.feetPos().toShortString(),
                navGraph.isWalkableAtXZ(firstInterior.getX(), firstInterior.getZ()),
                NavGraph.describeStandingCandidate(level, firstInterior),
                navGraph.version(), navGraph.isInsideBounds(firstInterior));
    }

    /**
     * Where a step from a claimed tile onto {@code nb} lands, or {@code null}
     * if {@code nb} is not part of the same building.
     */
    @Nullable
    private static Claim step(ServerLevel level, Claim from, NavNode cur, NavNode nb, Pass pass) {
        // A water landing is reached by a fall the nav graph allows from any ledge
        // with deep water somewhere below the next column, rock or not. It is never
        // a place a building stands: without this, a flooded cave twenty blocks
        // down read as a basement.
        if (nb.waterDrop) return null;

        Storey storey = from.storey();
        int roofY = ceilingY(level, nb.feetPos(), pass.ceilings);

        // A door. One that belongs to a building token is that building's
        // boundary and is never crossed. Any other is an inside door between
        // rooms of this one: its own top half is no ceiling, so the roof from
        // before it is carried through, and what lies behind it is a new
        // segment that is dropped again if it turns out to be outdoors.
        if (nb.portal) {
            if (pass.registeredDoors.contains(nb.feetPos().asLong())) return null;
            Storey doorStorey = storey;
            if (Math.abs(nb.feetY - storey.floorY()) > MAX_ENTRY_FLOOR_DELTA) {
                // Only the top of a climb may open onto another storey.
                if (!pass.followStoreys || !from.inTransit() || !risesToNewStorey(storey, nb)) {
                    return null;
                }
                doorStorey = storey.next(nb.feetY);
            }
            return within(pass, nb, from.throughDoor(pass.newSegment(from.segment()), doorStorey));
        }

        // An ordinary tile of the storey we are on. When storeys are followed
        // a ladder rung never is: it is a way up, not somewhere to be.
        boolean underStairs = isStaircaseUnderside(level, nb, roofY)
                || isStaircaseUnderside(level, cur, from.roofY());
        if (!(pass.followStoreys && nb.climb)
                && storey.admits(nb, roofY, cur.feetY, from.roofY(), underStairs)) {
            // A big jump in the ceiling on the entry floor is an opening: from
            // under a loft into the hall, or through a tall arch into a covered
            // street. Like an inside door, what lies beyond it is kept only if
            // it never reaches open sky.
            if (storey.isEntry() && !underStairs && Math.abs(roofY - from.roofY()) > MAX_ROOF_STEP) {
                return within(pass, nb, from.throughOpening(
                        pass.newSegment(from.segment()), roofY));
            }
            return within(pass, nb, from.next(storey, 0, roofY));
        }
        if (!pass.followStoreys) return null;

        // The entry floor belongs to the entry storey alone. A climb down from
        // another storey must not claim its tiles first, or a ground-floor tile
        // could end up a mere step of a staircase instead of part of the room.
        if (!storey.isEntry()
                && Math.abs(nb.feetY - storey.entryFloorY()) <= MAX_ENTRY_FLOOR_DELTA) {
            return null;
        }

        // Onto, or further along, a ladder or staircase. It must be indoors.
        // A staircase built of plain blocks and slabs has no stair block in
        // it, so walking up off the floor band starts a climb just the same.
        if (isConnector(nb) || (!from.inTransit() && leavesFloor(storey, cur, nb))) {
            if (from.transitSteps() >= MAX_TRANSIT_STEPS) return null;
            if (roofY == NO_CEILING) return null;
            return within(pass, nb, from.next(storey, from.transitSteps() + 1, roofY));
        }

        if (!from.inTransit()) return null;
        if (roofY == NO_CEILING) return null;

        // Off a ladder or staircase onto a floor a whole storey away. The
        // entry floor itself is never re-entered this way: it belongs to the
        // entry storey, with its roof plane, or not at all.
        if ((isConnector(cur) || isFloor(nb)) && risesToNewStorey(storey, nb)) {
            return within(pass, nb, from.next(storey.next(nb.feetY), 0, roofY));
        }

        // A plain tile part-way up: the landing of an L- or U-shaped stair.
        // It carries the climb on rather than ending it, and like every
        // other step of a climb it is not part of the room.
        if (nb.feetY != storey.floorY() && from.transitSteps() < MAX_TRANSIT_STEPS) {
            return within(pass, nb, from.next(storey, from.transitSteps() + 1, roofY));
        }
        return null;
    }

    /**
     * Keeps everything off the entry floor over that door's share of it.
     * The entry floor itself is unaffected; it is what defines the footprint.
     */
    @Nullable
    private static Claim within(Pass pass, NavNode nb, Claim claim) {
        if (claim.storey().isEntry() && !claim.inTransit()) return claim;
        Footprint footprint = pass.footprints.get(claim.owner());
        return footprint != null && footprint.contains(nb.x, nb.z) ? claim : null;
    }

    /**
     * The doors no scan may walk through: this building's own, and every
     * rival building's — found as the door or gate beside the rival's entry
     * tile. Each is widened to the other leaf of a double door, which is the
     * same entrance and would otherwise let the scan walk straight back out.
     */
    private static Set<Long> registeredDoors(ServerLevel level, BlockPos doorBase,
                                             Collection<BlockPos> rivalEntries) {
        List<BlockPos> doors = new ArrayList<>();
        doors.add(doorBase);
        for (BlockPos entry : rivalEntries) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos side = entry.relative(dir);
                if (isDoorway(level.getBlockState(side))) doors.add(side);
            }
        }
        Set<Long> keys = new HashSet<>();
        for (BlockPos door : doors) {
            keys.add(door.asLong());
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos leaf = door.relative(dir);
                if (isDoorway(level.getBlockState(leaf))) keys.add(leaf.asLong());
            }
        }
        return keys;
    }

    private static boolean isDoorway(BlockState state) {
        return state.getBlock() instanceof DoorBlock || state.getBlock() instanceof FenceGateBlock;
    }

    /**
     * Whether the ceiling over {@code node} is the underside of a staircase:
     * a bottom-half stair block, which is what every step of a staircase is.
     *
     * <p>The floor under a staircase has a low ceiling that drops suddenly from
     * the room's, which is exactly what a doorway's lintel looks like, so the
     * roof rule stopped there and the tiles under the stairs were left out of
     * the building. A lintel is a door's top half or a solid block, and a
     * decorative arch over a doorway is built from upside-down (top-half)
     * stairs, so neither is mistaken for this.
     */
    private static boolean isStaircaseUnderside(ServerLevel level, NavNode node, int roofY) {
        if (roofY == NO_CEILING) return false;
        BlockState ceiling = level.getBlockState(new BlockPos(node.x, roofY, node.z));
        return ceiling.getBlock() instanceof StairBlock
                && ceiling.getValue(StairBlock.HALF) == Half.BOTTOM;
    }

    /** A ladder rung, or a stair block to walk up. */
    private static boolean isConnector(NavNode node) {
        return node.climb || node.surfaceBlock instanceof StairBlock;
    }

    /**
     * Whether {@code nb} is a whole storey away from both the floor the climb
     * started on and the entry floor, with storey changes to spare.
     */
    private static boolean risesToNewStorey(Storey storey, NavNode nb) {
        return storey.changes() < MAX_STOREY_CHANGES
                && Math.abs(nb.feetY - storey.floorY()) >= MIN_STOREY_RISE
                && Math.abs(nb.feetY - storey.entryFloorY()) >= MIN_STOREY_RISE;
    }

    /** A step up or down from {@code cur} that leaves the storey's floor band. */
    private static boolean leavesFloor(Storey storey, NavNode cur, NavNode nb) {
        return nb.feetY != cur.feetY
                && Math.abs(nb.feetY - storey.floorY()) > MAX_ENTRY_FLOOR_DELTA;
    }

    /**
     * Whether {@code node} is floor rather than one step of a staircase: at
     * least two of its neighbours stand at exactly its height. A step of a
     * one- or two-wide staircase built from blocks and slabs has at most one
     * — its twin beside it — so a climb that is not on stair blocks runs on
     * until it reaches real floor instead of making a storey of a step.
     */
    private static boolean isFloor(NavNode node) {
        int level = 0;
        for (NavNode other : node.neighbours()) {
            if (Math.abs(other.standY - node.standY) < 1.0E-3 && ++level >= 2) return true;
        }
        return false;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Settings and results of one BFS pass. */
    private static final class Pass {
        /** Whether ladders and stairs may lead onto other storeys. */
        final boolean followStoreys;
        /** Ceiling heights already read, shared by both passes. */
        final Map<Long, Integer> ceilings;
        /** Input: each owner's extent on its entry floor. Output: the same, as found. */
        Map<Integer, Footprint> footprints;
        /** Whether the BFS came across a ladder or stair at all. */
        boolean sawConnector;
        /** Whether the BFS ran into {@link #MAX_ROOM_NODES}. */
        boolean overflowed;
        RegistrationFailure failure;
        /** Doors that belong to a building token, ours or a rival's: never crossed. */
        final Set<Long> registeredDoors;
        /**
         * Parent of each door segment; index 0 is the side of the entrance
         * every scan starts on, with no parent.
         */
        final List<Integer> segmentParent = new ArrayList<>(List.of(-1));
        /** Segments that reach open sky: whatever lies behind that door is outdoors. */
        final BitSet leaky = new BitSet();

        Pass(boolean followStoreys, Map<Integer, Footprint> footprints,
             Map<Long, Integer> ceilings, Set<Long> registeredDoors) {
            this.followStoreys = followStoreys;
            this.footprints = footprints;
            this.ceilings = ceilings;
            this.registeredDoors = registeredDoors;
        }

        int newSegment(int parent) {
            segmentParent.add(parent);
            return segmentParent.size() - 1;
        }

        /** Whether this segment, or one it lies behind, turned out to be outdoors. */
        boolean isOutdoors(int segment) {
            for (int s = segment; s > 0; s = segmentParent.get(s)) {
                if (leaky.get(s)) return true;
            }
            return false;
        }
    }

    /**
     * The floor a tile belongs to.
     *
     * @param floorY      feet height of the floor
     * @param changes     storey changes between here and the door; 0 = entry floor
     * @param entryFloorY feet height of the entry floor
     */
    private record Storey(int floorY, int changes, int entryFloorY) {
        static Storey entry(int floorY) {
            return new Storey(floorY, 0, floorY);
        }

        boolean isEntry() {
            return changes == 0;
        }

        Storey next(int newFloorY) {
            return new Storey(newFloorY, changes + 1, entryFloorY);
        }

        /**
         * Whether a tile with ceiling {@code nodeRoofY}, reached from one at
         * {@code fromFeetY} with ceiling {@code fromRoofY}, is an ordinary tile
         * of this storey. On the entry floor the ceiling may not jump across a
         * doorway or a low passage — that is what keeps porches and covered
         * paths out — while a sloped roof, a loft or a gallery is followed.
         * Other storeys only need a roof, and are held to the footprint instead.
         * Stepping under a staircase is never a doorway, however far the ceiling
         * drops ({@code underStairs}; see {@link #isStaircaseUnderside}).
         */
        boolean admits(NavNode node, int nodeRoofY, int fromFeetY, int fromRoofY, boolean underStairs) {
            if (Math.abs(node.feetY - floorY) > MAX_ENTRY_FLOOR_DELTA) return false;
            if (nodeRoofY == NO_CEILING) return false;
            if (!isEntry()) return true;
            if (underStairs || Math.abs(nodeRoofY - fromRoofY) <= MAX_ROOF_STEP) return true;
            return nodeRoofY - node.feetY >= MIN_OPEN_HEADROOM
                    && fromRoofY - fromFeetY >= MIN_OPEN_HEADROOM;
        }
    }

    /**
     * Who reached a node first, in how many steps, on which storey, how many
     * ladder or stair steps since that storey's last ordinary tile, and the
     * ceiling height over it.
     */
    private record Claim(int owner, int distance, Storey storey, int transitSteps, int roofY,
                         int doors, int segment) {
        static Claim start(int owner, Storey storey, int roofY) {
            return new Claim(owner, 0, storey, 0, roofY, 0, 0);
        }

        boolean inTransit() {
            return transitSteps > 0;
        }

        /** One step further, behind the same doors. */
        Claim next(Storey storey, int transitSteps, int roofY) {
            return new Claim(owner, distance + 1, storey, transitSteps, roofY, doors, segment);
        }

        /**
         * One step further, through an unregistered door into a new segment.
         * The door's own top half is not a ceiling, so this side's roof is
         * carried through for the tile beyond it to be compared against.
         */
        Claim throughDoor(int newSegment, Storey doorStorey) {
            return new Claim(owner, distance + 1, doorStorey, 0, roofY, doors + 1, newSegment);
        }

        /**
         * One step further, under a much higher or lower ceiling, into a new
         * segment. Not a door: it does not count against this claim's rank.
         */
        Claim throughOpening(int newSegment, int newRoofY) {
            return new Claim(owner, distance + 1, storey, 0, newRoofY, doors, newSegment);
        }

        /** Fewer doors crossed wins; then fewer steps. */
        int compareRank(Claim other) {
            if (doors != other.doors) return Integer.compare(doors, other.doors);
            return Integer.compare(distance, other.distance);
        }

        Claim contested() {
            return new Claim(CONTESTED, distance, storey, transitSteps, roofY, doors, segment);
        }
    }

    /** A node waiting in the scan queue, with the claim it was queued under. */
    private record Queued(NavNode node, Claim claim) {}

    /** XZ extent of one door's share of its entry floor. */
    private record Footprint(int minX, int maxX, int minZ, int maxZ) {
        static Footprint of(BlockPos pos) {
            return new Footprint(pos.getX(), pos.getX(), pos.getZ(), pos.getZ());
        }

        Footprint union(Footprint other) {
            return new Footprint(Math.min(minX, other.minX), Math.max(maxX, other.maxX),
                    Math.min(minZ, other.minZ), Math.max(maxZ, other.maxZ));
        }

        boolean contains(int x, int z) {
            return x >= minX - FOOTPRINT_MARGIN && x <= maxX + FOOTPRINT_MARGIN
                    && z >= minZ - FOOTPRINT_MARGIN && z <= maxZ + FOOTPRINT_MARGIN;
        }
    }

    private static long nodeKey(NavNode node) {
        return NavNode.key(node.x, node.feetY, node.z);
    }

    /**
     * How much of a scanned room opens straight onto open ground: the share of
     * its tiles with a walkable neighbour, outside the room, under open sky.
     *
     * <p>A real room is walled in, so this is near 0. A covered porch in front
     * of a recessed door has a roof and a floor, so it scans as a room too, but
     * its edges are all grass and street. Used to tell the two sides of a door
     * apart when both scan.
     *
     * @return 0 to 1; 0 for an empty scan
     */
    public static double openEdgeShare(ServerLevel level, NavGraph navGraph, ScanResult scan) {
        Set<BlockPos> tiles = scan.tiles();
        if (tiles == null || tiles.isEmpty()) return 0.0;
        Map<Long, Integer> ceilings = new HashMap<>(scan.ceilings());
        int open = 0;
        for (BlockPos tile : tiles) {
            NavNode node = navGraph.nearest(tile, 0, 0);
            if (node == null) continue;
            for (NavNode nb : node.neighbours()) {
                if (nb.waterDrop || nb.portal || tiles.contains(nb.feetPos())) continue;
                if (ceilingY(level, nb.feetPos(), ceilings) == NO_CEILING) {
                    open++;
                    break;
                }
            }
        }
        return open / (double) tiles.size();
    }

    /** {@link #findCeilingY}, remembered for the length of one scan. */
    private static int ceilingY(ServerLevel level, BlockPos pos, Map<Long, Integer> cache) {
        return cache.computeIfAbsent(pos.asLong(), k -> findCeilingY(level, pos));
    }

    /**
     * Returns the Y position of the first solid block within
     * {@value #MAX_CEILING_DIST} blocks directly above {@code pos}, or
     * {@link #NO_CEILING} if no suitable ceiling exists.
     *
     * <p>Uses the collision-shape check rather than {@code blocksMotion()} because
     * directional blocks such as logs may report inconsistent {@code blocksMotion}
     * values in certain states in NeoForge 1.21.x.  Any block with a non-empty
     * collision shape (stone, planks, logs, etc.) counts as a ceiling; fully
     * passable blocks (air, grass, flowers, etc.) do not.
     *
     * <p>Climbable blocks are looked through. A ladder has a collision shape,
     * so every rung used to be the "ceiling" of the one below it, and a hanging
     * vine the ceiling of the tile under it — neither is a roof.
     */
    static int findCeilingY(ServerLevel level, BlockPos pos) {
        for (int dy = 1; dy <= MAX_CEILING_DIST; dy++) {
            BlockPos ceilPos = pos.above(dy);
            BlockState state = level.getBlockState(ceilPos);
            if (state.is(BlockTags.CLIMBABLE)) continue;
            // Primary: blocksMotion covers most solid blocks.
            // Fallback: non-empty collision shape covers logs and other directed blocks
            // that might report blocksMotion=false due to property caching.
            if (state.blocksMotion()
                    || (!state.isAir() && !state.getCollisionShape(level, ceilPos).isEmpty())) {
                return ceilPos.getY();
            }
        }
        return NO_CEILING;
    }
}

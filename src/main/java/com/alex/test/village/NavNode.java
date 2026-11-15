package com.alex.test.village;

import com.alex.test.village.building.StorageContainers;
import com.alex.test.village.nav.Piece;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.StemBlock;


/**
 * One walkable node in the nav-graph.
 *
 * <p>{@code x, z} are world-space block coordinates.
 * {@code surfaceY} is the Y coordinate of the top face of the surface block
 * the entity stands on; {@code feetY = surfaceY + 1} is where the entity's
 * feet sit (the first air block above the surface).
 *
 * <p>Adjacency is held as an array behind a volatile field and replaced rather
 * than edited, so the graph can be updated in place while a pathfind reads it
 * from another thread.
 */
public final class NavNode {

    public final int   x;
    public final int   z;
    public final int   surfaceY;
    public final int   feetY;       // = surfaceY + 1
    public final Block surfaceBlock;

    /**
     * The height an entity actually stands at here: the top of the surface
     * block's collision shape. Equal to {@link #feetY} on a full block, but half
     * a block lower on a bottom slab, and barely above the surface cell on a
     * carpet. The integer {@code feetY} cannot tell a bottom-slab floor from a
     * full one, which is how a step from a slab up onto a full block (one and
     * a half blocks, too high to jump) passed for an ordinary one-block step.
     */
    public final double standY;

    /**
     * True when a door or fence gate occupies this node's feet cell. Portals
     * must survive path smoothing: vanilla navigation cannot cross a closed
     * door/gate, so the route has to name the portal explicitly for the
     * door-opening logic to fire as the villager approaches it.
     */
    public final boolean portal;

    /**
     * True when this node is one rung of a ladder chain — the node's own
     * feet cell is the ladder block itself, not a floor one below it.
     * Mirrors {@link #portal}: kept as a hard waypoint through smoothing so a
     * multi-block climb never collapses into a single illegal vertical jump.
     */
    public final boolean climb;

    /** A ladder cell with a real floor beneath it can also be walked through. */
    public final boolean ladderFloor;

    /**
     * True when this node is a deliberate deep-water landing — a shortcut
     * created by {@link NavGraph#build} for a survivable drop/swim, not the
     * incidental furniture-recovery kind {@link #isAvoidedSurface()} exists
     * for. Sitting on {@code surfaceBlock instanceof LiquidBlock} would
     * normally trip {@link #isAvoidedSurface()} and make the node
     * unenterable from ordinary routing; this flag opts a genuine shortcut
     * node out of that veto while leaving it in place for anything else.
     */
    public final boolean waterDrop;

    /**
     * True when an open trapdoor stands in this node's feet or head cell. Its
     * plate blocks one side of the cell, which the graph's edges respect but a
     * straight smoothed line does not see, so smoothing never runs through one.
     */
    public final boolean openTrapdoor;

    /**
     * True when a cauldron stands beside this node at foot height: the tile a
     * farmer washes or refills from. Routing pays a little extra for it (see
     * {@code AStarPathfinder}); it stays a normal floor for everything else.
     */
    public final boolean besideCauldron;

    /**
     * Climb cost 2B (10-18): the sides of this tile where a body stepping in meets a half step
     * first, one bit a side ({@link #sideBit}). Set on stair floors only, from the stair's real
     * shape: the low front of a straight stair, the two low sides of an outer corner. The map
     * stands every stair at its full top ({@link #standY}), so without this a staircase step
     * reads as a full block to jump. Leaving by one of these sides is a half step down.
     * Nothing routes by it yet (climb cost 2C).
     */
    public final byte halfStepSides;

    /**
     * Raider plan round 2 (10-18): what stands in this node's feet cell when it is a
     * {@link #portal}: {@link #DOOR_HAND} a door a hand opens (wood, copper), {@link #DOOR_LOCKED}
     * one it cannot (iron: redstone only), {@link #GATE} a fence gate; {@link #NO_PORTAL} for any
     * other node. Read by {@link RouteRules} only; villagers' routes do not look at it.
     */
    public final byte portalKind;

    public static final byte NO_PORTAL = 0;
    public static final byte DOOR_HAND = 1;
    public static final byte DOOR_LOCKED = 2;
    public static final byte GATE = 3;

    /** The {@link #portalKind} for a feet cell holding {@code block}. */
    static byte portalKindOf(Block block) {
        if (block instanceof DoorBlock door) return door.type().canOpenByHand() ? DOOR_HAND : DOOR_LOCKED;
        return block instanceof FenceGateBlock ? GATE : NO_PORTAL;
    }

    /** The {@link #halfStepSides} bit for the side facing {@code (dx, dz)}: +x 1, -x 2, +z 4, -z 8. */
    public static int sideBit(int dx, int dz) {
        if (dx > 0) return 1;
        if (dx < 0) return 2;
        if (dz > 0) return 4;
        return dz < 0 ? 8 : 0;
    }

    /** True when the side of this tile facing {@code (dx, dz)} is a stair's low half. */
    public boolean halfStepToward(int dx, int dz) {
        return (halfStepSides & sideBit(dx, dz)) != 0;
    }

    /**
     * Adjacency, held as an array that is replaced wholesale rather than edited.
     *
     * <p>The graph used to be rebuilt from scratch after every block change,
     * which cost a fifth of a second of server thread per block a player
     * placed. It is now edited in place, so a pathfind running on a worker
     * thread can be walking this node's neighbours at the moment the server
     * thread changes them. An array swapped behind a volatile field makes that
     * safe without a lock: a reader sees either the old adjacency or the new
     * one, and both are internally consistent.
     */
    private volatile NavNode[] neighbours = EMPTY;

    /**
     * The piece this tile belongs to in the graph's cell map ({@link NavCells}):
     * the tiles of its chunk section it can reach and be reached from without
     * leaving the section. Null until the cell is grouped. The three
     * reachability questions are answered from it, so it replaces the stamps
     * the island sweep, the access flood and the labelling used to leave
     * here. Written on the server thread only, during an edit; the route
     * planner never reads it.
     */
    Piece<NavNode> piece;

    /** One int the grouper may use while it works through a cell. Server thread only. */
    int groupScratch;

    /** The piece this tile belongs to, or null if its cell has not been grouped. */
    public Piece<NavNode> piece() { return piece; }

    private static final NavNode[] EMPTY = new NavNode[0];

    NavNode(int x, int z, int surfaceY, Block surfaceBlock) {
        this(x, z, surfaceY, surfaceBlock, false);
    }

    NavNode(int x, int z, int surfaceY, Block surfaceBlock, boolean portal) {
        this(x, z, surfaceY, surfaceBlock, portal, false, false);
    }

    NavNode(int x, int z, int surfaceY, Block surfaceBlock, boolean portal, boolean climb, boolean waterDrop) {
        this(x, z, surfaceY, surfaceBlock, portal, climb, waterDrop, false);
    }

    NavNode(int x, int z, int surfaceY, Block surfaceBlock, boolean portal,
            boolean climb, boolean waterDrop, boolean ladderFloor) {
        this(x, z, surfaceY, surfaceY + 1, surfaceBlock, portal, climb, waterDrop, ladderFloor);
    }

    NavNode(int x, int z, int surfaceY, double standY, Block surfaceBlock, boolean portal,
            boolean climb, boolean waterDrop, boolean ladderFloor) {
        this(x, z, surfaceY, standY, surfaceBlock, portal, climb, waterDrop, ladderFloor, false);
    }

    NavNode(int x, int z, int surfaceY, double standY, Block surfaceBlock, boolean portal,
            boolean climb, boolean waterDrop, boolean ladderFloor, boolean openTrapdoor) {
        this(x, z, surfaceY, standY, surfaceBlock, portal, climb, waterDrop, ladderFloor, openTrapdoor, false);
    }

    NavNode(int x, int z, int surfaceY, double standY, Block surfaceBlock, boolean portal,
            boolean climb, boolean waterDrop, boolean ladderFloor, boolean openTrapdoor,
            boolean besideCauldron) {
        this(x, z, surfaceY, standY, surfaceBlock, portal, climb, waterDrop, ladderFloor, openTrapdoor,
                besideCauldron, (byte) 0);
    }

    NavNode(int x, int z, int surfaceY, double standY, Block surfaceBlock, boolean portal,
            boolean climb, boolean waterDrop, boolean ladderFloor, boolean openTrapdoor,
            boolean besideCauldron, byte halfStepSides) {
        this(x, z, surfaceY, standY, surfaceBlock, portal, climb, waterDrop, ladderFloor, openTrapdoor,
                besideCauldron, halfStepSides, portal ? DOOR_HAND : NO_PORTAL);
    }

    NavNode(int x, int z, int surfaceY, double standY, Block surfaceBlock, boolean portal,
            boolean climb, boolean waterDrop, boolean ladderFloor, boolean openTrapdoor,
            boolean besideCauldron, byte halfStepSides, byte portalKind) {
        this.portalKind = portal ? portalKind : NO_PORTAL;
        this.halfStepSides = halfStepSides;
        this.x            = x;
        this.z            = z;
        this.surfaceY     = surfaceY;
        this.feetY        = surfaceY + 1;
        this.standY       = standY;
        this.surfaceBlock = surfaceBlock;
        this.portal       = portal;
        this.climb        = climb;
        this.waterDrop    = waterDrop;
        this.ladderFloor  = ladderFloor;
        this.openTrapdoor = openTrapdoor;
        this.besideCauldron = besideCauldron;
    }

    // -------------------------------------------------------------------------
    // Key helpers
    // -------------------------------------------------------------------------

    /** 64-bit key encoding (x, feetY, z) — used as the graph's primary node key. */
    public static long key(int x, int feetY, int z) {
        return BlockPos.asLong(x, feetY, z);
    }

    /**
     * 64-bit key encoding (x, z) only — used by the XZ secondary index and by
     * {@link VillageManager} dirty-column tracking.
     */
    public static long xzKey(int x, int z) {
        return BlockPos.asLong(x, 0, z);
    }

    // -------------------------------------------------------------------------

    /**
     * True if the surface this node sits on is a transient block (sapling, crop)
     * that may change — the nav-graph re-checks these periodically.
     */
    public boolean isTransient() {
        return surfaceBlock instanceof SaplingBlock
            || surfaceBlock instanceof CropBlock
            || surfaceBlock instanceof StemBlock;
    }

    /**
     * True when this node sits on furniture/storage. These nodes exist only so
     * a villager can recover if it somehow gets on top of one; pathfinding avoids
     * entering them during ordinary routing.
     *
     * <p>{@code waterDrop} nodes are deliberately excluded from the LiquidBlock
     * case: those are the deep-water shortcut this class exists to describe,
     * not an incidental perch, so they must stay enterable by ordinary A*.
     */
    public boolean isAvoidedSurface() {
        if (waterDrop) return false;
        return surfaceBlock instanceof BedBlock
                || surfaceBlock instanceof LiquidBlock
                || surfaceBlock == Blocks.CRAFTING_TABLE
                // Vanilla and modded chests/barrels (Iron Chests, anything in the storage tag).
                || StorageContainers.isStorageBlock(surfaceBlock);
    }

    /**
     * True when ordinary routing may plan a journey through this node.
     *
     * <p>Furniture nodes are out because standing on a chest is a recovery, not
     * a route. Water drops are out for a blunter reason: nothing can use them.
     * The graph builds a one-way edge from a bank into the deep water below it,
     * up to sixty-four blocks down, and A* used to take it happily because the
     * edge is cheap and {@link #isAvoidedSurface()} deliberately exempts
     * {@link #waterDrop} nodes — but the walker underneath is vanilla ground
     * navigation, which will not step off a multi-block drop. The villager
     * therefore walks to the water's edge and stands there until the stuck
     * timer fires, which is exactly how two farmers ended up frozen on an
     * ocean shore with a ripe potato field behind them.
     *
     * <p>The nodes stay in the graph: a villager that has fallen in still needs
     * somewhere to be, and {@code VillagerModel}'s water-escape logic finds its
     * own way out without asking A*.
     */
    public boolean isRoutable() {
        return !waterDrop && !isAvoidedSurface();
    }

    /**
     * True when walking from this node onto {@code next} is a climb vanilla
     * ground navigation will make.
     *
     * <p>Edges are stored in both directions, but a drop is not always a climb
     * in reverse: stepping off a full block onto a bottom slab one lower is a
     * one-and-a-half-block fall, and back up again is a jump no mob can make.
     * Ladders and water have their own movement and are not judged here.
     */
    public boolean canStepTo(NavNode next) {
        if (climb || next.climb || waterDrop || next.waterDrop) return true;
        double rise = next.standY - standY;
        // Stuck spots C: a melon or pumpkin top is not a surface to step up
        // onto. Villagers hopped against them at the farm edge all day; walking
        // level across them, or down off one, stays allowed.
        if (WalkFeel.noProduceStep && rise > PRODUCE_STEP_MAX_RISE && next.isProduceTop()) return false;
        return rise <= NavGraph.MAX_STEP_UP + 1.0E-4;
    }

    /** A rise above a step's height onto produce is refused ({@link WalkFeel#noProduceStep}). */
    private static final double PRODUCE_STEP_MAX_RISE = 0.6;

    /** True when this node stands on a melon or pumpkin. */
    public boolean isProduceTop() {
        return surfaceBlock == Blocks.MELON || surfaceBlock == Blocks.PUMPKIN
                || surfaceBlock == Blocks.CARVED_PUMPKIN || surfaceBlock == Blocks.JACK_O_LANTERN;
    }

    // -------------------------------------------------------------------------
    // Failed steps
    // -------------------------------------------------------------------------

    /**
     * Steps from this node that a villager pressed against and could not make,
     * mapped to when that verdict lapses ({@link System#currentTimeMillis}).
     *
     * <p>The graph judges a jump from block geometry, but whether the jump
     * really lands depends on things it does not model — how much of the arc a
     * roof overhang clips, a hitbox catching an odd collision shape. A villager
     * that failed such a jump used to be re-routed straight back into it, and
     * the next wander goal behind the same counter did it all over again.
     *
     * <p>Held on the node rather than the graph so a block change nearby, which
     * rebuilds these nodes, forgets the verdict along with the old geometry.
     * Wall-clock time because A* reads it off the server thread.
     */
    private volatile java.util.Map<NavNode, Long> failedSteps;

    /**
     * How many times villagers have failed each step from this node this
     * session (stuck spots E). Forgotten with the node when the blocks there
     * change, like {@link #failedSteps}.
     */
    private volatile java.util.Map<NavNode, Integer> failedStepCounts;

    /** Counts one more failure of the step to {@code next}; the total so far. */
    public int countStepFailure(NavNode next) {
        java.util.Map<NavNode, Integer> map = failedStepCounts;
        if (map == null) {
            synchronized (this) {
                map = failedStepCounts;
                if (map == null) failedStepCounts = map = new java.util.concurrent.ConcurrentHashMap<>();
            }
        }
        return map.merge(next, 1, Integer::sum);
    }

    /** Keeps routes off the step to {@code next} for {@code durationMillis}. */
    public void markStepFailed(NavNode next, long durationMillis) {
        markStepFailedUntil(next, System.currentTimeMillis() + durationMillis);
    }

    /** Keeps routes off the step to {@code next} until the wall-clock time {@code untilMillis}. */
    public void markStepFailedUntil(NavNode next, long untilMillis) {
        java.util.Map<NavNode, Long> map = failedSteps;
        if (map == null) {
            synchronized (this) {
                map = failedSteps;
                if (map == null) failedSteps = map = new java.util.concurrent.ConcurrentHashMap<>();
            }
        }
        map.put(next, untilMillis);
    }

    /**
     * When the failed verdict on the step to {@code next} lapses, or 0 if
     * there is none still in force at {@code nowMillis}. Read-only, so two
     * reads at the same {@code nowMillis} always agree.
     */
    long failedStepUntil(NavNode next, long nowMillis) {
        java.util.Map<NavNode, Long> map = failedSteps;
        if (map == null) return 0L;
        Long until = map.get(next);
        return until != null && until > nowMillis ? until : 0L;
    }

    /** True while a villager's failed attempt at the step to {@code next} still counts. */
    public boolean hasFailedStepTo(NavNode next) {
        java.util.Map<NavNode, Long> map = failedSteps;
        if (map == null) return false;
        Long until = map.get(next);
        if (until == null) return false;
        if (until > System.currentTimeMillis()) return true;
        map.remove(next, until);
        return false;
    }

    // -------------------------------------------------------------------------
    // Adjacency
    // -------------------------------------------------------------------------

    /**
     * This node's neighbours. Safe to iterate from any thread: the array is
     * never edited, only replaced.
     */
    public NavNode[] neighbours() { return neighbours; }

    /** How many nodes this one connects to. */
    public int degree() { return neighbours.length; }

    public boolean hasNeighbour(NavNode other) {
        for (NavNode n : neighbours) if (n == other) return true;
        return false;
    }

    /** Adds an edge end, ignoring duplicates. */
    void addNeighbour(NavNode other) {
        NavNode[] current = neighbours;
        for (NavNode n : current) if (n == other) return;
        NavNode[] grown = java.util.Arrays.copyOf(current, current.length + 1);
        grown[current.length] = other;
        neighbours = grown;
    }

    /** Drops every edge end the predicate rejects. */
    void retainNeighbours(java.util.function.Predicate<NavNode> keep) {
        NavNode[] current = neighbours;
        int kept = 0;
        for (NavNode n : current) if (keep.test(n)) kept++;
        if (kept == current.length) return;
        NavNode[] shrunk = kept == 0 ? EMPTY : new NavNode[kept];
        int i = 0;
        for (NavNode n : current) if (keep.test(n)) shrunk[i++] = n;
        neighbours = shrunk;
    }

    /** The feet-level block position for movement commands. */
    public BlockPos feetPos() { return new BlockPos(x, feetY, z); }

    @Override
    public String toString() {
        return String.format("NavNode(%d,%d,%d surf=%d)", x, feetY, z, surfaceY);
    }
}

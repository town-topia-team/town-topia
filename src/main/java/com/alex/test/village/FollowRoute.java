package com.alex.test.village;

import net.minecraft.world.entity.PathfinderMob;
import com.alex.test.village.follow.OpenArea;
import com.alex.test.village.follow.RouteLine;
import com.alex.test.village.follow.RouteTile;
import com.alex.test.village.follow.Step;
import com.alex.test.village.follow.StepKind;
import com.alex.test.village.follow.StepRules;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * The Minecraft side of the route follower: turns the planner's tile path into
 * {@link RouteTile}s by reading the blocks along it, marks the tiles the walker must treat
 * specially (doors, gates, open trapdoors, ladder rungs), finds where on the route the villager
 * joins it, and answers {@link OpenArea} from the village map. The maths lives in
 * {@code village/follow/}, which has no Minecraft in it.
 *
 * <p>Every route the planner makes is walked here (the old walker is gone, 2026-10-18). A route
 * with a tile the walker cannot take (furniture, an unloaded chunk, a step the block shapes say no
 * body can take) is cut short before it: the walk goes that far, and the walker says why it
 * stopped ({@link Result#cut}). Water tiles are taken: a step into, across or out of deep water
 * gets its kind from the heights alone, as the swim-out does the swimming.
 */
final class FollowRoute {
    /** Slices per step: the body's leading edge moves one block in sixteenths. */
    static final int SLICES = 16;
    static final double SLICE = 1.0 / SLICES;
    private static final double HALF = RouteLine.HALF_WIDTH;
    private static final double EPS = 1.0E-4;

    /** Tile kinds: plain ground, or something the walker handles its own way. */
    static final byte GROUND = 0;
    static final byte DOOR = 1;
    static final byte GATE = 2;
    static final byte TRAPDOOR = 3;
    static final byte LADDER = 4;

    /** Why a route was cut short: not cut, ... */
    static final byte CUT_NONE = 0;
    /** ...a step or tile no body can take (marked failed in the map when reached)... */
    static final byte CUT_STEP = 1;
    /** ...an unloaded chunk ahead... */
    static final byte CUT_CHUNK = 2;
    /** ...too long to read in one tick (the rest is planned again on arrival)... */
    static final byte CUT_LENGTH = 3;
    /** ...or anything else (tiles not side by side). */
    static final byte CUT_OTHER = 4;

    /**
     * The most tiles read at once. A village route is a few hundred tiles at most; a longer
     * one is walked this far and the rest planned again from there, rather than read block by
     * block in one tick.
     */
    static final int MAX_TILES = 600;
    /**
     * Where the villager joins the route is looked for among this many first tiles. A route
     * starts where the villager stood when it was asked for; a running villager is a tile or
     * two further on by the time it arrives.
     */
    private static final int JOIN_TILES = 6;
    /** On the ground, standing about level with a tile: within a walking step. */
    private static final double JOIN_LEVEL = 0.6;
    /** In the air or on a ladder: this far below a tile's floor to this far above it. */
    private static final double JOIN_BELOW = 1.0;
    private static final double JOIN_ABOVE = 1.5;
    /** Not on a route tile: the nearest tile middle within this, with a clear way to it. */
    private static final double JOIN_REACH = 1.2;
    /** Half-size of the box slid from the body to that tile: the body and a little slack. */
    private static final double JOIN_SWEEP = HALF + 0.05;
    /** Box lists are read for floor height -5 to +5 around a step: drops, jumps and head room. */
    private static final int COLUMN_SPAN = 5;

    private FollowRoute() {}

    /**
     * The route ready for the walker.
     *
     * @param feet     each tile's feet cell, in route order (the walk's waypoints)
     * @param tiles    each tile with its judged step to the next
     * @param stair    true where the tile's floor is a stair
     * @param kind     {@link #GROUND}, {@link #DOOR}, {@link #GATE}, {@link #TRAPDOOR} or {@link #LADDER}
     * @param start    the tile the villager joins the route at
     * @param refused  why there is nothing to walk at all, or null
     * @param cut      why the route ends before the planner's last tile, or null when it does not
     * @param cutKind  {@link #CUT_NONE}, {@link #CUT_STEP}, {@link #CUT_CHUNK}, {@link #CUT_LENGTH} or {@link #CUT_OTHER}
     * @param cutFrom  the last tile kept (null when not cut)...
     * @param cutTo    ...and the first one cut: the step marked failed for {@link #CUT_STEP}
     * @param joinFar  the villager stands on none of the first tiles and has no clear way onto one:
     *                 it walks straight to the first tile before following the line
     */
    record Result(List<BlockPos> feet, List<RouteTile> tiles, boolean[] stair, byte[] kind, int start,
                  @Nullable String refused, @Nullable String cut, byte cutKind,
                  @Nullable NavNode cutFrom, @Nullable NavNode cutTo, boolean joinFar) {
        static Result no(String why) {
            return new Result(List.of(), List.of(), new boolean[0], new byte[0], 0, why,
                    null, CUT_NONE, null, null, false);
        }
    }

    /**
     * Reads the route's tiles and steps from the world. {@code join} finds where the villager
     * picks the route up (a new walk); a stuck catcher rewind sets its place itself.
     */
    static Result build(ServerLevel level, PathfinderMob npc, List<NavNode> nodes, boolean join, OpenArea area) {
        if (nodes.isEmpty()) return Result.no("empty route");
        int n = nodes.size();
        // Tiles walked: all of them, or up to where the route is cut short.
        int usable = n;
        String cut = null;
        byte cutKind = CUT_NONE;
        NavNode cutFrom = null, cutTo = null;
        if (n > MAX_TILES) {
            usable = MAX_TILES;
            cut = "route too long, read in parts";
            cutKind = CUT_LENGTH;
            cutFrom = nodes.get(usable - 1);
            cutTo = nodes.get(usable);
        }
        byte[] kind = new byte[n];
        long lastChunk = Long.MIN_VALUE;
        for (int i = 0; i < usable; i++) {
            NavNode node = nodes.get(i);
            long chunk = ((long) (node.x >> 4) << 32) ^ ((node.z >> 4) & 0xFFFFFFFFL);
            if (chunk != lastChunk) {
                if (!level.hasChunk(node.x >> 4, node.z >> 4)) {
                    if (i == 0) return Result.no("starts in an unloaded chunk");
                    usable = i;
                    cut = "an unloaded chunk ahead";
                    cutKind = CUT_CHUNK;
                    cutFrom = nodes.get(i - 1);
                    cutTo = node;
                    break;
                }
                lastChunk = chunk;
            }
            // A route may start on a chest or bed the villager stands on, or in water, and go on
            // over more of them (the planner allows that from such a start), but never step from
            // ordinary ground onto one: a route that does was planned before the chest was placed.
            if (i > 0 && !node.isRoutable() && nodes.get(i - 1).isRoutable()) {
                usable = i;
                cut = "furniture on the route";
                cutKind = CUT_STEP;
                cutFrom = nodes.get(i - 1);
                cutTo = node;
                break;
            }
            kind[i] = kindOf(level, node);
            if (i > 0) {
                NavNode a = nodes.get(i - 1);
                int apart = Math.abs(a.x - node.x) + Math.abs(a.z - node.z);
                boolean ladderStep = kind[i] == LADDER || kind[i - 1] == LADDER;
                if (ladderStep ? apart > 1 : apart != 1) {
                    usable = i;
                    cut = "tiles not side by side";
                    cutKind = CUT_OTHER;
                    cutFrom = a;
                    cutTo = node;
                    break;
                }
            }
        }

        CollisionContext ctx = CollisionContext.of(npc);
        double height = npc.getBbHeight();
        Map<Long, List<AABB>> columns = new HashMap<>();
        List<BlockPos> feet = new ArrayList<>(usable);
        List<RouteTile> tiles = new ArrayList<>(usable);
        boolean[] stair = new boolean[usable];
        for (int i = 0; i < usable; i++) {
            NavNode node = nodes.get(i);
            Step toNext;
            if (i + 1 >= usable) {
                toNext = new Step(StepKind.WALK, 0.0, 0.5);
            } else if (kind[i] == LADDER || kind[i + 1] == LADDER) {
                // Rungs are the ladder code's: never judged as ground, never straightened over.
                toNext = new Step(StepKind.LADDER, nodes.get(i + 1).standY - node.standY, 0.5);
            } else if (isWater(node) || isWater(nodes.get(i + 1))) {
                toNext = waterStep(node, nodes.get(i + 1));
            } else {
                toNext = step(level, ctx, columns, node, nodes.get(i + 1), height);
                RoutePriceCheck.note(node, nodes.get(i + 1), toNext.kind(),
                        !(npc instanceof com.alex.test.entity.NpcEntity));
                if (toNext.kind() == StepKind.NONE) {
                    // Walked up to this tile; the walker marks the step failed once it is there.
                    usable = i + 1;
                    cut = "a step the block shapes rule out";
                    cutKind = CUT_STEP;
                    cutFrom = node;
                    cutTo = nodes.get(i + 1);
                    toNext = new Step(StepKind.WALK, 0.0, 0.5);
                }
            }
            stair[i] = node.surfaceBlock instanceof StairBlock;
            feet.add(node.feetPos());
            tiles.add(new RouteTile(node.x, node.z, node.standY, false, toNext));
        }
        if (usable < n) {
            kind = Arrays.copyOf(kind, usable);
            stair = Arrays.copyOf(stair, usable);
        }
        n = usable;
        // The line passes through the middle of stairs, doors, gates and open trapdoors, and of
        // the tiles either side of a door, gate or trapdoor, so it meets them square on.
        for (int i = 0; i < n; i++) {
            boolean anchor = stair[i] || isNarrow(kind[i])
                    || (kind[i] != LADDER && ((i > 0 && isNarrow(kind[i - 1])) || (i + 1 < n && isNarrow(kind[i + 1]))));
            if (anchor) {
                RouteTile t = tiles.get(i);
                tiles.set(i, new RouteTile(t.x(), t.z(), t.stand(), true, t.toNext()));
            }
        }
        List<RouteTile> fixed = List.copyOf(tiles);
        int start = 0;
        boolean joinFar = false;
        if (join) {
            start = joinTile(npc, fixed, kind, area);
            if (start < 0) {
                start = 0;
                joinFar = true;
            }
        }
        return new Result(List.copyOf(feet), fixed, stair, kind, start, null,
                cut, cutKind, cutFrom, cutTo, joinFar);
    }

    /** A door, gate or open trapdoor: a tile walked through its middle, lined up first. */
    static boolean isNarrow(byte kind) {
        return kind == DOOR || kind == GATE || kind == TRAPDOOR;
    }

    /** A deep-water tile: swum, not walked (a water landing, or water as the floor). */
    private static boolean isWater(NavNode node) {
        return node.waterDrop || node.surfaceBlock instanceof LiquidBlock;
    }

    /**
     * A step into, across or out of deep water, from the heights alone: water has no collision
     * shape, so the block shapes would find no floor there. In: a drop when it falls more than a
     * walking step, else a walk. Across: a swim, walked. Out: a jump when it rises more than a
     * walking step (held in water, it lifts the body onto the bank), else a walk.
     */
    private static Step waterStep(NavNode from, NavNode to) {
        double rise = to.standY - from.standY;
        boolean fromWater = isWater(from), toWater = isWater(to);
        if (!fromWater && toWater) {
            return new Step(rise < -StepRules.STEP_UP ? StepKind.DROP : StepKind.WALK, rise, 0.5);
        }
        if (fromWater && !toWater) {
            return new Step(rise > StepRules.STEP_UP ? StepKind.JUMP : StepKind.WALK, rise, 0.5);
        }
        return new Step(StepKind.WALK, rise, 0.5);
    }

    /** What the walker has to do on this tile, from the blocks there now. */
    private static byte kindOf(ServerLevel level, NavNode node) {
        if (node.climb) return LADDER;
        BlockPos feet = node.feetPos();
        BlockState state = level.getBlockState(feet);
        if (state.getBlock() instanceof DoorBlock) return DOOR;
        if (state.getBlock() instanceof FenceGateBlock) return GATE;
        // One hung at head height over an open feet cell is opened and walked through the same way.
        BlockState head = level.getBlockState(feet.above());
        if (head.getBlock() instanceof DoorBlock) return DOOR;
        if (head.getBlock() instanceof FenceGateBlock) return GATE;
        if (node.openTrapdoor || isOpenTrapdoor(state) || isOpenTrapdoor(head)) {
            return TRAPDOOR;
        }
        return GROUND;
    }

    static boolean isOpenTrapdoor(BlockState state) {
        return state.getBlock() instanceof TrapDoorBlock && state.getValue(TrapDoorBlock.OPEN);
    }

    /**
     * The tile the villager joins the route at, or -1. First a tile whose column holds the
     * body's middle at about its height; failing that the nearest tile middle a short, clear
     * walk away. Never a straight line through a wall: the way to that tile is checked with a
     * box as wide as the body, over the villager's own cell, route tiles and open ground.
     */
    private static int joinTile(PathfinderMob npc, List<RouteTile> tiles, byte[] kind, OpenArea area) {
        double x = npc.getX(), y = npc.getY(), z = npc.getZ();
        int bx = Mth.floor(x), bz = Mth.floor(z);
        boolean ground = npc.onGround() && !npc.isInWaterOrBubble();
        int look = Math.min(JOIN_TILES, tiles.size());
        int best = -1;
        double bestDy = Double.POSITIVE_INFINITY;
        for (int i = 0; i < look; i++) {
            RouteTile t = tiles.get(i);
            if (t.x() != bx || t.z() != bz) continue;
            double dy = y - t.stand();
            boolean fits = ground && kind[i] != LADDER
                    ? Math.abs(dy) <= JOIN_LEVEL
                    : dy >= -JOIN_BELOW && dy <= JOIN_ABOVE;
            // Ties go to the later tile: a route doubling back over a cell is joined going on.
            if (fits && Math.abs(dy) <= bestDy) {
                best = i;
                bestDy = Math.abs(dy);
            }
        }
        if (best >= 0) return best;

        double bestDist = Double.POSITIVE_INFINITY;
        for (int i = 0; i < look; i++) {
            RouteTile t = tiles.get(i);
            if (kind[i] == LADDER) continue;
            double dist = Math.hypot(t.centreX() - x, t.centreZ() - z);
            if (dist > JOIN_REACH || dist >= bestDist) continue;
            if (Math.abs(y - t.stand()) > JOIN_ABOVE) continue;
            final int upTo = Math.min(i + 1, tiles.size() - 1);
            final double stand = t.stand();
            boolean clear = RouteLine.sweepClear(x, z, t.centreX(), t.centreZ(), JOIN_SWEEP, (cx, cz) -> {
                if (cx == bx && cz == bz) return true;
                for (int k = 0; k <= upTo; k++) {
                    RouteTile r = tiles.get(k);
                    if (r.x() == cx && r.z() == cz) return true;
                }
                return area.open(cx, cz, stand);
            });
            if (clear) {
                best = i;
                bestDist = dist;
            }
        }
        return best;
    }

    /**
     * Judges the step from {@code from} to the side-by-side {@code to} from the collision shapes
     * of both columns, across the body's width, the way {@link StepRules#classify} expects.
     * Doors and gates count as open: the door code opens them before the body gets there.
     */
    static Step step(ServerLevel level, CollisionContext ctx, Map<Long, List<AABB>> columns,
                     NavNode from, NavNode to, double height) {
        int dx = to.x - from.x, dz = to.z - from.z;
        double fromStand = from.standY;
        int base = Mth.floor(fromStand);
        List<AABB> fromBoxes = column(level, ctx, columns, from.x, from.z, base);
        List<AABB> toBoxes = column(level, ctx, columns, to.x, to.z, base);

        double cx = from.x + 0.5, cz = from.z + 0.5;
        double reach = fromStand + StepRules.JUMP_UP + EPS;
        double[] tops = new double[SLICES];
        double[] ceilings = new double[SLICES];
        for (int i = 0; i < SLICES; i++) {
            double u0 = HALF + i * SLICE, u1 = u0 + SLICE;
            double minX, maxX, minZ, maxZ;
            if (dx != 0) {
                minX = dx > 0 ? cx + u0 : cx - u1;
                maxX = dx > 0 ? cx + u1 : cx - u0;
                minZ = cz - HALF;
                maxZ = cz + HALF;
            } else {
                minZ = dz > 0 ? cz + u0 : cz - u1;
                maxZ = dz > 0 ? cz + u1 : cz - u0;
                minX = cx - HALF;
                maxX = cx + HALF;
            }
            // Floor: the highest top the body could stand on here. Anything reaching higher is
            // a wall or a ceiling, and counts below as the room left above that floor.
            double floor = Double.NaN;
            floor = highestFloor(fromBoxes, minX, maxX, minZ, maxZ, reach, floor);
            floor = highestFloor(toBoxes, minX, maxX, minZ, maxZ, reach, floor);
            double ceiling = Double.POSITIVE_INFINITY;
            if (!Double.isNaN(floor)) {
                ceiling = lowestCeiling(fromBoxes, minX, maxX, minZ, maxZ, floor, ceiling);
                ceiling = lowestCeiling(toBoxes, minX, maxX, minZ, maxZ, floor, ceiling);
            }
            tops[i] = floor;
            ceilings[i] = ceiling;
        }
        return StepRules.classify(fromStand, tops, ceilings, SLICE, 2.0 * HALF, height);
    }

    private static double highestFloor(List<AABB> boxes, double minX, double maxX, double minZ, double maxZ,
                                       double reach, double floor) {
        for (int k = 0, size = boxes.size(); k < size; k++) {
            AABB b = boxes.get(k);
            if (overlaps(b, minX, maxX, minZ, maxZ) && b.maxY <= reach && (Double.isNaN(floor) || b.maxY > floor)) {
                floor = b.maxY;
            }
        }
        return floor;
    }

    private static double lowestCeiling(List<AABB> boxes, double minX, double maxX, double minZ, double maxZ,
                                        double floor, double ceiling) {
        for (int k = 0, size = boxes.size(); k < size; k++) {
            AABB b = boxes.get(k);
            if (overlaps(b, minX, maxX, minZ, maxZ) && b.maxY > floor + EPS) {
                ceiling = Math.min(ceiling, Math.max(b.minY, floor));
            }
        }
        return ceiling;
    }

    /**
     * The collision boxes of one column from {@code base - COLUMN_SPAN} to
     * {@code base + COLUMN_SPAN}, read once per build: each tile's column serves the step into
     * it and the step out of it.
     */
    private static List<AABB> column(ServerLevel level, CollisionContext ctx, Map<Long, List<AABB>> columns,
                                     int x, int z, int base) {
        long key = BlockPos.asLong(x, base, z);
        List<AABB> known = columns.get(key);
        if (known != null) return known;
        List<AABB> out = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = base - COLUMN_SPAN; y <= base + COLUMN_SPAN; y++) {
            pos.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof FenceGateBlock) continue;
            VoxelShape shape = state.getCollisionShape(level, pos, ctx);
            if (shape.isEmpty()) continue;
            for (AABB b : shape.toAabbs()) out.add(b.move(x, y, z));
        }
        columns.put(key, out);
        return out;
    }

    private static boolean overlaps(AABB b, double minX, double maxX, double minZ, double maxZ) {
        return b.maxX > minX + EPS && b.minX < maxX - EPS && b.maxZ > minZ + EPS && b.minZ < maxZ - EPS;
    }

    /**
     * Open ground for straightening the line, from the map: a routable tile with its floor within
     * {@link RouteLine#FLAT} of {@code nearStand} that is no stair, door, ladder, trapdoor, water
     * or cell the walk was told to keep out of. {@code strict} (a step-by-step re-plan after a
     * stuck) answers no everywhere, so the line keeps to the route's own tiles. Answers are kept
     * for the walk, since the box test asks about the same columns often.
     */
    static OpenArea area(NavGraph graph, Set<Long> excluded, boolean strict) {
        if (strict) return (x, z, nearStand) -> false;
        Map<AreaKey, Boolean> seen = new HashMap<>();
        return (x, z, nearStand) -> {
            AreaKey key = new AreaKey(x, z, (int) Math.round(nearStand * 16.0));
            Boolean known = seen.get(key);
            if (known != null) return known;
            boolean open = isOpen(graph, excluded, x, z, nearStand);
            seen.put(key, open);
            return open;
        };
    }

    private record AreaKey(int x, int z, int stand16) {}

    /** How long a column a walker was pressed against stays off the line (route follower step 3). */
    private static final long BUMP_MILLIS = 5L * 60L * 1000L;
    /** Per map: columns (x, z packed) a walker was blocked by though the map called them open, until when. */
    private static final Map<NavGraph, Map<Long, Long>> BUMPED = new WeakHashMap<>();

    /**
     * A walker was pressed against a solid block in column {@code x, z} beside its route, where
     * the map has an open tile: straightened lines keep off that column for {@link #BUMP_MILLIS}
     * (the route's own tiles are still walked). Server thread only.
     */
    static void noteBump(NavGraph graph, int x, int z) {
        Map<Long, Long> columns = BUMPED.computeIfAbsent(graph, g -> new HashMap<>());
        long now = System.currentTimeMillis();
        if (columns.size() > 256) columns.values().removeIf(until -> until < now);
        columns.put(columnKey(x, z), now + BUMP_MILLIS);
    }

    private static boolean isBumped(NavGraph graph, int x, int z) {
        Map<Long, Long> columns = BUMPED.get(graph);
        if (columns == null || columns.isEmpty()) return false;
        Long until = columns.get(columnKey(x, z));
        if (until == null) return false;
        if (until >= System.currentTimeMillis()) return true;
        columns.remove(columnKey(x, z));
        return false;
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static boolean isOpen(NavGraph graph, Set<Long> excluded, int x, int z, double nearStand) {
        if (isBumped(graph, x, z)) return false;
        int lo = Mth.floor(nearStand - RouteLine.FLAT);
        int hi = Mth.floor(nearStand + RouteLine.FLAT) + 1;
        for (int feetY = lo; feetY <= hi; feetY++) {
            NavNode node = graph.nodeAt(x, feetY, z);
            if (node == null || Math.abs(node.standY - nearStand) > RouteLine.FLAT) continue;
            return node.isRoutable() && !node.portal && !node.climb && !node.ladderFloor
                    && !node.openTrapdoor && !node.waterDrop
                    && !(node.surfaceBlock instanceof StairBlock)
                    && !(node.surfaceBlock instanceof LiquidBlock)
                    && (excluded.isEmpty() || !excluded.contains(NavNode.key(node.x, node.feetY, node.z)));
        }
        return false;
    }
}

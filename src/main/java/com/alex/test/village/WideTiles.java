package com.alex.test.village;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Raider plan round 5 (10-19): the map tiles a big body (the Warlord, 1.4 wide and 3 tall; a
 * ravager, 1.95 wide) may use around a spot. A tile counts when it lies in a 2 by 2 square of map
 * tiles where the body has room: the four floors at most a block apart (it stands on the highest
 * and hangs over the rest) and {@link #BODY_HEIGHT} clear above the highest floor over all four.
 * Doors, gates, ladders, open trapdoors, water and furniture tops never count: big bodies open and
 * climb nothing.
 *
 * <p>Made on the server thread (it reads the blocks above the tiles) a slice at a time, one
 * {@link #advance} a tick, so a big village never costs a single tick much. Once done,
 * {@link #rules} is immutable and a route search on a worker thread may read it. The map's own
 * tiles are not touched.
 */
public final class WideTiles {

    /** A big body needs this much room above the floor it stands on (the Warlord is 3 tall). */
    public static final double BODY_HEIGHT = 3.0;
    /** The feet cell and the four above it are looked at: a floor a block up beside it needs five. */
    private static final int CELLS_UP = 5;

    private final NavGraph graph;
    private final BlockPos centre;
    private final long version;
    private final List<NavNode> nodes = new ArrayList<>();
    /** Per tile: the first block up from its feet cell that is not clear (its room's ceiling). */
    private final Map<NavNode, Integer> ceiling = new IdentityHashMap<>();
    private final LongOpenHashSet tiles = new LongOpenHashSet();
    private boolean squares;
    private int next;
    private long nanos;
    private int slices;
    @Nullable private RouteRules rules;

    private WideTiles(NavGraph graph, BlockPos centre) {
        this.graph = graph;
        this.centre = centre.immutable();
        this.version = graph.version();
    }

    /** Starts the tiles within {@code xzRadius} across and {@code yRadius} up or down of {@code centre}. */
    public static WideTiles start(NavGraph graph, BlockPos centre, int xzRadius, int yRadius) {
        long started = System.nanoTime();
        WideTiles wide = new WideTiles(graph, centre);
        // One block more: a square at the edge needs its far tiles.
        graph.forEachNodeInBox(centre, xzRadius + 1, yRadius + 2, node -> {
            if (usable(node)) wide.nodes.add(node);
        });
        wide.nanos = System.nanoTime() - started;
        wide.slices = 1;
        return wide;
    }

    /** Works through up to {@code budget} tiles; true once done ({@link #rules} is then ready). */
    public boolean advance(Level level, int budget) {
        if (rules != null) return true;
        long started = System.nanoTime();
        int end = Math.min(nodes.size(), next + budget);
        if (!squares) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (; next < end; next++) {
                NavNode node = nodes.get(next);
                ceiling.put(node, ceiling(level, node, pos));
            }
            if (next >= nodes.size()) {
                squares = true;
                next = 0;
            }
        } else {
            for (; next < end; next++) addSquare(nodes.get(next));
            if (next >= nodes.size()) rules = RouteRules.wide(LongSets.unmodifiable(tiles));
        }
        nanos += System.nanoTime() - started;
        slices++;
        return rules != null;
    }

    @Nullable public RouteRules rules() { return rules; }
    public NavGraph graph() { return graph; }
    public BlockPos centre() { return centre; }
    /** The map's version when this was started. */
    public long version() { return version; }
    public int tileCount() { return tiles.size(); }
    /** Server time spent so far, in milliseconds, and over how many ticks. */
    public double millis() { return nanos / 1.0e6; }
    public int slices() { return slices; }

    /** A tile a big body may stand on at all. */
    private static boolean usable(NavNode node) {
        return node.isRoutable() && !node.portal && !node.climb && !node.openTrapdoor;
    }

    /** The first cell up from the feet that is not clear; the map already keeps the feet and head cells clear. */
    private static int ceiling(Level level, NavNode node, BlockPos.MutableBlockPos pos) {
        int y = node.feetY + 2;
        for (; y < node.feetY + CELLS_UP; y++) {
            pos.set(node.x, y, node.z);
            // A chunk not loaded is never read (that would load it): no room there.
            if (!level.isLoaded(pos)) break;
            BlockState state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty() || !state.getCollisionShape(level, pos).isEmpty()) break;
        }
        return y;
    }

    /** The square with {@code corner} at its low x and z: its four tiles count when the body fits on it. */
    private void addSquare(NavNode corner) {
        NavNode east = floorNear(corner.x + 1, corner.feetY, corner.z);
        if (east == null) return;
        NavNode south = floorNear(corner.x, corner.feetY, corner.z + 1);
        if (south == null) return;
        NavNode both = floorNear(corner.x + 1, corner.feetY, corner.z + 1);
        if (both == null) return;
        double low = Math.min(Math.min(corner.standY, east.standY), Math.min(south.standY, both.standY));
        double high = Math.max(Math.max(corner.standY, east.standY), Math.max(south.standY, both.standY));
        if (high - low > 1.0 + 1.0E-4) return;
        int roof = Math.min(Math.min(ceiling.get(corner), ceiling.get(east)), Math.min(ceiling.get(south), ceiling.get(both)));
        if (roof < high + BODY_HEIGHT - 1.0E-4) return;
        tiles.add(NavNode.key(corner.x, corner.feetY, corner.z));
        tiles.add(NavNode.key(east.x, east.feetY, east.z));
        tiles.add(NavNode.key(south.x, south.feetY, south.z));
        tiles.add(NavNode.key(both.x, both.feetY, both.z));
    }

    /** The usable tile in column {@code x, z} on the floor of {@code feetY}, a block up or down. */
    @Nullable
    private NavNode floorNear(int x, int feetY, int z) {
        for (int dy = 0; dy <= 2; dy++) {
            int y = feetY + (dy == 0 ? 0 : dy == 1 ? 1 : -1);
            NavNode node = graph.nodeAt(x, y, z);
            if (node != null && ceiling.containsKey(node)) return node;
        }
        return null;
    }
}

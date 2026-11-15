package com.alex.test.village;

import com.alex.test.village.building.Building;
import com.alex.test.village.building.RanchBuilding;
import net.minecraft.core.BlockPos;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.Direction;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

/**
 * Which map tiles a body's route may use (round 2 of docs/plans/2026-10-18-raider-map-walking.md).
 * Villagers ({@link #VILLAGER}) may use every tile, as before; the route search does not even ask.
 * Raiders keep to what a raider can get through:
 * <ul>
 *   <li>one that opens doors ({@link #raider} with {@code opensDoors}, the illagers): doors a hand
 *   opens and fence gates, but no ranch gate (the village's animals would walk out), no iron door;</li>
 *   <li>one that opens nothing (the dead, the ravager, the Warlord): every door and gate is a wall;</li>
 *   <li>neither climbs ladders or squeezes past an open trapdoor.</li>
 * </ul>
 * Immutable once made, so a route search on a worker thread may read it. Built on the server
 * thread (the ranch gates come from the village's buildings). Big bodies (round 5, the Warlord and the
 * ravager) also keep to the tiles {@link WideTiles} found room for: no door, gate, ladder or trapdoor.
 */
public final class RouteRules {

    /** Every tile, as villagers have always routed. */
    public static final RouteRules VILLAGER = new RouteRules(true, true, true, Set.of(), null);

    private final boolean opensDoors;
    private final boolean climbs;
    private final boolean passesTrapdoors;
    /** Feet-cell keys ({@link NavNode#key}) of the ranch gates no raider may open. */
    private final Set<Long> closedGates;
    /** Round 5: feet-cell keys of the only tiles a big body may use; null for every other body. */
    @Nullable private final LongSet wideTiles;

    private RouteRules(boolean opensDoors, boolean climbs, boolean passesTrapdoors, Set<Long> closedGates,
                       @Nullable LongSet wideTiles) {
        this.opensDoors = opensDoors;
        this.climbs = climbs;
        this.passesTrapdoors = passesTrapdoors;
        this.closedGates = closedGates;
        this.wideTiles = wideTiles;
    }

    /**
     * A raider's rules on {@code village}'s map. Server thread only: reads the ranches. Make a
     * new one when the ranches may have changed (a raid's start is often enough).
     */
    public static RouteRules raider(VillageManager village, NavGraph graph, boolean opensDoors) {
        return new RouteRules(opensDoors, false, false, opensDoors ? ranchGates(village, graph) : Set.of(), null);
    }

    /**
     * Round 5 (10-19): a big body's rules, the tiles {@code tiles} only (made by {@link WideTiles}).
     * It opens nothing, climbs nothing and passes no open trapdoor.
     */
    static RouteRules wide(LongSet tiles) {
        return new RouteRules(false, false, false, Set.of(), tiles);
    }

    /** True for {@link #VILLAGER}: the route search skips the check altogether. */
    public boolean allowsEverything() {
        return this == VILLAGER;
    }

    /** True when the body may climb ladders (the route search's second, ladder pass). */
    public boolean climbs() {
        return climbs;
    }

    /** True when a route under these rules may step onto {@code node}. */
    public boolean mayEnter(NavNode node) {
        if (wideTiles != null && !wideTiles.contains(NavNode.key(node.x, node.feetY, node.z))) return false;
        if (node.climb && !climbs) return false;
        if (node.openTrapdoor && !passesTrapdoors) return false;
        if (!node.portal) return true;
        if (!opensDoors) return false;
        return switch (node.portalKind) {
            case NavNode.DOOR_HAND -> true;
            case NavNode.GATE -> !closedGates.contains(NavNode.key(node.x, node.feetY, node.z));
            default -> false;
        };
    }

    /**
     * Every fence gate of every ranch: its own gate, and any other gate in its fence (a gate tile
     * beside one of the pen's floor tiles, a block up or down for sloped ground). Stacked gates
     * are one gateway, kept by its bottom gate, which is the map's tile.
     */
    private static Set<Long> ranchGates(VillageManager village, NavGraph graph) {
        Set<Long> gates = new HashSet<>();
        for (Building building : village.getBuildings()) {
            if (!(building instanceof RanchBuilding ranch)) continue;
            addGate(graph, ranch.getGatePos(), gates);
            for (BlockPos tile : ranch.getNavMeshBlocks()) {
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    BlockPos beside = tile.relative(side);
                    for (int dy = -1; dy <= 1; dy++) addGate(graph, beside.above(dy), gates);
                }
            }
        }
        return Set.copyOf(gates);
    }

    private static void addGate(NavGraph graph, BlockPos feet, Set<Long> gates) {
        NavNode node = graph.nodeAt(feet.getX(), feet.getY(), feet.getZ());
        if (node != null && node.portalKind == NavNode.GATE) gates.add(NavNode.key(node.x, node.feetY, node.z));
    }
}

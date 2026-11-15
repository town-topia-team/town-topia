package com.alex.test.village.villagers.farmer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Game-independent farm geometry used by FarmerManager and plain JUnit tests. */
public final class FarmerPlanning {

    public record Point(int x, int y, int z) {}

    /** One valid planted crop observed in the 4x4 sample around a target. */
    public record CropSample(String cropId, int offsetX, int offsetZ) {}

    private FarmerPlanning() {}

    public static UUID stableFarmId(UUID villageId, long anchorKey) {
        String key = villageId + ":farm:" + anchorKey;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean withinVanillaHydration(Point soil, Point water) {
        return Math.abs(soil.x() - water.x()) <= 4
                && Math.abs(soil.z() - water.z()) <= 4
                && water.y() >= soil.y() && water.y() <= soil.y() + 1;
    }

    /**
     * One or two bare farmland blocks are too easy to create accidentally and
     * used to let a cane patch bootstrap itself forever. A planted crop or a
     * small recognisable tilled patch is required before expansion begins.
     */
    public static boolean isRecognizableCropField(
            boolean hasPlantedCropAnchor, int farmlandAnchors) {
        return hasPlantedCropAnchor || farmlandAnchors >= 3;
    }

    public static boolean canExpandHydratedField(
            boolean hasPlantedCropAnchor, int farmlandAnchors, int usableSoilTiles) {
        return usableSoilTiles >= 6
                && isRecognizableCropField(hasPlantedCropAnchor, farmlandAnchors);
    }

    /**
     * Resolves a deterministic weighted majority from a 4x4 local sample.
     * Nearer crops carry more weight. A previous choice wins an exact tie;
     * otherwise the stable crop id order does. No samples means there is no
     * established crop and the caller may use its normal fallback policy.
     */
    public static Optional<String> weightedCropChoice(
            Collection<CropSample> samples, String previousCropId) {
        Map<String, Integer> weights = new HashMap<>();
        for (CropSample sample : samples) {
            if (sample == null || sample.cropId() == null) continue;
            if (sample.offsetX() < -2 || sample.offsetX() > 1
                    || sample.offsetZ() < -2 || sample.offsetZ() > 1) continue;
            int distance = Math.abs(sample.offsetX()) + Math.abs(sample.offsetZ());
            weights.merge(sample.cropId(), Math.max(1, 5 - distance), Integer::sum);
        }
        if (weights.isEmpty()) return Optional.empty();

        int bestWeight = weights.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (previousCropId != null && weights.getOrDefault(previousCropId, 0) == bestWeight) {
            return Optional.of(previousCropId);
        }
        return weights.entrySet().stream()
                .filter(entry -> entry.getValue() == bestWeight)
                .map(Map.Entry::getKey)
                .sorted()
                .findFirst();
    }

    /**
     * Groups nearby planting tiles, bridging the single water/air column in a
     * normal irrigated row and allowing a one-level terrace step. A wider
     * horizontal gap starts a new site.
     */
    public static List<Set<Point>> connectedComponents(Collection<Point> positions) {
        Map<Point, Point> remaining = new HashMap<>();
        for (Point position : positions) remaining.put(position, position);
        List<Set<Point>> components = new ArrayList<>();
        while (!remaining.isEmpty()) {
            Point first = remaining.keySet().iterator().next();
            remaining.remove(first);
            ArrayDeque<Point> queue = new ArrayDeque<>();
            Set<Point> component = new LinkedHashSet<>();
            queue.add(first);
            while (!queue.isEmpty()) {
                Point current = queue.removeFirst();
                component.add(current);
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            Point neighbour = new Point(
                                    current.x() + dx, current.y() + dy, current.z() + dz);
                            if (remaining.remove(neighbour) != null) queue.addLast(neighbour);
                        }
                    }
                }
            }
            components.add(component);
        }
        return components;
    }

    /**
     * Splits farmland into physically touching plots using strict, one-tile
     * adjacency - unlike {@link #connectedComponents}, which deliberately
     * bridges small gaps so one hydrated site is discovered as a whole. Two
     * rows separated by so much as a path or an irrigation channel come back
     * as different plots here, even when both still sit inside the same
     * farm site, so crop inference never blends one plot's crop into another.
     */
    public static List<Set<Point>> tightlyConnectedComponents(Collection<Point> positions) {
        Map<Point, Point> remaining = new HashMap<>();
        for (Point position : positions) remaining.put(position, position);
        List<Set<Point>> components = new ArrayList<>();
        while (!remaining.isEmpty()) {
            Point first = remaining.keySet().iterator().next();
            remaining.remove(first);
            ArrayDeque<Point> queue = new ArrayDeque<>();
            Set<Point> component = new LinkedHashSet<>();
            queue.add(first);
            while (!queue.isEmpty()) {
                Point current = queue.removeFirst();
                component.add(current);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        Point neighbour = new Point(current.x() + dx, current.y(), current.z() + dz);
                        if (remaining.remove(neighbour) != null) queue.addLast(neighbour);
                    }
                }
            }
            components.add(component);
        }
        return components;
    }

    /**
     * Finds the components that each water source can hydrate. The returned
     * set at each component index contains indices into {@code waterSources},
     * in ascending order.
     *
     * <p>A tile is hydrated by water up to 4 blocks away horizontally, at the
     * tile's own height or one block below it (vanilla: water at the farmland's
     * Y or Y+1). The loop walks the tiles and looks up water, not the other way
     * round: a village beside an ocean has 100k+ water sources but only a few
     * thousand farm tiles.
     */
    public static List<Set<Integer>> waterComponentIndices(
            List<Set<Point>> components, List<Point> waterSources) {
        Map<Point, Integer> waterAt = new HashMap<>(Math.max(16, waterSources.size() * 2));
        Map<Integer, List<Integer>> duplicateWater = new HashMap<>();
        for (int waterIndex = 0; waterIndex < waterSources.size(); waterIndex++) {
            Integer first = waterAt.putIfAbsent(waterSources.get(waterIndex), waterIndex);
            if (first != null) duplicateWater.computeIfAbsent(first, ignored -> new ArrayList<>()).add(waterIndex);
        }

        List<Set<Integer>> result = new ArrayList<>(components.size());
        for (Set<Point> component : components) {
            Set<Integer> hydrating = new TreeSet<>();
            if (!waterAt.isEmpty()) {
                for (Point tile : component) {
                    for (int dx = -4; dx <= 4; dx++) {
                        for (int dz = -4; dz <= 4; dz++) {
                            addWater(waterAt, duplicateWater, hydrating,
                                    new Point(tile.x() + dx, tile.y(), tile.z() + dz));
                            addWater(waterAt, duplicateWater, hydrating,
                                    new Point(tile.x() + dx, tile.y() - 1, tile.z() + dz));
                        }
                    }
                }
            }
            result.add(hydrating);
        }
        return result;
    }

    private static void addWater(Map<Point, Integer> waterAt, Map<Integer, List<Integer>> duplicateWater,
                                 Set<Integer> hydrating, Point point) {
        Integer waterIndex = waterAt.get(point);
        if (waterIndex == null) return;
        hydrating.add(waterIndex);
        List<Integer> duplicates = duplicateWater.get(waterIndex);
        if (duplicates != null) hydrating.addAll(duplicates);
    }

    /**
     * Finds components within the existing horizontal two-block fruit rule.
     * Y is deliberately ignored so terraces retain the old behavior.
     */
    public static List<Set<Integer>> fruitComponentIndices(
            List<Set<Point>> components, List<Point> fruits) {
        Map<Long, Set<Integer>> componentsByColumn = new HashMap<>();
        List<Set<Integer>> result = emptyAssociationSets(components.size());
        for (int componentIndex = 0; componentIndex < components.size(); componentIndex++) {
            for (Point point : components.get(componentIndex)) {
                componentsByColumn.computeIfAbsent(columnKey(point.x(), point.z()), ignored -> new LinkedHashSet<>())
                        .add(componentIndex);
            }
        }

        for (int fruitIndex = 0; fruitIndex < fruits.size(); fruitIndex++) {
            Point fruit = fruits.get(fruitIndex);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    Set<Integer> componentIndices = componentsByColumn.get(
                            columnKey(fruit.x() + dx, fruit.z() + dz));
                    if (componentIndices == null) continue;
                    for (int componentIndex : componentIndices) result.get(componentIndex).add(fruitIndex);
                }
            }
        }
        return result;
    }

    /**
     * Flood-fills outward from every water source through {@code walkable}
     * tiles only, so a dirt block that is not connected to any water by an
     * unbroken chain of valid tiles is never reported reachable. Any tile
     * absent from {@code walkable} - regardless of what it actually is -
     * blocks the route through that spot, but a detour through other
     * walkable tiles around it still counts.
     *
     * <p>Straight steps only (a step up or down is fine), as for the cane
     * water's footprint: two blocks touching at a corner close it. A border
     * with a missing corner block let the field grow out through the gap.
     */
    public static Set<Point> reachableFromWater(Set<Point> walkable, Set<Point> waterSources) {
        Set<Point> visited = new LinkedHashSet<>();
        ArrayDeque<Point> queue = new ArrayDeque<>();
        for (Point water : waterSources) {
            for (Point candidate : walkable) {
                if (!visited.contains(candidate) && isAdjacentToWater(candidate, water)) {
                    visited.add(candidate);
                    queue.addLast(candidate);
                }
            }
        }
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            Point current = queue.removeFirst();
            for (int[] step : steps) {
                for (int dy = -1; dy <= 1; dy++) {
                    Point neighbour = new Point(current.x() + step[0], current.y() + dy, current.z() + step[1]);
                    if (walkable.contains(neighbour) && visited.add(neighbour)) queue.addLast(neighbour);
                }
            }
        }
        return visited;
    }

    /** A water source hydrates the tile directly beside or over it, so a walkable route may start there. */
    private static boolean isAdjacentToWater(Point feet, Point water) {
        int dx = Math.abs(feet.x() - water.x());
        int dz = Math.abs(feet.z() - water.z());
        int soilY = feet.y() - 1;
        return dx + dz <= 1 && Math.abs(soilY - water.y()) <= 1;
    }

    private static List<Set<Integer>> emptyAssociationSets(int componentCount) {
        List<Set<Integer>> result = new ArrayList<>(componentCount);
        for (int index = 0; index < componentCount; index++) result.add(new LinkedHashSet<>());
        return result;
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}

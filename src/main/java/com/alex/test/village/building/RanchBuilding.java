package com.alex.test.village.building;

import com.alex.test.village.NavGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ranch: a fenced, gated pen that holds the village's livestock. Created by
 * hanging a Ranch token in an item frame on the fence beside the gate.
 *
 * <p>Ranchers feed and breed the animals inside; butchers cull the surplus.
 * {@code functionalBlocks} is simply every pen tile — the whole floor is the
 * working area.
 */
public final class RanchBuilding extends EnclosureBuilding {

    /** Traditional recommended pen size; any non-empty closed pen now registers. */
    public static final int REQUIRED_TILES = 12;

    /**
     * Animals the pen supports per species: one per this many tiles.
     * A 6x8 pen (48 tiles) keeps up to 6 cows AND 6 sheep, etc.
     */
    public static final int TILES_PER_ANIMAL = 8;

    public RanchBuilding(UUID itemFrameId, BlockPos gatePos, BlockPos itemFramePos,
                         Direction interiorDirection, Set<BlockPos> initialTiles) {
        super(itemFrameId, gatePos, itemFramePos, interiorDirection, initialTiles);
    }

    /**
     * The whole floor is the working area. The result only says whether the
     * pen reaches the recommended size; validity is decided by the base class
     * from the tiles alone, so a smaller closed pen still registers.
     */
    @Override
    protected boolean scanFunctionalBlocks(ServerLevel level) {
        functionalBlocks.addAll(navMeshBlocks);
        return functionalBlocks.size() >= REQUIRED_TILES;
    }

    /** How many animals of one species this pen supports. */
    public int capacityPerSpecies() {
        return Math.max(2, navMeshBlocks.size() / TILES_PER_ANIMAL);
    }

    /** Water spots inside the fence from the last scan; null when not known yet (fresh or loaded pen). */
    @Nullable private Set<BlockPos> water;
    /** XZ column -> {lowest, highest} feet Y of floor or water there; null until next needed. */
    @Nullable private Map<Long, int[]> columns;

    @Override
    protected Set<BlockPos> scanRoomBlocks(ServerLevel level, NavGraph navGraph,
                                           Collection<BlockPos> rivalEntries) {
        EnclosureScanner.ScanResult scan =
                EnclosureScanner.scanDetailed(level, getDoorPos(), getInteriorDirection());
        water = scan.water();
        columns = null;
        return scan.tiles();
    }

    /**
     * Every living animal inside the pen. Queried from the entity index each
     * time — animals move constantly, so caching would only ever be wrong.
     */
    public List<Animal> animalsInside(ServerLevel level) {
        if (navMeshBlocks.isEmpty()) return List.of();
        Map<Long, int[]> penColumns = columns(level);
        AABB box = new AABB(minX, minY - 1, minZ, maxX + 1, maxY + 2, maxZ + 1);
        List<Animal> inside = new ArrayList<>();
        for (Animal animal : level.getEntitiesOfClass(Animal.class, box)) {
            if (!animal.isAlive()) continue;
            BlockPos feet = animal.blockPosition();
            // The AABB is the pen's bounding box, which can cover ground just
            // outside an L-shaped fence — verify against the actual tiles.
            int[] span = penColumns.get(columnKey(feet.getX(), feet.getZ()));
            if (span != null && feet.getY() >= span[0] - 2 && feet.getY() <= span[1] + 2) {
                inside.add(animal);
            }
        }
        long now = level.getGameTime();
        for (Animal animal : inside) {
            memberSeen.put(animal.getUUID(), now);
            removeStray(animal.getUUID());
        }
        return inside;
    }

    // -------------------------------------------------------------------------
    // Members and strays
    // -------------------------------------------------------------------------

    /** An animal seen inside within this long belongs to the pen: 5 minutes. */
    public static final long MEMBER_TICKS = 6000L;

    /** Animal -> game time it was last seen inside. Not saved: rebuilt as animals are seen. */
    private final Map<UUID, Long> memberSeen = new HashMap<>();

    /** Members found outside the pen -> game time they were first found out. */
    private final Map<UUID, Long> strays = new HashMap<>();

    /** Strays a villager let go out there (a walk given up): fetched from further off. Always also in {@link #strays}. */
    private final Set<UUID> letGo = new HashSet<>();

    /** Game time of the last stray scan, so it runs only every so often. */
    // Half the smallest number, never the smallest: "now - last" must not overflow. From the smallest it
    // came out negative, the scan was "not due" for ever, and no pen ever listed a stray (found 0.1.3).
    private long lastStrayScan = Long.MIN_VALUE / 2;

    /** Whether {@code animal} lives here: seen inside lately, or out as a stray. */
    public boolean isMember(UUID animal, long now) {
        if (strays.containsKey(animal)) return true;
        Long seen = memberSeen.get(animal);
        return seen != null && now - seen <= MEMBER_TICKS;
    }

    /** Whether {@code animal} was inside within the last {@code ticks}. */
    public boolean seenInsideWithin(UUID animal, long now, long ticks) {
        Long seen = memberSeen.get(animal);
        return seen != null && now - seen <= ticks;
    }

    /** Notes a member found outside; keeps the first time it was found. */
    public void addStray(UUID animal, long now) {
        strays.putIfAbsent(animal, now);
    }

    /** Notes a stray a villager let go outside, so it is fetched from further off; keeps the first time it was found. */
    public void addLetGoStray(UUID animal, long now) {
        strays.putIfAbsent(animal, now);
        letGo.add(animal);
    }

    /** Whether this stray was let go by a villager, rather than found out by the scan. */
    public boolean wasLetGo(UUID animal) {
        return letGo.contains(animal);
    }

    /** Strays and when each was first found out; read-only live view, for {@code RanchStrays}. */
    public Map<UUID, Long> strays() {
        return Collections.unmodifiableMap(strays);
    }

    /** The strays a villager let go; read-only live view. */
    public Set<UUID> letGoStrays() {
        return Collections.unmodifiableSet(letGo);
    }

    /** No longer a stray (home again), and no longer one a villager let go. Every stray leaves through here. */
    public void removeStray(UUID animal) {
        strays.remove(animal);
        letGo.remove(animal);
    }

    /** No longer this pen's: gone a day, dead, or taken by a player. */
    public void forget(UUID animal) {
        removeStray(animal);
        memberSeen.remove(animal);
    }

    /** True at most once per {@code interval} ticks: time for another stray scan. */
    public boolean strayScanDue(long now, long interval) {
        if (now - lastStrayScan < interval) return false;
        lastStrayScan = now;
        return true;
    }

    /** Seen inside just now: a member from this moment, so it is fetched back if it gets out. */
    public void markMember(UUID animal, long now) {
        memberSeen.put(animal, now);
        removeStray(animal);
    }

    // -------------------------------------------------------------------------
    // Loose animals around the pen, for the round-up
    // -------------------------------------------------------------------------

    /** Loose animals found near the gate on the last round-up scan; for {@code RanchRoundUp}. */
    private List<UUID> looseNearby = List.of();

    /** Game time of the last round-up scan. */
    private long lastLooseScan = Long.MIN_VALUE / 2; // see lastStrayScan: the round-up scan never ran either

    public List<UUID> looseNearby() {
        return looseNearby;
    }

    public void setLooseNearby(List<UUID> loose) {
        looseNearby = List.copyOf(loose);
    }

    /** True at most once per {@code interval} ticks: time for another round-up scan. */
    public boolean looseScanDue(long now, long interval) {
        if (now - lastLooseScan < interval) return false;
        lastLooseScan = now;
        return true;
    }

    /** Feet-level water spots inside the fence from the last scan; empty when none or not known yet. For the Ranch window. */
    public Set<BlockPos> knownWater() {
        return water == null ? Set.of() : Collections.unmodifiableSet(water);
    }

    /** Whether {@code pos} is a pen tile (floor or pond). */
    public boolean isPenTile(ServerLevel level, BlockPos pos) {
        int[] span = columns(level).get(columnKey(pos.getX(), pos.getZ()));
        return span != null && pos.getY() >= span[0] - 2 && pos.getY() <= span[1] + 2;
    }

    /**
     * The pen's floor and pond columns. Built once per scan: looping over
     * every tile for every animal ran on each rancher, butcher and milk
     * check. Water was missing before, so an animal swimming in a pond
     * inside the fence was invisible: never counted, sheared or culled.
     */
    private Map<Long, int[]> columns(ServerLevel level) {
        if (columns != null) return columns;
        if (water == null) {
            // Registered or loaded without a scan of ours: find the pond once.
            EnclosureScanner.ScanResult scan =
                    EnclosureScanner.scanDetailed(level, getDoorPos(), getInteriorDirection());
            water = scan.found() ? scan.water() : Set.of();
        }
        Map<Long, int[]> built = new HashMap<>();
        for (BlockPos tile : navMeshBlocks) addColumn(built, tile);
        for (BlockPos spot : water) addColumn(built, spot);
        columns = built;
        return built;
    }

    private static void addColumn(Map<Long, int[]> map, BlockPos pos) {
        int[] span = map.get(columnKey(pos.getX(), pos.getZ()));
        if (span == null) {
            map.put(columnKey(pos.getX(), pos.getZ()), new int[]{pos.getY(), pos.getY()});
        } else {
            span[0] = Math.min(span[0], pos.getY());
            span[1] = Math.max(span[1], pos.getY());
        }
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}

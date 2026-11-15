package com.alex.test.village;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.HouseBuilding;
import com.alex.test.village.building.SchoolBuilding;
import com.alex.test.village.stats.CrowdingBalance;
import com.alex.test.village.villagers.teacher.SchoolClasses;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who is standing in which building, for overcrowding.
 *
 * <p>Counted once a second for the whole village, so each villager's own
 * check is a map lookup rather than a sweep over every building and every
 * other villager. Everybody inside is a body -- villagers, children, the
 * Mayor, nomads, visitors, the merchant -- except a villager round at a
 * neighbour's house for the evening (a visit is meant to be busy), and the
 * teacher and children of a class in session at a school.
 *
 * <p>Containment is the room's own tiles, not its box, so the flat above a
 * stacked apartment is not counted into the one below. A villager asleep
 * stands in its bed block, one under the room tile on top of the bed.
 */
public final class VillageCrowding {

    /** Ticks between headcounts. */
    private static final int COUNT_INTERVAL = 20;
    /** How long a crowded building stays on the Mayor's list after the last complaint. */
    private static final long INCIDENT_MEMORY_TICKS = 24_000L;

    /** A building that made somebody feel crowded; for the Mayor's warning list. */
    public record Incident(UUID buildingId, BlockPos door, String name, int bodies, float space,
                           long gameTime) {}

    private final Map<UUID, Building> whereIs = new HashMap<>();
    private final Map<Building, Integer> bodies = new IdentityHashMap<>();
    private final Map<UUID, Incident> incidents = new HashMap<>();

    public void tick(ServerLevel level, VillageManager village) {
        if (level.getGameTime() % COUNT_INTERVAL != 0) return;
        whereIs.clear();
        bodies.clear();
        for (Building building : village.getBuildings()) {
            if (!building.countsCrowding() || !building.isValid()) continue;
            var tiles = building.getNavMeshBlocks();
            if (tiles.isEmpty()) continue;
            int count = 0;
            for (NpcEntity npc : level.getEntitiesOfClass(NpcEntity.class, building.roomBox())) {
                if (!npc.isAlive() || npc.isRemoved()) continue;
                if (!isInside(npc, tiles)) continue;
                if (whereIs.containsKey(npc.getNpcId())) continue;
                VillagerModel model = village.getModel(npc.getNpcId());
                if (model != null && building instanceof HouseBuilding
                        && model.brain().isVisitingHouse(building.getItemFrameId(), level.getGameTime())) {
                    continue;
                }
                // Hiding from a monster is meant to be a squeeze: whoever takes
                // shelter doesn't count, so neither they nor the people living
                // there feel crowded by it, and it is not a crowded building to the Mayor.
                if (model != null && model.brain().isTakingShelter()) continue;
                // A class in session is meant to be full: its teacher and children don't count.
                if (building instanceof SchoolBuilding
                        && SchoolClasses.isInClass(level, npc.getNpcId(), building.getItemFrameId())) {
                    continue;
                }
                whereIs.put(npc.getNpcId(), building);
                count++;
            }
            if (count > 0) bodies.put(building, count);
        }
    }

    /**
     * Standing on one of the room's tiles. Somebody lying in a bed or sitting
     * on a chair is in a furniture block instead, so for them a room tile
     * beside it (or on top of the bed) will do.
     */
    private static boolean isInside(NpcEntity npc, java.util.Set<BlockPos> tiles) {
        BlockPos feet = npc.blockPosition();
        if (tiles.contains(feet)) return true;
        if (!npc.isSleeping() && !npc.isPassenger()) return false;
        if (tiles.contains(feet.above())) return true;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (tiles.contains(feet.relative(dir))) return true;
        }
        return false;
    }

    /** The crowding-counted building this villager was last counted in, or null when outside. */
    @Nullable
    public Building buildingOf(UUID npcId) {
        return whereIs.get(npcId);
    }

    /** Bodies counted inside at the last headcount. */
    public int bodiesIn(Building building) {
        return bodies.getOrDefault(building, 0);
    }

    /**
     * Whether {@code building} is crowded with this villager in it: counting
     * it once more when it is not inside already.
     */
    public boolean wouldCrowd(Building building, UUID npcId) {
        if (!building.countsCrowding()) return false;
        int count = bodiesIn(building) + (whereIs.get(npcId) == building ? 0 : 1);
        return CrowdingBalance.isCrowded(building.getSpace().space(), count);
    }

    /** Somebody felt crowded in {@code building}; the Mayor keeps it on the list for a day. */
    public void recordIncident(Building building, int count, long gameTime) {
        incidents.put(building.getItemFrameId(), new Incident(building.getItemFrameId(),
                building.getDoorPos(), building.getDisplayName(), count,
                building.getSpace().space(), gameTime));
    }

    /** Crowded buildings from the last day, most recent first; buildings since removed are dropped. */
    public List<Incident> recentIncidents(VillageManager village, long gameTime) {
        incidents.values().removeIf(incident -> gameTime - incident.gameTime() > INCIDENT_MEMORY_TICKS
                || gameTime < incident.gameTime());
        List<Incident> out = new ArrayList<>();
        for (Incident incident : incidents.values()) {
            for (Building building : village.getBuildings()) {
                if (building.getItemFrameId().equals(incident.buildingId())) {
                    out.add(incident);
                    break;
                }
            }
        }
        out.sort((a, b) -> Long.compare(b.gameTime(), a.gameTime()));
        return out;
    }
}

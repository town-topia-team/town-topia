package com.alex.test.village.shelter;

import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.EnclosureBuilding;
import com.alex.test.village.building.MerchantStallBuilding;
import com.alex.test.village.building.MineBuilding;
import com.alex.test.village.building.WatchtowerBuilding;
import com.alex.test.village.villagers.guard.alert.GuardThreatRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What villagers hiding from monsters need to know about the buildings they
 * hide in: is a monster inside, is one still close by, and which buildings a
 * monster has got into lately.
 *
 * <p>"Inside" means standing on the building's own floor tiles (its nav mesh),
 * not in the rough box {@link Building#isInRoom} uses: that box reaches past
 * the walls, and a zombie pressed against the outside of a house must not
 * count as one in the kitchen.
 *
 * <p>A building a monster got into is <em>overrun</em>: the whole village
 * avoids it until {@link #OVERRUN_TICKS} after a monster was last seen inside.
 * Every look that finds one renews it, so a zombie still standing in the
 * kitchen keeps the house off the list.
 */
public final class ShelterWatch {

    /** How far around a building (or a villager hiding in the open) a monster still counts as near. */
    public static final double DANGER_RADIUS = 16.0;
    /** Up and down, a monster this far away does not count as near: caves under the village. */
    private static final double DANGER_HEIGHT = 6.0;
    /** A building a monster got into is avoided for this long after it was last seen inside (30 s). */
    public static final int OVERRUN_TICKS = 600;
    /** One look at a building serves everyone asking for this long (a second, as often as the checks look). */
    private static final int LOOK_TICKS = 20;

    /** What a look at a building found. */
    public record View(@Nullable Mob inside, boolean dangerNear) {
        static final View CLEAR = new View(null, false);
    }

    private record Cached(long at, View view) {}

    /** Recent looks, by building (item frame id). */
    private static final Map<UUID, Cached> LOOKS = new HashMap<>();
    /** Overrun buildings: village id -> building id -> game time the mark ends. */
    private static final Map<UUID, Map<UUID, Long>> OVERRUN = new HashMap<>();

    private ShelterWatch() {}

    /**
     * Whether a building is somewhere to hide: it has walls. A fenced pen, a
     * mine face, a tower top or an open-air stall is open ground; the monster
     * just follows the villager in.
     */
    public static boolean isShelter(Building building) {
        return !(building instanceof EnclosureBuilding)
                && !(building instanceof MineBuilding)
                && !(building instanceof MerchantStallBuilding stall && stall.isOpenAir())
                && !(building instanceof WatchtowerBuilding);
    }

    /**
     * Looks at {@code building}: a monster on its floor, and whether any
     * monster is within {@link #DANGER_RADIUS} of it. Finding one inside marks
     * the building overrun. Shared by everyone asking within a second.
     */
    public static View look(VillageManager mgr, ServerLevel level, Building building) {
        long now = level.getGameTime();
        UUID id = building.getItemFrameId();
        Cached cached = LOOKS.get(id);
        if (cached != null && now >= cached.at() && now - cached.at() < LOOK_TICKS) return cached.view();
        View view = scan(mgr, level, building);
        if (LOOKS.size() > 256) LOOKS.values().removeIf(c -> now < c.at() || now - c.at() >= LOOK_TICKS);
        LOOKS.put(id, new Cached(now, view));
        if (view.inside() != null) markOverrun(mgr, building, now);
        return view;
    }

    /** Whether any monster is within {@link #DANGER_RADIUS} of {@code pos}: for a villager hiding in the open. */
    public static boolean dangerNear(VillageManager mgr, ServerLevel level, Vec3 pos) {
        AABB box = new AABB(pos, pos).inflate(DANGER_RADIUS, DANGER_HEIGHT, DANGER_RADIUS);
        return !level.getEntitiesOfClass(Mob.class, box,
                mob -> mob.isAlive() && GuardThreatRules.isHostileMonster(mgr, mob)).isEmpty();
    }

    private static View scan(VillageManager mgr, ServerLevel level, Building building) {
        if (!building.isValid() || building.getNavMeshBlocks().isEmpty()) return View.CLEAR;
        AABB around = building.roomBox().inflate(DANGER_RADIUS, DANGER_HEIGHT, DANGER_RADIUS);
        List<Mob> mobs = level.getEntitiesOfClass(Mob.class, around,
                mob -> mob.isAlive() && GuardThreatRules.isHostileMonster(mgr, mob));
        if (mobs.isEmpty()) return View.CLEAR;
        for (Mob mob : mobs) {
            if (isOnFloor(building, mob.blockPosition())) return new View(mob, true);
        }
        return new View(null, true);
    }

    /**
     * Whether {@code pos} stands on one of the building's floor tiles, or a
     * block above or below one (on a bed, a slab, a table). Walls keep a
     * monster outside off every interior column.
     */
    public static boolean isOnFloor(Building building, BlockPos pos) {
        var tiles = building.getNavMeshBlocks();
        return tiles.contains(pos) || tiles.contains(pos.below()) || tiles.contains(pos.above());
    }

    /**
     * The shelter {@code pos} is in: one whose floor it stands on, or, with
     * {@code looseBox}, else one whose room box holds it (a sleeper lying on
     * a bed is not on a floor tile). Null when it is in none.
     */
    @Nullable
    public static Building shelterAt(VillageManager mgr, BlockPos pos, boolean looseBox) {
        Building boxed = null;
        for (Building building : mgr.getBuildings()) {
            if (building == null || !building.isValid() || !isShelter(building)) continue;
            if (!building.isInRoom(pos)) continue;
            if (isOnFloor(building, pos)) return building;
            if (boxed == null) boxed = building;
        }
        return looseBox ? boxed : null;
    }

    /** Marks {@code building} overrun for {@link #OVERRUN_TICKS} from {@code now}, for the whole village. */
    public static void markOverrun(VillageManager mgr, Building building, long now) {
        OVERRUN.computeIfAbsent(mgr.getVillageId(), k -> new HashMap<>())
                .put(building.getItemFrameId(), now + OVERRUN_TICKS);
    }

    /** Whether a monster got into {@code building} within the last {@link #OVERRUN_TICKS}. */
    public static boolean isOverrun(VillageManager mgr, Building building, long now) {
        Map<UUID, Long> byBuilding = OVERRUN.get(mgr.getVillageId());
        if (byBuilding == null) return false;
        Long until = byBuilding.get(building.getItemFrameId());
        if (until == null) return false;
        // Over, or from another world whose clock ran further.
        if (now >= until || until - now > OVERRUN_TICKS) {
            byBuilding.remove(building.getItemFrameId());
            return false;
        }
        return true;
    }
}

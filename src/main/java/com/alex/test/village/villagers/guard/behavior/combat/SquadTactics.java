package com.alex.test.village.villagers.guard.behavior.combat;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.CaptainBalance;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a captain tells the guards around it in a fight.
 *
 * <ul>
 *   <li><b>Fall back.</b> When the captain breaks off, guards around it that
 *       are hurt break off with it rather than fight on alone.</li>
 * </ul>
 *
 * <p>There is no focus fire: guards spread over the mobs by load
 * ({@code GuardAlertSystem}), the captain's mob counting like any other.
 */
public final class SquadTactics {

    /** Guards this close to a captain take its orders. */
    public static final double ORDER_RADIUS = CaptainBalance.RALLY_RADIUS;
    /** A fall-back call holds this long. */
    private static final int FALL_BACK_TICKS = 100;
    /** Guards below this share of health obey a fall-back call. */
    public static final float FALL_BACK_HEALTH = 0.6f;

    private record FallBack(long until) {}

    private static final Map<UUID, FallBack> FALL_BACKS = new HashMap<>();

    private SquadTactics() {}


    /** A captain breaking off calls the hurt guards around it back with it. */
    public static void callFallBack(NpcEntity captain, long now) {
        FALL_BACKS.put(captain.getNpcId(), new FallBack(now + FALL_BACK_TICKS));
        if (FALL_BACKS.size() > 32) FALL_BACKS.values().removeIf(call -> call.until < now);
    }

    /** Whether a captain near {@code guard} has called a fall-back that still holds. */
    public static boolean fallBackCalledNear(ServerLevel level, NpcEntity guard, long now) {
        if (FALL_BACKS.isEmpty()) return false;
        for (NpcEntity captain : captainsNear(level, guard)) {
            FallBack call = FALL_BACKS.get(captain.getNpcId());
            if (call != null && call.until >= now) return true;
        }
        return false;
    }

    /** Other guards, captains and archers within {@code radius} of {@code centre}, for spreading out. */
    public static List<NpcEntity> defendersAround(ServerLevel level, NpcEntity self, LivingEntity centre,
                                                  double radius) {
        return level.getEntitiesOfClass(NpcEntity.class, centre.getBoundingBox().inflate(radius),
                other -> other != self && other.isAlive() && other.getProfession().isDefender());
    }

    private static List<NpcEntity> captainsNear(ServerLevel level, NpcEntity guard) {
        return level.getEntitiesOfClass(NpcEntity.class, guard.getBoundingBox().inflate(ORDER_RADIUS),
                other -> other != guard && other.isAlive() && other.getProfession() == Profession.CAPTAIN);
    }
}

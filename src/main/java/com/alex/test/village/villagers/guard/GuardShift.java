package com.alex.test.village.villagers.guard;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.EveningSchedule;
import com.alex.test.village.brain.VillagerBrain;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.behavior.GuardActivity;
import com.alex.test.village.villagers.guard.settings.GuardSettings;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The guards' and archers' own shift clock, separate from
 * {@link VillagerBrain#isNight}, which still decides sleeping, waking and
 * monster danger for everyone.
 *
 * <p>Each defender's evening shift change is rolled per night between
 * {@value #SHIFT_CHANGE_MIN} and {@value #SHIFT_CHANGE_MAX}: the night shift
 * starts then, and the day shift ends then, so they never all go at once.
 * The morning change is the world's dawn as before.
 *
 * <p>Handover: a day-shift defender actually on duty at its change keeps
 * working until the night shift has taken over -- at a guard post, until a
 * night-shift defender on duty is within {@value #RELIEF_RADIUS} blocks of
 * that post; on patrol, until at least one night-shift defender is on duty --
 * but never more than {@value #HANDOVER_MAX_TICKS} ticks past its own change.
 * A village with no night-shift defenders has no handover. The night shift
 * never waits for anything.
 */
public final class GuardShift {

    public static final int SHIFT_CHANGE_MIN = 12_500;
    public static final int SHIFT_CHANGE_MAX = 13_200;
    public static final int HANDOVER_MAX_TICKS = 600;
    public static final int RELIEF_RADIUS = 8;
    /** How long a handover answer is reused before the village is looked at again. */
    private static final int HANDOVER_CHECK_TICKS = 20;
    private static final long SALT_SHIFT = 0x5A1F_7C4EL;

    private record Hold(long checkedAt, boolean hold) {}
    private static final Map<UUID, Hold> HOLDS = new HashMap<>();

    private GuardShift() {}

    /** Tonight's evening shift change for one defender: 12,500-13,200. */
    public static int shiftChange(EveningSchedule schedule, long day) {
        return SHIFT_CHANGE_MIN + schedule.dailyRoll(day, SALT_SHIFT, SHIFT_CHANGE_MAX - SHIFT_CHANGE_MIN + 1);
    }

    /**
     * Whether it is the night shift at {@code dayTime}: from the evening
     * change until the world's dawn, which keeps the old per-villager spread
     * of up to {@code delay} ticks.
     */
    public static boolean shiftNightAt(long dayTime, int shiftChange, int delay) {
        long t = EveningSchedule.timeOfDay(dayTime);
        long shifted = Math.floorMod(dayTime - delay, EveningSchedule.DAY_TICKS);
        return t >= shiftChange && shifted <= EveningSchedule.WAKE_TIME && shifted >= shiftChange - delay;
    }

    /** Night shift on this defender's own clock. */
    public static boolean isShiftNight(Activity.Context ctx) {
        if (VillagerBrain.heldAsleepAfterSkip(ctx)) return true;
        long dayTime = ctx.level().getDayTime();
        int change = shiftChange(ctx.brain().eveningSchedule(), EveningSchedule.dayIndex(dayTime));
        return shiftNightAt(dayTime, change, VillagerBrain.scheduleDelay(ctx.model().npcId));
    }

    /**
     * Whether this defender's passive schedule says work now: the night
     * shift at night, the day shift by day and through a handover.
     */
    public static boolean scheduled(Activity.Context ctx, GuardSettings settings) {
        boolean night = isShiftNight(ctx);
        if (settings.patrolAtNightOnly()) return night || reportedEarly(ctx);
        return !night || handoverHold(ctx);
    }

    /** In brain data: game time until which a night-shift defender is on duty ahead of its own change. */
    private static final String TAG_EARLY_UNTIL = "ShiftEarlyUntil";
    /** An early start is never longer than this; anything more is another world's clock. */
    private static final long EARLY_MAX_TICKS = 2_400L;

    /**
     * A night-shift defender goes on duty now, ahead of its own shift change at
     * game time {@code shiftStartsAt}: it left the barracks drill with a
     * partner whose shift began first, and the two go off together rather than
     * one standing about alone.
     */
    public static void reportEarly(Activity.Context ctx, long shiftStartsAt) {
        long now = ctx.level().getGameTime();
        if (shiftStartsAt <= now || shiftStartsAt - now > EARLY_MAX_TICKS) return;
        ctx.npc().getBrainData().putLong(TAG_EARLY_UNTIL, shiftStartsAt);
    }

    private static boolean reportedEarly(Activity.Context ctx) {
        net.minecraft.nbt.CompoundTag data = ctx.npc().getBrainData();
        if (!data.contains(TAG_EARLY_UNTIL)) return false;
        long left = data.getLong(TAG_EARLY_UNTIL) - ctx.level().getGameTime();
        if (left > 0 && left <= EARLY_MAX_TICKS) return true;
        data.remove(TAG_EARLY_UNTIL);
        return false;
    }

    /** A day-shift defender on duty past its change, waiting for the night shift. Cached for a second. */
    private static boolean handoverHold(Activity.Context ctx) {
        long now = ctx.level().getGameTime();
        UUID id = ctx.model().npcId;
        Hold cached = HOLDS.get(id);
        if (cached != null && now >= cached.checkedAt() && now - cached.checkedAt() < HANDOVER_CHECK_TICKS) {
            return cached.hold();
        }
        boolean hold = computeHold(ctx);
        if (HOLDS.size() > 256) HOLDS.clear();
        HOLDS.put(id, new Hold(now, hold));
        return hold;
    }

    private static boolean computeHold(Activity.Context ctx) {
        long dayTime = ctx.level().getDayTime();
        long t = EveningSchedule.timeOfDay(dayTime);
        int change = shiftChange(ctx.brain().eveningSchedule(), EveningSchedule.dayIndex(dayTime));
        if (!withinHandover(t, change)) return false;
        // Only a defender actually on duty waits; one already home stays home.
        Activity current = ctx.brain().currentActivity();
        if (!(current instanceof WorkActivity)) return false;
        // An archer waits at its watchtower or guard post too, not only a guard.
        BlockPos post = current instanceof GuardActivity guard ? guard.activeGuardPost()
                : current instanceof com.alex.test.village.villagers.archer.behavior.ArcherActivity archer
                ? archer.activeWatchPost()
                : current instanceof com.alex.test.village.villagers.mage.behavior.MageActivity mage ? mage.activeWatchPost() : null;
        // A lookout is relieved only by someone up on its platform. Anyone
        // within a few blocks of the token used to do, so a night guard
        // walking past the foot of the tower sent the lookout down before
        // the night archer had climbed up.
        com.alex.test.village.building.WatchtowerBuilding tower =
                current instanceof com.alex.test.village.villagers.archer.behavior.ArcherActivity lookout
                        ? lookout.activeTower() : null;

        boolean anyNightShift = false;
        for (VillagerModel other : ctx.mgr().getVillagers()) {
            if (other == ctx.model()) continue;
            NpcEntity puppet = other.puppet();
            if (puppet == null || !puppet.isAlive()) continue;
            Profession p = puppet.getProfession();
            if (!p.isDefender()) continue;
            if (!GuardSettings.of(puppet).patrolAtNightOnly()) continue;
            anyNightShift = true;
            if (!(other.brain().currentActivity() instanceof WorkActivity)) continue;
            // Relieved: at the post, somebody from the night shift has arrived;
            // on patrol, somebody from the night shift is patrolling.
            boolean relieved = post == null
                    || (tower != null ? tower.isOnPlatform(puppet.blockPosition())
                            : puppet.blockPosition().distSqr(post) <= (double) RELIEF_RADIUS * RELIEF_RADIUS);
            if (relieved) return false;
        }
        return anyNightShift;
    }

    /** Pure: whether time of day {@code t} is inside the handover window after {@code change}. */
    public static boolean withinHandover(long t, int change) {
        return t >= change && t - change <= HANDOVER_MAX_TICKS;
    }
}

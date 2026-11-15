package com.alex.test.village.economy.away;

import com.alex.test.village.VillageManager;
import com.alex.test.village.brain.EveningSchedule;
import com.alex.test.village.stats.HealthSystem;
import com.alex.test.village.stats.HungerSystem;
import com.alex.test.village.stats.VillagerStats;
import com.alex.test.village.stats.WellbeingSystem;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * One day's living while away, for every villager with a body at once, hour
 * by hour: hunger falling (awake, asleep, working; at {@link AwayHunger#HUNGER_PACE}
 * of live), the meal times ({@link AwayMeals}), snacks and the bite before bed,
 * starving hits and deaths, a cleric's heals, the dawn heal, and mood (the
 * work's toll, hunger, an easy evening, the night's sleep).
 *
 * <p>The day is looked at every {@link #CHECK_TICKS} ticks and at each meal,
 * bedtime, dawn and day change; in between, hunger falls in a straight line.
 * Runs with the book open and every eater's copy swapped into its body: the
 * live stats code ({@code VillagerStats}) does the sums on the copies.
 *
 * <p>My calls (10-22): defenders and night-shift clerics are counted as day
 * workers; a worker works {@link AwayHunger#WORKING_SHARE} of its work hours;
 * the evening is an easy one ({@code WANDER_GAIN}); a cleric heals a starving
 * villager at {@value #CLERIC_HEAL_BELOW} health or less back to half, using
 * one of its starving heals; a jobless villager wanders in the work hours.
 */
final class AwayHealth {

    /** Hunger is looked at this often for snacks, starving and heals. */
    static final long CHECK_TICKS = 500L;
    /** Walking a day, as the old calculator: about 250 blocks. */
    private static final float WALK_BLOCKS_PER_DAY = 250f;
    /** A cleric heals a starving villager at or below this ({@code ClericCare}'s nearly dead line). */
    private static final float CLERIC_HEAL_BELOW = 6f;
    /** Salt for tonight's sleep roll, so each villager and night rolls its own. */
    private static final long SLEEP_ROLL_SALT = 0x51EE9L;

    /** What a day did, for the day line. */
    static final class DayResult {
        final AwayMeals.Tally meals = new AwayMeals.Tally();
        /** Starving hits taken, in health points. */
        float hits;
        int heals;
        int died;
        /** At the day's end, of the living: under 25 hunger, and at 0. */
        int hungry;
        int starving;
    }

    private AwayHealth() {}

    static DayResult live(ServerLevel level, VillageManager mgr, AwayBook book, AwayDays.Day day,
                          List<AwayEater> eaters) {
        long dayTicks = EveningSchedule.DAY_TICKS;
        long wake = EveningSchedule.WAKE_TIME;
        // Offsets from dawn, as AwayDays cuts the day.
        long workEnd = Math.floorMod(EveningSchedule.MEAN_END_WORK - wake, dayTicks);
        long bed = workEnd + Math.floorMod(EveningSchedule.PROJECTED_BEDTIME - EveningSchedule.MEAN_END_WORK,
                dayTicks);
        long lunch = Math.floorMod(HungerSystem.LUNCH_START - wake, dayTicks);
        long dayChange = Math.floorMod(-wake, dayTicks);
        long from = Math.floorMod(day.start() - wake, dayTicks);
        long to = from + day.length();

        TreeSet<Long> points = new TreeSet<>();
        points.add(from);
        points.add(to);
        for (long t = (from / CHECK_TICKS + 1) * CHECK_TICKS; t < to; t += CHECK_TICKS) points.add(t);
        for (long t : new long[] {0L, lunch, workEnd, bed, dayChange}) {
            if (t >= from && t < to) points.add(t);
        }

        DayResult result = new DayResult();
        List<AwayEater> alive = new ArrayList<>(eaters);
        float walk = WALK_BLOCKS_PER_DAY * HungerSystem.WALK_HUNGER_PER_BLOCK / bed;
        Long previous = null;
        for (long point : points) {
            if (previous != null) {
                Phase phase = previous < workEnd ? Phase.WORK : previous < bed ? Phase.EVENING : Phase.NIGHT;
                pass(alive, phase, point - previous, walk, day.calendarDay(), result);
            }
            if (point >= to) break;
            // The day number changes a little after dawn: breakfast still counts on the day before.
            long mealDay = point < dayChange ? day.calendarDay() - 1 : day.calendarDay();
            if (point == 0L) {
                // Woke after a full night: the rest of the sleep gain arrives (onWokeAfterFullSleep).
                for (AwayEater eater : alive) eater.needs.finishNightSleep();
            }
            AwayMeals.Kind meal = point == 0L || point == lunch || point == workEnd ? AwayMeals.Kind.PROPER
                    : point == bed ? AwayMeals.Kind.BEDTIME
                    : point < bed ? AwayMeals.Kind.SNACK : null;
            if (meal != null && !alive.isEmpty()) AwayMeals.serve(level, mgr, book, alive, meal, mealDay, result.meals);
            if (point == dayChange) newDay(alive);
            if (point < bed) clericHeals(alive, result);
            previous = point;
        }
        for (AwayEater eater : eaters) {
            // Dead or alive, the copy keeps at least 1: dying happens at WRITE, through the damage path.
            eater.npc.setHealth(Math.max(1f, eater.health));
            if (eater.villager.starved()) continue;
            if (eater.needs.hunger < HungerSystem.LOW_HUNGER_WELLBEING_THRESHOLD) result.hungry++;
            if (eater.needs.hunger <= 0f) result.starving++;
        }
        return result;
    }

    private enum Phase { WORK, EVENING, NIGHT }

    /** {@code ticks} of one part of the day for everyone alive: hunger, starving, mood. */
    private static void pass(List<AwayEater> alive, Phase phase, long ticks, float walk, long calendarDay,
                             DayResult result) {
        if (ticks <= 0L) return;
        for (int i = alive.size() - 1; i >= 0; i--) {
            AwayEater eater = alive.get(i);
            VillagerStats needs = eater.needs;
            if (needs.hunger > 0f) {
                // As HealthSystem.tick: any hunger back stops the starving clock and gives the heals back.
                eater.villager.starveTimer = 0L;
                needs.starvingHealsLeft = VillagerStats.STARVING_HEALS;
            }
            HungerSystem rules = needs.hungerRules();
            float rate = phase == Phase.NIGHT ? rules.hungerPerTickAsleep()
                    : rules.hungerPerTickAwake() + walk
                            + (phase == Phase.WORK ? eater.exertion * AwayHunger.WORKING_SHARE : 0f);
            AwayHunger.Drain drain = AwayHunger.drain(needs.hunger, rate * AwayHunger.HUNGER_PACE, ticks,
                    HungerSystem.LOW_HUNGER_WELLBEING_THRESHOLD);
            needs.hunger = drain.hunger();
            needs.clamp();
            mood(eater, phase, ticks, drain.belowTicks(), calendarDay);
            if (drain.zeroTicks() > 0L && starve(eater, drain.zeroTicks(), result)) alive.remove(i);
        }
    }

    private static void mood(AwayEater eater, Phase phase, long ticks, long hungryTicks, long calendarDay) {
        VillagerStats needs = eater.needs;
        switch (phase) {
            case WORK -> needs.addWellbeing(eater.works
                    ? -eater.workDrain * ticks * AwayHunger.WORKING_SHARE
                    : WellbeingSystem.WANDER_GAIN * ticks);
            case EVENING -> needs.addWellbeing(WellbeingSystem.WANDER_GAIN * ticks);
            case NIGHT -> {
                if (!needs.sleepRolledTonight()) needs.startNightSleep(sleepRoll(eater, calendarDay), eater.hasBed);
                needs.sleepFor(ticks);
            }
        }
        if (phase != Phase.NIGHT) needs.addWellbeing(-WellbeingSystem.HUNGER_PENALTY * hungryTicks);
    }

    /** Tonight's sleep gain, rolled as live (40 to 55), the same for a villager and night every time. */
    private static float sleepRoll(AwayEater eater, long calendarDay) {
        return WellbeingSystem.SLEEP_GAIN_MIN + (float) EveningSchedule.sharedFraction(eater.villager.id,
                calendarDay, SLEEP_ROLL_SALT) * (WellbeingSystem.SLEEP_GAIN_MAX - WellbeingSystem.SLEEP_GAIN_MIN);
    }

    /** Starving hits for {@code zeroTicks} at hunger 0, at the away pace; true when it died of them. */
    private static boolean starve(AwayEater eater, long zeroTicks, DayResult result) {
        AwayVillager villager = eater.villager;
        villager.wentHungry = true;
        AwayHunger.Starve starve = AwayHunger.starve(villager.starveTimer, zeroTicks,
                HealthSystem.awayStarveDamageIntervalTicks(eater.npc));
        villager.starveTimer = starve.timer();
        float floor = HealthSystem.starvationHealthFloor(eater.npc);
        float hit = Math.min(starve.hits(), Math.max(0f, eater.health - floor));
        if (hit <= 0f) return false;
        eater.health -= hit;
        result.hits += hit;
        if (eater.health > 0f) return false;
        villager.markStarved();
        result.died++;
        return true;
    }

    /** A working cleric who is fed enough to work heals the starving who are nearly dead. */
    private static void clericHeals(List<AwayEater> alive, DayResult result) {
        boolean cleric = false;
        for (AwayEater eater : alive) {
            if (eater.villager.profession() == Profession.CLERIC && eater.works
                    && eater.needs.hungerRules().canRoutineWork(eater.needs)) {
                cleric = true;
                break;
            }
        }
        if (!cleric) return;
        for (AwayEater eater : alive) {
            VillagerStats needs = eater.needs;
            if (needs.hunger > 0f || eater.health > CLERIC_HEAL_BELOW || needs.starvingHealsLeft <= 0) continue;
            eater.health = Math.max(eater.health, eater.npc.getMaxHealth() / 2f);
            needs.starvingHealsLeft--;
            result.heals++;
        }
    }

    /** The day number changes (as {@code handleDayRollover}): the dawn heal, last night and evening forgotten. */
    private static void newDay(List<AwayEater> alive) {
        for (AwayEater eater : alive) {
            VillagerStats needs = eater.needs;
            // As HealthSystem.onNightEnded: no heal for a villager at hunger 0.
            if (needs.hunger > 0f) eater.health = eater.npc.getMaxHealth();
            needs.clearNightSleep();
            needs.clearEveningBonus();
        }
    }
}

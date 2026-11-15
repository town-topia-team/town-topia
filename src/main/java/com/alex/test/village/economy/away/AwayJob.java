package com.alex.test.village.economy.away;

import com.alex.test.village.economy.away.jobs.BlacksmithAway;
import com.alex.test.village.economy.away.jobs.ButcherAway;
import com.alex.test.village.economy.away.jobs.CookAway;
import com.alex.test.village.economy.away.jobs.EatOnlyAway;
import com.alex.test.village.economy.away.jobs.EnchanterAway;
import com.alex.test.village.economy.away.jobs.FarmerAway;
import com.alex.test.village.economy.away.jobs.LumberjackAway;
import com.alex.test.village.economy.away.jobs.MinerAway;
import com.alex.test.village.economy.away.jobs.RancherAway;
import com.alex.test.village.economy.away.jobs.TeacherAway;

import javax.annotation.Nullable;

/**
 * One profession's work for one away day. Every job file starts with a short
 * list of the live rules it copies and where they live.
 *
 * <p>A step runs with the book open and the villager's copy in its body
 * ({@link AwayRun}); a job reads real blocks only in loaded chunks and puts
 * world changes on {@link AwayContext#world}, never changes them itself.
 */
public interface AwayJob {

    /**
     * The checks before the day's work: null when it may work, else the first
     * reason in plain words ("no kitchen", "no seeds", "storage full"), for the
     * preview and the log.
     */
    @Nullable
    String cannotWork(AwayContext ctx);

    /** The day's work, after {@link #cannotWork} said yes. */
    void workDay(AwayContext ctx);

    /**
     * Whether the job reads the other villagers' packs (the smith and the enchanter
     * count every worker's tools): then every copy is swapped in for its step, not only its own.
     */
    default boolean seesEveryCopy() {
        return false;
    }

    static AwayJob of(AwayJobs.Kind kind) {
        return switch (kind) {
            case EAT_ONLY -> EatOnlyAway.INSTANCE;
            case FARMER -> FarmerAway.INSTANCE;
            case COOK -> CookAway.INSTANCE;
            case RANCHER -> RancherAway.INSTANCE;
            case BUTCHER -> ButcherAway.INSTANCE;
            case LUMBERJACK -> LumberjackAway.INSTANCE;
            case MINER -> MinerAway.INSTANCE;
            case BLACKSMITH -> BlacksmithAway.INSTANCE;
            case ENCHANTER -> EnchanterAway.INSTANCE;
            case TEACHER -> TeacherAway.INSTANCE;
        };
    }
}

package com.alex.test.village.economy.away.jobs;

import com.alex.test.village.building.Building;
import com.alex.test.village.building.ForgeBuilding;
import com.alex.test.village.economy.AwayClock;
import com.alex.test.village.economy.away.AwayContext;
import com.alex.test.village.economy.away.AwayJob;
import com.alex.test.village.economy.away.AwayRun;
import com.alex.test.village.economy.away.AwayXp;
import com.alex.test.village.villagers.blacksmith.behavior.BlacksmithActivity;
import com.alex.test.village.villagers.blacksmith.settings.BlacksmithSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import javax.annotation.Nullable;

/**
 * A blacksmith's away day (round 9 of the plan): the live planner and its pay
 * ({@code BlacksmithActivity.workOffline}) once a day, on the book, after the
 * day's producers, ranchers, butchers and cooks, so day 1's tools are in
 * storage for the others on day 2 (old bug S6).
 *
 * <p>Live rules it copies, and where they live:
 * <ul>
 *   <li>Checks: work on ({@code BlacksmithSettingsSchema.WORK_ENABLED}); a storage room; a Forge.</li>
 *   <li>Plan: {@code BlacksmithActivity.Planner} as live (Stock tab rows, missing and worn tools, shears,
 *       buckets, shields, armour, upgrades, spares), reading every villager's away copy
 *       ({@link #seesEveryCopy}), with no melt-down jobs (old bug S1).</li>
 *   <li>Pay: the pack, then the chests the smith can reach ({@code isChestUnreachable}, as the enchanter),
 *       above the Stock tab's holds; counted before anything is taken and every part checked, a short
 *       one put back (S3, S4); raw iron smelted against coal, charcoal, then planks and logs (S2).</li>
 *   <li>Time: the day's work hours at {@code AwayClock.AWAY_PACE}; a job is the live craft time plus its
 *       raw iron's smelt time (a blast furnace halves it), and a trip to storage per queue and per
 *       furnace load ({@link AwayWalk#TRIP_TICKS}).</li>
 *   <li>With nothing left to forge: the kitchen's raw gold, then the spare ore with "Smelting" on (S5).</li>
 *   <li>XP: forge points per job and load ({@link AwayXp#smithPoints}, on the day's real number), so a
 *       level-up counts for the next job (S7); a session's intelligence per queue, one more for smelting.</li>
 * </ul>
 * Not done away: melting old tools down, fitting the workers' enchanted books, the kitchen's gold riding
 * along with a load of iron while there is still forging to do.
 */
public final class BlacksmithAway implements AwayJob {

    public static final BlacksmithAway INSTANCE = new BlacksmithAway();

    private static final Logger LOGGER = LogUtils.getLogger();

    private BlacksmithAway() {}

    @Override
    @Nullable
    public String cannotWork(AwayContext ctx) {
        ProfessionSettings settings = ProfessionSettings.of(ctx.npc(), BlacksmithSettingsSchema.SCHEMA);
        if (!settings.getBool(BlacksmithSettingsSchema.WORK_ENABLED)) return "work off";
        if (!ctx.hasStorage()) return "no storage room";
        for (Building building : ctx.mgr.getBuildings()) {
            if (building instanceof ForgeBuilding forge && forge.isValid()) return null;
        }
        return "no Forge";
    }

    /** The planner counts every worker's tools: their away copies, not what they carried when you left. */
    @Override
    public boolean seesEveryCopy() {
        return true;
    }

    @Override
    public void workDay(AwayContext ctx) {
        long hours = Math.round(ctx.day.workTicks() * AwayClock.AWAY_PACE);
        int levelBefore = ctx.model().brain().blacksmithSkillLevel();
        BlacksmithActivity.OfflineWork work = BlacksmithActivity.workOffline(ctx.activity(), hours,
                new BlacksmithActivity.AwayHooks(points -> AwayXp.smithPoints(ctx, points), AwayWalk.TRIP_TICKS,
                        ctx.orders()));
        work.made().forEach(ctx::made);
        work.used().forEach(ctx::used);
        AwayXp.workSessions(ctx, work.sessions() + (work.smelted() > 0 ? 1 : 0));
        ctx.stoppedPartway(work.stop());
        LOGGER.info("[AwayBlacksmith] {} day {}: forged {} item(s), smelted {} ingot(s) on their own, {} queue(s); "
                        + "{} forge point(s), level {} -> {}; {} tick(s) used of {}; made {}; used {}{}",
                ctx.npc().getNpcId().toString().substring(0, 8), ctx.day.index() + 1, work.jobs(), work.smelted(),
                work.sessions(), String.format("%.1f", work.points()), levelBefore,
                ctx.model().brain().blacksmithSkillLevel(), work.ticks(), hours, AwayRun.counts(work.made()),
                AwayRun.counts(work.used()), work.stop() == null ? "" : "; stopped: " + work.stop());
    }
}

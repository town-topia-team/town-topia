package com.alex.test.village.economy.away;

import com.alex.test.testkit.TestKit;
import com.alex.test.village.VillageDays;
import com.alex.test.village.VillageManager;
import com.alex.test.village.brain.EveningSchedule;
import com.alex.test.village.economy.CatchUp;
import com.alex.test.village.economy.ReconciliationSummary;
import com.alex.test.village.economy.away.jobs.EatOnlyAway;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The away calculator (the only one since round 12 of the rebuild): one village's catch-up,
 * day by day, on copies.
 *
 * <p>READ (one tick): the village book ({@link AwayBook}) and a copy of every
 * villager with a body ({@link AwayVillager}); the counted time cut into days
 * ({@link AwayDays}). DAYS (3 ms a tick): one step is one
 * villager's job for one day ({@link AwayJob}), with the book open and its
 * copy swapped into the body; nothing real changes. WRITE (one tick,
 * {@link AwayWrite}): chests, villagers and the world get the differences,
 * then the days not counted are skipped.
 *
 * <p>Saved while it runs: only the numbers it was started with. A server
 * stopped before WRITE starts again from READ; nothing was applied, so nothing
 * is paid twice.
 *
 * <p>An error in one villager's job stops only that villager's work for the
 * rest of the run ({@link #BROKE}, it still eats); an error in a day's meals
 * skips that day's meals. Either is logged and noted; the rest goes on.
 *
 * <p>Each day: the fields grow through the work hours ({@link AwayFarm}, round
 * 3), the villagers take their turns, the rest of the day grows, then the whole
 * village's meals, hunger, health and mood ({@link AwayHealth}, round 2). The
 * farmer works ({@code jobs/FarmerAway}), the cook ({@code jobs/CookAway},
 * in the shared {@link AwayKitchen}, round 4) and the rancher
 * ({@code jobs/RancherAway}, in the shared {@link AwayPens}, round 5: babies
 * grow at each day's end), then the butcher culls the spare ones from the
 * same pens ({@code jobs/ButcherAway}, round 6). The lumberjacks fell the
 * shared trees ({@code jobs/LumberjackAway}, in {@link AwayTrees}, round 7)
 * and the miners dig the shared mines ({@code jobs/MinerAway}, in
 * {@link AwayMines}, round 8). The smith forges what is short
 * ({@code jobs/BlacksmithAway}, round 9) and the enchanter writes books
 * ({@code jobs/EnchanterAway}, round 10), each with every copy swapped in, so
 * their plans see the workers' away tools; the teacher teaches the whole school
 * days away ({@code jobs/TeacherAway}, round 11); every other job only eats and
 * sleeps ({@link EatOnlyAway}, each with its reason).
 */
public final class AwayRun implements CatchUp {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String TAG_AWAY = "away";
    private static final String TAG_COUNTED = "counted";
    private static final String TAG_VILLAGE_ELAPSED = "villageElapsed";
    private static final String TAG_START = "startTime";
    private static final String TAG_WATCHED = "watched";
    private static final String TAG_OWNER_OFFLINE = "ownerOffline";

    enum Phase { READ, DAYS, WRITE, DONE }

    final UUID villageId;
    private final long awayTicks;
    private final long countedTicks;
    /** How far the village clock ran while away, for children. */
    private final long villageElapsedTicks;
    /** Village time ({@link VillageDays#villageTime}) the village fell asleep at: the days start there. */
    private final long startVillageTime;
    /** A preview: READ and DAYS only, never WRITE. */
    private final boolean preview;
    /** Time away a player outside the border watched its fields or pens grow live: already left out of {@link #countedTicks}. */
    private long watchedTicks;
    /** Time away its owner and every Officer were off the server (owner offline, 10-25): already left out of {@link #countedTicks}. */
    private long ownerOfflineTicks;

    private Phase phase = Phase.READ;
    @Nullable private AwayBook book;
    /** The fields, read at READ (round 3). */
    @Nullable private AwayFarm farm;
    /** The kitchen, read at READ (round 4). */
    @Nullable private AwayKitchen kitchen;
    /** The pens, read at READ (round 5). */
    @Nullable private AwayPens pens;
    /** The trees, read at READ (round 7). */
    @Nullable private AwayTrees trees;
    /** The mines, read at READ (round 8). */
    @Nullable private AwayMines mines;
    /** The Stock tab orders forged away, told at WRITE (round 9). */
    private AwayOrders orders = new AwayOrders();
    /** The day's work hours have grown (once per day, before its first turn). */
    private boolean dayBegun;
    private final List<AwayVillager> villagers = new ArrayList<>();
    private final AwayWorld world = new AwayWorld();
    private List<AwayDays.Day> days = List.of();
    private int dayIndex;
    private int villagerIndex;
    private int noBody;
    /** Why villagers did not work today, counted, for the day line. */
    private final Map<String, Integer> stoppedToday = new TreeMap<>();
    /** The same over every day, for the preview. */
    final Map<String, Integer> stopped = new TreeMap<>();
    /** A villager that did not work a day, or stopped before its hours were up, and why: for the summary. */
    private record Stop(UUID villager, String job, String reason, boolean partway) {}
    /**
     * Per villager and reason (leftovers round 3): the work time it missed, in ticks; for a
     * stop part way through its hours, the days that happened on.
     */
    private final Map<Stop, Long> stops = new LinkedHashMap<>();
    /** Jobs that make nothing away, by reason: the villagers, one summary line each. */
    private final Map<String, Set<UUID>> eatOnly = new TreeMap<>();
    /** At most this many villager lines in the summary; the rest are counted. */
    private static final int STOP_LINES = 20;

    // What the run did, for the summary and the welcome-back lines.
    final Map<Item, Integer> produced = new LinkedHashMap<>();
    final Map<Item, Integer> consumed = new LinkedHashMap<>();
    final List<String> notes = new ArrayList<>();
    private final Set<UUID> notLoaded = new LinkedHashSet<>();
    int mealsEaten;
    float hungerRestored;
    int starvationHits;
    int died;
    /** Alive and at hunger 0 at the latest day's end. */
    private int starvingAtEnd;
    private boolean uncountedSkipped;

    /** Why a villager that starved to death on an earlier day does nothing more. */
    static final String STARVED = "starved to death";
    /** Why a villager whose away job threw on an earlier day does nothing more. */
    static final String BROKE = "job broke";
    /** Villagers whose away job threw (a bug of ours): no more work this run, they still eat. */
    private final Set<UUID> broken = new LinkedHashSet<>();
    /** A job, a day's meals or a write-back part threw: the welcome-back says some work was not counted. */
    boolean broke;

    public AwayRun(UUID villageId, long awayTicks, long countedTicks, long villageElapsedTicks,
                   long startVillageTime) {
        this(villageId, awayTicks, countedTicks, villageElapsedTicks, startVillageTime, false);
    }

    private AwayRun(UUID villageId, long awayTicks, long countedTicks, long villageElapsedTicks,
                    long startVillageTime, boolean preview) {
        this.villageId = villageId;
        this.awayTicks = Math.max(0L, awayTicks);
        this.countedTicks = Math.max(0L, countedTicks);
        this.villageElapsedTicks = Math.max(0L, villageElapsedTicks);
        this.startVillageTime = startVillageTime;
        this.preview = preview;
    }

    /** Notes the time watched from outside (away leftovers round 8), for the log and the summary; counts nothing. */
    public AwayRun watchedFromOutside(long ticks) {
        watchedTicks = Math.max(0L, ticks);
        return this;
    }

    /** Notes the time its owner and Officers were offline (owner offline, 10-25), for the log and the summary; counts nothing. */
    public AwayRun ownerOffline(long ticks) {
        ownerOfflineTicks = Math.max(0L, ticks);
        return this;
    }

    /**
     * {@code /vtest away preview}: READ and every DAYS step at once, from now,
     * for {@code dayCount} days. Nothing real changes; the copies are dropped.
     */
    static AwayRun previewAll(ServerLevel level, VillageManager mgr, int dayCount) {
        long counted = dayCount * EveningSchedule.DAY_TICKS;
        AwayRun run = new AwayRun(mgr.getVillageId(), counted, counted, counted,
                VillageDays.villageTime(level.getDayTime()), true);
        run.read(level, mgr);
        while (run.phase == Phase.DAYS) {
            if (!run.dayStep(level, mgr)) run.phase = Phase.DONE;
        }
        return run;
    }

    // =========================================================================
    // Steps
    // =========================================================================

    @Override
    public boolean step(ServerLevel level, VillageManager mgr, long deadlineNanos) {
        do {
            if (!stepOnce(level, mgr)) return true;
        } while (System.nanoTime() < deadlineNanos);
        return false;
    }

    /** One step; false once there is nothing left to do. */
    private boolean stepOnce(ServerLevel level, VillageManager mgr) {
        switch (phase) {
            case READ -> {
                read(level, mgr);
                return true;
            }
            case DAYS -> {
                if (!dayStep(level, mgr)) phase = Phase.WRITE;
                return true;
            }
            case WRITE -> {
                AwayWrite.apply(level, mgr, this);
                phase = Phase.DONE;
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    private void read(ServerLevel level, VillageManager mgr) {
        // Test-only fault injection (mctest village_dormancy_fault_recovery): exercises
        // VillageDormancy's catch-and-recover path on demand.
        if (!preview && TestKit.consumeForcedReconciliationThrow(villageId)) {
            throw new RuntimeException("TestKit: forced reconciliation failure for village " + villageId);
        }
        book = AwayBook.read(level, mgr);
        for (UUID id : mgr.getVillagerIds()) {
            AwayVillager villager = AwayVillager.read(id, mgr.getModel(id));
            if (villager == null) {
                noBody++;
                notes.add("Villager " + shortId(id) + " had no live body when the catch-up started; skipped.");
                continue;
            }
            villagers.add(villager);
        }
        villagers.sort(Comparator.comparingInt(villager -> AwayJobs.order(villager.profession())));
        farm = AwayFarm.read(level, mgr, villagers);
        kitchen = AwayKitchen.read(mgr);
        // Game time the village fell asleep at (a preview's "away" starts now): the pens' timers count from there.
        long sleptAt = preview ? level.getGameTime() : level.getGameTime() - awayTicks;
        pens = AwayPens.read(level, mgr, sleptAt, startVillageTime, countedTicks);
        trees = AwayTrees.read(level, mgr, villagers);
        mines = AwayMines.read(level, mgr);
        orders = new AwayOrders();
        days =AwayDays.cut(startVillageTime, countedTicks, EveningSchedule.DAY_TICKS, EveningSchedule.WAKE_TIME,
                EveningSchedule.MEAN_END_WORK, EveningSchedule.PROJECTED_BEDTIME);
        phase = !days.isEmpty() ? Phase.DAYS : preview ? Phase.DONE : Phase.WRITE;
        LOGGER.info("[AwayRun] village {} READ{}: {} villager(s) ({} with no body), {} container(s) in {} room(s) "
                        + "holding {} item(s), {} day(s) over {} counted of {} tick(s) away",
                shortId(villageId), preview ? " (preview)" : "", villagers.size(), noBody, book.containers(),
                book.rooms(), book.items(), days.size(), countedTicks, awayTicks);
        if (ownerOfflineTicks > 0L) {
            // Owner offline (10-25): the village rests while nobody it belongs to is on the server.
            String offline = realTime(ownerOfflineTicks);
            LOGGER.info("[AwayRun] village {} owner offline for {} ({} tick(s)): its owner and every Officer were off "
                    + "the server then, so that time is not counted", shortId(villageId), offline, ownerOfflineTicks);
            notes.add(offline + " with the owner and every Officer offline, not counted.");
        }
        if (watchedTicks > 0L) {
            // Leftovers round 8: the game grew them live then; the catch-up would grow them twice.
            String watched = realTime(watchedTicks);
            LOGGER.info("[AwayRun] village {} watched from outside the border for {} ({} tick(s)): its fields or "
                    + "pens grew live then, so that time is not counted", shortId(villageId), watched, watchedTicks);
            notes.add(watched + " watched from outside the border (fields or pens grew live), not counted.");
        }
        LOGGER.info("[AwayRun] village {} fields: {}", shortId(villageId), farm.describe());
        LOGGER.info("[AwayRun] village {} pens: {}", shortId(villageId), pens.describe());
        LOGGER.info("[AwayRun] village {} trees: {}", shortId(villageId), trees.describe());
        LOGGER.info("[AwayRun] village {} mines: {}", shortId(villageId), mines.describe());
    }

    /**
     * One villager's day, or the end of a day; false once every day is done.
     * Villagers that starved to death on an earlier day are out of the
     * village's member list meanwhile ({@link VillageManager#setAwayGone}).
     */
    private boolean dayStep(ServerLevel level, VillageManager mgr) {
        mgr.setAwayGone(starvedIds());
        try {
            return dayStepOnce(level, mgr);
        } finally {
            mgr.setAwayGone(Set.of());
        }
    }

    /** The villagers that starved to death so far this run; empty (no copy) when none. */
    private Set<UUID> starvedIds() {
        Set<UUID> gone = null;
        for (AwayVillager villager : villagers) {
            if (!villager.starved()) continue;
            if (gone == null) gone = new HashSet<>();
            gone.add(villager.id);
        }
        return gone == null ? Set.of() : gone;
    }

    private boolean dayStepOnce(ServerLevel level, VillageManager mgr) {
        if (dayIndex >= days.size()) return false;
        AwayDays.Day day = days.get(dayIndex);
        if (!dayBegun) {
            dayBegun = true;
            farm.startDay(day);
            kitchen.startDay(day);
            pens.startDay(day);
        }
        if (villagerIndex < villagers.size()) {
            workOne(level, mgr, villagers.get(villagerIndex++), day);
            return true;
        }
        farm.endDay(day);
        pens.endDay(day);
        endDay(level, mgr, day);
        dayIndex++;
        villagerIndex = 0;
        dayBegun = false;
        return dayIndex < days.size();
    }

    /** One villager's job for one day, on its copy with the book open; the real state is back after. */
    private void workOne(ServerLevel level, VillageManager mgr, AwayVillager villager, AwayDays.Day day) {
        if (villager.starved()) {
            stoppedToday.merge(STARVED, 1, Integer::sum);
            stopped.merge(STARVED, 1, Integer::sum);
            return;
        }
        if (broken.contains(villager.id)) {
            stoppedToday.merge(BROKE, 1, Integer::sum);
            stopped.merge(BROKE, 1, Integer::sum);
            return;
        }
        AwayJob job = AwayJob.of(AwayJobs.kind(villager.profession()));
        AwayContext ctx = new AwayContext(level, mgr, villager, world, day, this);
        String reason;
        List<AwayVillager> others = new ArrayList<>();
        book.open();
        try {
            if (!villager.swapIn()) {
                reason = "no body";
            } else {
                try {
                    if (job.seesEveryCopy()) {
                        for (AwayVillager other : villagers) {
                            if (other != villager && !other.starved() && other.swapIn()) others.add(other);
                        }
                    }
                    reason = job.cannotWork(ctx);
                    if (reason == null) job.workDay(ctx);
                } catch (RuntimeException e) {
                    reason = jobBroke(villager, day, e);
                } finally {
                    for (AwayVillager other : others) other.swapOut();
                    villager.swapOut();
                }
            }
        } finally {
            book.close();
        }
        if (reason != null) {
            stoppedToday.merge(reason, 1, Integer::sum);
            stopped.merge(reason, 1, Integer::sum);
        }
        // For the summary (a broken job has its own note).
        if (job == EatOnlyAway.INSTANCE && reason != null) {
            eatOnly.computeIfAbsent(reason, why -> new LinkedHashSet<>()).add(villager.id);
        } else if (reason != null && !reason.equals(BROKE)) {
            // A part of the time away with no work hours in it (a night) has no work to miss.
            if (day.workTicks() > 0L) {
                stops.merge(new Stop(villager.id, villager.profession().displayName(), reason, false),
                        day.workTicks(), Long::sum);
            }
        } else if (reason == null && ctx.partwayStop != null) {
            stops.merge(new Stop(villager.id, villager.profession().displayName(), ctx.partwayStop, true), 1L,
                    Long::sum);
        }
        boolean quiet = job == EatOnlyAway.INSTANCE && ctx.madeToday.isEmpty() && ctx.usedToday.isEmpty();
        if (!quiet) {
            LOGGER.info("[AwayDay] village {} day {} villager {} ({}): made {}; used {}; {}",
                    shortId(villageId), day.index() + 1, shortId(villager.id), villager.profession().displayName(),
                    counts(ctx.madeToday), counts(ctx.usedToday), reason == null ? "worked" : "stopped: " + reason);
        }
    }

    /**
     * A job that threw: logged once with its trace and noted in the summary;
     * the villager does no more work this run but still eats, and everyone else
     * goes on. Its copy and the book keep what the step did before the throw.
     */
    private String jobBroke(AwayVillager villager, AwayDays.Day day, RuntimeException e) {
        broken.add(villager.id);
        broke = true;
        LOGGER.error("[AwayRun] village {} day {}: villager {} ({}) job broke; it does no more work this catch-up, "
                        + "the rest go on", shortId(villageId), day.index() + 1, shortId(villager.id),
                villager.profession().displayName(), e);
        notes.add("Villager " + shortId(villager.id) + " (" + villager.profession().displayName()
                + ") job broke on away day " + (day.index() + 1) + " (" + e + "); no more work this catch-up.");
        return BROKE;
    }

    /**
     * The day's end: the whole day's meals, hunger, health and mood for every
     * villager still alive at once ({@link AwayHealth}), with the book open and
     * every copy swapped in; the real state is back after.
     */
    private void endDay(ServerLevel level, VillageManager mgr, AwayDays.Day day) {
        List<AwayVillager> swapped = new ArrayList<>();
        AwayHealth.DayResult result = null;
        book.open();
        try {
            List<AwayEater> eaters = new ArrayList<>();
            for (AwayVillager villager : villagers) {
                if (villager.starved() || !villager.swapIn()) continue;
                swapped.add(villager);
                eaters.add(new AwayEater(villager, mgr));
            }
            result = AwayHealth.live(level, mgr, book, day, eaters);
            kitchen.afterMeals(day, result.meals.used, swapped);
        } catch (RuntimeException e) {
            // A bug of ours: this day's meals, hunger and health are skipped (any
            // part done before the throw stays), the other days go on.
            broke = true;
            LOGGER.error("[AwayRun] village {} day {}: the day's meals broke; skipped, the other days go on",
                    shortId(villageId), day.index() + 1, e);
            notes.add("Away day " + (day.index() + 1) + " meals broke (" + e + "); skipped.");
        } finally {
            for (AwayVillager villager : swapped) villager.swapOut();
            book.close();
        }
        if (result == null) {
            stoppedToday.clear();
            return;
        }
        AwayMeals.Tally meals = result.meals;
        mealsEaten += meals.servings();
        hungerRestored += meals.hungerRestored;
        starvationHits += Math.round(result.hits);
        died += result.died;
        starvingAtEnd = result.starving;
        meals.used.forEach((item, count) -> consumed.merge(item, count, Integer::sum));
        // Loaves baked for the hungry: their wheat is in used, so the bread goes in made.
        if (meals.baked > 0) produced.merge(Items.BREAD, meals.baked, Integer::sum);
        if (result.died > 0) {
            notes.add(result.died + " villager(s) starved to death on away day " + (day.index() + 1) + ".");
        }
        LOGGER.info("[AwayDay] village {} day {}/{} (calendar day {}; {} work, {} evening, {} night tick(s)): "
                        + "{} villager(s), stopped: {}; meals: {} serving(s) ({} from packs, {} from storage), "
                        + "{} loaf/loaves baked, +{} hunger; hungry {}, starving {}, starving hits {}, cleric heals {}, "
                        + "died {}; food left in storage {}",
                shortId(villageId), day.index() + 1, days.size(), day.calendarDay(), day.workTicks(),
                day.eveningTicks(), day.nightTicks(), villagers.size(),
                stoppedToday.isEmpty() ? "none" : stoppedToday, meals.servings(), meals.fromPacks, meals.fromStorage,
                meals.baked, Math.round(meals.hungerRestored), result.hungry, result.starving,
                Math.round(result.hits), result.heals, result.died, book.food());
        stoppedToday.clear();
    }

    // =========================================================================
    // For AwayWrite and the preview
    // =========================================================================

    AwayBook book() { return book; }

    AwayFarm farm() { return farm; }

    AwayKitchen kitchen() { return kitchen; }

    AwayPens pens() { return pens; }

    AwayTrees trees() { return trees; }

    AwayMines mines() { return mines; }

    AwayOrders orders() { return orders; }

    List<AwayVillager> villagers() { return villagers; }

    AwayWorld world() { return world; }

    List<AwayDays.Day> days() { return days; }

    int noBody() { return noBody; }

    // =========================================================================
    // CatchUp
    // =========================================================================

    @Override public long awayTicks() { return awayTicks; }
    @Override public long countedTicks() { return countedTicks; }
    @Override public long uncountedTicks() { return Math.max(0L, villageElapsedTicks - countedTicks); }
    @Override public Set<UUID> notLoaded() { return notLoaded; }
    @Override public Map<Item, Integer> made() { return net(produced, consumed); }
    /** The last day's end: alive and at hunger 0 (the dead left out, round 12). */
    @Override public int starvingNow() { return starvingAtEnd; }
    @Override public boolean somethingBroke() { return broke; }

    /** Villagers who were at hunger 0 at some time while away, the dead too: the summary and the preview. */
    int wentHungry() {
        int hungry = 0;
        for (AwayVillager villager : villagers) {
            if (villager.wentHungry) hungry++;
        }
        return hungry;
    }

    /** What rose, item by item: made less used ({@link CatchUp#made}). */
    static Map<Item, Integer> net(Map<Item, Integer> made, Map<Item, Integer> used) {
        Map<Item, Integer> net = new LinkedHashMap<>();
        made.forEach((item, count) -> {
            int rise = count - used.getOrDefault(item, 0);
            if (rise > 0) net.put(item, rise);
        });
        return net;
    }

    /** Whether the counted days held any night: the villagers slept through it here (round 12). */
    boolean livedANight() {
        for (AwayDays.Day day : days) {
            if (day.nightTicks() > 0L) return true;
        }
        return false;
    }

    @Override
    public boolean claimUncountedSkip() {
        if (uncountedSkipped) return false;
        uncountedSkipped = true;
        return true;
    }

    @Override
    public ReconciliationSummary summary() {
        List<String> all = new ArrayList<>(notes);
        all.addAll(stopLines());
        return new ReconciliationSummary(countedTicks, villagers.size(), hungerRestored, mealsEaten, starvationHits,
                byRegistryKey(produced), byRegistryKey(consumed), 0, List.copyOf(all), awayTicks, wentHungry());
    }

    /**
     * Why villagers did not work or stopped early, one line per villager and
     * reason ("Miner 1a2b3c4d did not work 3 day(s): no mine"), at most
     * {@link #STOP_LINES}; then one line per job that makes nothing away.
     * The days not worked are work time, so a 3-day absence that starts and
     * ends mid-shift says 3 days, not the 4 days it touched.
     */
    private List<String> stopLines() {
        List<String> lines = new ArrayList<>();
        int more = 0;
        for (Map.Entry<Stop, Long> entry : stops.entrySet()) {
            if (lines.size() >= STOP_LINES) {
                more++;
                continue;
            }
            Stop stop = entry.getKey();
            lines.add(stop.job() + " " + shortId(stop.villager())
                    + (stop.partway() ? " stopped early on " + entry.getValue() + " day(s): "
                            : " did not work " + workDays(entry.getValue()) + ": ")
                    + stop.reason());
        }
        if (more > 0) lines.add("... and " + more + " more (see the [AwayDay] and job log lines).");
        eatOnly.forEach((why, ids) -> lines.add(ids.size() + " villager(s), " + why));
        return lines;
    }

    /** Missed work time in whole work days, to the half day ("3 day(s)", "2.5 day(s)"). */
    static String workDays(long workTicks) {
        long fullDay = Math.floorMod(EveningSchedule.MEAN_END_WORK - EveningSchedule.WAKE_TIME, EveningSchedule.DAY_TICKS);
        long halves = fullDay > 0L ? Math.round(workTicks * 2.0 / fullDay) : 0L;
        if (halves <= 0L) return "part of a day";
        return (halves % 2 == 0 ? Long.toString(halves / 2) : halves / 2 + ".5") + " day(s)";
    }

    /** Only the numbers it started with: a restart begins again at READ. */
    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong(TAG_AWAY, awayTicks);
        tag.putLong(TAG_COUNTED, countedTicks);
        tag.putLong(TAG_VILLAGE_ELAPSED, villageElapsedTicks);
        tag.putLong(TAG_START, startVillageTime);
        if (watchedTicks > 0L) tag.putLong(TAG_WATCHED, watchedTicks);
        if (ownerOfflineTicks > 0L) tag.putLong(TAG_OWNER_OFFLINE, ownerOfflineTicks);
        return tag;
    }

    public static AwayRun load(UUID villageId, CompoundTag tag) {
        return new AwayRun(villageId, tag.getLong(TAG_AWAY), tag.getLong(TAG_COUNTED),
                tag.getLong(TAG_VILLAGE_ELAPSED), tag.getLong(TAG_START)).watchedFromOutside(tag.getLong(TAG_WATCHED))
                .ownerOffline(tag.getLong(TAG_OWNER_OFFLINE));
    }

    /** Real time: "40 s", "12 min", "3.5 h". */
    private static String realTime(long ticks) {
        long seconds = ticks / 20L;
        if (seconds < 60L) return seconds + " s";
        if (seconds < 3_600L) return Math.round(seconds / 60.0) + " min";
        return Math.round(seconds / 360.0) / 10.0 + " h";
    }

    private static Map<String, Integer> byRegistryKey(Map<Item, Integer> byItem) {
        Map<String, Integer> result = new TreeMap<>();
        byItem.forEach((item, count) -> result.put(BuiltInRegistries.ITEM.getKey(item).toString(), count));
        return result;
    }

    /** "12 minecraft:wheat, 3 minecraft:bone_meal", or "nothing". */
    public static String counts(Map<Item, Integer> byItem) {
        if (byItem.isEmpty()) return "nothing";
        StringBuilder text = new StringBuilder();
        byItem.forEach((item, count) -> {
            if (!text.isEmpty()) text.append(", ");
            text.append(count).append(' ').append(BuiltInRegistries.ITEM.getKey(item));
        });
        return text.toString();
    }

    static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}

package com.alex.test.village.brain;

import com.alex.test.village.VillagerModel;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavReachability;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.building.StorageContainers;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Base for activities that interact with a storage chest, with the full
 * Tektopia-style theatre: walk up to the chest, stand in front of it looking
 * at it, the lid swings open with a sound, a short busy pause, the actual item
 * transfer happens, then the lid closes.
 *
 * <p>Subclasses implement {@link #selectStorage} (which storage room to use)
 * and {@link #transact} (what to move once the chest is open).
 *
 * <p>Important: chest selection is now item-aware. A subclass can override
 * {@link #candidateChests} and {@link #isSelectedChestStillValid} so this base
 * class only walks to a chest that is already known to contain the wanted item.
 */
public abstract class ChestActivity extends Activity {

    private static final Logger LOGGER = LogUtils.getLogger();

    private enum Phase { WALK, BUSY }

    private static final int BUSY_TICKS_MIN = 20; // 1 s with the chest open
    private static final int BUSY_TICKS_RND = 10;

    /**
     * How many times this may give up on a chest and walk to another one.
     *
     * <p>Arriving to find the chest already emptied is ordinary in a village
     * where several villagers share one larder, so one or two retries is right.
     * Unbounded retries are not: a playthrough had two villagers spend an entire
     * in-game day inside `fetch_food`, 1200 of 1200 samples, walking 539 blocks
     * and producing nothing. A fetch that *fails* is rate-limited by the brain's
     * 600-tick cooldown; one that never finishes is not, so the loop has to be
     * bounded here.
     */
    private static final int MAX_CHEST_RETRIES = 3;

    /**
     * Ticks a walk may go without getting at least {@link #PROGRESS_STEP}
     * blocks closer to the chest before that chest is written off.
     *
     * <p>This, not a clock, is what ends a walk that is going nowhere. The old
     * flat 600-tick deadline failed long-but-valid walks across a big village
     * and let a villager pressed against a wall of a freshly built room burn
     * the full half minute before giving up — and then the brain read the
     * failure as "no food" and waited another 600 ticks.
     *
     * <p>Eight seconds, not five: a route out of a room whose door faces away
     * from the chest walks away from it first, and that detour is not a stall.
     */
    private static final int NO_PROGRESS_TICKS = 160;
    /** How much closer, in blocks, counts as progress. */
    private static final double PROGRESS_STEP = 1.0;

    /** Per-walk backstop: a base allowance plus this much per block of straight-line distance. */
    private static final int WALK_BASE_TICKS = 200;
    private static final int WALK_TICKS_PER_BLOCK = 20;
    private static final int WALK_MAX_TICKS = 1200;

    /**
     * Backstop for the whole trip across every chest it tries.
     *
     * <p>The progress check ends a stuck walk within seconds; this only bounds
     * a villager that keeps inching forward without ever arriving.
     */
    private static final int TRIP_MAX_TICKS = 2400;

    /**
     * Why the trip failed, so the brain can react to the cause instead of
     * treating every failure as an empty larder.
     */
    public enum Failure {
        NONE,
        /** No valid storage room holds what this trip wants. */
        NO_STORAGE,
        /** The storage room has no chest holding what this trip wants. */
        NO_CHEST,
        /** Chests hold it, but none could be walked to. */
        UNREACHABLE,
        /** Every chest walked to had been emptied by the time we arrived. */
        CHEST_EMPTIED,
        /** The chest opened but the transfer itself failed. */
        TRANSACTION,
        /** The villager can't walk anywhere from where it stands; the chests are fine. */
        STUCK,
        /** The trip had to bake, but another villager holds the table; a top-up does not queue. */
        TABLE_BUSY
    }

    protected StorageBuilding storage;
    protected BlockPos chestPos;
    private BlockPos walkTarget;

    private Phase phase = Phase.WALK;
    private int busyTimer;
    private boolean chestOpened = false;
    private boolean transacted  = false;
    private boolean success     = false;
    private int retries         = 0;
    private int elapsed         = 0;
    private Failure failure     = Failure.NONE;

    // Progress watchdog for the current walk; reset whenever a chest is chosen.
    private double bestDistance;
    /** Furthest waypoint of the current route reached, or -1 before there is a route. */
    private int bestWaypoint = -1;
    private int ticksWithoutProgress;
    private int walkElapsed;
    private int walkDeadline;

    /** Why this trip failed; {@link Failure#NONE} while running or after success. */
    public Failure failure() { return failure; }

    // -------------------------------------------------------------------------
    // Subclass contract
    // -------------------------------------------------------------------------

    /** Pick the storage building to visit, or {@code null} if none qualifies. */
    protected abstract StorageBuilding selectStorage(Context ctx);

    /** Move the items. Runs once while the chest is open. Returns success. */
    protected abstract boolean transact(Context ctx);

    /**
     * Return the chests this activity is allowed to walk to.
     *
     * <p>Default behaviour is still valid for deposits: any chest in the storage
     * room may be used. Withdrawal activities should override this and return
     * only chests whose cached/indexed contents match the wanted item.
     */
    protected List<BlockPos> candidateChests(Context ctx, StorageBuilding selectedStorage) {
        return new ArrayList<>(selectedStorage.getFunctionalBlocks());
    }

    /**
     * Direct final validation right before the lid opens.
     *
     * <p>Do not use a cached answer here. This catches the case where another
     * villager or the player emptied the chosen chest while this villager was
     * walking to it.
     */
    protected boolean isSelectedChestStillValid(Context ctx) {
        return true;
    }

    /** Human-readable target for debug logs. */
    protected String targetDebugName(Context ctx) {
        return "any storage chest";
    }

    // -------------------------------------------------------------------------

    @Override
    protected void start(Context ctx) {
        storage = selectStorage(ctx);
        if (storage == null) {
            LOGGER.debug("[{}] {} found no valid storage for {}.",
                    name(), shortId(ctx), targetDebugName(ctx));
            failure = Failure.NO_STORAGE;
            return;
        }

        if (!chooseChest(ctx, false)) return;

        busyTimer = BUSY_TICKS_MIN + ctx.npc().getRandom().nextInt(BUSY_TICKS_RND);
    }

    @Override
    protected Status tick(Context ctx) {
        if (storage == null || chestPos == null || walkTarget == null) {
            if (failure == Failure.NONE) failure = Failure.NO_CHEST;
            return Status.FAILED;
        }

        if (++elapsed > TRIP_MAX_TICKS) {
            LOGGER.debug("[{}] {} gave up after {} ticks and {} chest change(s).",
                    name(), shortId(ctx), elapsed, retries);
            closeChest(ctx.level());
            if (phase == Phase.WALK) ctx.brain().markChestUnreachable(chestPos, ctx.level().getGameTime());
            failure = Failure.UNREACHABLE;
            return Status.FAILED;
        }

        switch (phase) {
            case WALK -> {
                boolean arrived = moveTo(ctx, walkTarget, VillagerModel.WALK_SPEED);
                if (!arrived) {
                    // Still walking. Write the chest off if the walk has stopped
                    // getting anywhere, or has run past its own distance budget.
                    String stall = checkWalkProgress(ctx);
                    if (stall != null) return giveUpOnChest(ctx, stall);
                    return Status.RUNNING;
                }

                // Pathing ended short of the chest (no route, or stuck and
                // blacklisted). That chest is unreachable, not empty: try another.
                if (!isCloseToWalkTarget(ctx.npc().blockPosition(), walkTarget)) {
                    return giveUpOnChest(ctx, "villager stopped away from walk target " + walkTarget);
                }

                // Final no-cache check before the visual cue. If the chest was
                // emptied while we walked to it, pick another chest and walk again.
                if (!isSelectedChestStillValid(ctx)) {
                    if (++retries > MAX_CHEST_RETRIES) {
                        LOGGER.debug("[{}] {} walked to {} chests for {} and found "
                                        + "every one of them empty; giving up.",
                                name(), shortId(ctx), retries, targetDebugName(ctx));
                        failure = Failure.CHEST_EMPTIED;
                        return Status.FAILED;
                    }
                    LOGGER.debug("[{}] {} re-searching ({}/{}): chosen chest {} no "
                                    + "longer contains {}.",
                            name(), shortId(ctx), retries, MAX_CHEST_RETRIES,
                            chestPos, targetDebugName(ctx));
                    storage.invalidateContentIndex();
                    resetMove();
                    if (!chooseChest(ctx, true)) {
                        // Nothing left to walk to after the others were emptied.
                        if (failure == Failure.NO_CHEST) failure = Failure.CHEST_EMPTIED;
                        return Status.FAILED;
                    }
                    return Status.RUNNING;
                }

                openChest(ctx.level());
                phase = Phase.BUSY;
            }
            case BUSY -> {
                // Stand still, look at the chest while "using" it.
                ctx.npc().getLookControl().setLookAt(
                        chestPos.getX() + 0.5, chestPos.getY() + 0.5, chestPos.getZ() + 0.5,
                        30f, 30f);

                if (!transacted && busyTimer <= 10) {
                    // Transfer near the end of the pause so the lid is visibly open first.
                    transacted = true;
                    success = transact(ctx);
                }
                if (--busyTimer <= 0) {
                    closeChest(ctx.level());
                    if (!success) failure = Failure.TRANSACTION;
                    return success ? Status.DONE : Status.FAILED;
                }
            }
        }
        return Status.RUNNING;
    }

    /**
     * One tick of the walk watchdog.
     *
     * @return why the walk is being abandoned, or {@code null} to keep walking
     */
    private String checkWalkProgress(Context ctx) {
        double distance = Math.sqrt(ctx.npc().blockPosition().distSqr(walkTarget));
        boolean closer = distance <= bestDistance - PROGRESS_STEP;
        if (closer) bestDistance = distance;
        // Progress along the route counts too: another waypoint reached. The
        // way to a chest can lead well away from it first, and straight-line
        // distance alone read that as a stall. A miner deep in a tunnel that
        // runs toward the village walks ninety blocks away from storage to
        // get out of it; it gave up on every food chest halfway and starved.
        // A new route (a re-plan starts again from its first waypoint) is
        // only noted, not counted.
        int waypoint = ctx.model().moveWaypointIndex();
        boolean along = bestWaypoint >= 0 && waypoint > bestWaypoint;
        if (waypoint >= 0 && (along || bestWaypoint < 0 || waypoint < bestWaypoint)) {
            bestWaypoint = waypoint;
        }
        if (along) {
            // And the walk's own budget, worked out from straight-line
            // distance, stretches to fit a route that is longer than that.
            // The whole trip is still capped by TRIP_MAX_TICKS.
            walkDeadline = Math.max(walkDeadline, walkElapsed + WALK_BASE_TICKS);
        }
        if (closer || along) {
            ticksWithoutProgress = 0;
        } else if (++ticksWithoutProgress > NO_PROGRESS_TICKS) {
            return "no progress for " + NO_PROGRESS_TICKS + " ticks at "
                    + String.format("%.1f", distance) + " blocks out";
        }
        if (++walkElapsed > walkDeadline) {
            return "walk ran past its " + walkDeadline + "-tick budget";
        }
        return null;
    }

    /**
     * Writes the current chest off as unreachable, for this villager, for a
     * while, then tries the next candidate. An unreachable chest is not an
     * empty larder, and the brain must be told which of the two happened.
     */
    private Status giveUpOnChest(Context ctx, String why) {
        if (villagerIsStuck(ctx)) {
            // Nothing wrong with this chest: no route leaves the villager's
            // tile. Writing it off would only blame the chests one by one --
            // a miner stuck in its shaft wrote off all 46 in twenty seconds,
            // and still couldn't use them for three minutes once it was free.
            LOGGER.debug("[{}] {} gave up the trip to chest {} for {}: {}, and it cannot "
                            + "reach the storage door {} either; chests left alone.",
                    name(), shortId(ctx), chestPos, targetDebugName(ctx), why, storage.getDoorPos());
            ctx.model().stopMoving();
            resetMove();
            failure = Failure.STUCK;
            return Status.FAILED;
        }
        LOGGER.debug("[{}] {} abandoned chest {} for {}: {}.",
                name(), shortId(ctx), chestPos, targetDebugName(ctx), why);
        ctx.brain().markChestUnreachable(chestPos, ctx.level().getGameTime());
        ctx.brain().diary().log(String.format("%s %s  can't reach chest %s (%s)",
                VillagerDiary.ts(ctx.level()), name(), chestPos.toShortString(), why));
        ctx.model().stopMoving();
        resetMove();

        if (++retries > MAX_CHEST_RETRIES || !chooseChest(ctx, true)) {
            // Whatever made the last pick fail, this trip ended because the
            // chests it did find could not be reached.
            failure = Failure.UNREACHABLE;
            return Status.FAILED;
        }
        return Status.RUNNING;
    }

    /**
     * True when the walk failed because of where the villager stands, not
     * where the chest is: the graph has no route from its tile to the chest's
     * walk target, nor to the storage room's door.
     */
    private boolean villagerIsStuck(Context ctx) {
        NavGraph g = ctx.mgr().getGraph();
        if (g == null || storage == null || walkTarget == null) return false;
        BlockPos feet = NavGraph.feetCell(ctx.npc());
        return !NavReachability.canReach(g, feet, walkTarget)
                && !NavReachability.canReach(g, feet, storage.getDoorPos());
    }

    /**
     * Pick the nearest reachable chest from {@link #candidateChests} and store
     * both the chest block position and the adjacent walk target in this task.
     */
    private boolean chooseChest(Context ctx, boolean retry) {
        List<BlockPos> candidates = candidateChests(ctx, storage);
        if (candidates.isEmpty()) {
            LOGGER.debug("[{}] {} found no chest candidates for {} in storage door {}{}.",
                    name(), shortId(ctx), targetDebugName(ctx), storage.getDoorPos(),
                    retry ? " after retry" : "");
            chestPos = null;
            walkTarget = null;
            failure = Failure.NO_CHEST;
            return false;
        }

        BlockPos npcPos = ctx.npc().blockPosition();
        long now = ctx.level().getGameTime();
        BlockPos bestChest = null;
        BlockPos bestWalkTarget = null;
        double bestDist = Double.MAX_VALUE;

        LOGGER.debug("[{}] {} searching chest for {}. candidates={} retry={}.",
                name(), shortId(ctx), targetDebugName(ctx), candidates.size(), retry);

        for (BlockPos pos : candidates) {
            if (!storage.getFunctionalBlocks().contains(pos)) {
                LOGGER.debug("[{}] {} rejected chest {} for {}: not part of this storage room.",
                        name(), shortId(ctx), pos, targetDebugName(ctx));
                continue;
            }

            if (ctx.brain().isChestUnreachable(pos, now)) {
                LOGGER.debug("[{}] {} skipped chest {} for {}: recently unreachable.",
                        name(), shortId(ctx), pos, targetDebugName(ctx));
                continue;
            }

            BlockPos target = walkTargetForChest(ctx, pos);
            if (target == null) {
                LOGGER.debug("[{}] {} rejected chest {} for {}: no reachable adjacent nav node.",
                        name(), shortId(ctx), pos, targetDebugName(ctx));
                // Remembered like a failed walk, so the brain does not keep
                // sending this villager back for a chest it can never stand at.
                ctx.brain().markChestUnreachable(pos, now);
                continue;
            }

            double d = target.distSqr(npcPos);
            if (d < bestDist) {
                bestDist = d;
                bestChest = pos;
                bestWalkTarget = target;
            }
        }

        if (bestChest == null || bestWalkTarget == null) {
            LOGGER.debug("[{}] {} could not find a reachable chest for {} in storage door {}.",
                    name(), shortId(ctx), targetDebugName(ctx), storage.getDoorPos());
            chestPos = null;
            walkTarget = null;
            // Chests hold the goods; none of them can be walked to.
            failure = Failure.UNREACHABLE;
            return false;
        }

        chestPos = bestChest;
        walkTarget = bestWalkTarget;
        phase = Phase.WALK;
        failure = Failure.NONE;

        double distance = Math.sqrt(npcPos.distSqr(walkTarget));
        bestDistance = distance;
        bestWaypoint = -1;
        ticksWithoutProgress = 0;
        walkElapsed = 0;
        walkDeadline = Math.min(WALK_MAX_TICKS,
                WALK_BASE_TICKS + (int) Math.ceil(distance * WALK_TICKS_PER_BLOCK));
        LOGGER.debug("[{}] {} selected chest {} and walk target {} for {}{}.",
                name(), shortId(ctx), chestPos, walkTarget, targetDebugName(ctx),
                retry ? " after retry" : "");
        return true;
    }

    /**
     * Returns the best walkable feet position next to {@code chest}, or null if
     * this chest cannot be reached by the current nav graph.
     */
    private BlockPos walkTargetForChest(Context ctx, BlockPos chest) {
        NavGraph g = ctx.mgr().getGraph();
        if (g == null || storage == null) return null;
        return storage.getWalkTargetForContainer(chest, g, ctx.npc().blockPosition());
    }


    private static boolean isCloseToWalkTarget(BlockPos npcPos, BlockPos target) {
        if (npcPos == null || target == null) return false;
        if (Math.abs(npcPos.getY() - target.getY()) > 1) return false;
        double dx = npcPos.getX() - target.getX();
        double dz = npcPos.getZ() - target.getZ();
        return dx * dx + dz * dz <= 4.0;
    }

    @Override
    public void stop(Context ctx) {
        closeChest(ctx.level()); // safe if never opened / already closed
        ctx.model().stopMoving();
    }

    // -------------------------------------------------------------------------
    // Chest lid + sound
    // -------------------------------------------------------------------------

    private void openChest(ServerLevel level) {
        if (chestOpened || chestPos == null) return;
        chestOpened = true;
        StorageContainers.setOpen(level, chestPos, true);
    }

    private void closeChest(ServerLevel level) {
        if (!chestOpened || chestPos == null) return;
        chestOpened = false;
        StorageContainers.setOpen(level, chestPos, false);
    }

    // -------------------------------------------------------------------------
    // Shared storage lookup
    // -------------------------------------------------------------------------

    /** Blocks further a villager will walk to reach a storage room that is not crowded. */
    private static final double CROWDED_STORAGE_DETOUR = 24.0;

    /**
     * Nearest valid storage building, optionally filtered, or {@code null}.
     * One this villager would crowd counts as {@value #CROWDED_STORAGE_DETOUR}
     * blocks further away, so with two storage rooms the busy one is skipped
     * unless the quiet one is a long way round.
     */
    protected static StorageBuilding nearestStorage(Context ctx,
                                                    java.util.function.Predicate<StorageBuilding> filter) {
        BlockPos npcPos = ctx.npc().blockPosition();
        StorageBuilding best = null;
        double bestDist = Double.MAX_VALUE;
        for (Building b : ctx.mgr().getBuildings()) {
            if (!(b instanceof StorageBuilding s) || !s.isValid()) continue;
            if (s.getFunctionalBlocks().isEmpty() || !filter.test(s)) continue;
            double d = Math.sqrt(s.getDoorPos().distSqr(npcPos));
            if (ctx.mgr().crowding().wouldCrowd(s, ctx.npc().getNpcId())) d += CROWDED_STORAGE_DETOUR;
            if (d < bestDist) { bestDist = d; best = s; }
        }
        return best;
    }

    private static String shortId(Context ctx) {
        return ctx.model().npcId.toString().substring(0, 8);
    }
}

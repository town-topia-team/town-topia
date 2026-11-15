package com.alex.test.village.villagers.guard.behavior.patrol;

import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity.Context;
import com.alex.test.village.brain.Activity.Status;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.villagers.guard.behavior.GuardPostRegistry;
import com.alex.test.village.villagers.guard.settings.GuardSettings;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Stateful guard-post patrol route used by {@code GuardActivity}. */
public final class GuardPostPatrol {

    private static final int INITIAL_JITTER_TICKS = 20 * 8;
    private static final int DEPARTURE_JITTER_TICKS = 20 * 6;
    private static final int PATROL_SECTORS = 8;
    private static final int DECISION_JITTER_TICKS = 20;
    private static final int DECISION_SPREAD_TICKS = 24;
    private static final int REBALANCE_MIN_TICKS = 80;
    private static final int REBALANCE_JITTER_TICKS = 80;
    private static final int REBALANCE_SPREAD_TICKS = 40;

    private final GuardLookAround lookAround = new GuardLookAround();
    private MoveActivity move;
    @Nullable private BlockPos target;
    @Nullable private BlockPos lastTarget;
    @Nullable private BlockPos patrolPoint;
    @Nullable private BlockPos lastPatrolPoint;
    private List<BlockPos> patrolCandidates = List.of();
    private int pause;
    private int stayTicksRemaining;
    private int lookPause;
    private int patrolSector;
    private int decisionDelay = -1;
    private int balanceCooldown;
    private boolean timerInitialized;
    private boolean areaActive;

    public void start(Context ctx) {
        if (timerInitialized) return;
        timerInitialized = true;
        pause = ctx.npc().getRandom().nextInt(INITIAL_JITTER_TICKS + 1);
    }

    public Status tick(Context ctx, GuardSettings settings, int combatCooldown) {
        if (combatCooldown > 0) return Status.RUNNING;

        GuardPostRegistry registry = GuardPostRegistry.get(ctx.level());
        if (target != null && !registry.isValidPost(ctx.level(), target)) {
            cancel(ctx);
            pause = 20 + ctx.npc().getRandom().nextInt(40);
            return Status.FAILED;
        }

        if (target == null && pause > 0) {
            pause--;
            return Status.RUNNING;
        }

        if (target == null) {
            Status status = choosePost(ctx, registry);
            if (status != Status.DONE) return status;
        }

        if (areaActive) {
            // A look around is finished before anything else at the post.
            if (lookAround.isActive() && lookAround.tick(ctx.npc())) {
                return Status.RUNNING;
            }
            if (target != null && shouldRebalance(ctx, registry)) {
                leaveArea(ctx, registry,
                        10 + ctx.npc().getRandom().nextInt(DECISION_JITTER_TICKS + 1));
                return Status.RUNNING;
            }

            if (stayTicksRemaining-- <= 0) {
                leaveArea(ctx, registry,
                        20 + ctx.npc().getRandom().nextInt(DEPARTURE_JITTER_TICKS + 1));
                return Status.RUNNING;
            }
            if (move == null && lookPause > 0) {
                lookPause--;
                return Status.RUNNING;
            }
        }

        if (move == null) {
            if (patrolPoint == null) patrolPoint = pickPatrolPoint(ctx, target);
            if (patrolPoint == null) {
                patrolPoint = GuardPatrolPlanner.nearestVillageNode(
                        ctx, target, GuardPostRegistry.PATROL_RADIUS,
                        fullGraphHeightRadius(ctx, target));
            }
            if (patrolPoint == null) return Status.FAILED;
            // Preserve every graph step for tall routes. A smoothed route could
            // skip part of a staircase or switchback (written for the old walker,
            // which handed stretches to vanilla navigation; kept as it is).
            move = new MoveActivity(patrolPoint, VillagerModel.WALK_SPEED, true);
        }

        Status status = move.tickOuter(ctx);
        if (status == Status.RUNNING) return Status.RUNNING;

        move.stop(ctx);
        move = null;
        if (status == Status.DONE && target != null
                && registry.isValidPost(ctx.level(), target)) {
            if (!areaActive) {
                registry.markVisited(ctx.level(), target, ctx.npc().getNpcId());
                registry.markActive(ctx.level(), target, ctx.npc().getNpcId());
                stayTicksRemaining = settings.guardPostStayTicks()
                        + ctx.npc().getRandom().nextInt(DEPARTURE_JITTER_TICKS + 1);
                areaActive = true;
            }
            lastPatrolPoint = patrolPoint;
            patrolPoint = null;
            lookPause = 15 + ctx.npc().getRandom().nextInt(55);
            if (ctx.npc().getRandom().nextFloat() < GuardLookAround.CHANCE) {
                lookAround.start(ctx.npc());
                lookPause = 5 + ctx.npc().getRandom().nextInt(20);
            }
            return Status.RUNNING;
        }

        patrolPoint = null;
        if (!areaActive) {
            registry.releaseReservation(ctx.npc().getNpcId());
            lastTarget = target;
            target = null;
            pause = 20 + ctx.npc().getRandom().nextInt(60);
            return Status.FAILED;
        }
        lookPause = 10 + ctx.npc().getRandom().nextInt(30);
        return Status.RUNNING;
    }

    /** The post this guard is standing watch at right now, or null while choosing or walking to one. */
    @Nullable
    public BlockPos activePost() { return areaActive ? target : null; }

    public void cancel(Context ctx) {
        lookAround.stop(ctx.npc());
        if (move != null) {
            move.stop(ctx);
            move = null;
        }
        target = null;
        lastTarget = null;
        patrolPoint = null;
        lastPatrolPoint = null;
        patrolCandidates = List.of();
        stayTicksRemaining = 0;
        lookPause = 0;
        decisionDelay = -1;
        balanceCooldown = 0;
        areaActive = false;
        GuardPostRegistry registry = GuardPostRegistry.get(ctx.level());
        registry.releaseReservation(ctx.npc().getNpcId());
        registry.releaseActive(ctx.npc().getNpcId());
    }

    private Status choosePost(Context ctx, GuardPostRegistry registry) {
        if (decisionDelay < 0) {
            decisionDelay = asyncDelay(ctx, DECISION_SPREAD_TICKS);
        }
        if (decisionDelay > 0) {
            decisionDelay--;
            return Status.RUNNING;
        }
        decisionDelay = -1;

        Set<BlockPos> unreachable = new HashSet<>();
        lastPatrolPoint = null;
        while (true) {
            target = registry.choosePost(
                    ctx.level(), ctx.mgr(), ctx.npc(), lastTarget, unreachable);
            if (target == null) return Status.FAILED;

            patrolCandidates = registry.patrolCandidates(ctx.level(), ctx.mgr(), target);
            patrolSector = ctx.npc().getRandom().nextInt(PATROL_SECTORS);
            patrolPoint = pickPatrolPoint(ctx, target);
            if (patrolPoint != null && ctx.model().canReach(ctx.mgr().getGraph(), patrolPoint)) {
                break;
            }

            registry.releaseReservation(ctx.npc().getNpcId());
            unreachable.add(target);
            target = null;
            patrolPoint = null;
        }
        lastTarget = null;
        areaActive = false;
        stayTicksRemaining = 0;
        lookPause = 0;
        balanceCooldown = nextBalanceCooldown(ctx);
        return Status.DONE;
    }

    private boolean shouldRebalance(Context ctx, GuardPostRegistry registry) {
        if (balanceCooldown > 0) {
            balanceCooldown--;
            return false;
        }
        balanceCooldown = nextBalanceCooldown(ctx);
        return target != null
                && registry.shouldRelocateFrom(ctx.level(), ctx.mgr(), ctx.npc(), target);
    }

    private int nextBalanceCooldown(Context ctx) {
        return REBALANCE_MIN_TICKS
                + asyncDelay(ctx, REBALANCE_SPREAD_TICKS)
                + ctx.npc().getRandom().nextInt(REBALANCE_JITTER_TICKS + 1);
    }

    private void leaveArea(Context ctx, GuardPostRegistry registry, int pauseTicks) {
        lookAround.stop(ctx.npc());
        if (move != null) {
            move.stop(ctx);
            move = null;
        }
        lastTarget = target;
        target = null;
        patrolPoint = null;
        lastPatrolPoint = null;
        patrolCandidates = List.of();
        areaActive = false;
        stayTicksRemaining = 0;
        lookPause = 0;
        balanceCooldown = 0;
        registry.releaseActive(ctx.npc().getNpcId());
        pause = pauseTicks;
    }

    @Nullable
    private BlockPos pickPatrolPoint(Context ctx, BlockPos center) {
        if (patrolCandidates.isEmpty()) {
            patrolCandidates = GuardPostRegistry.get(ctx.level()).patrolCandidates(ctx.level(), ctx.mgr(), center);
        }
        if (patrolCandidates.isEmpty()) return null;

        for (int offset = 0; offset < PATROL_SECTORS; offset++) {
            int sector = (patrolSector + offset) % PATROL_SECTORS;
            BlockPos picked = bestCandidateInSector(ctx, center, sector);
            if (picked != null) {
                patrolSector = (sector + 1 + ctx.npc().getRandom().nextInt(2)) % PATROL_SECTORS;
                return picked;
            }
        }

        return bestCandidateInSector(ctx, center, -1);
    }

    @Nullable
    private BlockPos bestCandidateInSector(Context ctx, BlockPos center, int wantedSector) {
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        BlockPos current = ctx.npc().blockPosition();
        for (BlockPos pos : patrolCandidates) {
            if (wantedSector >= 0 && sectorFor(center, pos) != wantedSector) continue;
            double centerDistSq = pos.distSqr(center);
            if (pos.equals(lastPatrolPoint)) continue;

            double centerDist = Math.sqrt(centerDistSq);
            double stepDist = Math.sqrt(pos.distSqr(current));
            double score = -Math.abs(centerDist - 7.5D) * 5.0D
                    - Math.abs(stepDist - 7.0D) * 2.0D
                    + ctx.npc().getRandom().nextDouble() * 12.0D;
            if (best == null || score > bestScore) {
                best = pos;
                bestScore = score;
            }
        }
        return best;
    }

    private static int sectorFor(BlockPos center, BlockPos pos) {
        double angle = Math.atan2(pos.getZ() - center.getZ(), pos.getX() - center.getX());
        double normalized = angle < 0.0D ? angle + Math.PI * 2.0D : angle;
        return Math.min(PATROL_SECTORS - 1,
                (int) (normalized / (Math.PI * 2.0D) * PATROL_SECTORS));
    }

    private static int asyncDelay(Context ctx, int spreadTicks) {
        if (spreadTicks <= 1) return 0;
        int slot = Math.floorMod(ctx.npc().getNpcId().hashCode(), spreadTicks);
        int current = Math.floorMod((int) ctx.level().getGameTime(), spreadTicks);
        return Math.floorMod(slot - current, spreadTicks);
    }

    /**
     * Guard posts are valid across the village's full build height. The fallback
     * only searches a small horizontal radius, so allowing the entire graph Y
     * span here does not broaden patrols outside the post's local area.
     */
    private static int fullGraphHeightRadius(Context ctx, BlockPos center) {
        var graph = ctx.mgr().getGraph();
        if (graph == null) return 0;
        return Math.max(
                Math.abs(center.getY() - graph.getBoundsMinY()),
                Math.abs(graph.getBoundsMaxY() - center.getY()));
    }
}

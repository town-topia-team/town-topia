package com.alex.test.village.villagers.builder.behavior;

import com.alex.test.village.VillagerModel;
import com.alex.test.village.WalkFeel;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.building.Building;
import com.alex.test.village.villagers.builder.settings.BuilderSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Builder's working day: walk the village door to door, re-checking that
 * every building still meets its requirements.
 *
 * <p>The village's own 100-tick rolling re-check already covers every building;
 * the Builder targets whichever building has gone longest without a look and,
 * at its door, runs that same re-check ({@code VillageManager.recheckBuilding}),
 * so a broken one is queued for the rebuild there and then. Since 10-25 it no
 * longer validates or rebuilds the building itself: that skipped the token
 * shine, chat line, beds and Mayor link the rebuild queue takes care of.
 *
 * <p>Never finishes on its own; it loops building to building until the brain
 * interrupts it (eat, sleep, socialize). The per-building visit schedule lives
 * only on this instance, so an interrupt loses it — the next sweep just starts
 * fresh with every building equally overdue, which is an acceptable trade for
 * not touching any shared file.
 *
 * <p>Ordered builds come first ({@link BuildSiteWork}): between two inspections the
 * Builder looks for a project of its village and works on it; when there is none, or it
 * waits for blocks, the inspections carry on.
 */
public final class BuilderActivity extends WorkActivity {

    private static final float WALK_SPEED = VillagerModel.WALK_SPEED;
    /** Already standing close enough to the door to skip issuing a fresh walk. */
    private static final double ARRIVED_DIST_SQ = 2.25;
    /** How long it stands at a door looking a building over (3 s; a setting until 10-25). */
    private static final int INSPECT_TICKS = 60;

    /** Game time each building was last inspected this work session, by item-frame UUID. */
    private final Map<UUID, Long> lastVisited = new HashMap<>();

    @Nullable private Building target;
    @Nullable private MoveActivity move;
    private int inspectTimer;
    private final BuildSiteWork building = new BuildSiteWork();
    /** The Build setting as last seen, so switching it off stops the build work once (bug review round 5). */
    private boolean buildWasOn;

    // =========================================================================
    // WorkActivity contract
    // =========================================================================

    @Override
    protected Status doTick(Context ctx) {
        ProfessionSettings settings = ProfessionSettings.of(ctx.npc(), BuilderSettingsSchema.SCHEMA);
        if (!settings.getBool(BuilderSettingsSchema.WORK_ENABLED)) return Status.FAILED;

        boolean buildOn = settings.getBool(BuilderSettingsSchema.BUILD_ENABLED);
        if (buildWasOn && !buildOn && WalkFeel.buildOffStop) {
            // Build just switched off: the half-done trip stops once (a held table is let go), as on an interrupt.
            building.stop(ctx);
            diary(ctx, "BUILDER build switched off: half-done build work stopped");
        }
        buildWasOn = buildOn;

        // Between inspections: an ordered build, if there is one to work on now.
        if (target == null && buildOn
                && building.tick(ctx) == BuildSiteWork.Result.WORKING) {
            return Status.RUNNING;
        }

        if (target == null) {
            target = pickLeastRecentlyVisited(ctx);
            if (target == null) return Status.FAILED;
            if (ctx.npc().blockPosition().distSqr(target.getDoorPos()) <= ARRIVED_DIST_SQ) {
                beginInspecting(ctx);
            } else {
                move = new MoveActivity(target.getDoorPos(), WALK_SPEED);
            }
            return Status.RUNNING;
        }

        if (move != null) {
            Status status = move.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            move.stop(ctx);
            move = null;
            if (status == Status.FAILED) {
                diary(ctx, "BUILDER could not reach building at "
                        + target.getDoorPos().toShortString());
                markVisited(ctx, target);
                target = null;
                return Status.RUNNING;
            }
            beginInspecting(ctx);
            return Status.RUNNING;
        }

        if (inspectTimer > 0) {
            inspectTimer--;
            return Status.RUNNING;
        }

        finishInspection(ctx);
        return Status.RUNNING;
    }

    @Override
    protected void doStop(Context ctx) {
        if (move != null) { move.stop(ctx); move = null; }
        building.stop(ctx);
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.model().stopMoving();
        target = null;
        inspectTimer = 0;
    }

    @Override
    public boolean isMovingPhase() {
        return move != null || building.moving();
    }

    // =========================================================================
    // Inspection
    // =========================================================================

    private void beginInspecting(Context ctx) {
        inspectTimer = INSPECT_TICKS;
        // No dedicated inspection pose exists yet in VillagerAnimationState, so
        // this stands in for it — see the assignment note on that tradeoff.
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        BlockPos door = target.getDoorPos();
        ctx.npc().getLookControl().setLookAt(
                door.getX() + 0.5, door.getY() + 0.5, door.getZ() + 0.5, 30f, 30f);
        diary(ctx, "BUILDER inspecting building at " + door.toShortString());
    }

    private void finishInspection(Context ctx) {
        // The village's own re-check: a broken building, or one whose furniture changed,
        // goes to the rebuild queue, which also does the shine, chat line, beds and Mayor.
        boolean queued = ctx.mgr().recheckBuilding(ctx.level(), target);
        diary(ctx, "BUILDER inspection done, valid=" + target.isValid()
                + (queued ? ", re-read queued" : "")
                + " at " + target.getDoorPos().toShortString());
        markVisited(ctx, target);
        target = null;
    }

    // =========================================================================
    // Selection
    // =========================================================================

    @Nullable
    private Building pickLeastRecentlyVisited(Context ctx) {
        ProfessionSettings settings = ProfessionSettings.of(ctx.npc(), BuilderSettingsSchema.SCHEMA);
        Building best = null;
        long bestTime = Long.MAX_VALUE;
        for (Building candidate : ctx.mgr().getBuildings()) {
            if (!isAllowedType(settings, candidate)) continue;
            long visited = lastVisited.getOrDefault(candidate.getItemFrameId(), -1L);
            if (visited < bestTime) {
                bestTime = visited;
                best = candidate;
            }
        }
        return best;
    }

    private void markVisited(Context ctx, Building building) {
        lastVisited.put(building.getItemFrameId(), ctx.level().getGameTime());
    }

    private static boolean isAllowedType(ProfessionSettings settings, Building building) {
        return settings.isAllowed(
                BuilderSettingsSchema.ALLOWED_BUILDING_TYPES, building.getClass().getSimpleName());
    }

    private static void diary(Context ctx, String message) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + message);
    }

    /** Cheap pre-check for brain scoring. */
    public static boolean hasWork(Context ctx) {
        ProfessionSettings settings = ProfessionSettings.of(ctx.npc(), BuilderSettingsSchema.SCHEMA);
        if (!settings.getBool(BuilderSettingsSchema.WORK_ENABLED)) return false;
        if (BuildSiteWork.available(ctx)) return true;
        for (Building candidate : ctx.mgr().getBuildings()) {
            if (isAllowedType(settings, candidate)) return true;
        }
        return false;
    }
}

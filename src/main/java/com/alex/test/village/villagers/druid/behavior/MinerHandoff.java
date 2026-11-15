package com.alex.test.village.villagers.druid.behavior;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.MineBuilding;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The miner's side of sharing a mine with a druid.
 *
 * <p>A druid only regrows a mine nobody is digging, and holds the mine's claim
 * while it does -- the same claim a miner takes, so the two never work one
 * shaft at once. A miner that turns up wanting the mine walks over to the
 * druid and asks for it: once the druid has grown back a minimum for the visit
 * (see DruidActivity's MIN_BLOCKS_BEFORE_HANDOFF) and finished the dance it is
 * in, the two have a short chat -- mining, tools, mining -- and the druid
 * leaves. It always asks now. It used to ask only now and then and otherwise
 * wait out the visit, which was fine while a visit was capped at a few dozen
 * cells; a visit runs until the mine is full now, which can be most of a day.
 *
 * <p>One instance lives inside one miner work session, so it asks once per
 * trip to the mine.
 */
public final class MinerHandoff {

    /** Within this the two can talk: ConverseActivity's own pairing range. */
    private static final double TALK_RANGE_SQ = 6.25;
    /** Longest a miner waits on a druid before giving the shift up. */
    private static final int MAX_WAIT_TICKS = 6_000;
    private static final float WALK_SPEED = VillagerModel.WALK_SPEED;

    private record Hold(MineBuilding mine, NpcEntity druid, DruidActivity activity) {}

    @Nullable private MoveActivity move;
    private boolean decided;
    private int waited;

    /**
     * One tick of walking to the druid, asking or waiting.
     *
     * @return {@link Activity.Status#RUNNING} while this is going on, or null
     *         when no druid holds a mine (or the wait ran out) and the miner
     *         should carry on as it would have
     */
    @Nullable
    public Activity.Status tick(Activity.Context ctx) {
        Hold hold = findHold(ctx);
        if (hold == null || ++waited > MAX_WAIT_TICKS) {
            stop(ctx);
            return null;
        }
        NpcEntity npc = ctx.npc();
        if (npc.distanceToSqr(hold.druid()) > TALK_RANGE_SQ) {
            if (move == null) {
                npc.setAnimation(VillagerAnimationState.IDLE);
                move = new MoveActivity(hold.druid().blockPosition(), WALK_SPEED);
            }
            Activity.Status status = move.tickOuter(ctx);
            if (status == Activity.Status.RUNNING) return Activity.Status.RUNNING;
            move.stop(ctx);
            move = null;
            if (status == Activity.Status.FAILED) {
                diary(ctx, "MINE could not get to the druid at the mine "
                        + hold.mine().mouth().toShortString());
                return null;
            }
            return Activity.Status.RUNNING;
        }

        stop(ctx);
        ctx.model().stopMoving();
        npc.getLookControl().setLookAt(hold.druid(), 30f, 30f);
        if (!decided) {
            decided = true;
            if (hold.activity().requestHandoff(npc)) {
                diary(ctx, "MINE asked the druid for the mine at "
                        + hold.mine().mouth().toShortString());
            } else {
                diary(ctx, "MINE waiting for the druid to finish the mine at "
                        + hold.mine().mouth().toShortString());
            }
        }
        return Activity.Status.RUNNING;
    }

    public void stop(Activity.Context ctx) {
        if (move != null) {
            move.stop(ctx);
            move = null;
        }
    }

    /** Whether a druid is regrowing one of the village's mines right now. For brain scoring. */
    public static boolean druidHoldsAMine(Activity.Context ctx) {
        return findHold(ctx) != null;
    }

    @Nullable
    private static Hold findHold(Activity.Context ctx) {
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof MineBuilding mine) || !mine.isValid()) continue;
            // Paused by the player: the druid is on its way out, and the miner
            // could not take the mine after it anyway.
            if (ctx.mgr().mineShape(mine.getItemFrameId()).paused()) continue;
            UUID holder = ctx.mgr().getClaims().holder(mine.mouth());
            if (holder == null || holder.equals(ctx.model().npcId)) continue;
            VillagerModel model = ctx.mgr().getModel(holder);
            if (model == null) continue;
            NpcEntity druid = model.puppet();
            if (druid == null || !druid.isAlive()) continue;
            if (model.brain().currentActivity() instanceof DruidActivity activity
                    && activity.isRegrowing(mine.mouth())) {
                return new Hold(mine, druid, activity);
            }
        }
        return null;
    }

    private static void diary(Activity.Context ctx, String message) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + message);
    }
}

package com.alex.test.village.brain;

import com.alex.test.village.VillagerModel;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * One dancer's part of a {@link PairDance}: walk to its tile, step onto the
 * exact spot, wait there facing forward until the partner is on its own,
 * then dance the kick line from the shared start tick and hold still on the
 * spot until the last loop ends.
 *
 * <p>Interrupted (an alarm, a starving meal, a player taking over), it ends
 * the whole dance, and the partner's part ends on its next tick.
 */
public final class PairDanceActivity extends Activity {

    private enum Step { WALK, STEP_IN, WAIT, DANCE }

    /** Close enough to the tile to make the last step straight. */
    private static final double STEP_IN_RANGE_SQ = 2.25;
    /** On the spot (flat, squared): 0.1 blocks. */
    private static final double ON_SPOT_SQ = 0.01;
    /** The straight last step gives up after this and snaps onto the spot. */
    private static final int STEP_IN_MAX_TICKS = 40;
    /** The walk over gives up after this (the dance's own wait is shorter). */
    private static final float WALK_SPEED = VillagerModel.WALK_SPEED;

    private final PairDance dance;
    private Step step = Step.WALK;
    private int stepInTicks;
    private boolean danced;

    public PairDanceActivity(PairDance dance) {
        this.dance = dance;
    }

    @Override
    public String name() { return "pair_dance"; }

    public PairDance dance() { return dance; }

    @Override
    protected void start(Context ctx) {
        NpcEntity npc = ctx.npc();
        if (npc.isSleeping()) npc.stopSleeping();
        if (npc.isChairSeated()) npc.standUpFromChair();
    }

    @Override
    protected Status tick(Context ctx) {
        NpcEntity npc = ctx.npc();
        long now = ctx.level().getGameTime();
        dance.tick(now);
        if (dance.stage() == PairDance.Stage.OVER) {
            if (!danced) diary(ctx, "DANCE called off (" + dance.endReason() + ")");
            return danced ? Status.DONE : Status.FAILED;
        }
        Vec3 spot = dance.spotFor(npc.getUUID());
        BlockPos tile = BlockPos.containing(spot);
        switch (step) {
            case WALK -> {
                double dx = npc.getX() - spot.x, dz = npc.getZ() - spot.z;
                if (dx * dx + dz * dz <= STEP_IN_RANGE_SQ && Math.abs(npc.getY() - spot.y) < 0.6) {
                    ctx.model().stopMoving();
                    step = Step.STEP_IN;
                } else if (moveTo(ctx, tile, WALK_SPEED)) {
                    if (moveFailed(ctx, tile)) {
                        dance.end(shortId(ctx) + " could not reach the spot");
                        diary(ctx, "DANCE could not reach " + tile.toShortString());
                        return Status.FAILED;
                    }
                    step = Step.STEP_IN;
                }
            }
            case STEP_IN -> {
                double dx = npc.getX() - spot.x, dz = npc.getZ() - spot.z;
                if (dx * dx + dz * dz <= ON_SPOT_SQ || ++stepInTicks > STEP_IN_MAX_TICKS) {
                    settle(npc, spot);
                    step = Step.WAIT;
                    if (!dance.arrive(npc.getUUID(), now)) diary(ctx, "DANCE on the spot, waiting for the partner");
                } else {
                    npc.getNavigation().stop();
                    npc.getMoveControl().setWantedPosition(spot.x, npc.getY(), spot.z, 0.45);
                }
            }
            case WAIT -> {
                settle(npc, spot);
                npc.setAnimation(VillagerAnimationState.IDLE);
                long startAt = dance.startAt();
                if (startAt >= 0 && now >= startAt) {
                    npc.startWorkAnimation(dance.clipFor(npc.getUUID()), 1f);
                    danced = true;
                    step = Step.DANCE;
                    diary(ctx, "DANCE started");
                }
            }
            case DANCE -> {
                settle(npc, spot);
                if (now >= dance.endsAt()) {
                    dance.end("done");
                    npc.setAnimation(VillagerAnimationState.IDLE);
                    diary(ctx, "DANCE finished");
                    return Status.DONE;
                }
            }
        }
        return Status.RUNNING;
    }

    /** On the spot and facing forward; pushes and drift put back. */
    private void settle(NpcEntity npc, Vec3 spot) {
        npc.holdStillForAnimation();
        double dx = npc.getX() - spot.x, dz = npc.getZ() - spot.z;
        if (dx * dx + dz * dz > 0.0025
                && com.alex.test.village.farawake.FarAwake.readyFor(npc.level(), spot.x, spot.z)) {
            npc.setPos(spot.x, npc.getY(), spot.z);
        }
        float yaw = dance.yaw();
        npc.setYRot(yaw);
        npc.setYBodyRot(yaw);
        npc.setYHeadRot(yaw);
        npc.setXRot(0f);
    }

    @Override
    public void stop(Context ctx) {
        NpcEntity npc = ctx.npc();
        if (dance.stage() != PairDance.Stage.OVER) {
            dance.end(shortId(ctx) + " was interrupted");
            diary(ctx, "DANCE interrupted");
        }
        VillagerAnimationState state = npc.getAnimation();
        if (state == VillagerAnimationState.JAZZ_PAIR_R || state == VillagerAnimationState.JAZZ_PAIR_L) {
            npc.setAnimation(VillagerAnimationState.IDLE);
        }
        ctx.model().stopMoving();
    }

    private static String shortId(Context ctx) {
        return ctx.model().npcId.toString().substring(0, 8);
    }

    private static void diary(Context ctx, String message) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + message);
    }
}

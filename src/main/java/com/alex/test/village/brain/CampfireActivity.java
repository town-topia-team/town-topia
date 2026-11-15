package com.alex.test.village.brain;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillagerModel;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Free time by the fire: the villager walks to a lit campfire in the village,
 * finds a place in the ring around it, and stands there warming up, looking
 * into the flames, until its leisure slice is over.
 *
 * <p>Picked by {@link LeisureActivity} when a fire is near enough
 * ({@link #worthIt}). Several villagers at one fire each take their own tile
 * ({@link StandClaims}-free: the ring is small, so the tile is chosen by who
 * is already standing where), so a circle forms by itself, and people who
 * stand that close start talking the way they do anywhere else.
 */
public final class CampfireActivity extends Activity {

    /** How far a villager will walk for a fire. */
    static final int REACH = 40;
    /** Wellbeing a tick by the fire: a little better than admiring the town. */
    private static final float WARM_GAIN = 30f / 12_000f;
    private static final int TIMEOUT_TICKS = 1200;
    /** The ring: one clear tile between the villager and the flames at least, three at most. */
    private static final double RING_MIN_SQ = 2.0 * 2.0 - 0.01;
    private static final double RING_MAX_SQ = 3.2 * 3.2;

    private BlockPos fire;
    private BlockPos spot;
    private boolean arrived;
    private int ticks;

    /** Whether there is a fire for this villager to go to right now. */
    static boolean worthIt(Context ctx) {
        return ctx.mgr().getGraph() != null
                && VillageCampfires.nearest(ctx.mgr(), ctx.level(), ctx.npc().blockPosition(), REACH) != null;
    }

    @Override
    protected void start(Context ctx) {
        fire = VillageCampfires.nearest(ctx.mgr(), ctx.level(), ctx.npc().blockPosition(), REACH);
        spot = fire == null ? null : ringSpot(ctx, fire);
    }

    @Override
    protected Status tick(Context ctx) {
        if (fire == null || spot == null) return Status.FAILED;
        if (++ticks > TIMEOUT_TICKS) return Status.DONE;
        // Rained out, burnt down, or put out by a player: nothing to stand round.
        if (!VillageCampfires.isLit(ctx.level(), fire)) return Status.DONE;

        if (!arrived) {
            if (!moveTo(ctx, spot, VillagerModel.WALK_SPEED)) return Status.RUNNING;
            if (moveFailed(ctx, spot) || ctx.npc().blockPosition().distSqr(spot) > 4.0) return Status.FAILED;
            arrived = true;
            ctx.model().stopMoving();
            ctx.npc().setAnimation(VillagerAnimationState.COOK_CAMP_WAIT);
        }

        ctx.npc().getLookControl().setLookAt(fire.getX() + 0.5, fire.getY() + 0.4, fire.getZ() + 0.5, 30f, 30f);
        if (ctx.npc().getAnimation() != VillagerAnimationState.COOK_CAMP_WAIT) {
            ctx.npc().setAnimation(VillagerAnimationState.COOK_CAMP_WAIT);
        }
        ctx.brain().needs().addWellbeing(WARM_GAIN);
        return Status.RUNNING;
    }

    @Override
    public void stop(Context ctx) {
        if (ctx.npc().getAnimation() == VillagerAnimationState.COOK_CAMP_WAIT) {
            ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        }
        ctx.model().stopMoving();
    }

    @Override
    public String name() { return "campfire"; }

    /**
     * A free tile in the ring round {@code fire}: on the fire's own floor (one
     * step up or down at most), routable, nobody else's feet on it, the
     * nearest such tile to this villager. Null when the fire is boxed in.
     */
    @Nullable
    private static BlockPos ringSpot(Context ctx, BlockPos fire) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        List<BlockPos> taken = new ArrayList<>();
        for (VillagerModel other : ctx.mgr().getVillagers()) {
            if (other == ctx.model() || other.puppet() == null) continue;
            if (other.puppet().blockPosition().distSqr(fire) <= RING_MAX_SQ + 4.0) {
                taken.add(other.puppet().blockPosition());
            }
        }
        BlockPos me = ctx.npc().blockPosition();
        BlockPos[] best = new BlockPos[1];
        double[] bestSq = { Double.MAX_VALUE };
        graph.forEachNodeInBox(fire, 4, 1, (NavNode node) -> {
            if (!node.isRoutable()) return;
            BlockPos feet = node.feetPos();
            double dx = feet.getX() - fire.getX(), dz = feet.getZ() - fire.getZ();
            double flatSq = dx * dx + dz * dz;
            if (flatSq < RING_MIN_SQ || flatSq > RING_MAX_SQ || Math.abs(feet.getY() - fire.getY()) > 1) return;
            for (BlockPos other : taken) {
                if (other.distSqr(feet) < 1.5) return;
            }
            double sq = feet.distSqr(me);
            if (sq < bestSq[0]) {
                bestSq[0] = sq;
                best[0] = feet.immutable();
            }
        });
        return best[0];
    }
}

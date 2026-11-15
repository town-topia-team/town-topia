package com.alex.test.village.brain;

import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.TownHallBuilding;
import net.minecraft.core.BlockPos;

/**
 * Low-priority filler: stroll to a random nav node within 40 blocks of the
 * town hall (falls back to near the NPC if no town hall is registered), pause
 * briefly, done. Villagers in this activity are open to conversations
 * (see {@link SocialCoordinator}).
 */
public final class WanderActivity extends Activity {

    private static final int WANDER_RADIUS  = 40;
    private static final int TIMEOUT_TICKS  = 400;

    private BlockPos goal;
    private int ticks      = 0;
    private int pauseTicks = -1;

    @Override
    protected void start(Context ctx) {
        BlockPos center = townHallPos(ctx);
        if (center == null) center = ctx.npc().blockPosition();
        goal = randomStrollSpot(ctx, center, WANDER_RADIUS, 6,
                new java.util.Random());
    }

    private static BlockPos townHallPos(Context ctx) {
        for (Building b : ctx.mgr().getBuildings()) {
            if (b instanceof TownHallBuilding && b.isValid()) return b.getDoorPos();
        }
        return null;
    }

    @Override
    protected Status tick(Context ctx) {
        if (goal == null) return Status.FAILED;
        if (++ticks > TIMEOUT_TICKS) return Status.DONE;

        if (pauseTicks >= 0) {
            return --pauseTicks <= 0 ? Status.DONE : Status.RUNNING;
        }
        if (moveTo(ctx, goal, VillagerModel.WALK_SPEED)) {
            if (moveFailed(ctx, goal)) return Status.FAILED;
            pauseTicks = 30 + ctx.npc().getRandom().nextInt(60);
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(Context ctx) { ctx.model().stopMoving(); }

    @Override
    public String name() { return "wander"; }
}

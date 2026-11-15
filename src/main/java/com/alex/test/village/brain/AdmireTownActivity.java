package com.alex.test.village.brain;

import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.MineBuilding;
import com.alex.test.village.building.WatchtowerBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Leisure alternative to wandering: the villager visits up to
 * {@value #MAX_BUILDINGS} buildings, stands outside each one, and gazes at it
 * for a few seconds before moving on.
 *
 * <p>Picked by {@link LeisureActivity} when its admire score wins.
 */
public final class AdmireTownActivity extends Activity {

    private static final int MAX_BUILDINGS   = 3;
    private static final int LOOK_MIN_TICKS  = 60;   // 3 s
    private static final int LOOK_RND_TICKS  = 100;  // up to 8 s total
    private static final int STAND_DISTANCE  = 4;    // blocks outside the door
    private static final int TIMEOUT_TICKS   = 3000; // safety cap

    private List<Building> buildings;
    private int            buildingIdx   = 0;
    private BlockPos       walkGoal;
    private BlockPos       lookTarget;
    private boolean        arrived       = false;
    private int            lookTicks     = 0;
    private int            lookDuration  = 0;
    private int            totalTicks    = 0;

    @Override
    protected void start(Context ctx) {
        List<Building> valid = new ArrayList<>();
        for (Building b : ctx.mgr().getBuildings()) {
            // A watchtower's token is up on its platform: the "spot outside the
            // door" is up there too, and villagers climbed the tower to admire it.
            // A mine is a token in a rock face: nothing to look at, and one at the
            // foot of a staircase meant the whole walk down and back up again.
            if (b.isValid() && !(b instanceof WatchtowerBuilding) && !(b instanceof MineBuilding)) valid.add(b);
        }
        Collections.shuffle(valid, new java.util.Random());
        buildings = valid.size() > MAX_BUILDINGS ? valid.subList(0, MAX_BUILDINGS) : valid;

        if (!buildings.isEmpty()) setupBuilding(ctx, buildings.get(0));
    }

    @Override
    protected Status tick(Context ctx) {
        if (buildings == null || buildings.isEmpty()) return Status.FAILED;
        if (++totalTicks > TIMEOUT_TICKS) return Status.DONE;

        if (!arrived) {
            if (walkGoal == null || moveTo(ctx, walkGoal, VillagerModel.WALK_SPEED)) {
                if (walkGoal != null && moveFailed(ctx, walkGoal)) {
                    buildingIdx++;
                    if (buildingIdx >= buildings.size()) return Status.DONE;
                    setupBuilding(ctx, buildings.get(buildingIdx));
                    return Status.RUNNING;
                }
                arrived = true;
            }
            return Status.RUNNING;
        }

        // Stand still and look at the building.
        ctx.npc().getLookControl().setLookAt(
                lookTarget.getX() + 0.5,
                lookTarget.getY() + 1.0,
                lookTarget.getZ() + 0.5,
                30f, 30f);

        if (++lookTicks >= lookDuration) {
            buildingIdx++;
            if (buildingIdx >= buildings.size()) return Status.DONE;
            setupBuilding(ctx, buildings.get(buildingIdx));
        }

        return Status.RUNNING;
    }

    @Override
    public void stop(Context ctx) { ctx.model().stopMoving(); }

    @Override
    public String name() { return "admire"; }

    // -------------------------------------------------------------------------

    private void setupBuilding(Context ctx, Building b) {
        // Stand outside the door, facing the building from a short distance.
        Direction exterior = b.getInteriorDirection().getOpposite();
        BlockPos outside = b.getDoorPos().relative(exterior, STAND_DISTANCE);
        walkGoal = randomStrollSpot(ctx, outside, 5, 6, new java.util.Random());
        if (walkGoal == null) walkGoal = outside;

        lookTarget   = b.getDoorPos();
        arrived      = false;
        lookTicks    = 0;
        lookDuration = LOOK_MIN_TICKS + ctx.npc().getRandom().nextInt(LOOK_RND_TICKS);
        resetMove();
    }
}

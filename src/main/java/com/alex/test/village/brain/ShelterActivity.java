package com.alex.test.village.brain;

import com.alex.test.village.VillagerModel;
import com.alex.test.animation.HurryGait;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.building.Building;
import com.alex.test.village.shelter.ShelterWatch;
import com.alex.test.village.villagers.guard.behavior.GuardAlertSystem;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The alarm bell has rung: go indoors and stay there.
 *
 * <p>A villager heads for the nearest building (not its own house -- whatever
 * roof is closest), walks to a tile inside, away from the door, and waits there. It stays
 * while the alarm lasts: thirty seconds after the bell, kept going for as long
 * as a threat is live and thirty seconds past the last one
 * ({@link GuardAlertSystem#isVillageAlarmed}). Then it is done and its day
 * picks up again. A villager with no building to go to holds where it is and
 * looks again every five seconds.
 */
public final class ShelterActivity extends Activity {

    /**
     * A light jog indoors: about 3.3 blocks a second, a fifth faster than a walk,
     * which the jogging clip covers at one and a half times its own pace (RunPace).
     * It was 0.62, about 4.2 blocks a second, which the jog could only show with
     * its legs going nearly twice as fast or its feet sliding.
     */
    private static final float SHELTER_SPEED = VillagerModel.WALK_SPEED;
    /** Tiles this close to the door are not "inside" enough to wait on. */
    private static final double DOOR_KEEP_OUT_SQ = 2.0 * 2.0;
    private static final int RETRY_TICKS = 40;
    /** How often a villager on its way checks its building has not been overrun. */
    private static final int OVERRUN_CHECK_TICKS = 10;
    /** With nowhere to go (none left, or every one overrun), how often it looks again (5 s). */
    private static final int HOME_RETRY_TICKS = 100;

    @Nullable private Building home;
    @Nullable private MoveActivity move;
    @Nullable private BlockPos spot;
    private int retryIn;
    private int overrunCheckIn;
    /** A cleric keeps healing from inside (see ClericShelterCare). */
    @Nullable private com.alex.test.village.villagers.cleric.behavior.ClericShelterCare clericCare;

    @Override
    protected void start(Context ctx) {
        home = ctx.brain().shelterHome(ctx);
        // Each villager checks on its own beat, not the whole village on the bell's tick.
        overrunCheckIn = Math.floorMod(ctx.npc().getNpcId().hashCode(), OVERRUN_CHECK_TICKS);
    }

    @Override
    protected Status tick(Context ctx) {
        if (!GuardAlertSystem.isVillageAlarmed(ctx.mgr(), ctx.level())) return Status.DONE;
        NpcEntity npc = ctx.npc();
        if (home == null || !home.isValid()) {
            ctx.model().stopMoving();
            npc.getNavigation().stop();
            // Look again every five seconds, each villager on its own beat so
            // a whole village left without a building does not all look at once.
            if (Math.floorMod(ctx.level().getGameTime() + npc.getNpcId().hashCode(), HOME_RETRY_TICKS) == 0) {
                home = ctx.brain().shelterHome(ctx);
                spot = null;
                retryIn = 0;
            }
            return Status.RUNNING;
        }

        // A monster got into the building on the way there: pick another. One
        // already inside with the monster is sent running by the brain instead.
        if (++overrunCheckIn >= OVERRUN_CHECK_TICKS) {
            overrunCheckIn = 0;
            if (!ShelterWatch.isOnFloor(home, npc.blockPosition())
                    && ShelterWatch.isOverrun(ctx.mgr(), home, ctx.level().getGameTime())) {
                if (move != null) {
                    npc.stopHurry();
                    move.stop(ctx);
                    move = null;
                }
                spot = null;
                retryIn = 0;
                home = ctx.brain().shelterHome(ctx);
                if (home == null) return Status.RUNNING;
            }
        }

        if (move != null) {
            Status status = move.tickOuter(ctx);
            if (status == Status.RUNNING) {
                npc.hurry(HurryGait.JOG);
                return Status.RUNNING;
            }
            npc.stopHurry();
            move.stop(ctx);
            move = null;
            if (status == Status.FAILED) {
                spot = null;
                retryIn = RETRY_TICKS;
            }
        }

        if (home.isInRoom(npc.blockPosition()) && spot != null) {
            // Inside: wait out the alarm, with the door shut. A cleric heals whoever it can see from here.
            npc.getNavigation().stop();
            if (overrunCheckIn == 0) ShelterDoor.keepShut(ctx, home);
            if (npc.getProfession() == com.alex.test.village.villagers.core.Profession.CLERIC) {
                if (clericCare == null) {
                    clericCare = new com.alex.test.village.villagers.cleric.behavior.ClericShelterCare();
                }
                clericCare.tick(ctx);
            }
            return Status.RUNNING;
        }

        if (retryIn > 0) {
            retryIn--;
            return Status.RUNNING;
        }
        spot = pickSpot(ctx, home);
        if (spot == null) {
            retryIn = RETRY_TICKS;
            return Status.RUNNING;
        }
        move = new MoveActivity(spot, SHELTER_SPEED);
        return Status.RUNNING;
    }

    /** The building this villager is sheltering in or heading to; null when it has none. */
    @Nullable
    Building home() {
        return home;
    }

    /** Whether this villager is on its way into {@code building} and not yet on its floor. */
    boolean isHeadingInto(Building building, NpcEntity npc) {
        return home == building && !ShelterWatch.isOnFloor(building, npc.blockPosition());
    }

    /** A random floor tile inside {@code building}, kept back from the door. */
    @Nullable
    private static BlockPos pickSpot(Context ctx, Building building) {
        BlockPos door = building.getDoorPos();
        List<BlockPos> inner = new ArrayList<>();
        for (BlockPos tile : building.getNavMeshBlocks()) {
            if (tile.distSqr(door) > DOOR_KEEP_OUT_SQ) inner.add(tile);
        }
        List<BlockPos> pool = inner.isEmpty() ? new ArrayList<>(building.getNavMeshBlocks()) : inner;
        if (pool.isEmpty()) return null;
        return pool.get(ctx.npc().getRandom().nextInt(pool.size()));
    }

    @Override
    public void stop(Context ctx) {
        if (move != null) {
            ctx.npc().stopHurry();
            move.stop(ctx);
        }
        move = null;
        if (clericCare != null) clericCare.stop(ctx);
        clericCare = null;
    }

    @Override
    public String name() {
        return "shelter";
    }
}

package com.alex.test.village.villagers.butcher.lead;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * A led animal walks in its villager's footsteps.
 *
 * <p>The villager drops a breadcrumb every 0.75 blocks it walks; the animal
 * always steers to the oldest crumb it has not reached yet, never straight at
 * the villager. That is what made vanilla leads snag on fence corners and
 * door frames: the leashed mob takes the straight line, the villager took the
 * path. Here the animal takes the same path, a few steps behind.
 *
 * <p>Steering uses the animal's own move control, so it walks, steps up and
 * swims like itself. Its goals are switched off while it is led (see
 * {@link LedAnimals}), and its own pathfinding is stopped every tick so
 * nothing fights the trail.
 */
public final class LeadWalk {

    public enum Result {
        /** Walking the trail. */
        FOLLOWING,
        /** Trail done, the animal is within 2 blocks of the villager. */
        CAUGHT_UP,
        /** More than 8 blocks from the villager: the villager should wait or come back. */
        TOO_FAR,
        /** No progress for 8 seconds, even after a hop and a nudge. */
        STUCK,
        /** The animal is gone, dead or no longer led. */
        LOST
    }

    private static final double CRUMB_SPACING = 0.75;
    private static final int MAX_CRUMBS = 64;
    private static final double REACHED_XZ = 0.5;
    private static final double REACHED_Y = 1.0;
    /** The animal never comes nearer the villager than this. */
    private static final double STAND_OFF = 1.5;
    /** Clear space kept between the animal and its villager, body to body. */
    private static final double GAP = 0.5;
    private static final double CAUGHT_UP_DIST = 2.0;
    private static final double TOO_FAR_DIST = 8.0;
    /**
     * Pace over the villager's, normally and when falling behind. A cow's own
     * walk (0.2) is slower than a leading villager's (0.225), so a fixed
     * speed left it dropping back and the walk went stop and go.
     */
    private static final double SPEED = 1.05;
    private static final double CATCH_UP_SPEED = 1.3;
    /** Matching never speeds an animal up past this: a villager breaking into a run does not drag it along. */
    private static final double MAX_PACE = 1.5;
    private static final int CATCH_UP_CRUMBS = 3;
    private static final double PROGRESS = 0.2;
    private static final int HOP_TICKS = 40;
    private static final int NUDGE_TICKS = 80;
    private static final int STUCK_TICKS = 160;
    private static final double NUDGE_MAX = 2.0;
    /** Where vanilla's rope would snap: an animal this far behind is brought up at once. */
    private static final double ROPE_BREAK_DIST = 10.0;
    /** Further than {@link #TOO_FAR_DIST} this long (5 s): brought up too. */
    private static final int FAR_TICKS = 100;
    /** Brought up onto the trail no further than this from the villager. */
    private static final double BRING_UP_MAX = 4.0;
    /**
     * Times an animal is brought up at one snag; one stuck past that is given
     * up as before. The count starts again once it has walked
     * {@link #SNAG_CLEAR_CRUMBS} crumbs on its own, so a long walk with a few
     * awkward corners no longer runs out halfway.
     */
    private static final int MAX_BRING_UPS = 4;
    /** Crumbs walked (about 9 blocks) after a bring-up before the next snag gets its own four. */
    private static final int SNAG_CLEAR_CRUMBS = 12;
    /** And never more than this in one walk: an animal that falls back every few steps is given up. */
    private static final int MAX_WALK_BRING_UPS = 12;
    /** Last steps (into the Butchery or the pen): no closer this long (6 s), the animal is put on its spot. */
    private static final int PLACE_TICKS = 120;
    /** Put on its spot only from this near: a snag on the door frame, not a wall away. */
    private static final double PLACE_MAX = 4.0;
    /** The spot moved this far: a new spot, and the stall count starts again. */
    private static final double SPOT_MOVED = 0.5;

    private final ArrayDeque<Vec3> trail = new ArrayDeque<>();
    /** Where the last crumb went, or where the trail started. */
    @Nullable private Vec3 lastCrumb;

    /** Crumbs the animal has reached since the walk began. */
    private long reachedCount;

    /** The villager's last walking speed, kept while it stands waiting. */
    private double leaderSpeed;

    /** Ticks in a row the animal has been further than {@link #TOO_FAR_DIST}. */
    private int farTicks;
    /** Times the animal was brought up to the villager this walk; only ever goes up (doorways read it). */
    private int bringUps;
    /** Bring-ups at the snag it is at now; back to 0 once it walks clear. */
    private int snagBringUps;
    /** {@link #reachedCount} just after the last bring-up. */
    private long reachedAtBringUp;

    private double bestDist = Double.MAX_VALUE;
    private int stallTicks;
    private boolean nudged;
    /** Last tick the animal stood at its villager's heels, the crumbs left too near the villager to step on. */
    private boolean atHeelsNow;

    /** The last-steps stall count, apart from the trail's: the trail resets its own every tick once it is walked. */
    @Nullable private Vec3 lastSpot;
    private double lastBest = Double.MAX_VALUE;
    private int lastStall;
    private boolean lastNudged;
    private int lastStepTick;

    /** Starts (or restarts) the trail from where the animal stands now. */
    public void restartFrom(Vec3 start) {
        trail.clear();
        lastCrumb = start;
        resetProgress();
    }

    /**
     * A lead put on from a distance (the animal is somewhere the villager
     * could not walk to): the trail starts as the villager's own last
     * footsteps, oldest first. The animal steers along them toward the
     * villager, and if it cannot climb out its stuck rules bring it up onto
     * one of them: floor the villager just stood on, on its side of the wall.
     */
    public void seedFootsteps(Iterable<Vec3> footsteps) {
        trail.clear();
        for (Vec3 step : footsteps) {
            if (trail.size() >= MAX_CRUMBS) break;
            trail.addLast(step);
            lastCrumb = step;
        }
        resetProgress();
    }

    /** Crumbs the animal still has to reach: how far behind it is. */
    public int crumbsBehind() {
        return trail.size();
    }

    /**
     * Whether the animal stood still at its villager's heels on the last
     * {@link #tick}: its next crumbs lie too near the villager to step on, so
     * the trail has taken it as far as it goes although crumbs are left.
     */
    public boolean waitingAtHeels() {
        return atHeelsNow;
    }

    /** The crumbs, oldest first, for doorway checks; do not modify. */
    public Iterable<Vec3> crumbs() {
        return trail;
    }

    @Nullable
    public Vec3 target() {
        return trail.peekFirst();
    }

    public Result tick(NpcEntity npc, Animal animal) {
        atHeelsNow = false;
        if (!animal.isAlive() || animal.isRemoved() || !LedAnimals.isLed(animal)) return Result.LOST;
        if (lastCrumb == null) lastCrumb = animal.position();
        record(npc);

        animal.getNavigation().stop();
        floatInWater(animal);

        Vec3 target = trail.peekFirst();
        while (target != null && (reached(animal, target) || underVillager(npc, animal, target))) {
            trail.pollFirst();
            reachedCount++;
            resetProgress();
            target = trail.peekFirst();
        }

        double toVillager = animal.distanceTo(npc);
        // Where vanilla's rope would snap, or far behind for a while: brought
        // up onto the villager's own trail, a few steps behind it.
        farTicks = toVillager > TOO_FAR_DIST ? farTicks + 1 : 0;
        if ((toVillager > ROPE_BREAK_DIST || farTicks >= FAR_TICKS) && bringUp(npc, animal)) {
            log(animal, String.format("%.1f", toVillager) + " behind the villager: brought up");
            return Result.FOLLOWING;
        }
        if (target != null && atHeels(npc, animal, target)) {
            // The next crumb is too close to the villager, who has stopped:
            // wait here, half a block clear of it, until it walks on.
            standStill(animal);
            resetProgress();
            atHeelsNow = true;
            if (toVillager > TOO_FAR_DIST) return Result.TOO_FAR;
            return toVillager <= CAUGHT_UP_DIST ? Result.CAUGHT_UP : Result.FOLLOWING;
        }
        if (target == null) {
            // No crumb left: the villager has barely moved since the last one.
            // Stand, never walk straight at the villager (or onto it).
            standStill(animal);
            resetProgress();
            if (toVillager > TOO_FAR_DIST) return Result.TOO_FAR;
            return toVillager <= CAUGHT_UP_DIST ? Result.CAUGHT_UP : Result.FOLLOWING;
        }

        double speed = paceOf(npc, animal) * (trail.size() >= CATCH_UP_CRUMBS ? CATCH_UP_SPEED : SPEED);
        animal.getMoveControl().setWantedPosition(target.x, target.y, target.z, speed);
        animal.getLookControl().setLookAt(target.x, target.y + animal.getEyeHeight(), target.z);

        Result stuck = checkStuck(npc, animal, target);
        if (stuck != null) return stuck;
        if (toVillager > TOO_FAR_DIST) return Result.TOO_FAR;
        return Result.FOLLOWING;
    }

    // -------------------------------------------------------------------------
    // Recording
    // -------------------------------------------------------------------------

    private void record(NpcEntity npc) {
        if (!npc.onGround() && !npc.isInWater()) return;
        // Through a double door the crumb goes on the seam between the leaves:
        // the animal does not fit down the middle of one.
        Vec3 feet = LeadDoorways.laneThrough(npc.level(), npc.position());
        if (lastCrumb != null && horizontalDistSqr(feet, lastCrumb) < CRUMB_SPACING * CRUMB_SPACING
                && Math.abs(feet.y - lastCrumb.y) < 0.5) return;
        if (isHazard(npc.level(), npc.blockPosition())) return;
        // Full trail: hold it. The villager waits long before this (4+ behind),
        // and dropping a crumb the animal still needs would make it cut a corner.
        if (trail.size() >= MAX_CRUMBS) return;
        trail.addLast(feet);
        lastCrumb = feet;
    }

    /** Never lead through fire, lava, powder snow or onto magma. */
    private static boolean isHazard(Level level, BlockPos feet) {
        BlockState at = level.getBlockState(feet);
        if (at.is(Blocks.FIRE) || at.is(Blocks.SOUL_FIRE) || at.is(Blocks.LAVA)
                || at.is(Blocks.POWDER_SNOW)) return true;
        if (at.getFluidState().is(FluidTags.LAVA)) return true;
        return level.getBlockState(feet.below()).is(Blocks.MAGMA_BLOCK);
    }

    // -------------------------------------------------------------------------
    // Steering
    // -------------------------------------------------------------------------

    private static boolean reached(Animal animal, Vec3 crumb) {
        return horizontalDistSqr(animal.position(), crumb) <= REACHED_XZ * REACHED_XZ
                && Math.abs(animal.getY() - crumb.y) < REACHED_Y;
    }

    /**
     * The newest crumb, where the villager stands now, once the animal is
     * already close to the villager: it stops short rather than walk into it.
     * While the villager walks on, the crumb is soon clear of it and is kept.
     */
    private boolean underVillager(NpcEntity npc, Animal animal, Vec3 crumb) {
        return trail.size() == 1
                && horizontalDistSqr(npc.position(), crumb) < STAND_OFF * STAND_OFF
                && horizontalDistSqr(npc.position(), animal.position()) < STAND_OFF * STAND_OFF;
    }

    /**
     * The animal's speed factor that matches the villager's walk: the
     * villager's current speed over the animal's own, never below its normal
     * walk. Both are the same move-speed units, so a slow cow is sped up and
     * a quick chicken is left as it is.
     */
    private double paceOf(NpcEntity npc, Animal animal) {
        if (npc.getSpeed() > 0f) leaderSpeed = npc.getSpeed();
        double own = animal.getAttributeValue(Attributes.MOVEMENT_SPEED);
        if (own <= 0d || leaderSpeed <= 0d) return 1.0;
        return Math.min(MAX_PACE, Math.max(1.0, leaderSpeed / own));
    }

    /**
     * How near the animal may come to the villager, centre to centre: both
     * half-widths and {@link #GAP} between them (1.25 for a cow).
     */
    public static double keepOff(NpcEntity npc, Animal animal) {
        return (npc.getBbWidth() + animal.getBbWidth()) / 2.0 + GAP;
    }

    /**
     * The animal is close behind and its next crumb lies inside the
     * villager's {@link #keepOff} distance. Only while it is close: a crumb
     * near the villager with the animal still far off is a trail that doubled
     * back, and the animal must still walk it.
     */
    private static boolean atHeels(NpcEntity npc, Animal animal, Vec3 crumb) {
        double keep = keepOff(npc, animal);
        double near = keep + CRUMB_SPACING;
        return horizontalDistSqr(npc.position(), crumb) < keep * keep
                && horizontalDistSqr(npc.position(), animal.position()) < near * near;
    }

    static void standStill(Animal animal) {
        animal.getMoveControl().setWantedPosition(animal.getX(), animal.getY(), animal.getZ(), 0.0);
        animal.setZza(0f);
        animal.setXxa(0f);
    }

    /**
     * Its float goal is off with the rest of its goals, so it is kept afloat
     * here, as {@code FloatGoal} would.
     */
    private static void floatInWater(Animal animal) {
        boolean deep = animal.isInWater() && animal.getFluidHeight(FluidTags.WATER) > animal.getFluidJumpThreshold();
        if ((deep || animal.isInLava()) && animal.getRandom().nextFloat() < 0.8f) {
            animal.getJumpControl().jump();
        }
    }

    @Nullable
    private Result checkStuck(NpcEntity npc, Animal animal, Vec3 target) {
        double dist = Math.sqrt(horizontalDistSqr(animal.position(), target))
                + Math.abs(animal.getY() - target.y);
        if (dist < bestDist - PROGRESS) {
            bestDist = dist;
            stallTicks = 0;
            return null;
        }
        stallTicks++;
        if (stallTicks == HOP_TICKS) {
            animal.getJumpControl().jump();
            log(animal, "no closer for 2 s: hop");
        } else if (stallTicks == NUDGE_TICKS && !nudged) {
            nudged = true;
            log(animal, "no closer for 4 s: slide " + (nudge(animal, target) ? "done" : "blocked"));
        } else if (stallTicks >= STUCK_TICKS) {
            // A hop and a nudge did not free it: brought up past whatever holds it.
            if (bringUp(npc, animal)) {
                log(animal, "no closer for 8 s: brought up behind the villager (" + snagBringUps + " at this snag, " + bringUps + " this walk)");
                return null;
            }
            log(animal, "STUCK: no bring-up left or no room near the villager (" + snagBringUps + " at this snag, "
                    + bringUps + " this walk, villager " + String.format("%.1f", animal.distanceTo(npc)) + " away)");
            return Result.STUCK;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Last steps
    // -------------------------------------------------------------------------

    /**
     * Steers the animal its last steps onto {@code spot} (through the
     * Butchery door, or in past the pen gate), after {@link #tick} this tick.
     * Those steps are off the trail, so the trail's stuck rules never saw
     * them and an animal snagged on the door frame stood there until the
     * walk was given up. Here it gets the same help: no closer for 2 s, a
     * hop; 4 s, a short slide onto the spot if nothing is in the way; 6 s,
     * put on the spot if its body fits there. True on the tick it was put.
     */
    public boolean lastSteps(Animal animal, Vec3 spot, double speed) {
        animal.getMoveControl().setWantedPosition(spot.x, spot.y, spot.z, speed);
        // A new spot, or not steered here last tick: counted from now.
        if (lastSpot == null || lastSpot.distanceTo(spot) > SPOT_MOVED || animal.tickCount - lastStepTick > 2) {
            lastSpot = spot;
            lastBest = Double.MAX_VALUE;
            lastStall = 0;
            lastNudged = false;
        }
        lastStepTick = animal.tickCount;
        double dist = Math.sqrt(horizontalDistSqr(animal.position(), spot)) + Math.abs(animal.getY() - spot.y);
        if (dist < lastBest - PROGRESS) {
            lastBest = dist;
            lastStall = 0;
            return false;
        }
        lastStall++;
        if (lastStall == HOP_TICKS) {
            animal.getJumpControl().jump();
            log(animal, "last steps, no closer for 2 s: hop");
        } else if (lastStall == NUDGE_TICKS && !lastNudged) {
            lastNudged = true;
            log(animal, "last steps, no closer for 4 s: slide " + (nudge(animal, spot) ? "done" : "blocked"));
        } else if (lastStall >= PLACE_TICKS) {
            log(animal, "last steps, no closer for 6 s: " + (dist <= PLACE_MAX && fitsAt(animal, spot)
                    ? "put on its spot" : "spot too far or no room, not moved"));
            lastStall = 0;
            lastBest = Double.MAX_VALUE;
            lastNudged = false;
            if (dist <= PLACE_MAX && fitsAt(animal, spot)) {
                animal.moveTo(spot.x, spot.y, spot.z, animal.getYRot(), animal.getXRot());
                animal.setDeltaMovement(Vec3.ZERO);
                animal.resetFallDistance();
                animal.getNavigation().stop();
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Bringing a lost animal up
    // -------------------------------------------------------------------------

    /**
     * Puts the animal on the trail a few steps behind the villager: the newest
     * crumb at least {@link #STAND_OFF} and at most {@link #BRING_UP_MAX} from
     * it where the animal's body fits (on a stair's lower step: on the step's
     * top, {@link #landingFor}). Crumbs are spots the villager itself
     * just walked, so the animal lands on floor, on the right side of every
     * wall and gate. The crumbs before that one lie behind it now and go.
     * False when no crumb fits or this walk has used up its bring-ups.
     */
    private boolean bringUp(NpcEntity npc, Animal animal) {
        if (reachedCount - reachedAtBringUp >= SNAG_CLEAR_CRUMBS) snagBringUps = 0; // walked clear of the last snag
        if (snagBringUps >= MAX_BRING_UPS || bringUps >= MAX_WALK_BRING_UPS) return false;
        Vec3 spot = null;
        Vec3 land = null;
        for (Iterator<Vec3> it = trail.descendingIterator(); it.hasNext(); ) {
            Vec3 crumb = it.next();
            double toVillager = Math.sqrt(horizontalDistSqr(crumb, npc.position()));
            if (toVillager < STAND_OFF) continue;
            if (toVillager > BRING_UP_MAX) break;
            land = landingFor(animal, crumb);
            if (land != null) {
                spot = crumb;
                break;
            }
        }
        if (spot == null) return false;
        while (!trail.isEmpty() && trail.pollFirst() != spot) reachedCount++;
        reachedCount++;
        if (land != spot) log(animal, "brought up onto the top of a stair step, not the step the villager stood on");
        animal.moveTo(land.x, land.y, land.z, animal.getYRot(), animal.getXRot());
        animal.setDeltaMovement(Vec3.ZERO);
        animal.resetFallDistance();
        animal.getNavigation().stop();
        bringUps++;
        snagBringUps++;
        reachedAtBringUp = reachedCount;
        farTicks = 0;
        resetProgress();
        return true;
    }

    /**
     * Puts the animal straight onto {@code spot} if its body fits there, out
     * of fire and lava, and starts its trail again from there. For an animal
     * stuck outside the room or pen its villager already stands in: a single
     * door is narrower than a cow or pig, so no hop or bring-up gets it in.
     */
    public boolean placeAt(Animal animal, Vec3 spot) {
        if (!fitsAt(animal, spot)) return false;
        animal.moveTo(spot.x, spot.y, spot.z, animal.getYRot(), animal.getXRot());
        animal.setDeltaMovement(Vec3.ZERO);
        animal.resetFallDistance();
        animal.getNavigation().stop();
        restartFrom(spot);
        // Counts as a bring-up and as progress: a doorway it skipped is let
        // go (LeadDoorways), and the doorway clock starts again.
        bringUps++;
        reachedCount++;
        return true;
    }

    /**
     * Where a bring-up onto {@code crumb} puts the animal: the crumb if its
     * body fits there, else on top of the stair the crumb lies in. A crumb on
     * a stair's lower step is where the villager stood, but the animal is
     * wider (0.9 to the villager's 0.6) and its box reaches into the step's
     * riser, so near stairs no crumb fitted and the walk was given up. Null
     * when neither fits.
     */
    @Nullable
    private static Vec3 landingFor(Animal animal, Vec3 crumb) {
        if (fitsAt(animal, crumb)) return crumb;
        BlockPos cell = BlockPos.containing(crumb);
        if (!(animal.level().getBlockState(cell).getBlock() instanceof StairBlock)) return null;
        Vec3 top = new Vec3(crumb.x, cell.getY() + 1.0, crumb.z);
        return fitsAt(animal, top) ? top : null;
    }

    /** Whether the animal's body fits standing at {@code spot}, out of fire and lava. */
    private static boolean fitsAt(Animal animal, Vec3 spot) {
        // A chunk still loading has no blocks, so it "fits" anywhere (FarAwake).
        if (!com.alex.test.village.farawake.FarAwake.readyFor(animal.level(), spot.x, spot.z)) return false;
        if (isHazard(animal.level(), BlockPos.containing(spot))) return false;
        AABB box = animal.getBoundingBox().move(spot.subtract(animal.position()));
        return animal.level().noCollision(animal, box);
    }

    /** Times the animal was brought up this walk; doorways it skipped count as passed. */
    public int bringUps() {
        return bringUps;
    }

    /** A short move onto the crumb when nothing is in the way: never through a wall. False when it did not move. */
    private static boolean nudge(Animal animal, Vec3 crumb) {
        if (animal.position().distanceTo(crumb) > NUDGE_MAX) return false;
        if (!com.alex.test.village.farawake.FarAwake.readyFor(animal.level(), crumb.x, crumb.z)) return false;
        AABB box = animal.getBoundingBox().move(crumb.subtract(animal.position()));
        if (!animal.level().noCollision(animal, box)) return false;
        if (!clearBetween(animal, crumb)) return false;
        animal.moveTo(crumb.x, crumb.y, crumb.z, animal.getYRot(), animal.getXRot());
        return true;
    }

    /** One {@code [Lead]} line in the log: what the stuck rules did, and where. */
    public static void log(Animal animal, String what) {
        DynamicVillages.LOGGER.info("[Lead] {} at ({}, {}, {}): {}", animal.getType().getDescription().getString(),
                String.format("%.1f", animal.getX()), String.format("%.1f", animal.getY()),
                String.format("%.1f", animal.getZ()), what);
    }

    /** Whether the animal's box can slide from where it is to the crumb without hitting a block. */
    private static boolean clearBetween(Animal animal, Vec3 crumb) {
        Vec3 from = animal.position();
        for (int i = 1; i <= 4; i++) {
            Vec3 step = from.lerp(crumb, i / 4.0);
            AABB box = animal.getBoundingBox().move(step.subtract(from));
            if (!animal.level().noCollision(animal, box)) return false;
        }
        return true;
    }

    private void resetProgress() {
        bestDist = Double.MAX_VALUE;
        stallTicks = 0;
        nudged = false;
    }

    private static double horizontalDistSqr(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    /** Crumbs the animal has reached since the walk began; counts how far past a doorway it is. */
    public long reachedCount() {
        return reachedCount;
    }

    /** Whether the part of the trail the animal has still to walk runs through {@code block}. */
    public boolean trailPasses(BlockPos block) {
        for (Iterator<Vec3> it = trail.iterator(); it.hasNext(); ) {
            if (BlockPos.containing(it.next()).equals(block)) return true;
        }
        return false;
    }
}

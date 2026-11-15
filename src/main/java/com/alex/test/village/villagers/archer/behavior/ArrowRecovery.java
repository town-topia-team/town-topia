package com.alex.test.village.villagers.archer.behavior;

import com.alex.test.animation.HurryGait;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VanillaNavCount;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.WalkFeel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.MoveActivity;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * An archer's own arrows that missed and stuck in the ground, walked to and
 * picked up once the fight is over. A quarter of them break. Arrows that hit a
 * mob are gone, as in vanilla. Arrows stuck high in a wall, out of the
 * village or far away are left.
 *
 * <p>Practice arrows are gathered by the practice itself: pulled out of the
 * target from the spot in front of it ({@link #pullOneInReach}), and the
 * misses walked to ({@link #tickPractice}). An arrow that landed in a hay bale
 * never breaks.
 *
 * <p>Each archer has one tracker ({@link #of}), shared by its duty and its
 * range time. A session cut short (range time over, a chat, an alarm) leaves
 * its arrows on the list, and whatever the archer does next picks them up.
 *
 * <p>The run to an arrow is a village-map walk ({@link WalkFeel#arrowMap}):
 * round by the streets, through gates and doors. An arrow with no map tile by
 * it, or none the walk can reach, is left at once. The last step from the map
 * tile to the arrow, under 1.8 blocks with room for the body, is steered
 * straight. With the switch off it is Minecraft's own path, as before.
 */
final class ArrowRecovery {

    /** Share of picked-up arrows that turn out broken. Practice arrows hit straw and soft ground. */
    private static final float BREAK_CHANCE = 0.25f;
    private static final float PRACTICE_BREAK_CHANCE = 0.05f;
    /** Arrows older than this are no longer looked for (vanilla despawns them at 1200 in the ground). */
    private static final int TRACK_TICKS = 1100;
    private static final int MAX_TRACKED = 48;
    private static final double SEARCH_RADIUS_SQ = 20.0 * 20.0;
    /** Above the archer's feet it would need to climb for it. */
    private static final double MAX_REACH_UP = 2.5;
    /** A target sits up to three blocks above the range floor; its arrows are still pulled out. */
    private static final double PULL_REACH_UP = 4.2;
    private static final double PULL_REACH_DOWN = 1.0;
    private static final double PULL_REACH_XZ_SQ = 2.5 * 2.5;
    private static final double PICKUP_XZ_SQ = 1.6 * 1.6;
    private static final int WALK_GIVE_UP_TICKS = 160;
    /** After the map walk, an arrow this close (1.8 blocks, squared) is stepped to straight. */
    private static final double LAST_STEP_SQ = 1.8 * 1.8;
    /** How close to a hay bale a stuck arrow must be to count as landed in it. */
    private static final double HAY_TOUCH = 0.2;

    private record Fired(UUID arrowId, ItemStack stack, long firedAt, boolean practice) {}

    private static final int MAX_ARCHERS = 256;
    /** Archer npc id -> its tracker. */
    private static final Map<UUID, ArrowRecovery> BY_ARCHER = new HashMap<>();

    private final List<Fired> fired = new ArrayList<>();
    @Nullable private Fired current;
    private int walkTicks;
    /** The village-map walk to {@link #current}; null on Minecraft's path or on the last step. */
    @Nullable private MoveActivity move;
    @Nullable private VillagerModel moveModel;
    @Nullable private BlockPos moveTile;
    /** The map walk is over and the arrow is a step away: steered straight at it. */
    private boolean lastStep;

    /** A tracker of its own, for an activity that has not started yet (see {@link #of}). */
    ArrowRecovery() {}

    /**
     * This archer's tracker. Each activity used to keep its own, so practice
     * arrows still in the target when range time ended (or a chat cut in)
     * were forgotten and left to vanish.
     */
    static ArrowRecovery of(UUID archer) {
        if (BY_ARCHER.size() > MAX_ARCHERS) {
            BY_ARCHER.values().removeIf(r -> r.fired.isEmpty() && r.current == null);
        }
        return BY_ARCHER.computeIfAbsent(archer, id -> new ArrowRecovery());
    }

    /** How far above the archer's feet an arrow is still worth fetching. */
    private static double reachUp(Fired f) {
        return f.practice() ? PULL_REACH_UP : MAX_REACH_UP;
    }

    void remember(AbstractArrow arrow, ItemStack stack, long now, boolean practice) {
        if (fired.size() >= MAX_TRACKED) fired.remove(0);
        fired.add(new Fired(arrow.getUUID(), stack.copyWithCount(1), now, practice));
    }

    boolean isBusy() {
        return current != null;
    }

    void cancel(NpcEntity npc) {
        if (current != null) {
            npc.getNavigation().stop();
            npc.stopHurry();
        }
        stopWalk();
        current = null;
        walkTicks = 0;
    }

    /**
     * Drops the map walk. The model is only stopped while it is still on this
     * walk: whatever the archer does next may already have asked for another.
     */
    private void stopWalk() {
        if (moveModel != null && moveTile != null && moveTile.equals(moveModel.moveGoal())) {
            moveModel.stopMoving();
        }
        move = null;
        moveModel = null;
        moveTile = null;
        lastStep = false;
    }

    /**
     * Running to an arrow at a guard's pace (ArcherActivity.runSpeed). It used
     * to be vanilla navigation's 1.0, vanilla's "normal" -- but a villager's
     * walk is 0.5 here, and ground speed goes with the square of it, so the
     * archer covered about 11 blocks a second on walking legs, four times a walk.
     */
    private static void runTo(Activity.Context ctx, double x, double y, double z) {
        VanillaNavCount.note(VanillaNavCount.Site.ARROWS, ctx.npc());
        ctx.npc().getNavigation().moveTo(x, y, z, ArcherActivity.runSpeed(ctx));
    }

    /**
     * Sets off for {@code arrow}: a map walk to the map tile beside it (at most
     * one block off, on a floor no higher than the arrow and within reach of
     * it), or Minecraft's path with {@link WalkFeel#arrowMap} off or no map.
     * False when the map has no such tile: the arrow is out of reach.
     */
    private boolean startWalk(Activity.Context ctx, AbstractArrow arrow, Fired f) {
        stopWalk();
        NavGraph graph = ctx.mgr().getGraph();
        if (!WalkFeel.arrowMap || graph == null || graph.isEmpty()) {
            runTo(ctx, arrow.getX(), arrow.getY(), arrow.getZ());
            return true;
        }
        double ay = arrow.getY();
        double up = reachUp(f);
        NavNode tile = graph.nearestRoutable(arrow.blockPosition(), 1, (int) Math.ceil(up) + 1,
                node -> node.feetPos().getY() <= ay + 0.5 && ay - node.feetPos().getY() <= up);
        if (tile == null) return false;
        moveTile = tile.feetPos();
        moveModel = ctx.model();
        move = new MoveActivity(moveTile, ArcherActivity.runSpeed(ctx), true);
        return true;
    }

    private boolean inReach(NpcEntity npc, Vec3 at, Fired f) {
        double dx = at.x - npc.getX();
        double dz = at.z - npc.getZ();
        return dx * dx + dz * dz <= PICKUP_XZ_SQ && at.y - npc.getY() <= reachUp(f)
                && npc.getY() - at.y <= MAX_REACH_UP;
    }

    /** The arrow is a short step off, with room for the body halfway there and at it. */
    private static boolean lastStepClear(Activity.Context ctx, NpcEntity npc, Vec3 at) {
        double dx = at.x - npc.getX();
        double dz = at.z - npc.getZ();
        if (dx * dx + dz * dz > LAST_STEP_SQ) return false;
        for (double t : new double[]{0.5, 1.0}) {
            if (!ctx.level().noCollision(npc, npc.getBoundingBox().move(dx * t, 0.0, dz * t))) return false;
        }
        return true;
    }

    /**
     * One tick of picking up: RUNNING while walking to an arrow, DONE when
     * there is nothing left worth fetching.
     */
    Activity.Status tick(Activity.Context ctx) {
        return tick(ctx, f -> true);
    }

    /** As {@link #tick}, for this archer's practice arrows lying in {@code area} only. */
    Activity.Status tickPractice(Activity.Context ctx, Predicate<BlockPos> area) {
        return tick(ctx, f -> {
            if (!f.practice()) return false;
            AbstractArrow arrow = arrowOf(ctx, f);
            return arrow != null && area.test(arrow.blockPosition());
        });
    }

    /** As {@link #tick}, for this archer's arrows of any kind lying in {@code area} only. */
    Activity.Status tickIn(Activity.Context ctx, Predicate<BlockPos> area) {
        return tick(ctx, f -> {
            AbstractArrow arrow = arrowOf(ctx, f);
            return arrow != null && area.test(arrow.blockPosition());
        });
    }

    /**
     * The nearest of this archer's arrows lying in {@code area}, if at least
     * {@code atLeast} lie there; null otherwise. Only looks: nothing is
     * forgotten here, wherever the archer stands.
     */
    @Nullable
    BlockPos lyingIn(Activity.Context ctx, Predicate<BlockPos> area, int atLeast) {
        long now = ctx.level().getGameTime();
        NpcEntity npc = ctx.npc();
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        int count = 0;
        for (Fired f : fired) {
            if (now - f.firedAt() > TRACK_TICKS) continue;
            AbstractArrow arrow = arrowOf(ctx, f);
            if (arrow == null || !isLying(arrow) || !area.test(arrow.blockPosition())) continue;
            count++;
            double d = arrow.distanceToSqr(npc);
            if (d < best) {
                best = d;
                nearest = arrow.blockPosition();
            }
        }
        return count >= atLeast ? nearest : null;
    }

    private Activity.Status tick(Activity.Context ctx, Predicate<Fired> wanted) {
        NpcEntity npc = ctx.npc();
        long now = ctx.level().getGameTime();
        if (current != null) {
            AbstractArrow arrow = arrowOf(ctx, current);
            if (arrow == null || !isLying(arrow) || ++walkTicks > WALK_GIVE_UP_TICKS) {
                fired.remove(current);
                cancel(npc);
                return Activity.Status.RUNNING;
            }
            Vec3 at = arrow.position();
            if (inReach(npc, at, current)) {
                pickUp(ctx, arrow, current);
                fired.remove(current);
                cancel(npc);
                return Activity.Status.RUNNING;
            }
            if (move != null) {
                Activity.Status walk = move.tickOuter(ctx);
                if (walk != Activity.Status.RUNNING) {
                    // The map walk is over: as near as the map gets, or no way there.
                    move = null;
                    moveModel = null;
                    moveTile = null;
                    if (walk != Activity.Status.DONE
                            || (!inReach(npc, at, current) && !lastStepClear(ctx, npc, at))) {
                        fired.remove(current);
                        cancel(npc);
                        return Activity.Status.RUNNING;
                    }
                    lastStep = true;
                }
            }
            if (lastStep) {
                npc.getMoveControl().setWantedPosition(at.x, npc.getY(), at.z, ArcherActivity.runSpeed(ctx));
            } else if (move == null && npc.getNavigation().isDone()) {
                runTo(ctx, at.x, at.y, at.z);
            }
            npc.hurry(HurryGait.RUN);
            return Activity.Status.RUNNING;
        }

        Fired next = null;
        double best = Double.MAX_VALUE;
        Iterator<Fired> it = fired.iterator();
        while (it.hasNext()) {
            Fired f = it.next();
            AbstractArrow arrow = now - f.firedAt() > TRACK_TICKS ? null : arrowOf(ctx, f);
            if (arrow == null) {
                it.remove();
                continue;
            }
            if (!wanted.test(f) || !isLying(arrow)) continue;
            double d = arrow.distanceToSqr(npc);
            if (d > SEARCH_RADIUS_SQ || arrow.getY() - npc.getY() > reachUp(f)
                    || !ctx.mgr().contains(arrow.blockPosition())) {
                it.remove();
                continue;
            }
            if (d < best) {
                best = d;
                next = f;
            }
        }
        if (next == null) return Activity.Status.DONE;
        current = next;
        walkTicks = 0;
        AbstractArrow arrow = arrowOf(ctx, next);
        if (arrow != null) {
            if (!startWalk(ctx, arrow, next)) {
                // No map tile by it: left where it lies, the next one tried next tick.
                fired.remove(next);
                current = null;
                return Activity.Status.RUNNING;
            }
            npc.hurry(HurryGait.RUN);
        }
        return Activity.Status.RUNNING;
    }

    /**
     * Pulls one practice arrow stuck within arm's reach of where the archer
     * stands (in the target in front of it, or the ground at its feet).
     * Returns false when there is none left to pull.
     */
    boolean pullOneInReach(Activity.Context ctx) {
        NpcEntity npc = ctx.npc();
        Iterator<Fired> it = fired.iterator();
        while (it.hasNext()) {
            Fired f = it.next();
            if (!f.practice()) continue;
            AbstractArrow arrow = arrowOf(ctx, f);
            if (arrow == null) {
                it.remove();
                continue;
            }
            if (!isLying(arrow)) continue;
            Vec3 at = arrow.position();
            double dx = at.x - npc.getX();
            double dz = at.z - npc.getZ();
            double dy = at.y - npc.getY();
            if (dx * dx + dz * dz > PULL_REACH_XZ_SQ || dy > PULL_REACH_UP || dy < -PULL_REACH_DOWN) continue;
            npc.getLookControl().setLookAt(at.x, at.y, at.z, 30f, 30f);
            pickUp(ctx, arrow, f);
            it.remove();
            return true;
        }
        return false;
    }

    /** Whether any practice arrow fired by this archer is still out there. */
    boolean hasPracticeArrows(Activity.Context ctx) {
        for (Fired f : fired) {
            if (f.practice() && arrowOf(ctx, f) != null) return true;
        }
        return false;
    }

    /**
     * Not moving since last tick, as an arrow stuck in a block is; one still in
     * flight is not fetched yet. (1.21.1 keeps "in ground" private, and a stuck
     * arrow keeps the last bit of its velocity, so position is the tell.)
     */
    static boolean isLying(AbstractArrow arrow) {
        return arrow.distanceToSqr(arrow.xo, arrow.yo, arrow.zo) < 1.0E-6 && arrow.tickCount > 2;
    }

    @Nullable
    private static AbstractArrow arrowOf(Activity.Context ctx, Fired f) {
        Entity entity = ctx.level().getEntity(f.arrowId());
        return entity instanceof AbstractArrow arrow && arrow.isAlive() ? arrow : null;
    }

    private static void pickUp(Activity.Context ctx, AbstractArrow arrow, Fired f) {
        NpcEntity npc = ctx.npc();
        float breakChance = !f.practice() ? BREAK_CHANCE
                : landedInHay(ctx, arrow) ? 0f : PRACTICE_BREAK_CHANCE;
        arrow.discard();
        npc.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        if (npc.getRandom().nextFloat() < breakChance) return;
        if (npc.getInventory().addItem(f.stack().copy())) {
            ctx.level().playSound(null, npc.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL,
                    0.2f, 1.4f + npc.getRandom().nextFloat() * 0.4f);
        }
    }

    /** A stuck arrow sits a hair outside the block it hit: any hay bale that close holds it. */
    private static boolean landedInHay(Activity.Context ctx, AbstractArrow arrow) {
        Vec3 at = arrow.position();
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                for (int k = -1; k <= 1; k++) {
                    BlockPos pos = BlockPos.containing(at.x + i * HAY_TOUCH, at.y + j * HAY_TOUCH,
                            at.z + k * HAY_TOUCH);
                    if (ctx.level().isLoaded(pos) && ctx.level().getBlockState(pos).is(Blocks.HAY_BLOCK)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}

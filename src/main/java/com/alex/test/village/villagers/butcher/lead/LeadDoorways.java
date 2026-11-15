package com.alex.test.village.villagers.butcher.lead;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.DoorHolds;
import com.alex.test.village.RouteDoorHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The doors and gates a villager leads an animal through.
 *
 * <p>The villager's route handler opens a doorway and would shut it again
 * 2.5 blocks later, in the animal's face. So every doorway on the trail is
 * held open ({@link DoorHolds}) until the animal is two crumbs past it and
 * clear of it; then the leader lets go and shuts it itself. Nothing is ever
 * closed on an animal or a person standing in the doorway.
 *
 * <p>A double door is one doorway: both leaves are opened and held. An open
 * leaf leaves 13/16 of a block, narrower than a cow, pig or sheep (0.9), so
 * the animal only fits through the pair, down the seam between the leaves
 * ({@link #laneThrough}).
 *
 * <p>A doorway held for {@link #MAX_HOLD_TICKS} with the animal getting no
 * further along its trail is a failed walk: {@link #tick} reports it, the
 * leader gives up.
 */
public final class LeadDoorways {

    /**
     * A doorway is held open at most this long (12 s) with the animal getting
     * nowhere. Counted from its last crumb or bring-up, not from when the
     * hold began: from the hold, 8 s ran out at the ranch gate before an
     * animal snagged on a gate post got its bring-up (8 s after it stopped),
     * so the stuck rules never had their turn there.
     */
    public static final int MAX_HOLD_TICKS = 240;

    /** Crumbs the animal must be past a doorway before it may close. */
    private static final int PAST_CRUMBS = 2;

    private static final class Held {
        /** When the hold began, or the animal last got further along its trail. */
        long since;
        /** {@link LeadWalk#reachedCount()} at {@link #since}. */
        long reachedAtSince;
        /** {@link LeadWalk#bringUps()} when the hold began: a bring-up since may have carried the animal past. */
        final int bringUpsAtStart;
        /** The animal has stood in the doorway: it is going through, not still on its way to it. */
        boolean animalWasIn;
        /** {@link LeadWalk#reachedCount()} when the animal came out the other side, or -1. */
        long passedAt = -1;

        Held(long since, int bringUpsAtStart) {
            this.since = since;
            this.bringUpsAtStart = bringUpsAtStart;
        }
    }

    private final Map<BlockPos, Held> held = new HashMap<>();

    /** Whether any doorway is being held. */
    public boolean isHolding() {
        return !held.isEmpty();
    }

    /**
     * Holds the doorways on the trail, lets go of those the animal is past.
     *
     * @param clock the leader's {@link com.alex.test.village.brain.Activity.Context#clock()}:
     *              time paused for a player's screen does not count against a hold
     * @return true when a doorway has been held too long: give up the walk
     */
    public boolean tick(ServerLevel level, NpcEntity npc, Animal animal, LeadWalk walk, long clock) {
        // New doorways: the one the villager stands in, and any the trail runs through.
        addIfDoorway(level, npc, npc.blockPosition(), walk, clock);
        for (Vec3 crumb : walk.crumbs()) addIfDoorway(level, npc, BlockPos.containing(crumb), walk, clock);

        boolean overdue = false;
        List<BlockPos> done = new ArrayList<>();
        for (Map.Entry<BlockPos, Held> entry : held.entrySet()) {
            BlockPos anchor = entry.getKey();
            Held hold = entry.getValue();
            if (!isDoorway(level.getBlockState(anchor))) {
                done.add(anchor); // broken or replaced: nothing left to hold
                continue;
            }
            BlockPos partner = partnerOf(level, anchor);
            // Past = it has been in the doorway, is out of it, and none of the
            // trail it still has to walk runs back through it.
            boolean inNow = touches(animal, anchor, partner);
            if (inNow) hold.animalWasIn = true;
            // Brought up beyond it, the animal is through without ever standing in it.
            boolean skipped = !hold.animalWasIn && walk.bringUps() > hold.bringUpsAtStart;
            if (hold.passedAt < 0 && (hold.animalWasIn || skipped) && !inNow
                    && !trailPasses(walk, anchor) && (partner == null || !trailPasses(walk, partner))) {
                hold.passedAt = walk.reachedCount() - (skipped ? PAST_CRUMBS : 0);
            }
            boolean past = hold.passedAt >= 0 && walk.reachedCount() - hold.passedAt >= PAST_CRUMBS;
            if (past && !inNow) {
                if (tryClose(level, npc, anchor, partner)) done.add(anchor);
                else hold(level, npc, anchor, partner); // someone in the way: wait
                continue;
            }
            hold(level, npc, anchor, partner);
            // A crumb reached or a bring-up (both count crumbs) is progress: the clock starts again.
            if (walk.reachedCount() != hold.reachedAtSince) {
                hold.reachedAtSince = walk.reachedCount();
                hold.since = clock;
            }
            if (clock - hold.since > MAX_HOLD_TICKS) overdue = true;
        }
        for (BlockPos anchor : done) held.remove(anchor);
        return overdue;
    }

    /**
     * The walk is over (arrived, gave up, interrupted): let go of every
     * doorway, closing those nobody stands in. One the animal still stands in
     * is left open rather than shut on it.
     */
    public void releaseAll(ServerLevel level, NpcEntity npc, @Nullable Animal animal) {
        for (BlockPos anchor : held.keySet()) {
            BlockPos partner = partnerOf(level, anchor);
            if (animal != null && animal.isAlive() && touches(animal, anchor, partner)
                    || !tryClose(level, npc, anchor, partner)) {
                release(level, npc, anchor, partner);
                closeLater(level, anchor);
            }
        }
        held.clear();
    }

    // -------------------------------------------------------------------------
    // Doorways to shut once clear
    // -------------------------------------------------------------------------

    /** How long a doorway left open at the end of a walk is watched for a chance to shut it: 30 s. */
    private static final long CLOSE_LATER_TICKS = 600L;

    /** Dimension, doorway, and when to stop trying. */
    private static final Map<ResourceKey<Level>, Map<BlockPos, Long>> CLOSE_LATER = new HashMap<>();

    /**
     * Someone stood in the doorway when the walk ended. Without this nobody
     * would ever shut it: the route handler that opened it was gone too.
     */
    private static void closeLater(ServerLevel level, BlockPos anchor) {
        CLOSE_LATER.computeIfAbsent(level.dimension(), k -> new HashMap<>())
                .put(anchor.immutable(), level.getGameTime() + CLOSE_LATER_TICKS);
    }

    /** Every tick per level: shuts each waiting doorway once nobody stands in it or holds it. */
    public static void tickCloseLater(ServerLevel level) {
        Map<BlockPos, Long> doors = CLOSE_LATER.get(level.dimension());
        if (doors == null || doors.isEmpty()) return;
        long now = level.getGameTime();
        doors.entrySet().removeIf(entry -> {
            BlockPos anchor = entry.getKey();
            if (now > entry.getValue() || !level.isLoaded(anchor)) return true;
            if (!isDoorway(level.getBlockState(anchor))) return true;
            BlockPos partner = partnerOf(level, anchor);
            if (!isOpen(level, anchor) && !isOpen(level, partner)) return true;
            if (isHeld(level, anchor, partner) || occupied(level, anchor, partner)) return false;
            RouteDoorHandler.shutDoorAt(level, anchor, null);
            return true;
        });
    }

    /** Server stopping. */
    public static void clearCloseLater() {
        CLOSE_LATER.clear();
    }

    /** The animal is inside, at the end of the walk: shut every held doorway it is clear of. */
    public void closeClearOf(ServerLevel level, NpcEntity npc, Animal animal) {
        held.keySet().removeIf(anchor -> {
            BlockPos partner = partnerOf(level, anchor);
            return !touches(animal, anchor, partner) && tryClose(level, npc, anchor, partner);
        });
    }

    /**
     * Lets go of the doorway (both leaves of a double door) and shuts it,
     * unless someone stands in it or another villager still holds it.
     */
    private static boolean tryClose(ServerLevel level, NpcEntity npc, BlockPos anchor, @Nullable BlockPos partner) {
        if (occupied(level, anchor, partner)) return false;
        release(level, npc, anchor, partner);
        if (isHeld(level, anchor, partner)) return true; // another leader still needs it
        if (isOpen(level, anchor) || isOpen(level, partner)) {
            RouteDoorHandler.shutDoorAt(level, anchor, npc); // shuts an open other leaf too
        }
        return true;
    }

    private static void hold(ServerLevel level, NpcEntity npc, BlockPos anchor, @Nullable BlockPos partner) {
        DoorHolds.hold(level, anchor, npc.getUUID());
        if (partner != null) DoorHolds.hold(level, partner, npc.getUUID());
    }

    private static void release(ServerLevel level, NpcEntity npc, BlockPos anchor, @Nullable BlockPos partner) {
        DoorHolds.release(level, anchor, npc.getUUID());
        if (partner != null) DoorHolds.release(level, partner, npc.getUUID());
    }

    private static boolean isHeld(ServerLevel level, BlockPos anchor, @Nullable BlockPos partner) {
        return DoorHolds.isHeld(level, anchor) || partner != null && DoorHolds.isHeld(level, partner);
    }

    private void addIfDoorway(ServerLevel level, NpcEntity npc, BlockPos pos, LeadWalk walk, long clock) {
        BlockPos anchor = anchorOf(level, pos);
        if (anchor == null || held.containsKey(anchor)) return;
        BlockPos partner = partnerOf(level, anchor);
        if (partner != null && held.containsKey(partner)) return; // the pair is held under its other leaf
        if (!isOpen(level, anchor) && !isOpen(level, partner)) return; // only doorways the route opened
        // The route opened the leaf the villager walks through; the animal
        // needs the pair.
        if (partner != null) {
            RouteDoorHandler.openDoorAt(level, anchor, npc);
            RouteDoorHandler.openDoorAt(level, partner, npc);
        }
        Held hold = new Held(clock, walk.bringUps());
        hold.reachedAtSince = walk.reachedCount();
        held.put(anchor, hold);
    }

    /** The other leaf of a double door at {@code anchor}, or null for a single door or a gate. */
    @Nullable
    private static BlockPos partnerOf(ServerLevel level, BlockPos anchor) {
        return RouteDoorHandler.doubleDoorPartner(level, anchor);
    }

    private static boolean isOpen(ServerLevel level, @Nullable BlockPos anchor) {
        if (anchor == null) return false;
        BlockState state = level.getBlockState(anchor);
        return isDoorway(state) && state.getValue(BlockStateProperties.OPEN);
    }

    /** Whether the trail the animal has still to walk runs through the door leaf or gate at {@code anchor}. */
    private static boolean trailPasses(LeadWalk walk, BlockPos anchor) {
        return walk.trailPasses(anchor) || walk.trailPasses(anchor.above());
    }

    // -------------------------------------------------------------------------
    // The lane through a double door
    // -------------------------------------------------------------------------

    /**
     * Where a led animal should cross a double door: on the seam between the
     * two leaves, clear of both open panels, rather than down the middle of
     * the one leaf the villager walked through, where a 0.9-wide animal hits
     * the open panel. {@code feet} is a breadcrumb in the doorway or on the
     * tile just in front of or behind it; anything else, a single door, or a
     * pair hung with both hinges in the middle, comes back unchanged.
     */
    public static Vec3 laneThrough(BlockGetter level, Vec3 feet) {
        BlockPos at = BlockPos.containing(feet);
        BlockPos leaf = null;
        boolean inDoorway = false;
        BlockState here = level.getBlockState(at);
        if (here.getBlock() instanceof DoorBlock) {
            leaf = here.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? at.below() : at;
            inDoorway = true;
        } else {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos next = at.relative(dir);
                BlockState state = level.getBlockState(next);
                if (state.getBlock() instanceof DoorBlock
                        && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                        && state.getValue(DoorBlock.FACING).getAxis() == dir.getAxis()) {
                    leaf = next;
                    break;
                }
            }
        }
        if (leaf == null) return feet;
        BlockPos partner = RouteDoorHandler.doubleDoorPartner(level, leaf);
        if (partner == null) return feet;

        Direction toPartner = Direction.getNearest(
                partner.getX() - leaf.getX(), 0, partner.getZ() - leaf.getZ());
        // An open leaf lies against its hinge side; the seam is clear only
        // when both hinges are on the outside, as vanilla hangs a pair.
        BlockState leafState = level.getBlockState(leaf);
        Direction facing = leafState.getValue(DoorBlock.FACING);
        Direction panelSide = leafState.getValue(DoorBlock.HINGE) == DoorHingeSide.RIGHT
                ? facing.getClockWise() : facing.getCounterClockWise();
        if (panelSide != toPartner.getOpposite()) return feet;

        // In front of or behind the pair, the tile beside this one must be
        // open too, or the animal would be steered into a wall.
        if (!inDoorway) {
            BlockPos beside = at.relative(toPartner);
            if (!level.getBlockState(beside).getCollisionShape(level, beside).isEmpty()
                    || !level.getBlockState(beside.above()).getCollisionShape(level, beside.above()).isEmpty()) {
                return feet;
            }
        }

        Direction.Axis axis = toPartner.getAxis();
        double seam = leaf.get(axis) + (toPartner.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0);
        return axis == Direction.Axis.X
                ? new Vec3(seam, feet.y, feet.z)
                : new Vec3(feet.x, feet.y, seam);
    }

    /** A door's lower half or a gate at {@code pos}, or null. */
    @Nullable
    private static BlockPos anchorOf(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        // A tall gateway (stacked gates) is held by its bottom gate; the rest move with it.
        if (state.getBlock() instanceof FenceGateBlock) return RouteDoorHandler.gateAnchor(level, pos);
        if (state.getBlock() instanceof DoorBlock) {
            return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                    ? pos.below().immutable() : pos.immutable();
        }
        return null;
    }

    private static boolean isDoorway(BlockState state) {
        return state.getBlock() instanceof FenceGateBlock || state.getBlock() instanceof DoorBlock;
    }

    /** The doorway's block space, two high for a door, both leaves of a double door. */
    private static AABB doorwayBox(BlockPos anchor, @Nullable BlockPos partner) {
        AABB box = new AABB(anchor).expandTowards(0, 1, 0);
        return partner == null ? box : box.minmax(new AABB(partner).expandTowards(0, 1, 0));
    }

    private static boolean touches(Animal animal, BlockPos anchor, @Nullable BlockPos partner) {
        return animal.getBoundingBox().intersects(doorwayBox(anchor, partner));
    }

    /** Anyone, person or animal, standing in the doorway. */
    private static boolean occupied(ServerLevel level, BlockPos anchor, @Nullable BlockPos partner) {
        return !level.getEntitiesOfClass(LivingEntity.class, doorwayBox(anchor, partner), LivingEntity::isAlive)
                .isEmpty();
    }
}

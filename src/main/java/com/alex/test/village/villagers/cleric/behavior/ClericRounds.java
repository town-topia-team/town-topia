package com.alex.test.village.villagers.cleric.behavior;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.VillagerPause;
import com.alex.test.village.building.ArcheryRangeBuilding;
import com.alex.test.village.building.BarracksBuilding;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.ForgeBuilding;
import com.alex.test.village.building.KitchenBuilding;
import com.alex.test.village.building.MineBuilding;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.building.TownHallBuilding;
import com.alex.test.village.building.WatchtowerBuilding;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where a cleric on its rounds goes next and where it stands there, shared by
 * every cleric so they cover different parts of the village.
 *
 * <p>Each cleric picked its stops at random, skipping only the one it had
 * just left. With two stops that left one choice, so two clerics who once
 * met at a stop walked the rest of their rounds together, on the same block.
 * Now a cleric books the stop it is walking to or waiting at, and the others
 * pick a stop away from it; when every stop is booked, the one farthest from
 * the other clerics. A small village gets extra stops at its other doors.
 *
 * <p>It stands beside the doorway rather than on the path out of it, and
 * never on another cleric's spot.
 */
final class ClericRounds {

    /** A booking lapses this long after its cleric last renewed it. */
    private static final long CLAIM_TICKS = 60L;
    /** A stop this close to another cleric's booked stop counts as taken. */
    private static final double TAKEN_DIST = 8.0;
    /** A stop it could not walk to is left alone this long: five minutes. */
    private static final long BAD_STOP_TICKS = 20L * 60L * 5L;
    /** Two clerics never stand closer than this. */
    private static final double APART = 2.0;
    /** Stops are made from the door outward: this far out from it. */
    private static final int OUT = 2;
    /** Standing spots beside the doorway, as (out, sideways), nearest first. */
    private static final int[][] BESIDE = {{2, 2}, {3, 2}, {2, 3}, {1, 2}, {3, 3}, {3, 0}, {4, 0}};

    /** One place a cleric may go: where it is (by which it is booked), and where it stands. */
    record Stop(BlockPos base, BlockPos stand) {}

    /** A door to stand by, or a spot with no door (stood on as it is). */
    private record Place(BlockPos base, @Nullable BlockPos door, @Nullable Direction out) {}

    private record Claim(BlockPos base, BlockPos stand, long until) {}

    /** Each cleric's booked stop, by cleric. */
    private static final Map<UUID, Claim> CLAIMS = new HashMap<>();
    /** The stop each cleric was at last, kept across its work sessions. */
    private static final Map<UUID, BlockPos> LAST = new HashMap<>();
    /** Stops each cleric could not walk to, until when. */
    private static final Map<UUID, Map<BlockPos, Long>> BAD = new HashMap<>();

    private ClericRounds() {}

    /** The next stop for this cleric, booked for it, or null when the village has none. */
    @Nullable
    static Stop pick(Activity.Context ctx) {
        NpcEntity cleric = ctx.npc();
        long now = ctx.level().getGameTime();
        List<Place> places = places(ctx);
        if (places.isEmpty()) return null;
        List<Claim> others = otherClaims(cleric.getUUID(), now);
        BlockPos last = LAST.get(cleric.getUUID());

        List<Place> free = new ArrayList<>();
        Place farthest = null;
        double farthestDist = -1;
        for (Place place : places) {
            if (isBad(cleric.getUUID(), place.base(), now)) continue;
            double nearest = nearestOther(place.base(), others);
            if (nearest >= TAKEN_DIST && !place.base().equals(last)) free.add(place);
            // Ties go to a stop other than the last one.
            boolean better = nearest > farthestDist
                    || (nearest == farthestDist && farthest != null && farthest.base().equals(last));
            if (better) {
                farthestDist = nearest;
                farthest = place;
            }
        }
        Place place = free.isEmpty() ? farthest : free.get(cleric.getRandom().nextInt(free.size()));
        if (place == null) return null;
        Stop stop = new Stop(place.base(), standSpot(ctx, place, others));
        claim(cleric, stop, now);
        return stop;
    }

    /** Keeps this cleric's booking while it walks to or waits at {@code stop}. */
    static void claim(NpcEntity cleric, Stop stop, long now) {
        CLAIMS.put(cleric.getUUID(), new Claim(stop.base(), stop.stand(), now + CLAIM_TICKS));
    }

    /** The cleric is off its rounds or moving on: its stop is free for the others. */
    static void release(NpcEntity cleric) {
        CLAIMS.remove(cleric.getUUID());
    }

    /** Done with this stop: the next pick skips it when there is a choice. */
    static void finished(NpcEntity cleric, Stop stop) {
        LAST.put(cleric.getUUID(), stop.base());
    }

    /** Could not walk to this stop: left alone for a few minutes. */
    static void unreachable(NpcEntity cleric, Stop stop, long now) {
        finished(cleric, stop);
        Map<BlockPos, Long> mine = BAD.computeIfAbsent(cleric.getUUID(), k -> new HashMap<>());
        mine.put(stop.base(), now + BAD_STOP_TICKS);
        if (mine.size() > 32) mine.values().removeIf(until -> until <= now);
    }

    private static boolean isBad(UUID cleric, BlockPos base, long now) {
        Map<BlockPos, Long> mine = BAD.get(cleric);
        if (mine == null) return false;
        Long until = mine.get(base);
        if (until == null) return false;
        if (until <= now || until - now > BAD_STOP_TICKS) {
            mine.remove(base);
            return false;
        }
        return true;
    }

    private static List<Claim> otherClaims(UUID cleric, long now) {
        List<Claim> out = new ArrayList<>();
        for (Iterator<Map.Entry<UUID, Claim>> it = CLAIMS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Claim> entry = it.next();
            Claim claim = entry.getValue();
            if (VillagerPause.lapsed(entry.getKey(), claim.until(), now) || claim.until() - now > CLAIM_TICKS) {
                it.remove();
            } else if (!entry.getKey().equals(cleric)) {
                out.add(claim);
            }
        }
        return out;
    }

    private static double nearestOther(BlockPos base, List<Claim> others) {
        double nearest = Double.MAX_VALUE;
        for (Claim claim : others) nearest = Math.min(nearest, Math.sqrt(claim.base().distSqr(base)));
        return nearest;
    }

    /**
     * The places people get hurt, or that most of them pass: just outside the
     * door of the mine, barracks, watchtower, archery range, forge, kitchen,
     * storage or Town Hall. A village with fewer of those than its clerics
     * and two more adds its other doors (houses, the tavern, the library...),
     * and then a few spots in the Town Hall.
     */
    private static List<Place> places(Activity.Context ctx) {
        List<Place> main = new ArrayList<>();
        List<Place> extra = new ArrayList<>();
        for (Building b : ctx.mgr().getBuildings()) {
            if (!b.isValid() || b.getDoorPos() == null || b.getInteriorDirection() == null) continue;
            Direction out = b.getInteriorDirection().getOpposite();
            Place place = new Place(b.getDoorPos().relative(out, OUT), b.getDoorPos(), out);
            if (b instanceof MineBuilding || b instanceof BarracksBuilding || b instanceof WatchtowerBuilding
                    || b instanceof ArcheryRangeBuilding || b instanceof ForgeBuilding
                    || b instanceof KitchenBuilding || b instanceof StorageBuilding
                    || b instanceof TownHallBuilding) {
                main.add(place);
            } else {
                extra.add(place);
            }
        }
        int wanted = clericCount(ctx) + 2;
        if (main.size() >= wanted) return main;
        java.util.Collections.shuffle(extra, new java.util.Random(ctx.npc().getRandom().nextLong()));
        for (Place place : extra) {
            if (main.size() >= wanted) break;
            main.add(place);
        }
        for (int i = 0; main.size() < wanted && i < 3; i++) {
            BlockPos spot = ctx.mgr().getTownHallSpot(ctx.npc().blockPosition());
            if (spot != null) main.add(new Place(spot, null, null));
        }
        return main;
    }

    private static int clericCount(Activity.Context ctx) {
        int count = 0;
        for (VillagerModel model : ctx.mgr().getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc != null && npc.isAlive() && npc.getProfession() == Profession.CLERIC) count++;
        }
        return Math.max(1, count);
    }

    /**
     * Beside the doorway, on ground villagers walk on, a few blocks from any
     * other cleric's spot; which side first is random. The old spot straight
     * out from the door when nothing beside it will do.
     */
    private static BlockPos standSpot(Activity.Context ctx, Place place, List<Claim> others) {
        if (place.door() == null || place.out() == null || !place.out().getAxis().isHorizontal()) {
            return place.base();
        }
        NavGraph graph = ctx.mgr().graph();
        if (graph == null) return place.base();
        Direction out = place.out();
        Direction side = ctx.npc().getRandom().nextBoolean() ? out.getClockWise() : out.getCounterClockWise();
        for (int flip = 0; flip < 2; flip++) {
            Direction s = flip == 0 ? side : side.getOpposite();
            for (int[] offset : BESIDE) {
                BlockPos at = place.door().relative(out, offset[0]).relative(s, offset[1]);
                for (int dy : new int[] {0, 1, -1}) {
                    BlockPos spot = at.above(dy);
                    if (standable(ctx, graph, spot) && clearOfOthers(spot, others)) return spot;
                }
            }
        }
        return place.base();
    }

    private static boolean standable(Activity.Context ctx, NavGraph graph, BlockPos spot) {
        NavNode node = graph.nodeAt(spot.getX(), spot.getY(), spot.getZ());
        return node != null && node.isRoutable() && !node.climb && !node.portal && !node.ladderFloor
                && !node.openTrapdoor && ctx.level().getFluidState(spot).isEmpty()
                && ctx.mgr().contains(spot);
    }

    private static boolean clearOfOthers(BlockPos spot, List<Claim> others) {
        for (Claim claim : others) {
            if (claim.stand().distSqr(spot) < APART * APART) return false;
        }
        return true;
    }
}

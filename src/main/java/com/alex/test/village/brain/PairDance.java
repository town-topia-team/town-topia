package com.alex.test.village.brain;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Two villagers dancing the kick line together on a 2x2 patch of floor.
 *
 * <p>The pair stands side by side on the back two tiles of the patch, one
 * block apart, facing over the front two, which their kicks reach into. The
 * dancer with its partner on its right plays {@link VillagerAnimationState#JAZZ_PAIR_R},
 * the other {@link VillagerAnimationState#JAZZ_PAIR_L}: each clip lays the inner
 * arm across the partner's upper back, solved for exactly this spacing
 * (tools/mocap/jazz_pair_arms.py), so the dancers are placed on the tile
 * centres, not wherever the walk happened to stop.
 *
 * <p>Neither starts before both have arrived: each {@link PairDanceActivity}
 * reports in, and once both have, the dance is given a start tick a few ticks
 * ahead that both act on, so the two clips begin on the same server tick.
 *
 * <p>All four tiles are held in {@link StandClaims} under the dance's own id for
 * as long as it lasts, so no evening guest picks one to stand on.
 *
 * <p>In memory only: a reload ends every dance (the activities are not saved).
 */
public final class PairDance {

    public enum Stage { GATHERING, DANCING, OVER }

    /** The partner has this long to turn up, from the start of the walk over. */
    public static final int GATHER_TIMEOUT_TICKS = 400;
    /** Both arrived: start this many ticks later, so both activities see the same tick coming. */
    private static final int START_DELAY_TICKS = 3;
    /** The music stopped this long ago (a bard's meal break is shorter): finish the round. */
    private static final int MUSIC_GRACE_TICKS = 200;

    private static final Map<ServerLevel, List<PairDance>> ACTIVE = new WeakHashMap<>();

    public final UUID id = UUID.randomUUID();
    private final ServerLevel level;
    private final List<BlockPos> tiles;
    private final Direction facing;
    /** Dancer whose partner is on its right: stands on the left tile, plays JAZZ_PAIR_R. */
    private final UUID partnerOnRight;
    private final UUID partnerOnLeft;
    private final Vec3 leftSpot;
    private final Vec3 rightSpot;
    private int cycles;
    @Nullable private final UUID tavernId;
    private final long createdAt;
    private final Set<UUID> arrived = new HashSet<>();
    private Stage stage = Stage.GATHERING;
    private long startAt = -1L;
    private long musicHeardAt;
    private String endReason = "";

    /** A free 2x2 and the way its dancers face. */
    public record Spot(List<BlockPos> tiles, Direction facing) {}

    private PairDance(ServerLevel level, Spot spot, UUID onLeftTile, UUID onRightTile,
                      int cycles, @Nullable UUID tavernId) {
        this.level = level;
        this.tiles = List.copyOf(spot.tiles());
        this.facing = spot.facing();
        this.partnerOnRight = onLeftTile;
        this.partnerOnLeft = onRightTile;
        this.cycles = Math.max(1, cycles);
        this.tavernId = tavernId;
        this.createdAt = level.getGameTime();
        this.musicHeardAt = createdAt;
        BlockPos[] back = backRow(tiles, facing);
        this.leftSpot = Vec3.atBottomCenterOf(back[0]);
        this.rightSpot = Vec3.atBottomCenterOf(back[1]);
    }

    /**
     * Starts a dance on {@code spot} for {@code a} and {@code b} and holds its
     * tiles; the caller hands each a {@link PairDanceActivity}. Whoever is
     * further left (as the pair will face) takes the left tile, so neither has
     * to cross the other's path to get there.
     */
    public static PairDance begin(ServerLevel level, Spot spot, NpcEntity a, NpcEntity b,
                                  int cycles, @Nullable UUID tavernId) {
        Vec3 right = rightOf(spot.facing());
        boolean aLeft = a.position().dot(right) <= b.position().dot(right);
        NpcEntity left = aLeft ? a : b, rightOne = aLeft ? b : a;
        PairDance dance = new PairDance(level, spot, left.getUUID(), rightOne.getUUID(), cycles, tavernId);
        ACTIVE.computeIfAbsent(level, l -> new ArrayList<>()).add(dance);
        dance.holdTiles();
        return dance;
    }

    /** The dance {@code who} is part of, if any. */
    @Nullable
    public static PairDance of(ServerLevel level, UUID who) {
        for (PairDance d : active(level)) if (d.has(who)) return d;
        return null;
    }

    /** The dance at this tavern, if one is on. */
    @Nullable
    public static PairDance atTavern(ServerLevel level, UUID tavernId) {
        for (PairDance d : active(level)) if (tavernId.equals(d.tavernId)) return d;
        return null;
    }

    public static List<PairDance> active(ServerLevel level) {
        List<PairDance> list = ACTIVE.get(level);
        return list == null ? List.of() : List.copyOf(list);
    }

    public boolean has(UUID who) { return partnerOnRight.equals(who) || partnerOnLeft.equals(who); }
    public Stage stage()         { return stage; }
    public Direction facing()    { return facing; }
    public List<BlockPos> tiles() { return tiles; }
    public String endReason()    { return endReason; }
    @Nullable public UUID tavernId() { return tavernId; }

    /** The other dancer. */
    public UUID partnerOf(UUID who) { return partnerOnRight.equals(who) ? partnerOnLeft : partnerOnRight; }

    /** Where {@code who} stands: the centre of its back-row tile. */
    public Vec3 spotFor(UUID who) { return partnerOnRight.equals(who) ? leftSpot : rightSpot; }

    /** The clip whose inner arm reaches towards the partner. */
    public VillagerAnimationState clipFor(UUID who) {
        return partnerOnRight.equals(who) ? VillagerAnimationState.JAZZ_PAIR_R : VillagerAnimationState.JAZZ_PAIR_L;
    }

    /** Facing as an entity yaw. */
    public float yaw() { return facing.toYRot(); }

    /** The tick both start on, or -1 until both are there. */
    public long startAt() { return startAt; }

    /** When the last kick lands: whole loops of the clip, its blend-in first. */
    public long endsAt() {
        return startAt + (long) cycles * VillagerAnimationState.cycleTicks(VillagerAnimationState.JAZZ_PAIR_R)
                + VillagerAnimationState.WORK_BLEND_TICKS;
    }

    /**
     * Once a tick from each dancer: keeps the tiles held, gives up on a partner
     * that never came, and, at a tavern, winds down once the music has stopped.
     */
    public void tick(long now) {
        if (stage == Stage.OVER) return;
        holdTiles();
        if (stage == Stage.GATHERING && now - createdAt > GATHER_TIMEOUT_TICKS) {
            end("partner never arrived");
            return;
        }
        if (tavernId != null && now % 20 == 0) {
            if (TavernDances.musicPlaying(level, tavernId)) musicHeardAt = now;
            else if (now - musicHeardAt > MUSIC_GRACE_TICKS) {
                if (stage == Stage.GATHERING) end("the music stopped");
                else finishThisRound(now);
            }
        }
    }

    /** {@code who} is on its spot. Returns true once both are and the start tick is set. */
    public boolean arrive(UUID who, long now) {
        if (stage == Stage.OVER) return false;
        arrived.add(who);
        if (arrived.size() == 2 && startAt < 0) {
            startAt = now + START_DELAY_TICKS;
            stage = Stage.DANCING;
        }
        return startAt >= 0;
    }

    public boolean hasArrived(UUID who) { return arrived.contains(who); }

    /** Cut the dance down to the loop now playing, so it ends on a whole kick cycle. */
    public void finishThisRound(long now) {
        if (stage != Stage.DANCING || startAt < 0) return;
        int cycle = VillagerAnimationState.cycleTicks(VillagerAnimationState.JAZZ_PAIR_R);
        long into = Math.max(0, now - startAt - VillagerAnimationState.WORK_BLEND_TICKS);
        cycles = Math.min(cycles, (int) (into / cycle) + 1);
    }

    /** Over, for whatever reason: frees the tiles. Both dancers' activities end on their next tick. */
    public void end(String reason) {
        if (stage == Stage.OVER) return;
        stage = Stage.OVER;
        endReason = reason;
        for (BlockPos t : tiles) StandClaims.release(level, t, id);
        List<PairDance> list = ACTIVE.get(level);
        if (list != null) list.remove(this);
        TavernDances.onDanceEnded(level, this);
    }

    private void holdTiles() {
        for (BlockPos t : tiles) StandClaims.claim(level, t, id);
    }

    // =========================================================================
    // Finding a spot
    // =========================================================================

    /** Right of facing {@code f}, on the ground plane. */
    static Vec3 rightOf(Direction f) {
        // Facing (fx, fz), y up: right = facing x up = (-fz, 0, fx).
        return new Vec3(-f.getStepZ(), 0, f.getStepX());
    }

    /** The two back-row tiles of a 2x2, left then right as the dancers face. */
    static BlockPos[] backRow(List<BlockPos> tiles, Direction facing) {
        // Back row: the two tiles furthest against the facing.
        List<BlockPos> sorted = new ArrayList<>(tiles);
        sorted.sort(Comparator.comparingInt(t -> t.getX() * facing.getStepX() + t.getZ() * facing.getStepZ()));
        BlockPos a = sorted.get(0), b = sorted.get(1);
        Vec3 right = rightOf(facing);
        double da = a.getX() * right.x + a.getZ() * right.z;
        double db = b.getX() * right.x + b.getZ() * right.z;
        return da <= db ? new BlockPos[]{a, b} : new BlockPos[]{b, a};
    }

    /** The four tiles of the 2x2 whose lowest x and z corner is {@code corner}. */
    static List<BlockPos> square(BlockPos corner) {
        return List.of(corner, corner.east(), corner.south(), corner.south().east());
    }

    /**
     * Whether a dancer can stand on {@code tile}: open at the feet and the
     * head (no chair, table or wall), a floor under it, nobody's standing
     * claim, and nobody but {@code dancers} on it.
     */
    static boolean tileFree(ServerLevel level, BlockPos tile, Collection<UUID> dancers) {
        if (!level.getBlockState(tile).getCollisionShape(level, tile).isEmpty()) return false;
        if (!level.getBlockState(tile.above()).getCollisionShape(level, tile.above()).isEmpty()) return false;
        BlockPos below = tile.below();
        if (level.getBlockState(below).getCollisionShape(level, below).isEmpty()) return false;
        // A claim by anyone (UUID nobody holds): guests on their way, another dance.
        if (!StandClaims.isFreeFor(level, tile, NO_ONE)) return false;
        for (net.minecraft.world.entity.LivingEntity e : level.getEntitiesOfClass(
                net.minecraft.world.entity.LivingEntity.class, new net.minecraft.world.phys.AABB(tile))) {
            if (!dancers.contains(e.getUUID())) return false;
        }
        return true;
    }

    private static final UUID NO_ONE = new UUID(0L, 0L);

    /**
     * A free 2x2 in {@code floor} (feet positions), at least {@code doorGap}
     * blocks from the door, facing whichever way points most nearly at
     * {@code aim} (the bard), and at least two blocks from it. The nearest to
     * {@code aim} of a few good ones, picked at random so a tavern does not
     * always dance in the same corner.
     */
    @Nullable
    public static Spot findSpot(ServerLevel level, Collection<BlockPos> floor, @Nullable BlockPos door,
                                @Nullable BlockPos aim, Collection<UUID> dancers, java.util.Random random) {
        Set<BlockPos> set = new HashSet<>(floor);
        List<Spot> found = new ArrayList<>();
        for (BlockPos corner : set) {
            List<BlockPos> sq = square(corner);
            if (!set.containsAll(sq)) continue;
            boolean ok = true;
            for (BlockPos t : sq) {
                if ((door != null && t.distManhattan(door) < 3)
                        || (aim != null && t.distManhattan(aim) < 2)
                        || !tileFree(level, t, dancers)) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            found.add(new Spot(sq, facingTowards(sq, aim, set)));
        }
        if (found.isEmpty()) return null;
        if (aim != null) {
            found.sort(Comparator.comparingDouble(s -> centre(s.tiles()).distanceToSqr(Vec3.atBottomCenterOf(aim))));
        }
        int pick = random.nextInt(Math.min(3, found.size()));
        return found.get(pick);
    }

    /** Facing for a 2x2: towards {@code aim} if given, else towards the most open floor. */
    static Direction facingTowards(List<BlockPos> sq, @Nullable BlockPos aim, Set<BlockPos> floor) {
        Vec3 c = centre(sq);
        if (aim != null) {
            Vec3 d = Vec3.atBottomCenterOf(aim).subtract(c);
            return Direction.getNearest(d.x, 0, d.z);
        }
        Direction best = Direction.NORTH;
        int bestOpen = -1;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int open = 0;
            for (BlockPos t : sq) for (int k = 1; k <= 3; k++) if (floor.contains(t.relative(d, k))) open++;
            if (open > bestOpen) { bestOpen = open; best = d; }
        }
        return best;
    }

    static Vec3 centre(List<BlockPos> sq) {
        double x = 0, y = 0, z = 0;
        for (BlockPos t : sq) { x += t.getX() + 0.5; y += t.getY(); z += t.getZ() + 0.5; }
        return new Vec3(x / sq.size(), y / sq.size(), z / sq.size());
    }
}

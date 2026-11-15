package com.alex.test.village.brain;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.VillageChairs;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.TavernBuilding;
import com.alex.test.village.building.TownHallBuilding;
import com.alex.test.village.social.MemoryTopic;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Go to the village's recreation venue — the tavern if it has one, the town
 * hall otherwise — hang around, and wait for the {@link SocialCoordinator} to
 * pair this villager with someone to talk to.
 *
 * <p>While waiting the villager repositions occasionally and emits ambient
 * villager noises to signal activity. The actual happiness gain happens in
 * {@link ConverseActivity} when a conversation is initiated.
 *
 * <p>No daily social-action limit check is performed here; that gate lives in
 * {@link LeisureActivity#pickSub} before this activity is ever created.
 *
 * <p>If the whole hang duration elapses without ever being paired into a
 * conversation ({@link #stop} sees {@code ticksHanging >= hangTicks}), the
 * villager reports an unpaired attempt to {@link VillagerBrain}, which briefly
 * suppresses the social score. Without this, a villager whose needs never
 * change while hanging around (nobody ever showed up to talk to) keeps
 * recomputing the exact same winning social score on every re-pick and goes
 * straight back into another full hang session forever, never trying
 * wander/admire — functionally correct in that each session does complete,
 * but indistinguishable from a genuine freeze from the outside.
 *
 * <p>Where the venue has chairs (inside it, or outdoors within
 * {@value #OUTDOOR_CHAIR_RADIUS} blocks of its door) the villager usually
 * sits down to wait, preferring a chair next to somebody else who is waiting,
 * since that is where a conversation can start. It stays seated if one does.
 */
public final class SocializeActivity extends Activity {

    private static final int MIN_HANG_TICKS  = 600;
    private static final int MAX_HANG_TICKS  = 1400;
    private static final int NOISE_MIN_TICKS = 80;
    private static final int NOISE_RND_TICKS = 100;

    /** Random venue tiles tried before settling for one somebody is already on. */
    private static final int SPOT_TRIES = 6;

    /** Chance each pick goes to a free chair rather than a floor tile, when there is one. */
    private static final float CHAIR_CHANCE = 0.75f;
    /** Chairs outside the venue still count as its seats up to this far from the door: a bench outside the tavern. */
    private static final int OUTDOOR_CHAIR_RADIUS = 8;
    /** Flat distance at which a chair is "next to" somebody: the conversation pairing range. */
    private static final double NEXT_TO_SOMEONE_SQ = 6.25;
    /** A villager that finished walking further than this from its chair did not really reach it. */
    private static final double SIT_REACH_SQ = 4.0;

    private Building venue;
    private BlockPos currentSpot;
    /** The chair being walked to or sat on, claimed so nobody else heads for it. */
    @Nullable
    private VillageChairs.Chair chair;
    /** The venue's chairs, scanned once per visit; each is checked again before sitting. */
    private List<VillageChairs.Chair> venueChairs;
    private int      hangTicks;
    private int      ticksHanging    = 0;
    private int      repositionTimer = 0;
    private int      noiseTimer      = 0;
    private boolean  arrived         = false;

    @Override
    protected void start(Context ctx) {
        venue        = pickVenue(ctx);
        venueChairs  = findVenueChairs(ctx);
        pickTarget(ctx);
        hangTicks    = MIN_HANG_TICKS + ctx.npc().getRandom().nextInt(MAX_HANG_TICKS - MIN_HANG_TICKS);
        noiseTimer   = NOISE_MIN_TICKS + ctx.npc().getRandom().nextInt(NOISE_RND_TICKS);
    }

    @Override
    protected Status tick(Context ctx) {
        if (currentSpot == null) return Status.FAILED;
        if (chair != null) VillageChairs.claim(ctx.level(), chair.pos(), ctx.npc().getUUID());

        if (!arrived) {
            if (moveTo(ctx, currentSpot, VillagerModel.WALK_SPEED)) {
                if (moveFailed(ctx, currentSpot)) {
                    if (chair == null) return Status.FAILED;
                    // Could not reach the chair: wait standing instead.
                    releaseChair(ctx);
                    currentSpot = pickSpot(ctx);
                    resetMove();
                    return currentSpot == null ? Status.FAILED : Status.RUNNING;
                }
                arrived = true;
                if (chair != null && !sitDown(ctx)) releaseChair(ctx);
            }
            return Status.RUNNING;
        }

        // Knocked off the chair (it was broken): keep waiting on foot.
        if (chair != null && !ctx.npc().isChairSeated()) releaseChair(ctx);

        if (++ticksHanging >= hangTicks) return Status.DONE;

        // Occasional ambient noise while hanging around
        if (--noiseTimer <= 0) {
            noiseTimer = NOISE_MIN_TICKS + ctx.npc().getRandom().nextInt(NOISE_RND_TICKS);
            ctx.level().playSound(null,
                    ctx.npc().getX(), ctx.npc().getY(), ctx.npc().getZ(),
                    SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL,
                    0.4f, 0.9f + ctx.npc().getRandom().nextFloat() * 0.2f);
        }

        // Periodically drift to another spot in the venue. Somebody sitting
        // down stays put about three times as long before getting up again.
        int driftTicks = ctx.npc().isChairSeated() ? 600 : 200;
        if (++repositionTimer >= driftTicks + ctx.npc().getRandom().nextInt(driftTicks)) {
            repositionTimer = 0;
            BlockPos oldSpot = currentSpot;
            BlockPos oldChair = chair != null ? chair.pos() : null;
            pickTarget(ctx);
            BlockPos newChair = chair != null ? chair.pos() : null;
            if (currentSpot == null) {
                currentSpot = oldSpot;
            } else if (!currentSpot.equals(oldSpot) || !java.util.Objects.equals(newChair, oldChair)) {
                ctx.npc().standUpFromChair();
                arrived = false;
                resetMove();
            }
        }
        return Status.RUNNING;
    }

    /** Stays seated while waiting, and into a conversation with a neighbour. */
    @Override
    public boolean keepsChairSeat() { return chair != null; }

    @Override
    public void stop(Context ctx) {
        ctx.model().stopMoving();
        // Only the claim: standing up is VillagerBrain's call, so a villager
        // paired into a conversation from its chair stays sitting for it.
        releaseChair(ctx);
        // Ran the whole hang out without ever being cut short (e.g. by
        // SocialCoordinator pairing this villager into a ConverseActivity, or
        // by a higher-priority override such as night/hunger/flee): nobody
        // ever showed up to talk to. Back off briefly so the next leisure
        // pick has a real chance at wander/admire instead of immediately
        // recomputing the exact same winning social score.
        if (ticksHanging >= hangTicks) {
            ctx.brain().recordUnpairedSocializeAttempt();
            ctx.brain().remember(MemoryTopic.LONELY, ctx.level());
        }
    }

    @Override
    public String name() { return "socialize"; }

    /** True while the villager is at the venue (eligible for coordinator-initiated conversations). */
    public boolean isHangingAround() { return arrived; }

    // =========================================================================

    /**
     * Where leisure happens, best venue first.
     *
     * <p>A tavern outranks the Town Hall outright rather than competing with it
     * on distance. The Town Hall is the fallback venue — somewhere to stand
     * when the village has nowhere better — whereas a tavern is the room the
     * player built *for* this, staffed by a bard whose whole job only pays off
     * if somebody is in the room to hear it. Ranking them by distance meant a
     * village with both sent everyone to whichever happened to be nearer, which
     * in practice was the Town Hall (it is central by construction) and left
     * the bard performing to an empty room.
     */
    private static final List<Class<? extends Building>> VENUES_BY_PREFERENCE =
            List.of(TavernBuilding.class, TownHallBuilding.class);

    private Building pickVenue(Context ctx) {
        for (Class<? extends Building> type : VENUES_BY_PREFERENCE) {
            Building best = nearestVenue(ctx, type);
            if (best != null) return best;
        }
        return null;
    }

    @Nullable
    private static Building nearestVenue(Context ctx, Class<? extends Building> type) {
        BlockPos npcPos  = ctx.npc().blockPosition();
        Building best    = null;
        double   bestDist = Double.MAX_VALUE;
        for (Building b : ctx.mgr().getBuildings()) {
            if (!type.isInstance(b) || !b.isValid() || b.getNavMeshBlocks().isEmpty()) continue;
            double d = b.getDoorPos().distSqr(npcPos);
            if (d < bestDist) { bestDist = d; best = b; }
        }
        return best;
    }

    /**
     * Somewhere in the venue to stand, preferring a tile nobody is on.
     *
     * <p>{@code NpcEntity.isPushable} is false -- villagers deliberately do not
     * shove each other out of narrow corridors -- so two that pick the same
     * tile stay in it, fully overlapping. Hanging around like that is only
     * untidy; being paired into a conversation like that is two villagers
     * talking from inside one another.
     *
     * <p>A handful of tries rather than a filtered list: the venue's tile list
     * is rebuilt on every call as it is, and a room where every tile is taken
     * should still return somewhere rather than nowhere.
     */
    private BlockPos pickSpot(Context ctx) {
        if (venue != null) {
            List<BlockPos> tiles = new ArrayList<>(venue.getNavMeshBlocks());
            if (tiles.isEmpty()) return null;
            BlockPos fallback = null;
            for (int attempt = 0; attempt < SPOT_TRIES; attempt++) {
                BlockPos tile = tiles.get(ctx.npc().getRandom().nextInt(tiles.size()));
                if (fallback == null) fallback = tile;
                if (!occupied(ctx, tile)) return tile;
            }
            return fallback;
        }
        return ctx.mgr().getTownHallSpot(ctx.npc().blockPosition());
    }

    /** Whether another villager of this village is standing on {@code tile}. */
    private static boolean occupied(Context ctx, BlockPos tile) {
        for (VillagerModel model : ctx.mgr().getVillagers()) {
            NpcEntity other = model.puppet();
            if (other == null || other == ctx.npc() || !other.isAlive()) continue;
            if (other.blockPosition().equals(tile)) return true;
        }
        return false;
    }

    // =========================================================================
    // Chairs
    // =========================================================================

    /** Choose the next place to wait: usually a free chair when there is one, else a floor tile. */
    private void pickTarget(Context ctx) {
        releaseChair(ctx);
        VillageChairs.Chair next = ctx.npc().getRandom().nextFloat() < CHAIR_CHANCE ? pickChair(ctx) : null;
        if (next != null) {
            chair = next;
            VillageChairs.claim(ctx.level(), next.pos(), ctx.npc().getUUID());
            currentSpot = next.approach();
        } else {
            currentSpot = pickSpot(ctx);
        }
    }

    private boolean sitDown(Context ctx) {
        if (ctx.npc().distanceToSqr(chair.pos().getCenter()) > SIT_REACH_SQ) return false;
        if (!chair.pos().equals(ctx.npc().seatedChairPos())
                && !VillageChairs.isFreeFor(ctx.level(), chair.pos(), ctx.npc().getUUID())) return false;
        return ctx.npc().sitOnChair(chair.pos());
    }

    private void releaseChair(Context ctx) {
        if (chair == null) return;
        VillageChairs.release(ctx.level(), chair.pos(), ctx.npc().getUUID());
        chair = null;
    }

    /**
     * A free chair, preferring one next to somebody else waiting to talk:
     * two villagers on opposite sides of the tavern never get paired.
     */
    @Nullable
    private VillageChairs.Chair pickChair(Context ctx) {
        if (venueChairs == null || venueChairs.isEmpty()) return null;
        BlockPos mine = ctx.npc().seatedChairPos();
        List<VillageChairs.Chair> free = new ArrayList<>();
        List<VillageChairs.Chair> social = new ArrayList<>();
        for (VillageChairs.Chair c : venueChairs) {
            if (!c.pos().equals(mine)
                    && !VillageChairs.isFreeFor(ctx.level(), c.pos(), ctx.npc().getUUID())) continue;
            free.add(c);
            if (nextToSomeone(ctx, c)) social.add(c);
        }
        List<VillageChairs.Chair> pool = social.isEmpty() ? free : social;
        return pool.isEmpty() ? null : pool.get(ctx.npc().getRandom().nextInt(pool.size()));
    }

    private static boolean nextToSomeone(Context ctx, VillageChairs.Chair c) {
        double cx = c.pos().getX() + 0.5, cz = c.pos().getZ() + 0.5;
        for (VillagerModel model : ctx.mgr().getVillagers()) {
            NpcEntity other = model.puppet();
            if (other == null || other == ctx.npc() || !other.isAlive()) continue;
            if (!model.brain().isOpenToConversation()) continue;
            if (Math.abs(other.getY() - c.pos().getY()) > 1.5) continue;
            double dx = other.getX() - cx, dz = other.getZ() - cz;
            if (dx * dx + dz * dz <= NEXT_TO_SOMEONE_SQ) return true;
        }
        return false;
    }

    /**
     * The venue's chairs: any in the venue whose sit-down tile is one of its
     * floor tiles, plus any outdoors within {@link #OUTDOOR_CHAIR_RADIUS} of its
     * door. Without a venue, the chairs around the Town Hall fallback spot.
     */
    private List<VillageChairs.Chair> findVenueChairs(Context ctx) {
        return venueChairs(ctx, venue, OUTDOOR_CHAIR_RADIUS);
    }

    /** The chairs belonging to {@code venue}; see {@link #findVenueChairs}. Shared with {@link EveningActivity}. */
    static List<VillageChairs.Chair> venueChairs(Context ctx, @Nullable Building venue,
                                                  int outdoorRadius) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return List.of();
        Map<BlockPos, VillageChairs.Chair> found = new LinkedHashMap<>();
        if (venue != null) {
            Set<BlockPos> tiles = venue.getNavMeshBlocks();
            if (!tiles.isEmpty()) {
                int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                for (BlockPos t : tiles) {
                    minX = Math.min(minX, t.getX()); maxX = Math.max(maxX, t.getX());
                    minY = Math.min(minY, t.getY()); maxY = Math.max(maxY, t.getY());
                    minZ = Math.min(minZ, t.getZ()); maxZ = Math.max(maxZ, t.getZ());
                }
                for (VillageChairs.Chair c : VillageChairs.findInBox(ctx.level(), graph,
                        new BlockPos(minX - 1, minY, minZ - 1), new BlockPos(maxX + 1, maxY, maxZ + 1))) {
                    if (tiles.contains(c.approach())) found.put(c.pos(), c);
                }
            }
            if (outdoorRadius > 0) {
                for (VillageChairs.Chair c : VillageChairs.findNear(ctx.level(), graph,
                        venue.getDoorPos(), outdoorRadius)) {
                    found.putIfAbsent(c.pos(), c);
                }
            }
        } else {
            BlockPos spot = ctx.mgr().getTownHallSpot(ctx.npc().blockPosition());
            if (spot != null) {
                for (VillageChairs.Chair c : VillageChairs.findNear(ctx.level(), graph, spot, OUTDOOR_CHAIR_RADIUS)) {
                    found.put(c.pos(), c);
                }
            }
        }
        return new ArrayList<>(found.values());
    }
}

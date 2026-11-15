package com.alex.test.village.brain;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillageChairs;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.LibraryBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * An evening visit to the library: reading, browsing the shelves, or sitting
 * quietly. Which of those comes from {@link LibraryPlan#action}; this activity
 * only carries it out.
 *
 * <ul>
 *   <li><b>Read</b>: a free chair with a bookshelf in view if there is one,
 *       otherwise a spot beside a shelf, facing it. The open book is shown
 *       only while actually reading ({@link VillagerAnimationState#READ});
 *       short pauses put it away. Intelligence is paid per settled reading
 *       tick through {@link VillagerBrain#onLibraryReadingTick}.</li>
 *   <li><b>Browse</b>: stand at a shelf and look it over, then move to
 *       another. No book, no intelligence.</li>
 *   <li><b>Sit and observe</b>: a chair, looking at the shelves and now and
 *       then at somebody else in the room. No book, no intelligence.</li>
 * </ul>
 *
 * <p>Chairs use the shared {@link VillageChairs} claims; standing spots have
 * claims of their own here, so two villagers never share one. Only chairs
 * that do not face a wall and have a bookshelf within the seated head's
 * turning range are used, so a seated villager can visibly look at a shelf.
 */
public final class LibraryActivity extends Activity {

    /** How far a shelf may be from a chair and still be "in view". */
    private static final double SHELF_VIEW_RADIUS_SQ = 36.0;
    /** A seated head turns up to 75 degrees; keep shelves a little inside that. */
    private static final double SHELF_VIEW_MIN_DOT = Math.cos(Math.toRadians(70));
    private static final double SIT_REACH_SQ = 4.0;
    private static final int    MAX_WALK_FAILURES = 2;
    private static final int    SPOT_CLAIM_TIMEOUT_TICKS = 40;

    private final LibraryBuilding library;
    private final LibraryPlan.Action action;

    private List<VillageChairs.Chair> chairs = List.of();
    private List<BlockPos> spots = List.of();
    @Nullable private VillageChairs.Chair chair;
    @Nullable private BlockPos spot;
    /** The shelf this villager is facing or reading beside. */
    @Nullable private BlockPos shelf;
    private BlockPos target;
    private boolean begun;
    private boolean arrived;
    /** No free chair and no free standing spot: the library is full for this visitor. */
    private boolean full;
    /** A browser who has taken its book to a chair and now reads there. */
    private boolean seatedReading;
    /** Shelf stops a browser makes before looking for a chair to read in. */
    private int     browseStopsLeft;
    private int     walkFailures;
    private int     settledTicks;
    /** Readers sometimes look at a shelf first; set in {@link #start}. */
    private boolean browseFirst;
    private int     stepTimer;
    /** >0 while a reader has put the book down for a moment. */
    private int     pauseTimer;
    /** >0 while glancing at something other than the book. */
    private int     glanceTimer;
    @Nullable private Vec3 glanceAt;

    public LibraryActivity(LibraryBuilding library, LibraryPlan.Action action) {
        this.library = library;
        this.action  = action;
    }

    public LibraryPlan.Action action() { return action; }
    public LibraryBuilding library()   { return library; }
    public boolean isSettled()         { return arrived; }
    /** Ticks spent settled in the library by this activity instance. */
    public int settledTicks()          { return settledTicks; }
    /** Whether the book is out right now. */
    public boolean isReading()         { return arrived && pauseTimer <= 0
                                                 && ((action == LibraryPlan.Action.READ && !browseFirst) || seatedReading); }
    /** No chair and no spot was free: the brain treats the library as full tonight. */
    public boolean wasFull()           { return full; }

    @Override
    protected void start(Context ctx) {
        begun = true;
        chairs = suitableChairs(ctx, library);
        spots  = standingSpots(ctx, library);
        browseFirst = action == LibraryPlan.Action.READ && !spots.isEmpty()
                && ctx.npc().getRandom().nextFloat() < 0.25f;
        browseStopsLeft = 2 + ctx.npc().getRandom().nextInt(3);
        stepTimer = 60 + ctx.npc().getRandom().nextInt(80);

        // Back from an interruption and still on one of the library's chairs:
        // stay seated and carry on.
        BlockPos seat = ctx.npc().isChairSeated() ? ctx.npc().seatedChairPos() : null;
        if (seat != null) {
            for (VillageChairs.Chair c : chairs) {
                if (!c.pos().equals(seat)) continue;
                chair = c;
                VillageChairs.claim(ctx.level(), c.pos(), ctx.npc().getUUID());
                shelf = shelfInView(ctx, c);
                target = c.approach();
                arrived = true;
                browseFirst = false;
                if (action == LibraryPlan.Action.BROWSE) seatedReading = true;
                ctx.brain().onEveningArrived(ctx, library);
                return;
            }
            ctx.npc().standUpFromChair();
        }
        pickPlace(ctx);
        if (target == null) full = true;
    }

    @Override
    protected Status tick(Context ctx) {
        if (!library.isValid()) return Status.FAILED;
        // The stay counts from the first arrival and ends no earlier than the
        // personal leaving time; the brain keeps both.
        if (ctx.brain().libraryVisitOver(ctx, library)) return Status.DONE;
        if (target == null) return Status.FAILED;
        refreshClaims(ctx);

        if (!arrived) {
            if (!moveTo(ctx, target, VillagerModel.WALK_SPEED)) return Status.RUNNING;
            if (moveFailed(ctx, target)) {
                releaseAll(ctx);
                if (++walkFailures > MAX_WALK_FAILURES) return Status.FAILED;
                pickPlace(ctx);
                resetMove();
                return target == null ? Status.FAILED : Status.RUNNING;
            }
            if (chair != null && !sitDown(ctx)) {
                // Could not sit after all: take a standing spot instead.
                releaseAll(ctx);
                seatedReading = false;
                spot = freeSpot(ctx, null);
                if (spot == null) {
                    full = true;
                    return Status.FAILED;
                }
                shelf = shelfBeside(ctx, spot);
                target = spot;
                resetMove();
                return Status.RUNNING;
            }
            arrived = true;
            ctx.model().stopMoving();
            ctx.brain().onEveningArrived(ctx, library);
            return Status.RUNNING;
        }

        if (chair != null && !ctx.npc().isChairSeated()) {
            // Knocked off the chair: finish the visit standing at a shelf.
            releaseAll(ctx);
            arrived = false;
            spot = freeSpot(ctx, null);
            if (spot == null) return Status.DONE;
            shelf = shelfBeside(ctx, spot);
            target = spot;
            resetMove();
            showBook(ctx, false);
            return Status.RUNNING;
        }

        settledTicks++;
        if (seatedReading) {
            tickReader(ctx);
            return Status.RUNNING;
        }
        switch (action) {
            case READ        -> tickReader(ctx);
            case BROWSE      -> tickBrowser(ctx);
            case SIT_OBSERVE -> tickObserver(ctx);
        }
        return Status.RUNNING;
    }

    /** Until {@link #start} has run a fresh visit keeps the seat, so it can adopt it. */
    @Override
    public boolean keepsChairSeat() { return !begun || chair != null; }

    @Override
    public void stop(Context ctx) {
        showBook(ctx, false);
        releaseAll(ctx);
        ctx.model().stopMoving();
    }

    @Override
    public String name() {
        return switch (action) {
            case READ        -> "library_read";
            case BROWSE      -> "library_browse";
            case SIT_OBSERVE -> "library_sit";
        };
    }

    // =========================================================================
    // Settled behaviour
    // =========================================================================

    private void tickReader(Context ctx) {
        if (browseFirst) {
            // A look along the shelf before settling down with a book.
            showBook(ctx, false);
            lookAtShelf(ctx);
            if (--stepTimer <= 0) {
                browseFirst = false;
                stepTimer = 0;
                if (spot != null && !chairs.isEmpty()) {
                    // Take the book to a chair if one is free.
                    VillageChairs.Chair seat = freeChair(ctx);
                    if (seat != null) {
                        releaseSpot(ctx);
                        chair = seat;
                        VillageChairs.claim(ctx.level(), seat.pos(), ctx.npc().getUUID());
                        shelf = shelfInView(ctx, seat);
                        target = seat.approach();
                        arrived = false;
                        resetMove();
                    }
                }
            }
            return;
        }

        if (pauseTimer > 0) {
            // Book down: a stretch, a look around. No reading, no intelligence.
            showBook(ctx, false);
            lookAtShelf(ctx);
            pauseTimer--;
            return;
        }
        if (ctx.npc().getRandom().nextInt(900) == 0) {
            pauseTimer = 40 + ctx.npc().getRandom().nextInt(60);
            return;
        }

        showBook(ctx, true);
        if (glanceTimer > 0) {
            glanceTimer--;
            lookAtShelf(ctx);
        } else {
            lookAtBook(ctx);
            if (ctx.npc().getRandom().nextInt(300) == 0) glanceTimer = 25 + ctx.npc().getRandom().nextInt(20);
        }
        ctx.brain().onLibraryReadingTick(ctx);
    }

    private void tickBrowser(Context ctx) {
        showBook(ctx, false);
        lookAtShelf(ctx);
        if (--stepTimer > 0) return;
        stepTimer = 80 + ctx.npc().getRandom().nextInt(120);
        // After a few shelves, take the book to a free chair and read it there.
        if (--browseStopsLeft <= 0 && !chairs.isEmpty()) {
            VillageChairs.Chair seat = freeChair(ctx);
            if (seat != null) {
                releaseSpot(ctx);
                chair = seat;
                VillageChairs.claim(ctx.level(), seat.pos(), ctx.npc().getUUID());
                shelf = shelfInView(ctx, seat);
                target = seat.approach();
                seatedReading = true;
                arrived = false;
                resetMove();
                return;
            }
        }
        BlockPos next = freeSpot(ctx, spot);
        if (next == null) return;
        releaseSpot(ctx);
        spot = next;
        shelf = shelfBeside(ctx, next);
        target = next;
        arrived = false;
        resetMove();
    }

    private void tickObserver(Context ctx) {
        showBook(ctx, false);
        if (glanceTimer > 0) {
            glanceTimer--;
            if (glanceAt != null) look(ctx, glanceAt);
            return;
        }
        lookAtShelf(ctx);
        if (--stepTimer > 0) return;
        stepTimer = 150 + ctx.npc().getRandom().nextInt(150);
        NpcEntity other = someoneElseHere(ctx);
        glanceAt = other != null ? other.getEyePosition() : null;
        if (glanceAt != null) glanceTimer = 30 + ctx.npc().getRandom().nextInt(30);
    }

    private void showBook(Context ctx, boolean reading) {
        NpcEntity npc = ctx.npc();
        if (reading) {
            if (npc.getAnimation() != VillagerAnimationState.READ) npc.setAnimation(VillagerAnimationState.READ);
        } else if (npc.getAnimation() == VillagerAnimationState.READ) {
            npc.setAnimation(VillagerAnimationState.IDLE);
        }
    }

    private void lookAtBook(Context ctx) {
        NpcEntity npc = ctx.npc();
        double yaw = Math.toRadians(npc.getYRot());
        Vec3 eye = npc.getEyePosition();
        look(ctx, new Vec3(eye.x - Math.sin(yaw) * 0.7, eye.y - 0.7, eye.z + Math.cos(yaw) * 0.7));
    }

    private void lookAtShelf(Context ctx) {
        if (shelf != null) look(ctx, shelf.getCenter());
    }

    private static void look(Context ctx, Vec3 at) {
        ctx.npc().getLookControl().setLookAt(at.x, at.y, at.z, 20f, 20f);
    }

    @Nullable
    private NpcEntity someoneElseHere(Context ctx) {
        List<NpcEntity> here = new ArrayList<>();
        for (VillagerModel m : ctx.mgr().getVillagers()) {
            NpcEntity other = m.puppet();
            if (other == null || other == ctx.npc() || !other.isAlive()) continue;
            if (library.getNavMeshBlocks().contains(other.blockPosition())) here.add(other);
        }
        return here.isEmpty() ? null : here.get(ctx.npc().getRandom().nextInt(here.size()));
    }

    // =========================================================================
    // Places
    // =========================================================================

    private void pickPlace(Context ctx) {
        releaseAll(ctx);
        boolean wantsChair = !browseFirst && action != LibraryPlan.Action.BROWSE;
        VillageChairs.Chair seat = wantsChair ? freeChair(ctx) : null;
        if (seat != null) {
            chair = seat;
            VillageChairs.claim(ctx.level(), seat.pos(), ctx.npc().getUUID());
            shelf = shelfInView(ctx, seat);
            target = seat.approach();
            return;
        }
        spot = freeSpot(ctx, null);
        if (spot != null) {
            claimSpot(ctx.level(), spot, ctx.npc().getUUID());
            shelf = shelfBeside(ctx, spot);
            target = spot;
            return;
        }
        target = null;
    }

    @Nullable
    private VillageChairs.Chair freeChair(Context ctx) {
        VillageChairs.Chair best = null;
        double bestDist = Double.MAX_VALUE;
        for (VillageChairs.Chair c : chairs) {
            if (!VillageChairs.isFreeFor(ctx.level(), c.pos(), ctx.npc().getUUID())) continue;
            double d = c.pos().distSqr(ctx.npc().blockPosition()) * (0.5 + ctx.npc().getRandom().nextFloat());
            if (d < bestDist) { bestDist = d; best = c; }
        }
        return best;
    }

    @Nullable
    private BlockPos freeSpot(Context ctx, @Nullable BlockPos avoid) {
        List<BlockPos> free = new ArrayList<>();
        for (BlockPos s : spots) {
            if (s.equals(avoid)) continue;
            if (isSpotFreeFor(ctx.level(), s, ctx.npc().getUUID()) && !standingOn(ctx, s)) free.add(s);
        }
        if (free.isEmpty()) return null;
        BlockPos pick = free.get(ctx.npc().getRandom().nextInt(free.size()));
        claimSpot(ctx.level(), pick, ctx.npc().getUUID());
        return pick;
    }

    private static boolean standingOn(Context ctx, BlockPos tile) {
        for (VillagerModel m : ctx.mgr().getVillagers()) {
            NpcEntity other = m.puppet();
            if (other != null && other != ctx.npc() && other.isAlive()
                    && other.blockPosition().equals(tile)) return true;
        }
        return false;
    }

    private void refreshClaims(Context ctx) {
        if (chair != null) VillageChairs.claim(ctx.level(), chair.pos(), ctx.npc().getUUID());
        if (spot != null) claimSpot(ctx.level(), spot, ctx.npc().getUUID());
    }

    private void releaseAll(Context ctx) {
        if (chair != null) {
            VillageChairs.release(ctx.level(), chair.pos(), ctx.npc().getUUID());
            chair = null;
        }
        releaseSpot(ctx);
    }

    private void releaseSpot(Context ctx) {
        if (spot == null) return;
        releaseSpotClaim(ctx.level(), spot, ctx.npc().getUUID());
        spot = null;
    }

    private boolean sitDown(Context ctx) {
        if (ctx.npc().distanceToSqr(chair.pos().getCenter()) > SIT_REACH_SQ) return false;
        if (!chair.pos().equals(ctx.npc().seatedChairPos())
                && !VillageChairs.isFreeFor(ctx.level(), chair.pos(), ctx.npc().getUUID())) return false;
        return ctx.npc().sitOnChair(chair.pos());
    }

    @Nullable
    private BlockPos shelfInView(Context ctx, VillageChairs.Chair c) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos s : library.getFunctionalBlocks()) {
            if (!inView(c, s)) continue;
            double d = s.distSqr(c.pos());
            if (d < bestDist) { bestDist = d; best = s; }
        }
        return best;
    }

    @Nullable
    private BlockPos shelfBeside(Context ctx, BlockPos tile) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos s = tile.relative(d).above(dy);
                if (ctx.level().getBlockState(s).is(Blocks.BOOKSHELF)) return s;
            }
        }
        return null;
    }

    // =========================================================================
    // Library survey, shared with the brain's capacity check
    // =========================================================================

    /**
     * Chairs a reader can use: inside the library, not facing straight into a
     * wall, with a bookshelf within reach of the seated head's turn.
     */
    static List<VillageChairs.Chair> suitableChairs(Context ctx, LibraryBuilding library) {
        List<VillageChairs.Chair> out = new ArrayList<>();
        for (VillageChairs.Chair c : SocializeActivity.venueChairs(ctx, library, 0)) {
            BlockPos facing = c.pos().relative(c.front());
            BlockState ahead = ctx.level().getBlockState(facing);
            boolean wall = !ahead.is(Blocks.BOOKSHELF) && ahead.isSolidRender(ctx.level(), facing)
                    && ctx.level().getBlockState(facing.above()).isSolidRender(ctx.level(), facing.above());
            if (wall) continue;
            boolean shelf = false;
            for (BlockPos s : library.getFunctionalBlocks()) {
                if (inView(c, s)) { shelf = true; break; }
            }
            if (shelf) out.add(c);
        }
        return out;
    }

    private static boolean inView(VillageChairs.Chair c, BlockPos shelf) {
        double dx = shelf.getX() - c.pos().getX();
        double dz = shelf.getZ() - c.pos().getZ();
        double flat = dx * dx + dz * dz;
        if (flat < 0.5 || flat > SHELF_VIEW_RADIUS_SQ) return false;
        double len = Math.sqrt(flat);
        double dot = (dx * c.front().getStepX() + dz * c.front().getStepZ()) / len;
        return dot >= SHELF_VIEW_MIN_DOT;
    }

    /** Library floor tiles with a bookshelf right beside them, at foot or head height. */
    static List<BlockPos> standingSpots(Context ctx, LibraryBuilding library) {
        NavGraph graph = ctx.mgr().getGraph();
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos tile : library.getNavMeshBlocks()) {
            NavNode node = graph != null ? graph.nodeAt(tile.getX(), tile.getY(), tile.getZ()) : null;
            if (node == null || !node.isRoutable() || node.climb) continue;
            boolean besideShelf = false;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = tile.relative(d);
                if (library.getFunctionalBlocks().contains(n)
                        || library.getFunctionalBlocks().contains(n.above())) { besideShelf = true; break; }
            }
            if (besideShelf) out.add(tile);
        }
        return out;
    }

    private record Capacity(long day, int spots) {}
    private static final Map<UUID, Capacity> CAPACITY = new HashMap<>();

    /**
     * How many villagers the library can take at once tonight: its usable
     * chairs plus its standing spots by the shelves. Surveyed once per
     * library per day and shared by every villager asking.
     */
    static int capacity(Context ctx, LibraryBuilding library, long day) {
        Capacity cached = CAPACITY.get(library.getItemFrameId());
        if (cached != null && cached.day() == day) return cached.spots();
        java.util.Set<BlockPos> places = new java.util.HashSet<>(standingSpots(ctx, library));
        for (VillageChairs.Chair c : suitableChairs(ctx, library)) places.add(c.pos());
        int spots = places.size();
        CAPACITY.put(library.getItemFrameId(), new Capacity(day, spots));
        return spots;
    }

    // =========================================================================
    // Standing-spot claims (chairs use VillageChairs)
    // =========================================================================

    private record SpotClaim(UUID owner, long refreshedAt) {}
    private static final Map<ServerLevel, Map<BlockPos, SpotClaim>> SPOT_CLAIMS = new WeakHashMap<>();

    private static Map<BlockPos, SpotClaim> spotClaims(ServerLevel level) {
        return SPOT_CLAIMS.computeIfAbsent(level, l -> new HashMap<>());
    }

    static boolean isSpotFreeFor(ServerLevel level, BlockPos spot, UUID who) {
        SpotClaim claim = spotClaims(level).get(spot);
        return claim == null || claim.owner().equals(who)
                || VillagerPause.stale(claim.owner(), claim.refreshedAt(), SPOT_CLAIM_TIMEOUT_TICKS, level.getGameTime());
    }

    private static void claimSpot(ServerLevel level, BlockPos spot, UUID who) {
        spotClaims(level).put(spot.immutable(), new SpotClaim(who, level.getGameTime()));
    }

    private static void releaseSpotClaim(ServerLevel level, BlockPos spot, UUID who) {
        Map<BlockPos, SpotClaim> claims = spotClaims(level);
        SpotClaim claim = claims.get(spot);
        if (claim != null && claim.owner().equals(who)) claims.remove(spot);
    }
}

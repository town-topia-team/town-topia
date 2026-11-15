package com.alex.test.village.villagers.farmer.behavior;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.CraftingStation;
import com.alex.test.village.brain.StandClaims;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.CauldronClaims;
import com.alex.test.village.building.CauldronIndex;
import com.alex.test.village.building.MuddyCauldrons;
import com.alex.test.village.villagers.farmer.FarmerCauldrons;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * A dirty farmer's end-of-shift wash: walk to tonight's cauldron, scrub for
 * {@link #SCRUB_TICKS} ticks, and come away fully clean. The water drops one
 * level and turns browner (see {@link MuddyCauldrons}); the third wash leaves
 * the cauldron empty.
 *
 * <p>One washer per cauldron, and a line behind it ({@link CauldronClaims#joinLine}):
 * no more farmers head for a cauldron than it has water for. A waiting farmer
 * looks again every {@link #RECHECK_TICKS} ticks: it moves to another cauldron
 * that is free, and leaves when the line ahead of it would use up the water.
 * The next farmer in line waits beside the cauldron; the rest wait outside the
 * room. When the cauldron runs dry, the first farmer in line refills it if it
 * can find a bucket and water; otherwise the line breaks up. When the
 * farmer's own cauldron is empty and nobody refilled it today, it refills it
 * first ({@link RefillCauldronActivity}, evening purpose) and then washes.
 */
public final class WashActivity extends Activity {

    /** How long the scrub takes: the work blend, then the whole farmer_wash clip. */
    public static final int SCRUB_TICKS = VillagerAnimationState.WORK_BLEND_TICKS
            + VillagerAnimationState.cycleTicks(VillagerAnimationState.WASH);
    /** A pause (the villager's screen open) leaves the farmer idle; the wash starts over at most this often. */
    private static final int MAX_RESTARTS = 2;
    /** Clip ticks where the hands go into the water (farmer_wash). */
    private static final int[] DIPS = {43, 85, 144};
    /** Clip ticks where the head shakes the water off. */
    private static final int[] SPRAYS = {72, 74, 75};
    /** Clip ticks where one hand scrubs the other forearm, or the hands rub together. */
    private static final int[] SCRUBS = {97, 104, 115, 122, 129, 133, 137};
    /** Clip ticks where the hands are flicked dry, beside the body. */
    private static final int[] FLICKS = {198, 202, 206};
    /** Clip tick where the water reaches the face. */
    private static final int FACE = 56;
    /**
     * Clip tick spans where water drips, with where the drops fall from: blocks
     * ahead of the feet and blocks up (-1: from the chin). Off the hands over the
     * cauldron, the face, the forearms in front of the belly, the hands again.
     */
    private static final double[][] DRIPS = {
            {48, 56, 0.75, 1.15}, {56, 72, 0.3, -1}, {90, 138, 0.5, 1.0}, {146, 152, 0.75, 1.15}};
    /** How far from the cauldron the next farmer in line stands. */
    private static final int WAIT_RADIUS = 3;
    /** How often a waiting farmer looks at the line and the other cauldrons again. */
    private static final int RECHECK_TICKS = 40;
    /** How far from the door, outside, the rest of the line looks for a place to stand. */
    private static final int OUTSIDE_RADIUS = 4;

    private enum Phase { REFILL, WALK, WAIT, SCRUB }

    private BlockPos cauldron;
    private UUID buildingId;

    private Phase phase;
    @Nullable private RefillCauldronActivity refill;
    /** The exact tile beside the cauldron this farmer scrubs on, from picking it to the end. */
    private final CauldronStand tile = new CauldronStand();
    @Nullable private BlockPos waitSpot;
    /** Whether {@link #waitSpot} is beside the cauldron (next in line) or outside the room. */
    private boolean waitInside;
    private int recheckTimer;
    private int scrubTimer;
    private int restarts;
    private boolean claimed;
    private boolean waitLogged;
    private float dirtBefore;
    @Nullable private String failure;

    public WashActivity(BlockPos cauldron, UUID buildingId, boolean refillFirst) {
        this.cauldron = cauldron.immutable();
        this.buildingId = buildingId;
        this.phase = refillFirst ? Phase.REFILL : Phase.WALK;
    }

    /**
     * The wash at the cauldron just picked, with the farmer in its line at
     * once: the next farmer to pick, this same tick, already counts it.
     */
    public static WashActivity forChoice(Context ctx, FarmerCauldrons.WashChoice choice) {
        CauldronClaims.joinLine(ctx.level(), choice.spot().pos(), ctx.model().npcId, choice.refillFirst());
        return new WashActivity(choice.spot().pos(), choice.spot().building().getItemFrameId(),
                choice.refillFirst());
    }

    public BlockPos cauldron() { return cauldron; }

    /** Why the wash failed, or null. */
    @Nullable public String failure() { return failure; }

    @Override
    protected void start(Context ctx) {
        if (phase == Phase.REFILL) {
            refill = new RefillCauldronActivity(cauldron, buildingId, RefillCauldronActivity.Purpose.EVENING);
        }
    }

    @Override
    protected Status tick(Context ctx) {
        NpcEntity npc = ctx.npc();
        if (phase != Phase.SCRUB && npc.getDirt() <= 0f) {
            diary(ctx, "WASH already clean");
            leaveLine(ctx);
            return Status.DONE;
        }
        ServerLevel level = ctx.level();
        UUID me = ctx.model().npcId;
        CauldronClaims.joinLine(level, cauldron, me, phase == Phase.REFILL);
        switch (phase) {
            case REFILL -> {
                Status refilled = refill.tickOuter(ctx);
                if (refilled == Status.RUNNING) return Status.RUNNING;
                refill.stop(ctx);
                // Done with water in it: poured, or someone filled it meanwhile.
                boolean ok = refilled == Status.DONE && CauldronIndex.waterLevel(level, cauldron) > 0;
                refill = null;
                if (!ok) return fail(ctx, "evening refill failed");
                phase = Phase.WALK;
                resetMove();
            }
            case WALK -> {
                int water = CauldronIndex.waterLevel(level, cauldron);
                if (water < 0) return fail(ctx, "cauldron gone");
                if (water == 0) {
                    // Someone else is refilling it: wait for the water.
                    if (FarmerCauldrons.refillUnderway(level, cauldron)) {
                        toWait(ctx);
                        return Status.RUNNING;
                    }
                    return onDry(ctx);
                }
                if (!claimed) {
                    // Turns go in line order: nobody walks in ahead of those before it.
                    int ahead = CauldronClaims.ahead(level, cauldron, me);
                    if (ahead >= FarmerCauldrons.washesLeft(level, cauldron)) {
                        Status after = leaveOrSwitch(ctx, ahead);
                        return after != null ? after : Status.RUNNING;
                    }
                    if (ahead > 0 || !CauldronClaims.tryClaim(level, cauldron, me, CauldronClaims.Kind.WASH)) {
                        toWait(ctx);
                        return Status.RUNNING;
                    }
                    claimed = true;
                } else {
                    CauldronClaims.tryClaim(level, cauldron, me, CauldronClaims.Kind.WASH);
                }
                CauldronStand.Step step = tile.step(ctx, cauldron, buildingId,
                        goal -> moveTo(ctx, goal, VillagerModel.WALK_SPEED), this::resetMove);
                if (step == CauldronStand.Step.WALKING) return Status.RUNNING;
                if (step == CauldronStand.Step.FAILED) return fail(ctx, tile.failure());
                dirtBefore = npc.getDirt();
                CauldronIndex.StandSpot spot = tile.spot();
                diary(ctx, "WASH at tile " + spot.pos().toShortString() + " ("
                        + spot.fit().name().toLowerCase(java.util.Locale.ROOT) + ")");
                startScrub(npc);
                phase = Phase.SCRUB;
            }
            case WAIT -> {
                int water = CauldronIndex.waterLevel(level, cauldron);
                if (water < 0) return fail(ctx, "cauldron gone");
                boolean recheck = --recheckTimer <= 0;
                if (recheck) recheckTimer = RECHECK_TICKS;
                if (water == 0 && !FarmerCauldrons.refillUnderway(level, cauldron)) {
                    // The first in line refills at once; the rest give it a moment to start.
                    if (CauldronClaims.ahead(level, cauldron, me) == 0) return onDry(ctx);
                    if (recheck) return fail(ctx, "cauldron dry");
                }
                if (recheck) {
                    Status after = recheckLine(ctx);
                    if (after != null) return after;
                    if (phase != Phase.WAIT) return Status.RUNNING;
                }
                if (water > 0 && CauldronClaims.isFreeFor(level, cauldron, me)
                        && CauldronClaims.ahead(level, cauldron, me) == 0) {
                    releaseWaitSpot(ctx);
                    phase = Phase.WALK;
                    resetMove();
                    return Status.RUNNING;
                }
                if (waitSpot != null) {
                    StandClaims.claim(level, waitSpot, me);
                    if (moveTo(ctx, waitSpot, VillagerModel.WALK_SPEED) && waitInside) CraftingStation.face(npc, cauldron);
                }
            }
            case SCRUB -> {
                CauldronClaims.tryClaim(level, cauldron, me, CauldronClaims.Kind.WASH);
                tile.keep(ctx);
                CraftingStation.face(npc, cauldron);
                // Back from a pause idle: the clip is gone, so the wash starts over.
                if (npc.getAnimation() != VillagerAnimationState.WASH && restarts < MAX_RESTARTS) {
                    restarts++;
                    startScrub(npc);
                }
                washEffects(level, npc, SCRUB_TICKS - scrubTimer - VillagerAnimationState.WORK_BLEND_TICKS);
                if (--scrubTimer > 0) return Status.RUNNING;
                return finishWash(ctx);
            }
        }
        return Status.RUNNING;
    }

    /** The scrub is done: mark the water muddy, take one level, and the farmer is clean. */
    private Status finishWash(Context ctx) {
        ServerLevel level = ctx.level();
        NpcEntity npc = ctx.npc();
        npc.setAnimation(VillagerAnimationState.IDLE);
        BlockState state = level.getBlockState(cauldron);
        int before = CauldronIndex.waterLevel(level, cauldron);
        if (before <= 0) return fail(ctx, before < 0 ? "cauldron gone" : "cauldron dry");
        // Marked first, so the client has the mark when the lower level arrives.
        MuddyCauldrons.mark(level, cauldron);
        LayeredCauldronBlock.lowerFillLevel(state, level, cauldron);
        npc.setDirt(0f);
        FarmerCauldrons.clearMorningWash(ctx.model().npcId);
        diary(ctx, String.format("WASH done dirt %.1f→0 cauldron %s lvl %d→%d",
                dirtBefore, cauldron.toShortString(), before, Math.max(0, CauldronIndex.waterLevel(level, cauldron))));
        releaseClaim(ctx);
        tile.release(ctx);
        leaveLine(ctx);
        return Status.DONE;
    }

    /** The wash clip from frame zero, and the scrub timer with it. */
    private void startScrub(NpcEntity npc) {
        CraftingStation.face(npc, cauldron);
        npc.startWorkAnimation(VillagerAnimationState.WASH, 1f);
        scrubTimer = SCRUB_TICKS;
    }

    /**
     * Splashes, drips and sounds on the clip's frames ({@code clipTick} counts
     * from the first frame after the blend). The hands reach the water only in
     * a full cauldron, so the dips splash only then.
     */
    private void washEffects(ServerLevel level, NpcEntity npc, int clipTick) {
        if (clipTick < 0) return;
        double fx = cauldron.getX() + 0.5 - npc.getX(), fz = cauldron.getZ() + 0.5 - npc.getZ();
        double len = Math.sqrt(fx * fx + fz * fz);
        if (len < 1.0e-3) return;
        fx /= len;
        fz /= len;
        // The farmer's right hand side, across the facing.
        double rx = -fz, rz = fx;
        double feetY = npc.getY();
        if (contains(DIPS, clipTick) && CauldronIndex.waterLevel(level, cauldron) >= 3) {
            level.playSound(null, cauldron, SoundEvents.GENERIC_SPLASH, SoundSource.NEUTRAL, 0.45f, 1.25f);
            level.sendParticles(ParticleTypes.SPLASH, cauldron.getX() + 0.5 - fx * 0.3, cauldron.getY() + 0.95,
                    cauldron.getZ() + 0.5 - fz * 0.3, 8, 0.15, 0.02, 0.15, 0.1);
        }
        double headX = npc.getX() + fx * 0.3, headY = npc.getEyeY(), headZ = npc.getZ() + fz * 0.3;
        if (clipTick == FACE) {
            level.playSound(null, npc.blockPosition(), SoundEvents.GENERIC_SPLASH, SoundSource.NEUTRAL, 0.35f, 1.5f);
            level.sendParticles(ParticleTypes.SPLASH, headX, headY, headZ, 10, 0.15, 0.1, 0.15, 0.1);
        }
        if (contains(SPRAYS, clipTick)) {
            level.sendParticles(ParticleTypes.SPLASH, headX, headY + 0.1, headZ, 6, 0.35, 0.1, 0.35, 0.2);
        }
        if (contains(SCRUBS, clipTick)) {
            level.sendParticles(ParticleTypes.SPLASH, npc.getX() + fx * 0.3, feetY + 0.85, npc.getZ() + fz * 0.3,
                    3, 0.12, 0.05, 0.12, 0.05);
        }
        if (contains(FLICKS, clipTick)) {
            for (int side = -1; side <= 1; side += 2) {
                level.sendParticles(ParticleTypes.SPLASH, npc.getX() + rx * 0.4 * side, feetY + 0.9,
                        npc.getZ() + rz * 0.4 * side, 3, 0.08, 0.05, 0.08, 0.15);
            }
        }
        // A drop every few ticks while something is wet.
        if (clipTick % 3 == 0) {
            for (double[] span : DRIPS) {
                if (clipTick < span[0] || clipTick >= span[1]) continue;
                double y = span[3] < 0 ? headY - 0.15 : feetY + span[3];
                level.sendParticles(ParticleTypes.FALLING_WATER, npc.getX() + fx * span[2], y,
                        npc.getZ() + fz * span[2], 1, 0.08, 0.02, 0.08, 0);
                break;
            }
        }
    }

    private static boolean contains(int[] ticks, int tick) {
        for (int t : ticks) if (t == tick) return true;
        return false;
    }

    /**
     * The cauldron is dry. The first farmer in line refills it, when it can
     * get a bucket and reach water; anyone else, or a farmer with no bucket,
     * gives up on it.
     */
    private Status onDry(Context ctx) {
        ServerLevel level = ctx.level();
        UUID me = ctx.model().npcId;
        if (CauldronClaims.ahead(level, cauldron, me) > 0 || !FarmerCauldrons.canRefill(ctx, cauldron, true)) {
            return fail(ctx, "cauldron dry");
        }
        diary(ctx, "WASH refill dry cauldron, first in line " + cauldron.toShortString());
        releaseClaim(ctx);
        tile.release(ctx);
        releaseWaitSpot(ctx);
        ctx.model().stopMoving();
        resetMove();
        CauldronClaims.joinLine(level, cauldron, me, true);
        refill = new RefillCauldronActivity(cauldron, buildingId, RefillCauldronActivity.Purpose.EVENING);
        phase = Phase.REFILL;
        return Status.RUNNING;
    }

    /**
     * A waiting farmer's look at the line: leave when the farmers ahead will
     * use up the water, move to another cauldron that is free now, and keep
     * the right place to stand (beside the cauldron when next, else outside).
     * Returns a status to end with, or null to keep going.
     */
    @Nullable
    private Status recheckLine(Context ctx) {
        ServerLevel level = ctx.level();
        int ahead = CauldronClaims.ahead(level, cauldron, ctx.model().npcId);
        if (ahead >= FarmerCauldrons.washesLeft(level, cauldron)) return leaveOrSwitch(ctx, ahead);
        FarmerCauldrons.WashChoice other = FarmerCauldrons.pickWash(ctx);
        // Only to a cauldron nobody is using or waiting for, so a farmer never
        // swaps back and forth between two lines. Waiting at home, only for a
        // line of two or more and a free cauldron closer than the home one.
        if (other != null && !other.spot().pos().equals(cauldron) && !other.busy() && !other.refillFirst()
                && (!atHome(ctx) || (ahead >= 2 && other.distance() < distanceToHomeStand(ctx)))) {
            switchTo(ctx, other);
            return null;
        }
        boolean shouldBeInside = ahead <= 1;
        if (shouldBeInside != waitInside || waitSpot == null) pickWaitSpot(ctx, shouldBeInside);
        return null;
    }

    /** The cauldron being waited at stands in this farmer's own house. */
    private boolean atHome(Context ctx) {
        Building home = ctx.mgr().getAssignedBedBuilding(ctx.model().npcId);
        return home != null && home.getItemFrameId().equals(buildingId);
    }

    /** How far the farmer is from the tile it would wash on at this cauldron. */
    private double distanceToHomeStand(Context ctx) {
        Building building = ctx.mgr().getBuilding(buildingId);
        BlockPos here = ctx.npc().blockPosition();
        BlockPos stand = building == null ? null
                : CauldronIndex.standTile(building, cauldron, ctx.mgr().getGraph(), here);
        return stand == null ? Double.MAX_VALUE : Math.sqrt(stand.distSqr(here));
    }

    /**
     * The farmers ahead use up this cauldron's water: another cauldron with
     * room, or none tonight. Returns null after a switch.
     */
    @Nullable
    private Status leaveOrSwitch(Context ctx, int ahead) {
        FarmerCauldrons.WashChoice other = FarmerCauldrons.pickWash(ctx);
        if (other != null && !other.spot().pos().equals(cauldron)) {
            switchTo(ctx, other);
            return null;
        }
        return fail(ctx, "line too long (" + ahead + " ahead)");
    }

    /** Leaves this cauldron's line for another cauldron's. */
    private void switchTo(Context ctx, FarmerCauldrons.WashChoice other) {
        diary(ctx, String.format("WASH switch %s → %s %s lvl=%d%s", cauldron.toShortString(),
                other.where().label, other.spot().pos().toShortString(), other.level(),
                other.busy() ? " (in use, will wait)" : ""));
        releaseClaim(ctx);
        tile.release(ctx);
        releaseWaitSpot(ctx);
        leaveLine(ctx);
        cauldron = other.spot().pos().immutable();
        buildingId = other.spot().building().getItemFrameId();
        waitLogged = false;
        ctx.model().stopMoving();
        resetMove();
        if (other.refillFirst()) {
            refill = new RefillCauldronActivity(cauldron, buildingId, RefillCauldronActivity.Purpose.EVENING);
            phase = Phase.REFILL;
        } else {
            phase = Phase.WALK;
        }
        CauldronClaims.joinLine(ctx.level(), cauldron, ctx.model().npcId, phase == Phase.REFILL);
    }

    /** Someone else is at the cauldron: find a place to wait for a turn. */
    private void toWait(Context ctx) {
        ServerLevel level = ctx.level();
        if (!waitLogged) {
            waitLogged = true;
            CauldronClaims.Claim claim = CauldronClaims.holder(level, cauldron);
            diary(ctx, "WASH wait busy (held by "
                    + (claim == null ? "nobody" : nameOf(ctx, claim.owner())) + ", "
                    + CauldronClaims.ahead(level, cauldron, ctx.model().npcId) + " ahead)");
        }
        tile.release(ctx);
        phase = Phase.WAIT;
        recheckTimer = RECHECK_TICKS;
        ctx.model().stopMoving();
        resetMove();
        pickWaitSpot(ctx, CauldronClaims.ahead(level, cauldron, ctx.model().npcId) <= 1);
    }

    /**
     * The next farmer in line stands on a free room tile near the cauldron;
     * everyone behind it stands outside, near the door, out of the way.
     */
    private void pickWaitSpot(Context ctx, boolean inside) {
        releaseWaitSpot(ctx);
        waitInside = inside;
        resetMove();
        Building building = ctx.mgr().getBuilding(buildingId);
        if (building == null) return;
        BlockPos spot = inside ? insideSpot(ctx, building) : outsideSpot(ctx, building);
        // No free place outside: the next best is a free place inside.
        if (spot == null && !inside) spot = insideSpot(ctx, building);
        waitSpot = spot;
    }

    @Nullable
    private BlockPos insideSpot(Context ctx, Building building) {
        ServerLevel level = ctx.level();
        UUID me = ctx.model().npcId;
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos spot : building.getNavMeshBlocks()) {
            if (Math.abs(spot.getX() - cauldron.getX()) > WAIT_RADIUS
                    || Math.abs(spot.getZ() - cauldron.getZ()) > WAIT_RADIUS
                    || Math.abs(spot.getY() - cauldron.getY()) > 1) continue;
            // Never on the washing tiles, and never up on a cauldron.
            if (isBesideCauldron(spot)) continue;
            if (level.getBlockState(spot.below()).getBlock() instanceof AbstractCauldronBlock) continue;
            NavNode node = graph.nodeAt(spot.getX(), spot.getY(), spot.getZ());
            if (node == null || !node.isRoutable()) continue;
            if (!StandClaims.isFreeFor(level, spot, me)) continue;
            double d = spot.distSqr(cauldron);
            if (d < bestDist) {
                bestDist = d;
                best = spot;
            }
        }
        return best;
    }

    /** One of the four tiles straight beside the cauldron, at its height. */
    private boolean isBesideCauldron(BlockPos spot) {
        if (spot.getY() != cauldron.getY()) return false;
        return Math.abs(spot.getX() - cauldron.getX()) + Math.abs(spot.getZ() - cauldron.getZ()) == 1;
    }

    /** A free routable tile outside the room, a step or two out from its door. */
    @Nullable
    private BlockPos outsideSpot(Context ctx, Building building) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null || building.getDoorPos() == null || building.getInteriorDirection() == null) return null;
        ServerLevel level = ctx.level();
        UUID me = ctx.model().npcId;
        BlockPos door = building.getDoorPos();
        BlockPos outside = door.relative(building.getInteriorDirection().getOpposite(), 2);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (NavNode node : graph.nodesInBox(outside, OUTSIDE_RADIUS, 2)) {
            if (!node.isRoutable()) continue;
            BlockPos tile = node.feetPos();
            // Not in the room, and not in the doorway itself.
            if (building.getNavMeshBlocks().contains(tile) || building.isInRoom(tile)) continue;
            if (tile.getX() == door.getX() && tile.getZ() == door.getZ()) continue;
            if (!StandClaims.isFreeFor(level, tile, me)) continue;
            double d = tile.distSqr(outside);
            if (d < bestDist) {
                bestDist = d;
                best = tile;
            }
        }
        if (best == null || !ctx.model().canReach(graph, best)) return null;
        return best;
    }

    private Status fail(Context ctx, String why) {
        failure = why;
        diary(ctx, "WASH cancel " + why + " " + cauldron.toShortString());
        if (phase == Phase.SCRUB) ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        releaseClaim(ctx);
        tile.release(ctx);
        releaseWaitSpot(ctx);
        leaveLine(ctx);
        return Status.FAILED;
    }

    private void releaseClaim(Context ctx) {
        if (!claimed) return;
        CauldronClaims.release(ctx.level(), cauldron, ctx.model().npcId);
        claimed = false;
    }

    private void releaseWaitSpot(Context ctx) {
        if (waitSpot == null) return;
        StandClaims.release(ctx.level(), waitSpot, ctx.model().npcId);
        waitSpot = null;
    }

    private void leaveLine(Context ctx) {
        CauldronClaims.leaveLine(ctx.level(), cauldron, ctx.model().npcId);
    }

    /** A villager's name for the diary, or the start of its id. */
    static String nameOf(Context ctx, UUID id) {
        VillagerModel model = ctx.mgr().getModel(id);
        NpcEntity puppet = model != null ? model.puppet() : null;
        return puppet != null ? puppet.getName().getString() : id.toString().substring(0, 8);
    }

    private static void diary(Context ctx, String line) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + line);
    }

    @Override
    public void stop(Context ctx) {
        if (refill != null) {
            refill.stop(ctx);
            refill = null;
        }
        if (phase == Phase.SCRUB && scrubTimer > 0) ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        releaseClaim(ctx);
        tile.release(ctx);
        releaseWaitSpot(ctx);
        leaveLine(ctx);
        ctx.model().stopMoving();
    }

    @Override
    public String name() {
        return "wash";
    }
}

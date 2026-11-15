package com.alex.test.village.villagers.farmer.behavior;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.CraftingStation;
import com.alex.test.village.brain.StandClaims;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.CauldronIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The exact tile one farmer stands on at one cauldron, for {@link WashActivity}
 * and {@link RefillCauldronActivity}: picks it ({@link CauldronIndex#workTile}),
 * keeps it claimed, walks there, and then takes the last step onto the tile's
 * centre so the work clips line up with the cauldron.
 *
 * <p>The walk itself is loose (a block or two short, or up on the cauldron's
 * rim). The settle step is the same idea as {@link CraftingStation#settle}:
 * walk straight to the centre for a moment, then place the farmer there. If the
 * tile is taken on the way, or the settle runs out of time, the tile is picked
 * again; after that the job fails.
 */
final class CauldronStand {

    enum Step { WALKING, READY, FAILED }

    private static final int SETTLE_MAX_TICKS = 30;
    private static final double SETTLE_DIST_SQ = 0.15 * 0.15;
    /** After the settle time, a farmer this close (across) is placed on the tile. */
    private static final double SNAP_DIST_SQ = 1.5 * 1.5;
    /** How often a tile someone else took is picked again, before giving up. */
    private static final int MAX_TAKEN_REPICKS = 3;
    private static final String UNREACHABLE = "could not reach the cauldron";

    @Nullable private CauldronIndex.StandSpot spot;
    private boolean settling;
    private int settleTicks;
    private boolean settleRepicked;
    private int takenRepicks;
    @Nullable private String failure;

    /** The tile picked now, or null before one is picked or after it is released. */
    @Nullable
    CauldronIndex.StandSpot spot() { return spot; }

    /** Why {@link Step#FAILED} was returned. */
    @Nullable
    String failure() { return failure; }

    /** Keeps the claim fresh on a tile already picked (the scrub). */
    void keep(Activity.Context ctx) {
        if (spot != null) StandClaims.claim(ctx.level(), spot.pos(), ctx.model().npcId);
    }

    /** Lets go of the tile and starts over with the next cauldron. */
    void release(Activity.Context ctx) {
        drop(ctx);
        settleRepicked = false;
        takenRepicks = 0;
        failure = null;
    }

    private void drop(Activity.Context ctx) {
        if (spot != null) StandClaims.release(ctx.level(), spot.pos(), ctx.model().npcId);
        spot = null;
        settling = false;
        settleTicks = 0;
    }

    /**
     * One tick of getting onto the tile. {@code mover} is the activity's own
     * {@code moveTo} (true once the walk has finished) and {@code resetMove}
     * lets it issue a fresh walk.
     */
    Step step(Activity.Context ctx, BlockPos cauldron, UUID buildingId,
              Predicate<BlockPos> mover, Runnable resetMove) {
        ServerLevel level = ctx.level();
        NpcEntity npc = ctx.npc();
        UUID me = ctx.model().npcId;

        // Claimed by someone else before the farmer got there. A body on the
        // tile is only checked at the settle: it is usually just stepping off.
        if (spot != null && !settling && !StandClaims.isFreeFor(level, spot.pos(), me)) {
            if (++takenRepicks > MAX_TAKEN_REPICKS) return fail(UNREACHABLE);
            drop(ctx);
            resetMove.run();
        }
        if (spot == null) {
            Building building = ctx.mgr().getBuilding(buildingId);
            spot = building == null ? null : CauldronIndex.workTile(level, building, cauldron,
                    ctx.mgr().getGraph(), npc.blockPosition(), me, settleRepicked);
            if (spot == null) return fail("no tile beside the cauldron");
            resetMove.run();
        }
        // Claimed from the moment it is picked, so two farmers never pick the same one.
        StandClaims.claim(level, spot.pos(), me);
        BlockPos tile = spot.pos();

        if (!settling) {
            if (!mover.test(tile)) return Step.WALKING;
            if (!RefillCauldronActivity.isClose(npc.blockPosition(), tile)) return fail(UNREACHABLE);
            settling = true;
            settleTicks = 0;
        }

        double dx = tile.getX() + 0.5 - npc.getX();
        double dz = tile.getZ() + 0.5 - npc.getZ();
        double distSq = dx * dx + dz * dz;
        if (distSq <= SETTLE_DIST_SQ) {
            place(ctx, cauldron);
            return Step.READY;
        }
        if (++settleTicks > SETTLE_MAX_TICKS) {
            if (distSq <= SNAP_DIST_SQ && Math.abs(npc.getY() - tile.getY()) <= 1.0
                    && !CauldronIndex.tileTaken(level, tile, me)) {
                place(ctx, cauldron);
                return Step.READY;
            }
            if (settleRepicked) return fail(UNREACHABLE);
            settleRepicked = true;
            drop(ctx);
            resetMove.run();
            return Step.WALKING;
        }
        npc.getMoveControl().setWantedPosition(tile.getX() + 0.5, npc.getY(), tile.getZ() + 0.5, 0.4);
        return Step.WALKING;
    }

    /** Stops the walk, stands the farmer in the middle of the tile and turns it to the cauldron. */
    private void place(Activity.Context ctx, BlockPos cauldron) {
        NpcEntity npc = ctx.npc();
        BlockPos tile = spot.pos();
        ctx.model().stopMoving();
        npc.getNavigation().stop();
        NavGraph graph = ctx.mgr().getGraph();
        NavNode node = graph == null ? null : graph.nodeAt(tile.getX(), tile.getY(), tile.getZ());
        double y = node != null ? node.standY : tile.getY();
        // Not into a chunk still loading (FarAwake): it stands where it stopped.
        if (com.alex.test.village.farawake.FarAwake.readyFor(ctx.level(), tile.getX() + 0.5, tile.getZ() + 0.5)) {
            npc.setPos(tile.getX() + 0.5, y, tile.getZ() + 0.5);
        }
        npc.setDeltaMovement(0, npc.getDeltaMovement().y, 0);
        CraftingStation.face(npc, cauldron);
    }

    private Step fail(String why) {
        failure = why;
        return Step.FAILED;
    }
}

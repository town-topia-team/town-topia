package com.alex.test.village.brain;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.ClaimsRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.UUID;

/**
 * One villager's use of one crafting table: who has it, where to stand, and
 * which way to face.
 *
 * <p>A table is worked by one villager at a time. The table's position is
 * claimed in the village's {@link com.alex.test.village.ClaimsRegistry}; anyone
 * else who wants it waits where they are, watching it, until it frees up.
 * Waiting is bounded by {@link #MAX_WAIT_TICKS} so a claim left behind by a
 * villager that vanished without stopping cannot freeze the kitchen forever.
 *
 * <p>The villager works from the room tile beside the table and faces it. Path
 * arrival is loose (A* may finish a block short), so {@link #settle} finishes
 * the last step onto the tile's centre before work starts.
 */
public final class CraftingStation {

    /** How far back a villager may stand when no tile beside the table is free. */
    public static final int MAX_STAND_RADIUS = 3;

    /** Two minutes of waiting for a busy table before giving up on it. */
    private static final int MAX_WAIT_TICKS = 20 * 120;

    /** Only look over at the table while waiting if it is in plain view. */
    private static final double WATCH_DIST_SQ = 12.0 * 12.0;

    private static final double SETTLE_DIST_SQ = 0.15 * 0.15;
    private static final int SETTLE_MAX_TICKS = 30;

    public enum Access { MINE, WAIT, GIVE_UP }

    private final BlockPos table;
    private final int maxWaitTicks;
    private boolean claimed;
    private int waitTicks;
    private int settleTicks;

    public CraftingStation(BlockPos table) {
        this(table, MAX_WAIT_TICKS);
    }

    /** As {@link #CraftingStation(BlockPos)}, giving up after {@code maxWaitTicks} of waiting. */
    public CraftingStation(BlockPos table, int maxWaitTicks) {
        this.table = table;
        this.maxWaitTicks = maxWaitTicks;
    }

    public BlockPos table() { return table; }

    /**
     * Which of {@code stations} (a room's crafting tables, furnaces or
     * stonecutters) to work: the one this villager already holds, else the
     * nearest nobody holds, else {@code current} when it is one of them (a
     * villager waiting at a busy one keeps its place and its wait), else the
     * nearest. With one station it is always that one. Null when there are none.
     */
    @Nullable
    public static BlockPos pick(Activity.Context ctx, Collection<BlockPos> stations, @Nullable BlockPos current) {
        ClaimsRegistry claims = ctx.mgr().getClaims();
        UUID me = ctx.model().npcId;
        BlockPos from = ctx.npc().blockPosition();
        BlockPos nearestFree = null;
        BlockPos nearest = null;
        for (BlockPos pos : stations) {
            UUID holder = claims.holder(pos);
            if (me.equals(holder)) return pos;
            if (holder == null && (nearestFree == null || pos.distSqr(from) < nearestFree.distSqr(from))) {
                nearestFree = pos;
            }
            if (nearest == null || pos.distSqr(from) < nearest.distSqr(from)) nearest = pos;
        }
        if (nearestFree != null) return nearestFree;
        return current != null && stations.contains(current) ? current : nearest;
    }

    /** Where to stand to work the table: beside it, or a few steps back if boxed in. */
    @Nullable
    public BlockPos standFor(Activity.Context ctx, Building building) {
        return building.getWalkTargetNear(table, ctx.mgr().getGraph(),
                ctx.npc().blockPosition(), MAX_STAND_RADIUS);
    }

    /**
     * Try to take the table. Call every tick until it returns {@link Access#MINE}.
     * While another villager is at it this holds the caller still and returns
     * {@link Access#WAIT}; after the wait limit ({@link #MAX_WAIT_TICKS}
     * unless the constructor gave another) it returns {@link Access#GIVE_UP}.
     */
    public Access acquire(Activity.Context ctx) {
        if (claimed) return Access.MINE;
        if (ctx.mgr().getClaims().claim(table, ctx.model().npcId)) {
            claimed = true;
            waitTicks = 0;
            return Access.MINE;
        }
        if (waitTicks == 0) ctx.model().stopMoving();
        if (ctx.npc().blockPosition().distSqr(table) <= WATCH_DIST_SQ) {
            ctx.npc().getLookControl().setLookAt(
                    table.getX() + 0.5, table.getY() + 0.9, table.getZ() + 0.5, 30f, 30f);
        }
        return ++waitTicks >= maxWaitTicks ? Access.GIVE_UP : Access.WAIT;
    }

    /**
     * Take the table now if it is free, without waiting or stopping. A claim
     * left by a villager that is gone (removed without stopping) is taken over.
     */
    public boolean tryClaim(Activity.Context ctx) {
        if (claimed) return true;
        ClaimsRegistry claims = ctx.mgr().getClaims();
        UUID holder = claims.holder(table);
        if (holder != null && !holder.equals(ctx.model().npcId)) {
            VillagerModel other = ctx.mgr().getModel(holder);
            NpcEntity body = other != null ? other.puppet() : null;
            if (body == null || !body.isAlive()) claims.release(table, holder);
        }
        if (!claims.claim(table, ctx.model().npcId)) return false;
        claimed = true;
        waitTicks = 0;
        return true;
    }

    public void release(Activity.Context ctx) {
        if (!claimed) return;
        ctx.mgr().getClaims().release(table, ctx.model().npcId);
        claimed = false;
    }

    /**
     * Step onto the centre of {@code stand} after the path has ended. Call every
     * tick; returns {@code true} once there (or once it has tried long enough).
     */
    public boolean settle(Activity.Context ctx, BlockPos stand) {
        NpcEntity npc = ctx.npc();
        double dx = stand.getX() + 0.5 - npc.getX();
        double dz = stand.getZ() + 0.5 - npc.getZ();
        if (dx * dx + dz * dz <= SETTLE_DIST_SQ || ++settleTicks > SETTLE_MAX_TICKS) {
            settleTicks = 0;
            npc.getNavigation().stop();
            face(npc, table);
            return true;
        }
        npc.getMoveControl().setWantedPosition(
                stand.getX() + 0.5, npc.getY(), stand.getZ() + 0.5, 0.4);
        return false;
    }

    /** Turn body and head square to {@code block} and look down at its top. */
    public static void face(NpcEntity npc, BlockPos block) {
        Vec3 dir = Vec3.atCenterOf(block).subtract(npc.position());
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        npc.setYRot(yaw);
        npc.setYBodyRot(yaw);
        npc.setYHeadRot(yaw);
        npc.getLookControl().setLookAt(
                block.getX() + 0.5, block.getY() + 0.9, block.getZ() + 0.5, 30f, 30f);
    }
}

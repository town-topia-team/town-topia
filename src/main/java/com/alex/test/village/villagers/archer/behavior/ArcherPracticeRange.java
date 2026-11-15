package com.alex.test.village.villagers.archer.behavior;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.building.ArcheryRangeBuilding;
import com.alex.test.village.building.BarracksBuilding;
import com.alex.test.village.building.Building;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Practice targets around a barracks: vanilla target blocks inside it or within
 * a few blocks of it, and where to stand to shoot at one. Only used while the
 * village has no Archery Range with a lane (see {@link ArcheryPractice#begin}).
 * The block scan is cached per barracks for a minute.
 */
final class ArcherPracticeRange {

    /** How far outside the barracks room a target still counts as its range. */
    private static final int YARD_XZ = 10;
    private static final int YARD_Y = 3;
    private static final int RESCAN_TICKS = 1200;
    private static final double MIN_STAND_DIST = 6.0D;
    private static final double MAX_STAND_DIST = 12.0D;
    private static final double IDEAL_STAND_DIST = 9.0D;
    private static final int STAND_Y = 4;
    /** Around a target, where the floor to pull its arrows from is looked for. */
    private static final int PULL_XZ = 2;
    private static final int PULL_Y = 4;

    private record Scan(List<BlockPos> targets, long at) {}

    /** Barracks door -> target blocks found around it. */
    private static final Map<BlockPos, Scan> SCANS = new HashMap<>();

    private ArcherPracticeRange() {}

    /** The nearest barracks target no other archer is shooting at, or null. */
    @Nullable
    static BlockPos nearestFreeTarget(Activity.Context ctx) {
        long now = ctx.level().getGameTime();
        BlockPos from = ctx.npc().blockPosition();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof BarracksBuilding barracks) || !barracks.isValid()) continue;
            for (BlockPos target : targetsAround(ctx, barracks, now)) {
                if (!ArcheryLanes.isFree(ctx.level(), target, ctx.model().npcId)) continue;
                double d = target.distSqr(from);
                if (d < bestSq) {
                    bestSq = d;
                    best = target;
                }
            }
        }
        return best;
    }

    private static List<BlockPos> targetsAround(Activity.Context ctx, BarracksBuilding barracks, long now) {
        BlockPos key = barracks.getDoorPos();
        Scan scan = SCANS.get(key);
        if (scan != null && now - scan.at() < RESCAN_TICKS && now >= scan.at()) {
            // A target broken since the scan is dropped on sight.
            scan.targets().removeIf(pos -> !ctx.level().getBlockState(pos).is(Blocks.TARGET));
            return scan.targets();
        }
        AABB box = barracks.roomBox().inflate(YARD_XZ, YARD_Y, YARD_XZ);
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                (int) Math.floor(box.minX), (int) Math.floor(box.minY), (int) Math.floor(box.minZ),
                (int) Math.floor(box.maxX), (int) Math.floor(box.maxY), (int) Math.floor(box.maxZ))) {
            if (!ctx.level().isLoaded(pos)) continue;
            if (ctx.level().getBlockState(pos).is(Blocks.TARGET)) found.add(pos.immutable());
        }
        SCANS.put(key, new Scan(found, now));
        if (SCANS.size() > 64) SCANS.values().removeIf(s -> now - s.at() > RESCAN_TICKS * 10L);
        return found;
    }

    /** A walkable spot 6 to 12 blocks from the target with a clear line to it. */
    @Nullable
    static BlockPos standFor(Activity.Context ctx, BlockPos target) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        double eye = ctx.npc().getEyeHeight();
        BlockPos from = ctx.npc().blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (NavNode node : graph.nodesInBox(target, (int) MAX_STAND_DIST, STAND_Y)) {
            if (node.isAvoidedSurface()) continue;
            BlockPos pos = node.feetPos();
            double dx = pos.getX() + 0.5D - (target.getX() + 0.5D);
            double dz = pos.getZ() + 0.5D - (target.getZ() + 0.5D);
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < MIN_STAND_DIST || dist > MAX_STAND_DIST) continue;
            if (Math.abs(pos.getY() - target.getY()) > STAND_Y) continue;
            if (ctx.model().hasRecentMoveFailure(pos)) continue;
            if (ArcheryLanes.standTaken(ctx.level(), pos, ctx.model().npcId)) continue;
            Vec3 eyeAt = new Vec3(pos.getX() + 0.5D, pos.getY() + eye, pos.getZ() + 0.5D);
            if (!isLineClear(ctx, eyeAt, target)) continue;
            double score = Math.abs(dist - IDEAL_STAND_DIST) * 4.0D + Math.sqrt(pos.distSqr(from)) * 0.1D;
            if (score < bestScore) {
                bestScore = score;
                best = pos;
            }
        }
        return best;
    }

    /**
     * Where to stand to pull arrows out of a barracks target: the walkable
     * spot nearest its middle, a step or two away. Null if there is none.
     */
    @Nullable
    static BlockPos pullSpotFor(Activity.Context ctx, BlockPos target) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        Vec3 centre = Vec3.atCenterOf(target);
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (NavNode node : graph.nodesInBox(target, PULL_XZ, PULL_Y)) {
            if (node.isAvoidedSurface() || !node.isRoutable()) continue;
            BlockPos pos = node.feetPos();
            if (pos.getY() > target.getY()) continue;
            double d = centre.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D);
            if (d < bestSq) {
                bestSq = d;
                best = pos;
            }
        }
        return best;
    }

    /** Whether a target block still stands at {@code pos}. */
    static boolean isTarget(Activity.Context ctx, BlockPos pos) {
        return ctx.level().isLoaded(pos) && ctx.level().getBlockState(pos).is(Blocks.TARGET);
    }

    /** The middle of the target's face the archer looks at; see {@link ArcheryRangeBuilding#aimPoint}. */
    static Vec3 aimPoint(BlockPos target, Vec3 eye) {
        return ArcheryRangeBuilding.aimPoint(target, eye);
    }

    /**
     * Nothing solid and no person standing between {@code eye} and the middle
     * of the target's face: the same point {@link ArcheryRangeBuilding#hasClearShot}
     * plans a lane with, so a lane it accepts is not then always blocked here.
     *
     * <p>Only players and villagers count. The arrow flies through anything
     * friendly anyway ({@code ArcherEvents}); an archer just does not shoot
     * past somebody's head. An armor stand dummy, a cat or a chicken in the
     * lane used to stop every session there.
     */
    static boolean isLineClear(Activity.Context ctx, Vec3 eye, BlockPos target) {
        Vec3 aim = aimPoint(target, eye);
        HitResult hit = ctx.level().clip(new ClipContext(
                eye, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.npc()));
        if (hit.getType() == HitResult.Type.BLOCK && !((BlockHitResult) hit).getBlockPos().equals(target)) {
            return false;
        }
        AABB span = new AABB(eye, aim).inflate(1.0D);
        for (LivingEntity someone : ctx.level().getEntitiesOfClass(LivingEntity.class, span,
                e -> e != ctx.npc() && e.isAlive() && !e.isSpectator() && isPerson(e))) {
            if (someone.getBoundingBox().inflate(0.5D).clip(eye, aim).isPresent()) return false;
        }
        return true;
    }

    private static boolean isPerson(LivingEntity entity) {
        return entity instanceof Player || entity instanceof NpcEntity || entity instanceof AbstractVillager;
    }
}

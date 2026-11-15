package com.alex.test.village.villagers.archer.behavior.combat;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.brain.Activity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Positioning and range rules for archer ranged combat.
 *
 * <p>Deliberately not a reuse of {@code GuardCombatRules.bestApproach}: that
 * method searches for the nearest node within melee adjacency, the opposite
 * of what an archer wants. {@link #face} is genuinely identical between
 * melee and ranged (point the model at the target), so that one method is
 * reused directly from {@code GuardCombatRules} instead of being copied here.
 */
public final class ArcherCombatRules {

    private static final int APPROACH_XZ_RADIUS = 18;
    /**
     * How far above the mob an archer may shoot from (a wall, a tower). A
     * lookout on its watchtower passes its own, taller limit to the overloads
     * that take {@code maxDrop}.
     */
    public static final int MAX_SHOT_DROP = 12;
    /** How far below the mob it may shoot from. */
    private static final int MAX_SHOT_RISE = 4;
    /** Height above the mob that still earns a firing spot more points. */
    private static final double HIGH_GROUND_CAP = 6.0D;
    private static final double HIGH_GROUND_BONUS = 1.5D;
    private static final double REPLAN_BAND_SLACK = 2.0D;
    /** Guards this close to the mob can screen an archer. */
    private static final double GUARD_SCREEN_RADIUS = 8.0D;
    /** Within about 45 degrees of the line from the mob to the guard. */
    private static final double BEHIND_GUARD_COS = 0.7D;
    /** Worth a spot about two blocks off the ideal range. */
    private static final double BEHIND_GUARD_BONUS = 8.0D;
    /** Room left around a friend so a slightly wide arrow still misses it. */
    private static final double FRIEND_CLEARANCE = 0.5D;

    private ArcherCombatRules() {}

    /** True when the archer already has a clean shot within [minDist, maxDist] of the target. */
    public static boolean canShoot(
            NpcEntity npc, LivingEntity target, double minRangeSq, double maxRangeSq) {
        return canShoot(npc, target, minRangeSq, maxRangeSq, MAX_SHOT_DROP);
    }

    /** As above, from up to {@code maxDrop} blocks above the mob. */
    public static boolean canShoot(
            NpcEntity npc, LivingEntity target, double minRangeSq, double maxRangeSq, double maxDrop) {
        return shotBlocker(npc, target, minRangeSq, maxRangeSq, maxDrop) == null;
    }

    /** Why there is no clean shot, with the words the archer's diary uses. */
    public enum Blocker {
        NO_VIEW("no clear view of the mob", true),
        HEIGHT("the mob is too far above or below", false),
        TOO_CLOSE("the mob is too close", false),
        OUT_OF_RANGE("the mob is out of range", false),
        PLAYER_IN_LINE("a player in the line of fire", true);

        private final String why;
        private final boolean passing;

        Blocker(String why, boolean passing) {
            this.why = why;
            this.passing = passing;
        }

        public String why() {
            return why;
        }

        /** Usually over in a moment (someone steps past, the mob ducks behind a post). */
        public boolean passing() {
            return passing;
        }
    }

    /**
     * Vanilla's line of sight, eye to eye, except that the archer sees (and
     * shoots) past its own railing: see {@link RailingSight}.
     */
    public static boolean hasSight(NpcEntity npc, LivingEntity target) {
        if (target.level() != npc.level()) return false;
        Vec3 eye = new Vec3(npc.getX(), npc.getEyeY(), npc.getZ());
        Vec3 head = new Vec3(target.getX(), target.getEyeY(), target.getZ());
        if (head.distanceTo(eye) > 128.0D) return false;
        // The body (where the arrow is aimed) or the head: a mob with its head
        // behind a block, or its body behind a low wall, is still a target.
        // Eye to eye alone let draws down at full draw for no good reason.
        Vec3 body = new Vec3(target.getX(), target.getY(0.5D), target.getZ());
        return RailingSight.isClear(npc.level(), eye, body, npc) || RailingSight.isClear(npc.level(), eye, head, npc);
    }

    /**
     * Why the archer has no clean shot at {@code target} right now; null when
     * it has one. The checks of {@link #canShoot}.
     */
    @Nullable
    public static Blocker shotBlocker(
            NpcEntity npc, LivingEntity target, double minRangeSq, double maxRangeSq, double maxDrop) {
        if (!hasSight(npc, target)) return Blocker.NO_VIEW;
        if (!isShootableHeight(npc.getY() - target.getY(), maxDrop)) return Blocker.HEIGHT;

        double dx = npc.getX() - target.getX();
        double dz = npc.getZ() - target.getZ();
        double distSq = dx * dx + dz * dz;
        if (distSq < minRangeSq) return Blocker.TOO_CLOSE;
        if (distSq > maxRangeSq) return Blocker.OUT_OF_RANGE;
        if (isFriendInLine(npc.getEyePosition(), target, friendsNear(npc, target))) return Blocker.PLAYER_IN_LINE;
        return null;
    }

    /**
     * Anyone the archer will not shoot past, near the line to {@code target}:
     * players only. An archer's arrow flies straight through villagers, guards,
     * golems and animals ({@code ArcherEvents}), so they are no reason to hold
     * fire. They used to be, and with a guard at the mob's side nearly every
     * draw was let down.
     */
    public static List<LivingEntity> friendsNear(NpcEntity npc, LivingEntity target) {
        AABB box = npc.getBoundingBox().minmax(target.getBoundingBox()).inflate(APPROACH_XZ_RADIUS * 0.5D, 2.0D,
                APPROACH_XZ_RADIUS * 0.5D);
        return npc.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e instanceof Player && e != target && e.isAlive() && !e.isSpectator());
    }

    /** Whether one of {@code friends} stands in (or right beside) the arrow's path from {@code from}. */
    public static boolean isFriendInLine(Vec3 from, LivingEntity target, List<LivingEntity> friends) {
        Vec3 to = target.getBoundingBox().getCenter();
        for (LivingEntity friend : friends) {
            if (friend.getBoundingBox().inflate(FRIEND_CLEARANCE).clip(from, to).isPresent()) return true;
        }
        return false;
    }

    /** True while a previously chosen reposition spot is still a good place to shoot from. */
    public static boolean isPositionStillUseful(
            Activity.Context ctx, BlockPos pos, LivingEntity target, double minDist, double maxDist) {
        return isPositionStillUseful(ctx, pos, target, minDist, maxDist, MAX_SHOT_DROP);
    }

    /** As above, for a spot up to {@code maxDrop} blocks above the mob. */
    public static boolean isPositionStillUseful(Activity.Context ctx, BlockPos pos, LivingEntity target,
                                                double minDist, double maxDist, double maxDrop) {
        if (!isShootableHeight(pos.getY() - target.getY(), maxDrop)) return false;

        double dx = (pos.getX() + 0.5D) - target.getX();
        double dz = (pos.getZ() + 0.5D) - target.getZ();
        double xzDist = Math.sqrt(dx * dx + dz * dz);
        return xzDist >= minDist - REPLAN_BAND_SLACK
                && xzDist <= maxDist + REPLAN_BAND_SLACK
                && hasShotLineFrom(ctx, pos, target)
                && !isFriendInLine(eyeAt(ctx, pos), target, friendsNear(ctx.npc(), target));
    }

    /**
     * Best reachable nav node within [minDist, maxDist] of the target with a
     * clear shot, closest to the archer's current position among matches.
     */
    @Nullable
    public static BlockPos bestRangedPosition(
            Activity.Context ctx, LivingEntity target, double minDist, double maxDist) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;

        BlockPos targetBlock = target.blockPosition();
        BlockPos npcBlock = ctx.npc().blockPosition();
        double idealDist = (minDist + maxDist) / 2.0D;
        List<LivingEntity> friends = friendsNear(ctx.npc(), target);
        List<NpcEntity> guards = meleeDefendersNear(ctx.npc(), target.position(), GUARD_SCREEN_RADIUS);
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        // Searched from 4 below the mob to 12 above it: walls and towers count.
        BlockPos searchCentre = targetBlock.above((MAX_SHOT_DROP - MAX_SHOT_RISE) / 2);
        int searchY = (MAX_SHOT_DROP + MAX_SHOT_RISE) / 2 + 1;
        for (NavNode node : graph.nodesInBox(searchCentre, APPROACH_XZ_RADIUS, searchY)) {
            if (node.isAvoidedSurface()) continue;

            BlockPos pos = node.feetPos();
            if (Math.abs(pos.getX() - targetBlock.getX()) > APPROACH_XZ_RADIUS
                    || Math.abs(pos.getZ() - targetBlock.getZ()) > APPROACH_XZ_RADIUS) {
                continue;
            }

            double above = pos.getY() - target.getY();
            if (!isShootableHeight(above)) continue;

            double dx = (pos.getX() + 0.5D) - target.getX();
            double dz = (pos.getZ() + 0.5D) - target.getZ();
            double xzDist = Math.sqrt(dx * dx + dz * dz);
            if (xzDist < minDist || xzDist > maxDist) continue;
            if (!hasShotLineFrom(ctx, pos, target)) continue;
            if (isFriendInLine(eyeAt(ctx, pos), target, friends)) continue;
            if (ctx.model().hasRecentMoveFailure(pos)) continue;

            double roughRouteDist = Math.sqrt(pos.distSqr(npcBlock));
            // Below the mob is poor; above it is better, up to a point (a
            // mob that cannot climb cannot reach the archer at all).
            double heightScore = above < 0 ? -above * 20.0D : -Math.min(above, HIGH_GROUND_CAP) * HIGH_GROUND_BONUS;
            double score = heightScore
                    + Math.abs(xzDist - idealDist) * 4.0D
                    + roughRouteDist * 0.1D;
            if (isBehindAGuard(pos, target, guards)) score -= BEHIND_GUARD_BONUS;
            if (best == null || score < bestScore) {
                best = pos;
                bestScore = score;
            }
        }

        return best;
    }

    /** {@code above}: archer feet minus mob feet. Up to 12 above the mob, or 4 below it. */
    public static boolean isShootableHeight(double above) {
        return isShootableHeight(above, MAX_SHOT_DROP);
    }

    /** As above, from up to {@code maxDrop} above the mob instead of 12. */
    public static boolean isShootableHeight(double above, double maxDrop) {
        return above <= maxDrop && above >= -MAX_SHOT_RISE;
    }

    /**
     * The archer stands well above a mob that cannot climb, on a wall or a
     * tower: the mob cannot reach it, so there is nothing to run from.
     */
    public static boolean isOutOfReachAbove(NpcEntity npc, LivingEntity mob) {
        return npc.getY() - mob.getY() >= OUT_OF_REACH_HEIGHT
                && !(mob instanceof net.minecraft.world.entity.monster.Spider)
                && !mob.isNoGravity();
    }

    private static final double OUT_OF_REACH_HEIGHT = 3.0D;

    /** Guards and captains within {@code radius} of {@code centre}. */
    public static List<NpcEntity> meleeDefendersNear(NpcEntity npc, Vec3 centre, double radius) {
        return npc.level().getEntitiesOfClass(NpcEntity.class, new AABB(centre, centre).inflate(radius),
                e -> e != npc && e.isAlive() && e.getProfession().isGuardLike()
                        && e.distanceToSqr(centre) <= radius * radius);
    }

    /**
     * A guard or captain near {@code mob} stands between it and the archer,
     * so the mob has to get past it first.
     */
    public static boolean isCoveredByGuard(NpcEntity npc, LivingEntity mob) {
        return isBehindAGuard(npc.blockPosition(), mob,
                meleeDefendersNear(npc, mob.position(), GUARD_SCREEN_RADIUS));
    }

    /**
     * A guard stands between {@code pos} and the mob: nearer the mob and on
     * the same side, so the mob has to get past it to reach the archer.
     */
    private static boolean isBehindAGuard(BlockPos pos, LivingEntity target, List<NpcEntity> guards) {
        double px = pos.getX() + 0.5D - target.getX();
        double pz = pos.getZ() + 0.5D - target.getZ();
        double pLen = Math.sqrt(px * px + pz * pz);
        if (pLen < 1.0E-3) return false;
        for (NpcEntity guard : guards) {
            double gx = guard.getX() - target.getX();
            double gz = guard.getZ() - target.getZ();
            double gLen = Math.sqrt(gx * gx + gz * gz);
            if (gLen < 1.0E-3 || gLen >= pLen) continue;
            if ((px * gx + pz * gz) / (pLen * gLen) >= BEHIND_GUARD_COS) return true;
        }
        return false;
    }

    /**
     * Of {@code spots} (a watchtower's platform), the one nearest the archer
     * with a clean shot at {@code target} within range, from at most
     * {@code maxDrop} above it; null if none has one. A lookout only ever
     * moves among these.
     */
    @javax.annotation.Nullable
    public static BlockPos bestSpotAmong(Activity.Context ctx, Iterable<BlockPos> spots, LivingEntity target,
                                         double minRange, double maxRange, double maxDrop) {
        BlockPos from = ctx.npc().blockPosition();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos pos : spots) {
            if (!isShootableHeight(pos.getY() - target.getY(), maxDrop)) continue;
            double dx = pos.getX() + 0.5D - target.getX();
            double dz = pos.getZ() + 0.5D - target.getZ();
            double distSq = dx * dx + dz * dz;
            if (distSq < minRange * minRange || distSq > maxRange * maxRange) continue;
            if (!hasShotLineFrom(ctx, pos, target)) continue;
            double d = pos.distSqr(from);
            if (d < bestSq) {
                bestSq = d;
                best = pos;
            }
        }
        return best;
    }

    private static Vec3 eyeAt(Activity.Context ctx, BlockPos feetPos) {
        return new Vec3(feetPos.getX() + 0.5D, feetPos.getY() + ctx.npc().getEyeHeight(), feetPos.getZ() + 0.5D);
    }

    /** A clear shot from a spot the archer may move to, past the railing at that spot. */
    private static boolean hasShotLineFrom(Activity.Context ctx, BlockPos feetPos, LivingEntity target) {
        return RailingSight.isClearFrom(ctx.level(), eyeAt(ctx, feetPos), target.getEyePosition(), ctx.npc(),
                Vec3.atBottomCenterOf(feetPos));
    }
}

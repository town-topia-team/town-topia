package com.alex.test.village.villagers.guard.behavior.combat;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.WalkFeel;
import com.alex.test.village.NavNode;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.villagers.guard.settings.GuardMobPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Positioning, range, and line-of-sight rules for guard melee combat.
 *
 * <p>Reach is measured from the guard's centre to the edge of the target's
 * hitbox, the way a sword actually meets a body, not centre to centre. The swing
 * reach grows with skill ({@code GuardBalance.swingReach}: 2.25 blocks at level
 * 1, 2.75 at 100 -- the author's numbers, a little past the model's blade tip at
 * about 1.6, so guards keep a clear edge over a zombie's 1.1). A swing starts
 * within that reach; a blow already on its way still lands {@link #LAND_SLACK}
 * past it, so a mob that leans back during the wind-up is still hit; and a
 * guard closing in stops {@link #STAND_INSET} inside it rather than walking up
 * against the mob.
 */
public final class GuardCombatRules {

    /** A blow already on its way still lands this far past swing reach. */
    public static final double LAND_SLACK = 0.35D;
    /** A guard closing in aims to stand this far inside its swing reach. */
    public static final double STAND_INSET = 0.3D;

    public static double landReach(double swingReach) {
        return swingReach + LAND_SLACK;
    }

    public static double standReach(double swingReach) {
        return swingReach - STAND_INSET;
    }

    private static final double MAX_ATTACK_FEET_Y_DIFF = 1.6D;
    private static final int APPROACH_XZ_RADIUS = 8;
    private static final int APPROACH_Y_RADIUS = 4;
    private static final double APPROACH_REPLAN_RANGE_SQ = 5.0D * 5.0D;
    /** Hostiles going for a defender within this many blocks count towards its next blow. */
    private static final double BLOW_SCAN_RADIUS = 6.0D;
    /** Approach spots this close to another defender are crowded: two guards would push each other out of reach. */
    private static final double CROWDED_SPOT_DIST_SQ = 1.3D * 1.3D;
    /** Score per block a spot lies from the guard, as it always was... */
    private static final double SPOT_DISTANCE_WEIGHT = 0.12D;
    /**
     * ...and with {@link WalkFeel#steadySpot}: enough that a spot on the far
     * side of the mob loses to one on the guard's own side unless it fits the
     * swing distance much better.
     */
    private static final double STEADY_SPOT_DISTANCE_WEIGHT = 0.8D;
    /** With {@link WalkFeel#steadySpot}, the spot already being run to is kept unless another beats it by this. */
    private static final double KEEP_SPOT_BONUS = 4.0D;
    /** How far below a mob in the air its floor is looked for. */
    private static final int FLOOR_SEARCH_DEPTH = 3;

    private GuardCombatRules() {}

    /**
     * The height of the floor {@code mob} stands on, or will land on: a hop or
     * a short fall in progress does not move it. With {@link WalkFeel#steadySpot}
     * off, the mob's own height, as before.
     *
     * <p>Spots were judged against the mob's exact height, so a hopping zombie
     * made a spot a block lower or higher win for a moment, and a guard on its
     * way went down a block and back up again.
     */
    public static double mobFloor(LivingEntity mob) {
        if (!WalkFeel.steadySpot || mob.onGround()) return mob.getY();
        BlockPos.MutableBlockPos feet = mob.blockPosition().mutable();
        for (int i = 0; i <= FLOOR_SEARCH_DEPTH; i++) {
            BlockPos below = feet.below();
            if (!mob.level().getBlockState(below).getCollisionShape(mob.level(), below).isEmpty()) {
                return NavGraph.standY(mob.level(), feet);
            }
            feet.move(0, -1, 0);
        }
        return mob.getY();
    }

    /** Guard centre to the target's hitbox edge, flat (X/Z). */
    public static double edgeDistance(NpcEntity npc, LivingEntity target) {
        double dx = npc.getX() - target.getX();
        double dz = npc.getZ() - target.getZ();
        return Math.max(0.0D, Math.sqrt(dx * dx + dz * dz) - target.getBbWidth() * 0.5D);
    }

    /** Whether {@code target} is within {@code reach} (to its hitbox edge), level enough and in sight. */
    public static boolean canMeleeAttack(NpcEntity npc, LivingEntity target, double reach) {
        if (Math.abs(npc.getY() - target.getY()) > MAX_ATTACK_FEET_Y_DIFF) return false;
        if (edgeDistance(npc, target) > reach) return false;
        return npc.hasLineOfSight(target);
    }

    public static boolean isLevelEnough(NpcEntity npc, LivingEntity target) {
        return Math.abs(npc.getY() - target.getY()) <= MAX_ATTACK_FEET_Y_DIFF;
    }

    public static boolean isApproachStillUseful(
            Activity.Context ctx, BlockPos approach, LivingEntity target) {
        if (Math.abs(approach.getY() - mobFloor(target)) > MAX_ATTACK_FEET_Y_DIFF) return false;

        double dx = (approach.getX() + 0.5D) - target.getX();
        double dz = (approach.getZ() + 0.5D) - target.getZ();
        return dx * dx + dz * dz <= APPROACH_REPLAN_RANGE_SQ
                && hasAttackLineFrom(ctx, approach, target);
    }

    public static void face(NpcEntity npc, Vec3 targetPos) {
        Vec3 dir = targetPos.subtract(npc.position());
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        npc.setYRot(yaw);
        npc.setYHeadRot(yaw);
        npc.getLookControl().setLookAt(targetPos.x, targetPos.y, targetPos.z, 30f, 30f);
    }

    /**
     * The best village nav-graph spot to strike {@code target} from, or null.
     *
     * <p>Only routable spots (no beds, liquids or one-way water drops), within
     * swing reach and sight of the target; a spot another defender stands on or
     * next to is scored down so guards sharing a mob spread around it instead of
     * queueing behind each other.
     */
    @Nullable
    public static BlockPos bestApproach(Activity.Context ctx, LivingEntity target,
                                        List<NpcEntity> otherDefenders, double swingReach,
                                        @Nullable BlockPos current) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;

        boolean steady = WalkFeel.steadySpot;
        double floor = mobFloor(target);
        double distanceWeight = steady ? STEADY_SPOT_DISTANCE_WEIGHT : SPOT_DISTANCE_WEIGHT;
        BlockPos targetBlock = target.blockPosition();
        BlockPos guardBlock = ctx.npc().blockPosition();
        double halfWidth = target.getBbWidth() * 0.5D;
        // Arrival is judged within about a block of the spot, so the spot itself
        // sits a little inside swing reach.
        double maxCentre = swingReach + halfWidth - 0.2D;
        double desiredCentre = standReach(swingReach) + halfWidth;
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (NavNode node : graph.nodesInBox(targetBlock, APPROACH_XZ_RADIUS,
                APPROACH_Y_RADIUS)) {
            if (!node.isRoutable()) continue;

            BlockPos pos = node.feetPos();
            if (Math.abs(pos.getX() - targetBlock.getX()) > APPROACH_XZ_RADIUS
                    || Math.abs(pos.getZ() - targetBlock.getZ()) > APPROACH_XZ_RADIUS
                    || Math.abs(pos.getY() - targetBlock.getY()) > APPROACH_Y_RADIUS) {
                continue;
            }

            double feetYDiff = steady ? Math.abs(node.standY - floor) : Math.abs(pos.getY() - target.getY());
            if (feetYDiff > MAX_ATTACK_FEET_Y_DIFF) continue;

            double dx = (pos.getX() + 0.5D) - target.getX();
            double dz = (pos.getZ() + 0.5D) - target.getZ();
            double xzDistSq = dx * dx + dz * dz;
            if (xzDistSq > maxCentre * maxCentre) continue;
            if (ctx.model().hasRecentMoveFailure(pos)) continue;
            if (!hasAttackLineFrom(ctx, pos, target)) continue;

            double xzDist = Math.sqrt(xzDistSq);
            double roughRouteDist = Math.sqrt(pos.distSqr(guardBlock));
            double score = feetYDiff * 20.0D
                    + Math.abs(xzDist - desiredCentre) * 6.0D
                    + roughRouteDist * distanceWeight
                    + crowdPenalty(pos, otherDefenders);
            if (steady && pos.equals(current)) score -= KEEP_SPOT_BONUS;
            if (best == null || score < bestScore) {
                best = pos;
                bestScore = score;
            }
        }

        return best;
    }

    private static double crowdPenalty(BlockPos pos, List<NpcEntity> otherDefenders) {
        double penalty = 0.0D;
        for (NpcEntity other : otherDefenders) {
            double dx = other.getX() - (pos.getX() + 0.5D);
            double dz = other.getZ() - (pos.getZ() + 0.5D);
            if (dx * dx + dz * dz <= CROWDED_SPOT_DIST_SQ) penalty += 9.0D;
        }
        return penalty;
    }

    private static boolean hasAttackLineFrom(Activity.Context ctx, BlockPos feetPos, LivingEntity target) {
        Vec3 from = new Vec3(
                feetPos.getX() + 0.5D,
                feetPos.getY() + ctx.npc().getEyeHeight(),
                feetPos.getZ() + 0.5D);
        return clearBlockLine(ctx, from, target.getEyePosition())
                || clearBlockLine(ctx, from, target.position().add(0.0D, target.getBbHeight() * 0.55D, 0.0D));
    }

    /**
     * The hardest single blow a hostile going for {@code defender} close by
     * could land on it now: the mob's attack damage (its weapon included),
     * less what the defender's armor takes off. 0 when none is near. Mob blows
     * on villagers are not scaled by difficulty, so this is what would land.
     * Creepers are left out -- their blast is dodged, not traded.
     */
    public static float heaviestBlowAgainst(ServerLevel level, NpcEntity defender) {
        float heaviest = 0f;
        for (Mob mob : level.getEntitiesOfClass(Mob.class,
                defender.getBoundingBox().inflate(BLOW_SCAN_RADIUS),
                mob -> mob.isAlive() && mob.getTarget() == defender && !(mob instanceof Creeper)
                        && GuardMobPolicy.isHostileCreature(mob))) {
            if (!mob.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE)) continue;
            float raw = (float) mob.getAttributeValue(Attributes.ATTACK_DAMAGE);
            float landed = CombatRules.getDamageAfterAbsorb(defender, raw,
                    defender.damageSources().mobAttack(mob), defender.getArmorValue(),
                    (float) defender.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
            heaviest = Math.max(heaviest, landed);
        }
        return heaviest;
    }

    public static boolean clearBlockLine(Activity.Context ctx, Vec3 from, Vec3 to) {
        HitResult hit = ctx.level().clip(new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.npc()));
        return hit.getType() == HitResult.Type.MISS;
    }
}

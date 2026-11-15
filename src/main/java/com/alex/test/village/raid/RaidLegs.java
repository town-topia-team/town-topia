package com.alex.test.village.raid;

import com.alex.test.DynamicVillages;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.util.GoalUtils;
import net.minecraft.world.entity.ai.util.RandomPos;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.function.Function;

/**
 * Raider pathfinding bugs B3 and B4 (docs/plans/2026-10-23-raider-pathfinding.md, 10-23): a raider's walk
 * on Minecraft's path, which it still takes off the village map, after the map gave up, and while
 * snatching. Minecraft searches only as far as the mob's follow range in walked blocks (a vindicator
 * 12), so a 16-block leg often had no full way; it then hands back a half path to the spot nearest the
 * goal, usually a wall, and still says it found one. Its legs are random spots in a 90-degree fan, the
 * darkest of 10, and were swapped every second.
 */
final class RaidLegs {

    /** B3 ({@code raidhalfpath}): searched this far in walked blocks, the node budget raised to match. */
    static final double REACH = 40.0;
    /** B3: a path ending further than this from its target (Manhattan blocks) is a half path. */
    private static final float SHORT_BY = 3.0F;
    /** B4 ({@code raidlegs}): legs as far out as Minecraft's, first within 18 degrees of the goal, else 90. */
    private static final int LEG_XZ = 16;
    private static final int LEG_Y = 7;
    private static final double NARROW = Math.PI / 10;
    private static final double WIDE = Math.PI / 2;
    /** Only for the length of one ask: never saved. */
    private static final ResourceLocation REACH_ID =
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "raid_leg_reach");

    private RaidLegs() {}

    /**
     * B3: {@code ask} run with the raider's follow range raised to {@link #REACH} and Minecraft's node
     * budget (fixed at the follow range the raider spawned with) scaled up with it; both put back after.
     * Only bees touch that budget in Minecraft. Goals that pick targets read the follow range too, but
     * none runs inside the ask.
     */
    @Nullable
    static Path far(PathfinderMob raider, Function<PathNavigation, Path> ask) {
        PathNavigation nav = raider.getNavigation();
        AttributeInstance follow = raider.getAttribute(Attributes.FOLLOW_RANGE);
        if (follow == null) return ask.apply(nav);
        if (follow.hasModifier(REACH_ID)) follow.removeModifier(REACH_ID);
        double range = follow.getValue();
        if (range >= REACH) return ask.apply(nav);
        follow.addTransientModifier(new AttributeModifier(REACH_ID, REACH - range, AttributeModifier.Operation.ADD_VALUE));
        nav.setMaxVisitedNodesMultiplier((float) (REACH / Math.max(1.0, range)));
        try {
            return ask.apply(nav);
        } finally {
            follow.removeModifier(REACH_ID);
            nav.resetMaxVisitedNodesMultiplier();
        }
    }

    /** B3: Minecraft found no full way and handed back the walk to the node nearest the target. */
    static boolean isHalf(@Nullable Path path) {
        return path != null && !path.canReach() && path.getDistToTarget() > SHORT_BY;
    }

    /**
     * B4: a leg spot toward {@code goal}, the one nearest it of 10 tries (Minecraft's
     * {@code DefaultRandomPos.getPosTowards} takes the darkest), within 18 degrees of the line to it,
     * or 90 when {@code wide}. Same checks as Minecraft's: inside its limits and home area, standing
     * ground, no danger. Null when none of the 10 passed.
     */
    @Nullable
    static Vec3 pickLeg(PathfinderMob raider, Vec3 goal, boolean wide) {
        Vec3 toward = goal.subtract(raider.position());
        boolean restricted = GoalUtils.mobRestricted(raider, LEG_XZ);
        double angle = wide ? WIDE : NARROW;
        return RandomPos.generateRandomPos(() -> {
            BlockPos dir = RandomPos.generateRandomDirectionWithinRadians(raider.getRandom(), LEG_XZ, LEG_Y, 0,
                    toward.x, toward.z, angle);
            if (dir == null) return null;
            BlockPos pos = RandomPos.generateRandomPosTowardDirection(raider, LEG_XZ, raider.getRandom(), dir);
            return !GoalUtils.isOutsideLimits(pos, raider) && !GoalUtils.isRestricted(restricted, raider, pos)
                    && !GoalUtils.isNotStable(raider.getNavigation(), pos) && !GoalUtils.hasMalus(raider, pos)
                    ? pos : null;
        }, pos -> -pos.distToCenterSqr(goal));
    }
}

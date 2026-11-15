package com.alex.test.village.raid;

import com.alex.test.village.WalkFeel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * A raider's orders from its raid:
 * <ul>
 * <li>march from the camp into the village;</li>
 * <li>a looter goes to a storage container, rummages for two seconds, and
 *     runs home with what it took (gold sparkles over its head);</li>
 * <li>once the raid breaks, everyone runs back to the camp.</li>
 * </ul>
 * While it has someone to fight, a marching raider steps aside and fights
 * with its own vanilla moves; a raider running home fights nobody.
 *
 * <p>Off the village map, Minecraft's search reaches only the follow range in walked blocks (a
 * vindicator 12; 40 with {@code raidhalfpath}), so a long walk goes in legs towards the goal, the
 * way vanilla raiders walk to a raid ({@link RaidLegs}).
 */
final class RaidMarchGoal extends Goal {

    /** Ahead of vanilla's random stroll, behind its attack goals. */
    static final int PRIORITY = 4;
    private static final double MARCH_SPEED = 1.0;
    private static final double RUN_SPEED = 1.25;
    /** Close enough to a march goal to pick the next one. */
    private static final double ARRIVED_SQ = 6.0 * 6.0;
    /** Close enough to the camp to count as got away. */
    private static final double HOME_SQ = 3.5 * 3.5;
    /** Close enough to a container to reach into it. */
    private static final double REACH_SQ = 2.6 * 2.6;
    /** Goals closer than this are walked to straight; further ones in legs. */
    private static final double DIRECT_SQ = 20.0 * 20.0;
    private static final int REPATH_TICKS = 20;
    /** Two seconds at the chest before the goods are gone. */
    private static final int RUMMAGE_TICKS = 40;
    /** A container not reached in this long (30 s) is given up, and looting with it. */
    private static final int LOOT_GIVE_UP_TICKS = 30 * 20;
    private static final int SPARKLE_TICKS = 10;
    /** How often a mounted raider tramples the crops under its horse. */
    private static final int TRAMPLE_TICKS = 10;
    /** Close enough to a villager to grab it. */
    private static final double GRAB_SQ = 2.2 * 2.2;
    /** Round 0 count: ten seconds between stuck checks on a walking raider. */
    private static final int STUCK_CHECK_TICKS = 200;
    /** Raider pathfinding round 4: a door the map found no way to is left alone this long (30 s). */
    private static final int SKIP_TICKS = 30 * 20;
    /** Bug B4 ({@code raidlegs}): a Minecraft leg is walked until done, or this long at most (10 s). */
    private static final int LEG_MAX_TICKS = 200;

    private final PathfinderMob raider;
    private final RaidManager raid;
    private BlockPos goal;
    private BlockPos lootTarget;
    private int lootTicks;
    private int rummage;
    private int repath;
    /**
     * The last ask found no path (or one already walked): wait the repath out. Until 10-18 a
     * raider with no way at all (a walled village) asked Minecraft for a path every tick.
     */
    private boolean lastAskFailed;
    /** Round 0 count: where a walking raider stood at its last ten-second check, and ticks since. */
    @Nullable private Vec3 checkFrom;
    private int checkTicks;
    private int lastWalkTick = Integer.MIN_VALUE;
    /** Round 3 (switch {@code raidmap}): the walk on the village map, tried before Minecraft's path. */
    private final RaiderMapWalk mapWalk;
    /** Round 5 (switch {@code raidbig}): a big body's walk by map route (the Warlord's own one). */
    private final WideMapWalk wideWalk;
    /** Raider pathfinding round 4 ({@code raidnovanilla}): doors the map gave up on, and the tick each is tried again. */
    private final Map<BlockPos, Integer> skipped = new HashMap<>();
    /** Bug B7 (10-23): no other door was left; not looked for again before this tick (a skipped one back). */
    private int noOtherDoorUntil = Integer.MIN_VALUE;
    /** Bug B4 ({@code raidlegs}): the goal the Minecraft leg under way is toward, and the tick it was asked. */
    @Nullable private BlockPos legGoal;
    private int legSince;
    /** Bugs B3/B4: the last leg ask found no way (or only a half path): the next is a side leg (90 degrees). */
    private boolean legBlocked;

    RaidMarchGoal(PathfinderMob raider, RaidManager raid) {
        this.raider = raider;
        this.raid = raid;
        this.mapWalk = new RaiderMapWalk(raider, raid);
        this.wideWalk = raider instanceof com.alex.test.village.raid.warlord.RaidWarlord warlord
                ? warlord.wideWalk() : new WideMapWalk(raider, raid::village);
        setFlags(EnumSet.of(Flag.MOVE));
    }

    /** Round 4: {@link RaidApproachGoal} walks the same map walk (the two never run together). */
    RaiderMapWalk mapWalk() {
        return mapWalk;
    }

    /** Round 5: the big-body walk, for {@link RaidApproachGoal} too. */
    WideMapWalk wideWalk() {
        return wideWalk;
    }

    @Override
    public boolean canUse() {
        if (!raid.isTracking(raider)) return false;
        return raid.isRetreating() || RaidLoot.isCarrying(raider) || raid.isSnatcher(raider) || !hasFight();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        goal = null;
        repath = 0;
        rummage = 0;
        lastAskFailed = false;
        checkFrom = null;
        legGoal = null;
        legBlocked = false;
    }

    @Override
    public void stop() {
        goal = null;
        rummage = 0;
        mapWalk.drop();
        wideWalk.drop();
    }

    @Override
    public void tick() {
        boolean carrying = RaidLoot.isCarrying(raider);
        if (raid.isRetreating() || carrying) {
            raider.setTarget(null);
            if (carrying && raider.tickCount % SPARKLE_TICKS == 0 && raider.level() instanceof ServerLevel level) {
                RaidLoot.sparkle(level, raider);
            }
            BlockPos camp = raid.campCentre();
            if (camp == null) return;
            if (raider.distanceToSqr(Vec3.atBottomCenterOf(camp)) <= HOME_SQ) {
                raid.onRaiderEscaped(raider);
                return;
            }
            walkTowards(camp, RUN_SPEED, RaidNavCount.Site.HOME);
            return;
        }
        if (raid.isSnatcher(raider) && tickSnatching()) return;
        if (RaidSpawner.isLooter(raider)) {
            tickLooting();
            return;
        }
        if (goal == null || raider.distanceToSqr(Vec3.atBottomCenterOf(goal)) <= ARRIVED_SQ) {
            goal = raid.pickMarchTarget(raider, skippedDoors());
            repath = 0;
        }
        if (goal != null) walkTowards(goal, MARCH_SPEED, RaidNavCount.Site.MARCH);
        // A rider's horse rides down the crops it passes over.
        if (raider.isPassenger() && raider.tickCount % TRAMPLE_TICKS == 0) raid.trampleUnder(raider);
    }

    /**
     * The kidnappers' snatcher: goes for a villager who is out, grabs it, and
     * runs it back to the camp, fighting nobody on the way. False when there
     * is nobody to grab right now (it then marches like the rest).
     */
    private boolean tickSnatching() {
        raider.setTarget(null);
        if (raid.hasCaptive()) {
            BlockPos camp = raid.campCentre();
            if (camp == null) return true;
            if (raider.distanceToSqr(Vec3.atBottomCenterOf(camp)) <= HOME_SQ) {
                raid.onCaptiveAtCamp(raider);
                return true;
            }
            // Not at a run: the captive has to keep up.
            walkTowards(camp, MARCH_SPEED, RaidNavCount.Site.HOME);
            return true;
        }
        com.alex.test.entity.NpcEntity victim = raid.snatchTarget(raider);
        if (victim == null) return false;
        if (raider.distanceToSqr(victim) <= GRAB_SQ) {
            mapWalk.drop();
            raider.getNavigation().stop();
            raid.capture(raider, victim);
            return true;
        }
        checkStuck();
        if (mapWalk.tick(victim.blockPosition(), RUN_SPEED, RaiderMapWalk.Goal.MOVING)) return true;
        if (--repath <= 0 || (raider.getNavigation().isDone() && !lastAskFailed)) {
            repath = 10;
            if (WalkFeel.raidHalfPath) {
                // Bug B3: searched 40 blocks; a half path toward the villager is not walked into its wall.
                Path path = RaidLegs.far(raider, n -> n.createPath(victim, 1));
                if (RaidLegs.isHalf(path)) path = null;
                lastAskFailed = !raider.getNavigation().moveTo(path, RUN_SPEED);
            } else {
                lastAskFailed = !raider.getNavigation().moveTo(victim, RUN_SPEED);
            }
            RaidNavCount.note(RaidNavCount.Site.SNATCH, raider, !lastAskFailed);
        }
        return true;
    }

    private void tickLooting() {
        if (lootTarget == null) {
            lootTarget = raid.pickLootTarget(raider);
            lootTicks = 0;
            rummage = 0;
            repath = 0;
            if (lootTarget == null) {
                RaidSpawner.stopLooting(raider);
                return;
            }
        }
        Vec3 chest = Vec3.atCenterOf(lootTarget);
        if (raider.distanceToSqr(chest) <= REACH_SQ) {
            mapWalk.drop();
            raider.getNavigation().stop();
            raider.getLookControl().setLookAt(chest);
            if (++rummage >= RUMMAGE_TICKS) {
                raid.onLooterAtChest(raider, lootTarget);
                lootTarget = null;
            }
            return;
        }
        rummage = 0;
        if (++lootTicks >= LOOT_GIVE_UP_TICKS) {
            // Could not get in (a wall, an iron door, a fence gate): fight instead.
            RaidSpawner.stopLooting(raider);
            lootTarget = null;
            return;
        }
        walkTowards(lootTarget, MARCH_SPEED, RaidNavCount.Site.LOOT);
    }

    private boolean hasFight() {
        LivingEntity target = raider.getTarget();
        return target != null && target.isAlive();
    }

    /**
     * On the village map when it can ({@link RaiderMapWalk}, switch {@code raidmap}); else on
     * Minecraft's path, asked again once a second, or at once when a leg has been walked.
     */
    private void walkTowards(BlockPos to, double speed, RaidNavCount.Site site) {
        checkStuck();
        if (mapWalk.tick(to, speed, site == RaidNavCount.Site.LOOT
                ? RaiderMapWalk.Goal.CONTAINER : RaiderMapWalk.Goal.PLACE)) return;
        if (WalkFeel.raidNoVanilla && mapWalk.gaveUpOn(to) && giveUpOn(to, site)) return;
        // Bug B2 (10-23): no other door was taken, so a two-stuck give-up really is Minecraft's walk now.
        mapWalk.countHandover();
        // Round 5: the Warlord and ravagers (never looters) by map route.
        if (site != RaidNavCount.Site.LOOT && wideWalk.tick(to, speed, WideMapWalk.Goal.PLACE)) return;
        if (WalkFeel.raidLegs || WalkFeel.raidHalfPath) {
            minecraftLeg(to, speed, site);
            return;
        }
        if (--repath > 0 && (lastAskFailed || !raider.getNavigation().isDone())) return;
        repath = REPATH_TICKS;
        Vec3 dest = Vec3.atBottomCenterOf(to);
        if (raider.distanceToSqr(dest) > DIRECT_SQ) {
            Vec3 leg = DefaultRandomPos.getPosTowards(raider, 16, 7, dest, Math.PI / 2);
            if (leg != null) dest = leg;
        }
        lastAskFailed = !raider.getNavigation().moveTo(dest.x, dest.y, dest.z, speed);
        RaidNavCount.note(site, raider, !lastAskFailed);
    }

    /**
     * Bugs B3 + B4 of docs/plans/2026-10-23-raider-pathfinding.md (10-23): Minecraft's path in legs.
     * B4 ({@code raidlegs}): a leg is walked until done (or 10 s), not swapped for another every second,
     * and is the spot nearest the goal within 18 degrees ({@link RaidLegs#pickLeg}); after a blocked leg,
     * or none near, the 90-degree fan, also for a goal close enough to walk to straight. B3
     * ({@code raidhalfpath}): searched 40 walked blocks ({@link RaidLegs#far}); a half path is not walked
     * into its wall but counted as no path, waited out a second, and a side leg tried.
     */
    private void minecraftLeg(BlockPos to, double speed, RaidNavCount.Site site) {
        PathNavigation nav = raider.getNavigation();
        boolean sameGoal = to.equals(legGoal);
        if (WalkFeel.raidLegs) {
            // Walking a leg toward this goal: walk it on (stuck, Minecraft drops the path: done).
            if (sameGoal && !lastAskFailed && !nav.isDone() && raider.tickCount - legSince < LEG_MAX_TICKS) return;
            if (sameGoal && lastAskFailed && --repath > 0) return;
        } else if (--repath > 0 && (lastAskFailed || !nav.isDone())) {
            return;
        }
        repath = REPATH_TICKS;
        Vec3 dest = Vec3.atBottomCenterOf(to);
        boolean side = WalkFeel.raidLegs && legBlocked && sameGoal;
        if (raider.distanceToSqr(dest) > DIRECT_SQ || side) {
            Vec3 leg;
            if (WalkFeel.raidLegs) {
                leg = RaidLegs.pickLeg(raider, dest, side);
                if (leg == null && !side) leg = RaidLegs.pickLeg(raider, dest, true);
            } else {
                leg = DefaultRandomPos.getPosTowards(raider, 16, 7, dest, Math.PI / 2);
            }
            if (leg != null) dest = leg;
        }
        double x = dest.x, y = dest.y, z = dest.z;
        Path path = WalkFeel.raidHalfPath ? RaidLegs.far(raider, n -> n.createPath(x, y, z, 1)) : nav.createPath(x, y, z, 1);
        // B3: no full way: not walked into the wall nearest the spot.
        if (WalkFeel.raidHalfPath && RaidLegs.isHalf(path)) path = null;
        lastAskFailed = !nav.moveTo(path, speed);
        legGoal = to.immutable();
        legSince = raider.tickCount;
        legBlocked = lastAskFailed;
        RaidNavCount.note(site, raider, !lastAskFailed);
    }

    /**
     * Round 4 of docs/plans/2026-10-23-raider-pathfinding.md (switch {@code raidnovanilla}): the map
     * found no way to {@code to} (no route, or stuck twice in a row there). Until 10-23 the raider then
     * walked Minecraft's path, which presses into the wall nearest the goal. Now a marcher leaves that
     * door alone for 30 s and goes for another; a looter gives the chest up and fights. True when it
     * did; false (no other door, the camp, running home, a snatch): Minecraft's path, as before.
     */
    private boolean giveUpOn(BlockPos to, RaidNavCount.Site site) {
        if (site == RaidNavCount.Site.LOOT) {
            RaidNavCount.noteMap(RaidNavCount.MapEvent.LOOT_GIVEN_UP, raider);
            RaidSpawner.stopLooting(raider);
            lootTarget = null;
            mapWalk.drop();
            raider.getNavigation().stop();
            return true;
        }
        // Kidnappers guarding their camp march to it: that one stays on Minecraft's path.
        if (site != RaidNavCount.Site.MARCH || to.equals(raid.campCentre())) return false;
        // Bug B7: asked every tick of Minecraft's 10 s; none was left a moment ago, none until a skip ends.
        if (raider.tickCount < noOtherDoorUntil) return false;
        skipped.put(to.immutable(), raider.tickCount + SKIP_TICKS);
        BlockPos other = raid.otherMarchDoor(raider, skippedDoors());
        if (other == null) {
            int soonest = Integer.MAX_VALUE;
            for (int until : skipped.values()) soonest = Math.min(soonest, until);
            noOtherDoorUntil = soonest;
            return false;
        }
        RaidNavCount.noteMap(RaidNavCount.MapEvent.OTHER_DOOR, raider);
        goal = other;
        repath = 0;
        raider.getNavigation().stop();
        return true;
    }

    /** Round 4: the doors left alone right now (each forgotten 30 s after the map gave up on it). */
    private Set<BlockPos> skippedDoors() {
        if (!skipped.isEmpty()) skipped.values().removeIf(until -> until <= raider.tickCount);
        return skipped.keySet();
    }

    /**
     * Round 0 count, reads only: every ten seconds of walking (a tick with no walk starts over),
     * a raider that moved under a block since the last check counts as stood stuck.
     */
    private void checkStuck() {
        if (raider.tickCount - lastWalkTick > 1) checkFrom = null;
        lastWalkTick = raider.tickCount;
        if (checkFrom == null) {
            checkFrom = raider.position();
            checkTicks = 0;
            return;
        }
        if (++checkTicks < STUCK_CHECK_TICKS) return;
        RaidNavCount.noteCheck(raider, raider.position().distanceToSqr(checkFrom) < 1.0);
        checkFrom = raider.position();
        checkTicks = 0;
    }

    /**
     * While a raider carries goods home, runs home or goes for a villager to
     * lead away, nothing makes it turn and fight: this target goal keeps its
     * target empty. Without it a pillager's crossbow attack (ahead of the march
     * goal) takes over the moment vanilla's target goals pick someone, which
     * they do every few ticks.
     */
    static final class HoldFireGoal extends Goal {
        private final PathfinderMob raider;
        private final RaidManager raid;

        HoldFireGoal(PathfinderMob raider, RaidManager raid) {
            this.raider = raider;
            this.raid = raid;
            setFlags(EnumSet.of(Flag.TARGET));
        }

        @Override
        public boolean canUse() {
            if (!raid.isTracking(raider)) return false;
            if (raid.isRetreating() || RaidLoot.isCarrying(raider)) return true;
            // One leading a captive runs, and fights only when cornered (hit in the last 2 s).
            return RaidKidnap.isCaptor(raider)
                    && (raider.getLastHurtByMob() == null || raider.tickCount - raider.getLastHurtByMobTimestamp() > 40);
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            if (raider.getTarget() != null) raider.setTarget(null);
        }
    }
}

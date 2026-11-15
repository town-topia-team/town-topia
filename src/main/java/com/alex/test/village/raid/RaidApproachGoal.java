package com.alex.test.village.raid;

import com.alex.test.village.VillageManager;
import com.alex.test.village.WalkFeel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.SpellcasterIllager;
import net.minecraft.world.item.BowItem;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Round 4 of docs/plans/2026-10-18-raider-map-walking.md (switch {@code raidchase}, needs
 * {@code raidmap}): a raider whose foe is far (over 12 blocks, 16 with a bow) or out of its sight
 * for a second walks a village map route toward it ({@link RaiderMapWalk}). Once the foe is in
 * sight and within 8 blocks (12 with a bow), or touching, Minecraft's own attack goal (crossbow,
 * axe, bow, zombie, witch) takes over for the last of it and the hit or shot, as before.
 *
 * <p>Ahead of every attack goal (they sit at 2 to 4), so they wait while it walks. Nothing to plan
 * to, no route, a stuck, water, or either side off the map: the fight is Minecraft's for 10 s.
 * Since 10-23 ({@code raidfoenear}) a foe on a roof, down a hole or just outside the border is
 * walked to the map tile nearest it first; only the raider off the map still means Minecraft's.
 * Never for a raider running home, carrying goods, snatching or leading a captive (the march
 * goal has those), nor for an evoker in the middle of a spell.
 *
 * <p>Round 5 (also {@code raidbig}): a ravager walks it by big-body map route ({@link WideMapWalk}).
 * Never the Warlord: this goal would hold back its charge; its own chase uses the map.
 */
final class RaidApproachGoal extends Goal {

    /** Ahead of the attack goals; level with an evoker's casting, which it never interrupts. */
    static final int PRIORITY = 1;
    /** Minecraft's attack goals chase at 1.0 too. */
    private static final double SPEED = 1.0;
    /** Further than this (squared): walked on the map. */
    private static final double FAR_SQ = 12.0 * 12.0;
    private static final double FAR_BOW_SQ = 16.0 * 16.0;
    /** In sight and this close: handed to the attack goal (a crossbow shoots from 8, a bow from 15). */
    private static final double CLOSE_SQ = 8.0 * 8.0;
    private static final double CLOSE_BOW_SQ = 12.0 * 12.0;
    /** This close it is handed over, in sight or not. */
    private static final double TOUCH_SQ = 2.5 * 2.5;
    /** Out of sight this long (1 s): walked on the map however near. */
    private static final long UNSEEN_TICKS = 20L;
    /** After the map walk gave up: Minecraft's chase for this long (10 s). */
    private static final long REST_TICKS = 200L;

    private final PathfinderMob raider;
    private final RaidManager raid;
    /** The march goal's map walk: one per raider, the two goals never run together. */
    private final RaiderMapWalk mapWalk;
    /** Round 5: the march goal's big-body walk (a ravager). */
    private final WideMapWalk wideWalk;
    /** The foe the sight times are about, and when it was last in sight. */
    @Nullable private LivingEntity watched;
    private long lastSeenAt;
    private long restUntil = Long.MIN_VALUE;
    /** The foe {@link #canUse} answered for (see {@link #start}). */
    @Nullable private LivingEntity startFoe;

    RaidApproachGoal(PathfinderMob raider, RaidManager raid, RaiderMapWalk mapWalk, WideMapWalk wideWalk) {
        this.raider = raider;
        this.raid = raid;
        this.mapWalk = mapWalk;
        this.wideWalk = wideWalk;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        LivingEntity foe = foeToWalkTo();
        if (foe == null) return false;
        long now = raider.level().getGameTime();
        seen(foe, now);
        if (now - lastSeenAt <= UNSEEN_TICKS && raider.distanceToSqr(foe) <= (hasBow() ? FAR_BOW_SQ : FAR_SQ)) return false;
        startFoe = foe;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity foe = foeToWalkTo();
        if (foe == null) return false;
        double distSq = raider.distanceToSqr(foe);
        boolean handOver = distSq <= TOUCH_SQ
                || (seen(foe, raider.level().getGameTime()) && distSq <= (hasBow() ? CLOSE_BOW_SQ : CLOSE_SQ));
        if (handOver) RaidNavCount.noteFight(RaidNavCount.FightEvent.HANDED, raider);
        return !handOver;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        // A pillager's crossbow goal forgets its target when it stops, and it stops for this
        // goal: without this the foe was lost the moment the walk began.
        if (raider.getTarget() == null && startFoe != null && startFoe.isAlive()) raider.setTarget(startFoe);
        startFoe = null;
        RaidNavCount.noteFight(RaidNavCount.FightEvent.STARTED, raider);
    }

    @Override
    public void stop() {
        mapWalk.drop();
        wideWalk.drop();
    }

    @Override
    public void tick() {
        LivingEntity foe = raider.getTarget();
        long now = raider.level().getGameTime();
        if (foe == null || now < restUntil) return;
        boolean walked = mapWalk.fits()
                ? mapWalk.tick(foe.blockPosition(), SPEED, RaiderMapWalk.Goal.MOVING)
                : wideWalk.tick(foe.blockPosition(), SPEED, WideMapWalk.Goal.MOVING);
        if (!walked) {
            restUntil = now + REST_TICKS;
            RaidNavCount.noteFight(RaidNavCount.FightEvent.GAVE_UP, raider);
        }
    }

    /**
     * The raider's foe when this goal may walk it there at all (switches on, raider fits the map,
     * not resting, both inside the village, no other orders); else null.
     */
    @Nullable
    private LivingEntity foeToWalkTo() {
        if (!WalkFeel.raidChase || !WalkFeel.raidMap) return null;
        if (!raid.isTracking(raider)) return null;
        // Bug B6 (10-23, raidwater): the map walk wades a bank and swims out of deep water itself; only a
        // big body (its walk has no water rule) still leaves any water to Minecraft.
        if (raider.isInWater() && !(WalkFeel.raidWater && mapWalk.fits())) return null;
        boolean big = !mapWalk.fits() && WalkFeel.raidBig && wideWalk.fits()
                && !(raider instanceof com.alex.test.village.raid.warlord.RaidWarlord);
        if (!mapWalk.fits() && !big) return null;
        if (raid.isRetreating() || RaidLoot.isCarrying(raider) || raid.isSnatcher(raider)
                || RaidKidnap.isCaptor(raider)) return null;
        if (raider instanceof SpellcasterIllager caster && caster.isCastingSpell()) return null;
        if (raider.level().getGameTime() < restUntil) return null;
        LivingEntity foe = raider.getTarget();
        if (foe == null || !foe.isAlive() || foe.level() != raider.level()) return null;
        VillageManager village = raid.village();
        if (!village.contains(raider.blockPosition())) return null;
        // Item 14 (10-23, raidfoenear): a foe just outside the border is walked to the map's edge toward it.
        if (!village.contains(foe.blockPosition()) && !WalkFeel.raidFoeNear) return null;
        return foe;
    }

    /** In sight now; keeps the last time {@code foe} was (a new foe counts as just seen). */
    private boolean seen(LivingEntity foe, long now) {
        if (foe != watched) {
            watched = foe;
            lastSeenAt = now;
        }
        boolean inSight = raider.getSensing().hasLineOfSight(foe);
        if (inSight) lastSeenAt = now;
        return inSight;
    }

    private boolean hasBow() {
        return raider.isHolding(stack -> stack.getItem() instanceof BowItem);
    }
}

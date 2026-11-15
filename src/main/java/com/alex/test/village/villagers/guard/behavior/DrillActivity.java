package com.alex.test.village.villagers.guard.behavior;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.EveningSchedule;
import com.alex.test.village.building.BarracksBuilding;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.CombatRank;
import com.alex.test.village.villagers.guard.GuardBalance;
import com.alex.test.village.villagers.guard.GuardShift;
import com.alex.test.village.villagers.guard.GuardSkillEffects;
import com.alex.test.village.villagers.guard.GuardSwordUtils;
import com.alex.test.village.villagers.guard.behavior.combat.GuardCombatRules;
import com.alex.test.village.villagers.guard.settings.GuardSettings;
import com.alex.test.village.villagers.registry.VillagerRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sword drill at the barracks (see {@link BarracksDrills}). A guard or captain
 * takes an armor stand of its own -- standing right in front of it -- or does a
 * shadow drill on open floor, and cuts at it in its off-duty window. Earns its
 * own job's experience for the time drilled: {@link GuardBalance#DRILL_EXPERIENCE_PER_MINUTE}
 * at a stand, {@link GuardBalance#SHADOW_DRILL_SHARE} of that shadow drilling.
 *
 * <p>The room drills in sets on a shared call ({@link BarracksDrills.Hall#phase});
 * every few sets is a shield set instead. Within a set each defender goes its
 * own way: it starts a moment after the call, swings at its own drill pace
 * (quicker with level, plus a little personal tempo) and stops after its own
 * three to six swings. Then it rests in its own way -- hand on hip, a shrug, a
 * think, a look at its neighbour, now and then a thought. Guards salute their
 * captain as it comes and goes.
 *
 * <p>Now and then a defender at a stand or shadow fighting alone powers up --
 * as it gets in place, or in a rest -- never two in a room at once. Two guards
 * shadow fighting each other ({@link BarracksDrills.Pair}) keep their own
 * time instead: bouts of exchanges between salutes, then a rest. A captain
 * kicks its stand now and then, kicks into the shield in its daily spar, and
 * once a day walks over to test one guard with a single kick.
 *
 * <p>Ends when its window closes, when the defender's shift starts or an
 * alarm makes it needed (the brain then sends it to work), or when it is too
 * tired or hungry to go on.
 */
public final class DrillActivity extends Activity {

    /** Experience is paid in slices of this many ticks drilled, the rest when the drill ends. */
    private static final int XP_SLICE_TICKS = 300;
    private static final int MINUTE_TICKS = 1200;
    /** How often a defender checks its armor stand is still there. */
    private static final int STAND_CHECK_TICKS = 40;
    /** Each defender starts a sword set up to this many ticks after the call... */
    private static final int SET_JOIN_SPREAD_TICKS = 30;
    /** ...and raises its shield for a shield set up to this many. */
    private static final int SHIELD_JOIN_SPREAD_TICKS = 10;
    /** Swings in one defender's set, picked anew each set. */
    private static final int MIN_SET_SWINGS = 3;
    private static final int MAX_SET_SWINGS = 6;
    /** Each breath between swings runs up to this many ticks longer, at random. */
    private static final int SWING_WOBBLE_TICKS = 4;
    /** A defender's own tempo: its drill pace times 0.94 to 1.06, the same every day. */
    private static final double TEMPO_SPREAD = 0.06;
    /** A defender at a stand or shadow fighting alone powers up this often as it gets in place... */
    private static final float POWER_UP_ON_ARRIVAL = 0.25f;
    /** ...and in one rest in this many between its sets. */
    private static final int POWER_UP_REST_ODDS = 8;
    /** Two defenders in one barracks never start a power-up closer together than this. */
    private static final int POWER_UP_SPACING_TICKS = 30;
    /** A captain at its stand kicks it instead of one swing in this many, at most once a set. */
    private static final int STAND_KICK_ODDS = 6;
    /** In its daily spar, one of the captain's strikes in this many is a kick into the guard's shield. */
    private static final int SPAR_KICK_ODDS = 4;
    /** The captain's daily kick test: in its brain data, the day it last had one. */
    private static final String TAG_KICK_TEST_DAY = "CaptainKickTestDay";
    private static final long SALT_KICK_TEST = 0x0C1C_7E57L;
    /** The kick test comes somewhere between 15% and 85% through the window, rolled daily. */
    private static final double KICK_TEST_EARLIEST = 0.15;
    private static final int KICK_TEST_SPREAD_PERCENT = 70;
    /**
     * A whole salute, from the raise through the held hand to the blend back
     * out: anything that follows one -- walking off, the first swing -- waits
     * this long, or the villager walked away with its legs still in the
     * salute and glided.
     */
    private static final int SALUTE_TICKS = (int) VillagerAnimationState.getDuration(VillagerAnimationState.SALUTE)
            + VillagerAnimationState.GESTURE_BLEND_TICKS;
    // --- Two guards shadow fighting each other (see BarracksDrills.Pair).
    /** A bout runs 20-40 s of exchanges between a salute in and a salute out. */
    private static final int BOUT_MIN_TICKS = 400;
    private static final int BOUT_MAX_TICKS = 800;
    /** The partner salutes a moment after the leader... */
    private static final int LEADER_SALUTE_AFTER = 4;
    private static final int PARTNER_SALUTE_AFTER = 12;
    /** ...and the salute stage lasts until its salute is done too. */
    private static final int BOUT_SALUTE_TICKS = PARTNER_SALUTE_AFTER + SALUTE_TICKS;
    /**
     * Partners part once the first of their drills has this little left:
     * room to finish the exchange under way (two blows and their blocks, the
     * pause before) and both salutes, before that one has to go.
     */
    private static final int PART_LEAD_TICKS = 180 + BOUT_SALUTE_TICKS;
    /** The rest between bouts: 5.5-11 s, long enough for a rest pose to play out. */
    private static final int BOUT_REST_MIN_TICKS = 110;
    private static final int BOUT_REST_MAX_TICKS = 220;
    /** A breath between exchanges. */
    private static final int BOUT_PAUSE_MIN = 8;
    private static final int BOUT_PAUSE_MAX = 22;
    /** One rest in this many shows a thought. */
    private static final int REST_THOUGHT_ODDS = 6;
    private static final int THOUGHT_TICKS = 50;
    /** Looking at a neighbour lasts this long. */
    private static final int GLANCE_TICKS = 40;
    /** Guards salute a captain that came or went this recently, if they can see it. */
    private static final int SALUTE_WINDOW_TICKS = 60;
    private static final int SALUTE_STAGGER_TICKS = 10;
    /** The captain answers its guards' salute this long after arriving. */
    private static final int CAPTAIN_SALUTE_DELAY = 15;
    /** A captain's farewell: it salutes, the whole salute, before leaving. */
    private static final int FAREWELL_TICKS = SALUTE_TICKS;
    /** The captain's daily visit: in its brain data, the day it last sparred with a guard. */
    private static final String TAG_VISIT_DAY = "CaptainVisitDay";
    private static final long SALT_VISIT = 0x5BA2_2140L;
    /** The visit starts somewhere between a quarter and three quarters through the window... */
    private static final double VISIT_EARLIEST = 0.25;
    private static final int VISIT_SPREAD_PERCENT = 50;
    /** ...and is not started this late, so a visit with nobody to spar with is skipped for the day. */
    private static final double VISIT_LATEST = 0.9;
    private static final int VISIT_TRY_TICKS = 40;
    /** Sparring lasts 30-45 s. */
    private static final int SPAR_MIN_TICKS = 600;
    private static final int SPAR_MAX_TICKS = 900;
    /** A breath between exchanges. */
    private static final int EXCHANGE_PAUSE_MIN = 10;
    private static final int EXCHANGE_PAUSE_MAX = 25;
    /** The captain says something every this many exchanges. */
    private static final int TALK_EVERY_EXCHANGES = 3;
    private static final int VISIT_SALUTE_AFTER = 10;
    private static final int VISIT_ANSWER_AFTER = 25;
    private static final int VISIT_THANKS_AFTER = 15;
    /** The captain goes back once its own salute, the later one, is done. */
    private static final int VISIT_PARTING_TICKS = VISIT_ANSWER_AFTER + SALUTE_TICKS;
    /** Time a visit needs besides the sparring itself: the walk over, the salutes, the walk back. */
    private static final int VISIT_SPARE_TICKS = 300;
    /** An inspecting captain waits this long in the barracks for a guard between sets. */
    private static final int INSPECTION_WAIT_TICKS = 300;
    /** The last step onto a spot ends this close to its centre (squared)... */
    private static final double SETTLE_DIST_SQ = 0.15 * 0.15;
    /** ...or after this long trying. */
    private static final int SETTLE_MAX_TICKS = 30;
    /** Hit wobble, sparks and sounds only with a player this close. */
    private static final double EFFECT_PLAYER_RANGE = 32.0;
    /** Vanilla armor stand entity event: hit wobble, plus its hit sound on the client. */
    private static final byte ARMOR_STAND_HIT_EVENT = 32;

    private final boolean captain;
    private final boolean inspection;
    /** An inspection that found nobody to spar with by this game time goes back to work. */
    private long inspectionUntil = Long.MAX_VALUE;
    @Nullable private BarracksBuilding barracks;
    @Nullable private BarracksDrills.Spot spot;
    private boolean arrived;
    /** Game time this defender was in place and ready; it joins a set only if ready when it began. */
    private long readySince = Long.MAX_VALUE;
    /** Ticks drilled not yet paid as experience. */
    private int unpaidTicks;
    /** Its fighting swing, for sparring with the captain. */
    private int attackTicks;
    /** Its drill swing at the stand: the slash, and slash plus breath, start to start. */
    private int drillSlashTicks;
    private int drillSwingTicks;

    // Rhythm.
    private int joinedSet = -1;
    private int swingsDone;
    private int setSwings;
    private long nextSwingAt;
    /** When the slash under way finishes; a rest does not cut it off. */
    private long slashEndsAt = Long.MIN_VALUE;
    /** The captain kicked its stand this set (once a set at most). */
    private boolean kickedThisSet;
    /** The blow due at {@link #strikeAt} is a kick. */
    private boolean strikeIsKick;
    /** A power-up planned for this game time; it waits while another one in the room has just begun. */
    private long powerUpAt = Long.MAX_VALUE;
    /** Until when a power-up or a rest pose is playing: no swings meanwhile. */
    private long busyUntil = Long.MIN_VALUE;
    /** Until when the power-up under way plays: the drill holds still for it. */
    private long powerUpUntil = Long.MIN_VALUE;
    /** When the power-up under way lets out its yell. */
    private long yellAt = Long.MAX_VALUE;
    /** When a shadow fighter's second slash starts. */
    private long followUpAt = Long.MAX_VALUE;
    /** This shield set's block has been raised (once a set). */
    private boolean shieldSetRaised;
    /** When the practice block under way comes down: the whole clip through. */
    private long practiceShieldUntil = Long.MIN_VALUE;

    // Shadow fighting a partner.
    /** The stage start this defender last saluted at, so it salutes once a stage. */
    private long salutedStageAt = Long.MIN_VALUE;
    /** The bout whose rest this defender last planned. */
    private int restedAfterBout = -1;
    /** The last blow (exchange times blows, plus which) this defender swung and heard land. */
    private int lastBoutSwung = -1;
    private int lastBoutStruck = -1;
    /** The exchange this defender last looked for its shield in. */
    private int shieldCheckedExchange = -1;
    /** Its pair's leader went without parting; it fights alone from here. */
    private boolean pairBroken;
    /** Game time this defender's drill window closes, kept fresh every tick. */
    private long windowEndsAt = Long.MAX_VALUE;
    /** Ticks drilled not yet paid, in stand-drill terms: a shadow tick counts for its share of one. */
    private float unpaidStandTicks;
    private int restedAfterSet = -1;
    private long restPoseAt = Long.MAX_VALUE;
    @Nullable private VillagerAnimationState restPose;
    private long thoughtAt = Long.MAX_VALUE;
    @Nullable private ThoughtIcon thought;
    private long glanceUntil = Long.MIN_VALUE;
    @Nullable private Vec3 glanceAt;

    // Salutes.
    private long salutedArrival = Long.MIN_VALUE;
    private long salutedLeave = Long.MIN_VALUE;
    private long saluteAt = Long.MAX_VALUE;
    private long farewellUntil = Long.MIN_VALUE;

    // The captain's visit, from either side.
    @Nullable private BarracksDrills.Visit myVisit;
    /** A captain walking back to its own stand after a visit: no second arrival salute. */
    private boolean returning;
    private int lastSwungExchange = -1;
    private int lastStruckExchange = -1;
    private boolean visitSaluted;
    private boolean visitThanked;
    private long nextVisitTry = Long.MIN_VALUE;
    /** Ticks spent on a visit, not yet paid; always at the stand rate. */
    private int unpaidVisitTicks;
    /** When this defender's current drill swing reaches the stand. */
    private long strikeAt = Long.MAX_VALUE;
    /** Whether it carries a shield, looked at as each set or sparring visit begins. */
    private boolean shieldInPack;
    /** The walk to the current spot has ended; the last step onto it is being taken. */
    private boolean walked;
    private int settleTicks;

    DrillActivity(boolean captain) {
        this(captain, false);
    }

    /**
     * @param inspection a captain on duty whose own shift has no guards: it
     *                   only walks over, spars with a guard drilling there and
     *                   goes back to work (see {@link BarracksDrills#inspectionFor})
     */
    DrillActivity(boolean captain, boolean inspection) {
        this.captain = captain;
        this.inspection = inspection;
    }

    /** Whether this captain has had its daily visit -- at its own drill or as an inspection -- on {@code day}. */
    static boolean visitedOn(NpcEntity captain, long day) {
        return captain.getBrainData().contains(TAG_VISIT_DAY) && captain.getBrainData().getLong(TAG_VISIT_DAY) == day;
    }

    @Override
    protected void start(Context ctx) {
        barracks = BarracksDrills.barracksFor(ctx);
        if (barracks == null) return;
        // The sword it fights with: the main hand is emptied when a shift ends.
        GuardSwordUtils.equipBestAllowedSword(ctx.npc(), GuardSettings.of(ctx.npc()).getAllowedSwords());
        int level = ctx.brain().defenderSkillLevel();
        attackTicks = CombatRank.of(ctx.npc().getProfession()).attackTicks(level);
        double tempo = personalTempo(ctx.model().npcId);
        drillSlashTicks = (int) Math.round(GuardBalance.drillSlashTicks(level) * tempo);
        drillSwingTicks = drillSlashTicks + (int) Math.round(GuardBalance.drillPauseTicks(level) * tempo);
        // An inspecting captain has no stand of its own here: it only comes to spar.
        if (inspection) return;
        // A guard shadow-fights now and then even with a stand free, more on some
        // days than others; a captain keeps to its own stand.
        long day = EveningSchedule.dayIndex(ctx.level().getDayTime());
        boolean shadow = !captain
                && ctx.npc().getRandom().nextFloat() < BarracksDrills.shadowChance(barracks, day);
        spot = takeSpot(ctx, shadow);
        // Salutes already given before this drill started do not repeat.
        BarracksDrills.Hall hall = BarracksDrills.hall(barracks);
        salutedArrival = hall.captainArrivedAt;
        salutedLeave = hall.captainLeftAt;
    }

    @Override
    protected Status tick(Context ctx) {
        if (inspection) return tickInspection(ctx);
        if (barracks == null || spot == null) {
            // No room left (or no barracks): not again this window.
            BarracksDrills.giveUpForWindow(ctx);
            return Status.FAILED;
        }
        NpcEntity npc = ctx.npc();
        long now = ctx.level().getGameTime();
        BarracksDrills.Hall hall = BarracksDrills.hall(barracks);

        // An alarm or an emergency: off at once, whatever it was doing.
        if (!barracks.isValid() || GuardAlertSystem.isVillageAlarmed(ctx.mgr(), ctx.level())
                || GuardAlertSystem.hasEmergencyForGuard(ctx)) {
            return Status.DONE;
        }
        if (farewellUntil != Long.MIN_VALUE) {
            npc.holdStillForAnimation();
            return now >= farewellUntil ? Status.DONE : Status.RUNNING;
        }
        boolean onDuty = VillagerRegistry.shouldWorkNow(npc.getProfession(), ctx);
        // A captain says goodbye just before its window closes rather than as it
        // does: the guards' windows close at the same time (day shift) or around
        // it (night shift), and at the close itself they were already leaving.
        boolean leaving = captain && arrived
                && BarracksDrills.windowEnd(ctx) - EveningSchedule.timeOfDay(ctx.level().getDayTime()) <= FAREWELL_TICKS;
        if (onDuty || leaving || !BarracksDrills.inWindow(ctx) || !BarracksDrills.fitToDrill(ctx)) {
            // Its shift, or simply the end of the drill: whatever it is in the
            // middle of -- a salute, a pose, a power-up, a swing -- plays to its
            // end first. Walking off mid-way, its legs stayed in the pose.
            if (now < busyUntil || now < slashEndsAt) {
                npc.holdStillForAnimation();
                BarracksDrills.renew(barracks, spot, ctx.model().npcId, now);
                if (spot.pair() != null) markSeen(spot.pair(), ctx.model().npcId, now, windowEndsAt);
                return Status.RUNNING;
            }
            clearVisit(ctx, hall);
            // A drill that simply runs out: a captain in place salutes the room first.
            if (captain && arrived && !onDuty) {
                farewellUntil = now + FAREWELL_TICKS;
                busyUntil = Math.max(busyUntil, farewellUntil);
                npc.setShieldRaised(false);
                npc.setAnimation(VillagerAnimationState.SALUTE);
                hall.captainLeftAt = now;
                return Status.RUNNING;
            }
            return Status.DONE;
        }
        BarracksDrills.renew(barracks, spot, ctx.model().npcId, now);
        // Seen even while on the captain's visit, so its partner does not take it for gone.
        windowEndsAt = BarracksDrills.drillEndsAt(ctx);
        if (spot.pair() != null) {
            BarracksDrills.Pair pair = spot.pair();
            markSeen(pair, ctx.model().npcId, now, windowEndsAt);
            // Parted at the end of the drill: the two go off together. On the
            // night shift the one whose shift starts later goes on duty early
            // with its partner instead of standing about alone; neither comes
            // back to the drill this window.
            if (pair.stage == BarracksDrills.Pair.Stage.PARTED && member(pair, ctx.model().npcId)) {
                BarracksDrills.giveUpForWindow(ctx);
                if (pair.nightShift) GuardShift.reportEarly(ctx, windowEndsAt);
                return Status.DONE;
            }
        }

        if (!arrived) {
            if (!walked) {
                if (!moveTo(ctx, spot.cell(), VillagerModel.WALK_SPEED)) return Status.RUNNING;
                if (moveFailed(ctx, spot.cell())) {
                    BarracksDrills.giveUpForWindow(ctx);
                    return Status.FAILED;
                }
                walked = true;
            }
            if (!settle(ctx, spot.cell())) return Status.RUNNING;
            walked = false;
            arrived = true;
            readySince = now;
            boolean firstArrival = !returning;
            if (captain && !returning) {
                hall.captain = ctx.model().npcId;
                hall.captainArrivedAt = now;
                salutedArrival = now;
                saluteAt = now + CAPTAIN_SALUTE_DELAY;
            }
            returning = false;
            BarracksDrills.Pair pair = spot.pair();
            if (pair != null && ctx.model().npcId.equals(pair.partner)) pair.partnerArrived = true;
            // Now and then it psyches itself up before it starts -- not a guard
            // that came to fight a partner. A captain salutes the room first.
            boolean partnered = pair != null && !pair.leader.equals(ctx.model().npcId);
            RandomSource random = npc.getRandom();
            if (firstArrival && !partnered && random.nextFloat() < POWER_UP_ON_ARRIVAL) {
                powerUpAt = now + (captain ? 40 + random.nextInt(41) : 5 + random.nextInt(36));
            }
            return Status.RUNNING;
        }

        // The captain's daily visit takes both of them off their own drill.
        if (captain ? captainVisit(ctx, hall, now) : guardVisit(ctx, hall, now)) {
            unpaidVisitTicks++;
            if (unpaidTicks + unpaidVisitTicks >= XP_SLICE_TICKS) payExperience(ctx);
            return Status.RUNNING;
        }

        if (spot.post() != null && now % STAND_CHECK_TICKS == 0 && !standStillThere(ctx)) {
            // The stand was broken or moved: take another, or drill on open floor.
            BarracksDrills.release(barracks, spot.cell(), ctx.model().npcId);
            spot = takeSpot(ctx, false);
            if (spot == null) {
                BarracksDrills.giveUpForWindow(ctx);
                return Status.DONE;
            }
            arrived = false;
            returning = captain;
            readySince = Long.MAX_VALUE;
            npc.setShieldRaised(false);
            resetMove();
            walked = false;
            return Status.RUNNING;
        }

        npc.holdStillForAnimation();
        // Shadow fighting a partner runs on its own bouts, not the room's sets.
        boolean inBout = spot.pair() != null && pairBout(ctx, hall, now);
        if (!inBout && !poweringUp(ctx, hall, now)) {
            drillInRhythm(ctx, hall, hall.phase(now, npc.getRandom()), now);
        }
        if (now >= strikeAt) {
            strikeAt = Long.MAX_VALUE;
            strikeEffects(ctx);
        }
        salutes(ctx, hall, now);

        unpaidStandTicks += inBout ? GuardBalance.PAIRED_SHADOW_SHARE
                : spot.shadow() ? GuardBalance.SHADOW_DRILL_SHARE : 1f;
        if (++unpaidTicks >= XP_SLICE_TICKS) payExperience(ctx);
        return Status.RUNNING;
    }

    /** Claims a spot (see {@link BarracksDrills#claimSpot}) and, in a pair, tells it this defender's level and pace. */
    @Nullable
    private BarracksDrills.Spot takeSpot(Context ctx, boolean shadow) {
        BarracksDrills.Spot taken = BarracksDrills.claimSpot(ctx, barracks, captain, shadow);
        if (taken != null && taken.pair() != null) {
            BarracksDrills.Pair pair = taken.pair();
            int level = ctx.brain().defenderSkillLevel();
            // The slash as it plays on screen, so the partner's blocks meet it.
            int slash = ctx.npc().attackAnimationTicks(drillSlashTicks);
            if (pair.leader.equals(ctx.model().npcId)) {
                pair.leaderLevel = level;
                pair.leaderSlashTicks = slash;
            } else {
                pair.partnerLevel = level;
                pair.partnerSlashTicks = slash;
            }
        }
        return taken;
    }

    /**
     * A power-up planned or playing. It starts once the slash under way is
     * done, and waits a moment if another defender here has just begun one,
     * so two never go together. True while it holds the defender: no swings.
     */
    private boolean poweringUp(Context ctx, BarracksDrills.Hall hall, long now) {
        NpcEntity npc = ctx.npc();
        if (powerUpAt != Long.MAX_VALUE && now >= powerUpAt && now >= slashEndsAt && now >= busyUntil) {
            if (now - hall.lastPowerUpAt < POWER_UP_SPACING_TICKS) {
                powerUpAt = now + 10 + npc.getRandom().nextInt(21);
            } else {
                powerUpAt = Long.MAX_VALUE;
                hall.lastPowerUpAt = now;
                powerUpUntil = now + VillagerAnimationState.POWER_UP_TICKS;
                busyUntil = Math.max(busyUntil, powerUpUntil);
                yellAt = now + VillagerAnimationState.POWER_UP_YELL_TICKS;
                npc.setShieldRaised(false);
                npc.setAnimation(VillagerAnimationState.POWER_UP);
            }
        }
        if (now >= yellAt) {
            yellAt = Long.MAX_VALUE;
            yell(ctx);
        }
        if (now < powerUpUntil) {
            GuardCombatRules.face(npc, spot.faceAt());
            return true;
        }
        return false;
    }

    /**
     * The power-up's yell, from this defender. Vanilla has no human shout;
     * the author's pick is the ravager's roar, pitched up (1.3) and quieter
     * (0.6) so it reads as a war cry rather than a beast. Each defender's
     * voice sits a little higher or lower, with a touch more each time, so a
     * room never sounds the same.
     */
    private void yell(Context ctx) {
        NpcEntity npc = ctx.npc();
        if (!playerNear(ctx, npc)) return;
        float pitch = (float) (YELL_PITCH + (personalTempo(ctx.model().npcId) - 1.0) * 2.0)
                + (npc.getRandom().nextFloat() - 0.5F) * 0.08F;
        ctx.level().playSound(null, npc.getX(), npc.getEyeY(), npc.getZ(),
                SoundEvents.RAVAGER_ROAR, npc.getSoundSource(), YELL_VOLUME, pitch);
    }

    /** The yell's loudness and pitch: /playsound minecraft:entity.ravager.roar ... 0.6 1.3. */
    private static final float YELL_VOLUME = 0.6F;
    private static final double YELL_PITCH = 1.3;

    // =========================================================================
    // Rhythm
    // =========================================================================

    private void drillInRhythm(Context ctx, BarracksDrills.Hall hall, BarracksDrills.Phase phase, long now) {
        NpcEntity npc = ctx.npc();
        RandomSource random = npc.getRandom();

        // A late arrival waits for the next set rather than starting half-way.
        if (phase.set() && joinedSet != phase.index() && readySince <= phase.setStart()) {
            joinedSet = phase.index();
            shieldInPack = hasShield(npc);
            // Each starts a moment after the call and stops on its own count,
            // so the room never swings as one.
            boolean shieldSet = phase.shield() && shieldInPack;
            long begin = phase.setStart()
                    + random.nextInt((shieldSet ? SHIELD_JOIN_SPREAD_TICKS : SET_JOIN_SPREAD_TICKS) + 1);
            setSwings = MIN_SET_SWINGS + random.nextInt(MAX_SET_SWINGS - MIN_SET_SWINGS + 1);
            swingsDone = 0;
            kickedThisSet = false;
            shieldSetRaised = false;
            // Joined late (back from something mid-set): the swings already due
            // are skipped, not fired one a tick to catch up.
            if (now > begin) {
                int missed = (int) ((now - begin + drillSwingTicks - 1) / drillSwingTicks);
                swingsDone = missed;
                begin += (long) missed * drillSwingTicks;
            }
            nextSwingAt = begin;
        }
        // A shadow fighter's second slash follows its first straight away.
        if (followUpAt != Long.MAX_VALUE && now >= followUpAt) {
            followUpAt = Long.MAX_VALUE;
            slash(ctx, now);
        }
        // A practice block runs the whole clip, set or no set, then comes down.
        if (practiceShieldUntil != Long.MIN_VALUE && now >= practiceShieldUntil) {
            practiceShieldUntil = Long.MIN_VALUE;
            npc.raiseShieldForPractice(false);
        }
        boolean ownSet = phase.set() && joinedSet == phase.index();
        if (ownSet && phase.shield() && shieldInPack) {
            // Shield practice: the shield up once, through the whole block clip.
            // Purely a pose -- the guard meter and the combat stance are not
            // touched. One with no shield in its pack keeps swinging instead:
            // the drawn shield does not check the pack, so it would hold up one
            // it does not own.
            GuardCombatRules.face(npc, spot.faceAt());
            if (!shieldSetRaised && now >= nextSwingAt && now >= busyUntil && now >= slashEndsAt) {
                shieldSetRaised = true;
                practiceShieldUntil = now + BarracksDrills.PRACTICE_SHIELD_TICKS;
                npc.raiseShieldForPractice(true);
            }
            return;
        }
        if (ownSet && swingsDone < setSwings && nextSwingAt < phase.setEnd()) {
            GuardCombatRules.face(npc, spot.faceAt());
            // A rest pose or a power-up still playing finishes first.
            if (now >= nextSwingAt && now >= busyUntil && now >= slashEndsAt) {
                swingsDone++;
                nextSwingAt = now + swing(ctx) + random.nextInt(SWING_WOBBLE_TICKS + 1);
            }
            return;
        }
        // The last slash or block plays out, and a late arrival stands ready for the next call.
        if (now < slashEndsAt || practiceShieldUntil != Long.MIN_VALUE || phase.set() && joinedSet != phase.index()) {
            GuardCombatRules.face(npc, spot.faceAt());
            return;
        }

        // Rest: after its own set, while the others may still be swinging.
        if (npc.isShieldRaised()) npc.setShieldRaised(false);
        if (restedAfterSet != phase.index()) {
            restedAfterSet = phase.index();
            planRest(ctx, joinedSet == phase.index(), random, now, true);
        }
        applyRest(ctx, now, spot.faceAt());
    }

    /**
     * Plays the planned rest as its moments come: the pose, the thought, the
     * look at a neighbour. A pose plays to its end before the next swing.
     */
    private void applyRest(Context ctx, long now, Vec3 faceAt) {
        NpcEntity npc = ctx.npc();
        if (now >= restPoseAt && restPose != null) {
            npc.setAnimation(restPose);
            busyUntil = Math.max(busyUntil, now + (long) VillagerAnimationState.getDuration(restPose));
            restPose = null;
        }
        if (now >= thoughtAt && thought != null) {
            npc.showBubble(thought, false, THOUGHT_TICKS);
            thought = null;
        }
        if (now < glanceUntil && glanceAt != null) {
            GuardCombatRules.face(npc, glanceAt);
        } else {
            GuardCombatRules.face(npc, faceAt);
        }
    }

    /**
     * One rest: a pose, or a look at a neighbour, or nothing; now and then a
     * thought. With {@code allowPowerUp} (not shadow fighting a partner), one
     * rest in {@value #POWER_UP_REST_ODDS} is a power-up instead.
     */
    private void planRest(Context ctx, boolean afterOwnSet, RandomSource random, long now, boolean allowPowerUp) {
        restPose = null;
        restPoseAt = Long.MAX_VALUE;
        glanceUntil = Long.MIN_VALUE;
        long at = now + 5 + random.nextInt(21);
        if (allowPowerUp && random.nextInt(POWER_UP_REST_ODDS) == 0) {
            powerUpAt = at;
        } else switch (random.nextInt(5)) {
            case 0 -> restPose = VillagerAnimationState.ARM_ON_HIP;
            case 1 -> restPose = VillagerAnimationState.SHRUG;
            case 2 -> restPose = VillagerAnimationState.THINK;
            case 3 -> {
                NpcEntity neighbour = nearestDrillingNeighbour(ctx, now);
                if (neighbour != null) {
                    glanceAt = neighbour.getEyePosition();
                    glanceUntil = at + GLANCE_TICKS;
                }
            }
            default -> { }
        }
        if (restPose != null) restPoseAt = at;

        thought = null;
        thoughtAt = Long.MAX_VALUE;
        if (random.nextInt(REST_THOUGHT_ODDS) == 0) {
            boolean late = BarracksDrills.windowProgress(ctx) > 0.6;
            if (late && random.nextInt(3) == 0) {
                thought = ThoughtIcon.EMOTE_BOREDOM;
            } else if (afterOwnSet) {
                thought = ThoughtIcon.EMOTE_PRIDE;
            }
            // Staggered so a room that just finished a set does not think at once.
            thoughtAt = now + 10 + random.nextInt(31);
        }
    }

    @Nullable
    private NpcEntity nearestDrillingNeighbour(Context ctx, long now) {
        UUID self = ctx.model().npcId;
        NpcEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Map.Entry<UUID, BlockPos> e : BarracksDrills.hall(barracks).holders(now).entrySet()) {
            if (e.getKey().equals(self)) continue;
            NpcEntity other = BarracksDrills.puppet(ctx.mgr(), e.getKey());
            if (other == null) continue;
            double dist = other.distanceToSqr(ctx.npc());
            if (dist < bestDist) {
                bestDist = dist;
                best = other;
            }
        }
        return best;
    }

    /**
     * One swing at the stand, at this defender's drill pace; a captain at its
     * stand kicks it instead now and then, once a set at most. Shadow fighting,
     * a swing is the whole slash twice, the second straight after the first.
     * Returns the ticks until the next swing may start.
     */
    private int swing(Context ctx) {
        NpcEntity npc = ctx.npc();
        long now = ctx.level().getGameTime();
        int breath = drillSwingTicks - drillSlashTicks;
        if (captain && spot.post() != null && !kickedThisSet && npc.getRandom().nextInt(STAND_KICK_ODDS) == 0) {
            kickedThisSet = true;
            npc.setShieldRaised(false);
            npc.setAnimation(VillagerAnimationState.KICK);
            slashEndsAt = now + VillagerAnimationState.KICK_TICKS;
            strikeAt = now + VillagerAnimationState.KICK_CONTACT_TICKS;
            strikeIsKick = true;
            return VillagerAnimationState.KICK_TICKS + breath;
        }
        int slash = slash(ctx, now);
        if (spot.shadow()) {
            followUpAt = now + slash + 2;
            // Both slashes are one swing: nothing starts until the second is done.
            slashEndsAt = followUpAt + slash;
            return 2 * slash + 2 + breath;
        }
        return slash + breath;
    }

    /**
     * Plays the whole slash once, at this defender's drill pace (slower while
     * sad), timing its blow. Returns how long it takes on screen.
     */
    private int slash(Context ctx, long now) {
        NpcEntity npc = ctx.npc();
        // A bout that just ended may have left the shield up; raising it cuts a slash, so it is down first.
        if (npc.isShieldRaised()) npc.setShieldRaised(false);
        npc.swing(InteractionHand.MAIN_HAND);
        npc.startAttackAnimation(drillSlashTicks);
        int onScreen = npc.attackAnimationTicks(drillSlashTicks);
        slashEndsAt = Math.max(slashEndsAt, now + onScreen);
        // The hit lands where the blade meets the stand, not as the swing starts.
        strikeAt = now + Math.max(1, Math.round(onScreen * GuardBalance.STRIKE_POINT));
        strikeIsKick = false;
        return onScreen;
    }

    /**
     * A defender's own drill tempo, 0.94 to 1.06, from its id: two recruits of
     * the same level still swing a little apart, and each keeps its own.
     */
    private static double personalTempo(UUID npcId) {
        double unit = (npcId.getLeastSignificantBits() >>> 11) / (double) (1L << 53);
        return 1.0 - TEMPO_SPREAD + 2 * TEMPO_SPREAD * unit;
    }

    // =========================================================================
    // Shadow fighting a partner
    // =========================================================================

    /** Paired with a partner that is in place: the captain leaves it be for its spar. */
    private boolean inBout() {
        return spot != null && spot.pair() != null && spot.pair().partner != null && spot.pair().partnerArrived;
    }

    /** This side is still here, and its drill closes at {@code endsAt}. */
    private static void markSeen(BarracksDrills.Pair pair, UUID self, long now, long endsAt) {
        if (pair.leader.equals(self)) {
            pair.leaderSeen = now;
            pair.leaderEndsAt = endsAt;
        } else if (self.equals(pair.partner)) {
            pair.partnerSeen = now;
            pair.partnerEndsAt = endsAt;
        }
    }

    private static boolean member(BarracksDrills.Pair pair, UUID self) {
        return pair.leader.equals(self) || self.equals(pair.partner);
    }

    /** A whole salute: shield down, hand up, and nothing else starts until it is done. */
    private void salute(NpcEntity npc, long now) {
        npc.setShieldRaised(false);
        npc.setAnimation(VillagerAnimationState.SALUTE);
        busyUntil = Math.max(busyUntil, now + SALUTE_TICKS);
    }

    /**
     * The other side left without parting -- to eat, say, or called away: the
     * shield comes down, a shrug, and back to the room's sets alone, rather
     * than straight into swinging at the air.
     */
    private void breakOff(Context ctx, long now) {
        NpcEntity npc = ctx.npc();
        if (npc.isShieldRaised()) npc.setShieldRaised(false);
        if (now < slashEndsAt) return;
        npc.setAnimation(VillagerAnimationState.SHRUG);
        busyUntil = Math.max(busyUntil, now + (long) VillagerAnimationState.getDuration(VillagerAnimationState.SHRUG));
    }

    /**
     * Shadow fighting a partner: the leader runs the bouts, both play their
     * part. False when there is no bout -- the leader still waiting, or the
     * other side gone -- and this defender drills alone meanwhile.
     */
    private boolean pairBout(Context ctx, BarracksDrills.Hall hall, long now) {
        BarracksDrills.Pair pair = spot.pair();
        UUID self = ctx.model().npcId;
        boolean leader = pair.leader.equals(self);
        if (!leader && !self.equals(pair.partner)) return false;
        if (leader) {
            if (pair.partner != null && pair.partnerGone(now)) {
                pair.partner = null;
                pair.partnerArrived = false;
                pair.partnerEndsAt = Long.MAX_VALUE;
            }
            if (pair.partner == null || !pair.partnerArrived) {
                if (pair.met) breakOff(ctx, now);
                pair.stage = BarracksDrills.Pair.Stage.WAITING;
                pair.met = false;
                pair.parted = false;
            } else if (pair.stage == BarracksDrills.Pair.Stage.WAITING
                    && powerUpAt == Long.MAX_VALUE && now >= busyUntil && now >= slashEndsAt
                    && (hall.visit == null || !hall.visit.guard.equals(pair.partner))) {
                // The partner is in place: they salute and begin, once the leader's own swing or power-up is done.
                pair.met = true;
                pair.stage = BarracksDrills.Pair.Stage.SALUTE_IN;
                pair.stageAt = now;
            }
            // The first of the two drills is nearly over: between exchanges, they
            // salute to part on and go off together (see tick).
            boolean between = pair.stage == BarracksDrills.Pair.Stage.RESTING
                    || pair.stage == BarracksDrills.Pair.Stage.SPARRING && now >= pair.exchangeEnd;
            if (pair.met && !pair.parted && between && pair.endsAt() - now <= PART_LEAD_TICKS) {
                pair.parted = true;
                pair.stage = BarracksDrills.Pair.Stage.SALUTE_OUT;
                pair.stageAt = now;
            }
            advanceBout(ctx, hall, pair, now);
        } else if (pair.leaderGone(now)) {
            if (!pairBroken) {
                pairBroken = true;
                if (pair.met) breakOff(ctx, now);
            }
            return false;
        }
        NpcEntity npc = ctx.npc();
        NpcEntity other = BarracksDrills.puppet(ctx.mgr(), leader ? pair.partner : pair.leader);
        if (pair.stage == BarracksDrills.Pair.Stage.WAITING) {
            // A partner in place stands ready, facing the leader, until it begins;
            // swinging alone meanwhile, the bout's start would cut a slash short.
            if (leader || !pair.partnerArrived || other == null) return false;
            if (npc.isShieldRaised()) npc.setShieldRaised(false);
            GuardCombatRules.face(npc, other.getEyePosition());
            return true;
        }
        if (other == null) return false;
        playBout(ctx, hall, pair, leader, other, now);
        return true;
    }

    /** The next run of exchanges, after the salute on meeting or a rest. */
    private static void startSparring(BarracksDrills.Pair pair, long now, RandomSource random) {
        pair.bout++;
        pair.stage = BarracksDrills.Pair.Stage.SPARRING;
        pair.stageAt = now;
        pair.stageEnd = now + BOUT_MIN_TICKS + random.nextInt(BOUT_MAX_TICKS - BOUT_MIN_TICKS + 1);
        pair.exchangeEnd = now;
        pair.nextExchangeAt = now + 10;
    }

    /**
     * The leader moves the bout on: the salute on meeting, runs of exchanges
     * with a rest between, and the salute to part on when its drill ends.
     */
    private void advanceBout(Context ctx, BarracksDrills.Hall hall, BarracksDrills.Pair pair, long now) {
        RandomSource random = ctx.npc().getRandom();
        switch (pair.stage) {
            case SALUTE_IN -> {
                if (now >= pair.stageAt + BOUT_SALUTE_TICKS) startSparring(pair, now, random);
            }
            case SPARRING -> {
                // The captain testing either of them holds the bout until it is done.
                BarracksDrills.Visit visit = hall.visit;
                if (visit != null && (visit.guard.equals(pair.leader) || visit.guard.equals(pair.partner))) {
                    pair.stageEnd++;
                    if (pair.nextExchangeAt != Long.MAX_VALUE) pair.nextExchangeAt++;
                    return;
                }
                if (now >= pair.exchangeEnd && pair.nextExchangeAt == Long.MAX_VALUE) {
                    if (now >= pair.stageEnd) {
                        // A breather, no salute: they only salute meeting and parting.
                        pair.stage = BarracksDrills.Pair.Stage.RESTING;
                        pair.stageAt = now;
                        pair.stageEnd = now + BOUT_REST_MIN_TICKS
                                + random.nextInt(BOUT_REST_MAX_TICKS - BOUT_REST_MIN_TICKS + 1);
                        return;
                    }
                    pair.nextExchangeAt = now + BOUT_PAUSE_MIN + random.nextInt(BOUT_PAUSE_MAX - BOUT_PAUSE_MIN + 1);
                }
                if (now >= pair.nextExchangeAt) pair.nextExchange(now, random);
            }
            case SALUTE_OUT -> {
                // Saluted: both leave on their next tick (see tick).
                if (now >= pair.stageAt + BOUT_SALUTE_TICKS) {
                    pair.stage = BarracksDrills.Pair.Stage.PARTED;
                    pair.stageAt = now;
                }
            }
            case RESTING -> {
                if (now >= pair.stageEnd) startSparring(pair, now, random);
            }
            case WAITING, PARTED -> { }
        }
    }

    /** This defender's part in the bout's current stage. */
    private void playBout(Context ctx, BarracksDrills.Hall hall, BarracksDrills.Pair pair, boolean leader,
                          NpcEntity other, long now) {
        NpcEntity npc = ctx.npc();
        // The captain has come to test one of the two: the other calls off the
        // exchange under way, lowers its guard and watches -- not swinging at a
        // partner who has turned its back to face the captain.
        BarracksDrills.Visit visit = hall.visit;
        if (visit != null && (pair.stage == BarracksDrills.Pair.Stage.SPARRING
                || pair.stage == BarracksDrills.Pair.Stage.RESTING)
                && (visit.guard.equals(pair.leader) || visit.guard.equals(pair.partner))) {
            pair.callOff(now);
            if (npc.isShieldRaised()) npc.setShieldRaised(false);
            NpcEntity captainNpc = BarracksDrills.puppet(ctx.mgr(), visit.captain);
            // A slash already on its way finishes facing the partner it was aimed at.
            GuardCombatRules.face(npc, now < slashEndsAt || captainNpc == null
                    ? other.getEyePosition() : captainNpc.getEyePosition());
            return;
        }
        switch (pair.stage) {
            case SALUTE_IN, SALUTE_OUT -> {
                if (npc.isShieldRaised()) npc.setShieldRaised(false);
                GuardCombatRules.face(npc, other.getEyePosition());
                if (salutedStageAt != pair.stageAt && now >= slashEndsAt
                        && now >= pair.stageAt + (leader ? LEADER_SALUTE_AFTER : PARTNER_SALUTE_AFTER)) {
                    salutedStageAt = pair.stageAt;
                    salute(npc, now);
                }
            }
            case SPARRING -> {
                GuardCombatRules.face(npc, other.getEyePosition());
                if (pair.exchange <= 0) return;
                if (shieldCheckedExchange != pair.exchange) {
                    shieldCheckedExchange = pair.exchange;
                    shieldInPack = hasShield(npc);
                }
                if (pair.leaderAttacks == leader) {
                    // Two blows, the whole slash each time.
                    if (npc.isShieldRaised()) npc.setShieldRaised(false);
                    for (int blow = 0; blow < BarracksDrills.Pair.BLOWS; blow++) {
                        int id = pair.exchange * BarracksDrills.Pair.BLOWS + blow;
                        if (lastBoutSwung < id && now >= pair.swingAt[blow]) {
                            lastBoutSwung = id;
                            npc.swing(InteractionHand.MAIN_HAND);
                            npc.startAttackAnimation(drillSlashTicks);
                            slashEndsAt = now + npc.attackAnimationTicks(drillSlashTicks);
                        }
                        if (lastBoutStruck < id && now >= pair.strikeAt[blow]) {
                            lastBoutStruck = id;
                            sparBlockSound(ctx, other);
                        }
                    }
                } else {
                    // Each blow met by the whole block clip, the arm up as the blade lands.
                    boolean up = shieldInPack && pair.blocking(now);
                    if (up != npc.isShieldRaised()) npc.raiseShieldForPractice(up);
                }
            }
            case RESTING -> {
                if (npc.isShieldRaised()) npc.setShieldRaised(false);
                if (restedAfterBout != pair.bout) {
                    restedAfterBout = pair.bout;
                    planRest(ctx, true, npc.getRandom(), now, false);
                }
                applyRest(ctx, now, other.getEyePosition());
            }
            case WAITING, PARTED -> { }
        }
    }

    // =========================================================================
    // Effects (only with a player close enough to see them)
    // =========================================================================

    private static boolean playerNear(Context ctx, Entity at) {
        return ctx.level().hasNearbyAlivePlayer(at.getX(), at.getY(), at.getZ(), EFFECT_PLAYER_RANGE);
    }

    /**
     * The blade reaches the stand: it rocks (vanilla's hit wobble, which also
     * plays the armor stand's hit sound on the client) and a few sparks fly.
     * A shadow drill just cuts the air.
     */
    private void strikeEffects(Context ctx) {
        NpcEntity npc = ctx.npc();
        if (spot.post() == null) {
            if (!playerNear(ctx, npc)) return;
            ctx.level().playSound(null, npc.getX(), npc.getY(), npc.getZ(),
                    SoundEvents.PLAYER_ATTACK_WEAK, npc.getSoundSource(), 0.25F,
                    1.1F + npc.getRandom().nextFloat() * 0.2F);
            return;
        }
        if (!(ctx.level().getEntity(spot.post().standId()) instanceof ArmorStand stand)
                || !stand.isAlive() || !playerNear(ctx, stand)) return;
        ctx.level().broadcastEntityEvent(stand, ARMOR_STAND_HIT_EVENT);
        if (strikeIsKick) {
            // A kick lands lower and heavier: a thud, and more of a shower.
            ctx.level().sendParticles(ParticleTypes.CRIT, stand.getX(), stand.getY() + 0.7, stand.getZ(),
                    8, 0.2, 0.2, 0.2, 0.08);
            ctx.level().playSound(null, stand.getX(), stand.getY(), stand.getZ(),
                    SoundEvents.PLAYER_ATTACK_KNOCKBACK, npc.getSoundSource(), 0.6F,
                    0.8F + npc.getRandom().nextFloat() * 0.15F);
            return;
        }
        ctx.level().sendParticles(ParticleTypes.CRIT, stand.getX(), stand.getY() + 1.2, stand.getZ(),
                4, 0.15, 0.2, 0.15, 0.05);
    }

    /** A captain's kick lands on a guard's raised shield, or on the guard itself without one. */
    private void kickBlockSound(Context ctx, NpcEntity blocker) {
        if (!playerNear(ctx, blocker)) return;
        RandomSource random = ctx.npc().getRandom();
        if (blocker.isShieldRaised()) {
            ctx.level().playSound(null, blocker.getX(), blocker.getY(), blocker.getZ(),
                    SoundEvents.SHIELD_BLOCK, blocker.getSoundSource(), 0.8F, 0.75F + random.nextFloat() * 0.15F);
        }
        ctx.level().playSound(null, blocker.getX(), blocker.getY(), blocker.getZ(),
                SoundEvents.PLAYER_ATTACK_KNOCKBACK, blocker.getSoundSource(), 0.6F, 0.8F + random.nextFloat() * 0.15F);
    }

    /** Sparring: the blow lands on the partner's raised shield, or is pulled short of one without. */
    private void sparBlockSound(Context ctx, NpcEntity blocker) {
        if (!playerNear(ctx, blocker)) return;
        boolean shield = blocker.isShieldRaised();
        ctx.level().playSound(null, blocker.getX(), blocker.getY(), blocker.getZ(),
                shield ? SoundEvents.SHIELD_BLOCK : SoundEvents.PLAYER_ATTACK_NODAMAGE,
                blocker.getSoundSource(), shield ? 0.5F : 0.35F,
                0.9F + ctx.npc().getRandom().nextFloat() * 0.2F);
    }

    /**
     * Steps onto the centre of {@code cell} after the walk there has ended: a
     * walk counts as done up to a block and a half short, which left a guard
     * cutting at the air in front of its stand. True once there, or once it
     * has tried long enough (as {@code CraftingStation.settle}).
     */
    private boolean settle(Context ctx, BlockPos cell) {
        return settle(ctx, cell.getX() + 0.5, cell.getZ() + 0.5);
    }

    /** As {@link #settle(Context, BlockPos)}, onto an exact spot that need not be a tile's middle. */
    private boolean settle(Context ctx, double x, double z) {
        NpcEntity npc = ctx.npc();
        double dx = x - npc.getX();
        double dz = z - npc.getZ();
        if (dx * dx + dz * dz <= SETTLE_DIST_SQ || ++settleTicks > SETTLE_MAX_TICKS) {
            settleTicks = 0;
            npc.getNavigation().stop();
            return true;
        }
        npc.getMoveControl().setWantedPosition(x, npc.getY(), z, 0.4);
        return false;
    }

    /** Whether this defender carries a shield to raise. */
    private static boolean hasShield(NpcEntity npc) {
        return GuardSkillEffects.findShieldSlot(npc.getInventory()) >= 0;
    }

    // =========================================================================
    // Salutes
    // =========================================================================

    private void salutes(Context ctx, BarracksDrills.Hall hall, long now) {
        NpcEntity npc = ctx.npc();
        // A guard shadow fighting keeps at it; only those at the stands salute the captain.
        if (!captain && hall.captain != null && !spot.shadow()) {
            // The captain came in, or is leaving: salute it once, if it can be seen.
            long event = Long.MIN_VALUE;
            if (hall.captainArrivedAt > salutedArrival && now - hall.captainArrivedAt < SALUTE_WINDOW_TICKS) {
                event = hall.captainArrivedAt;
                salutedArrival = event;
            } else if (hall.captainLeftAt > salutedLeave && now - hall.captainLeftAt < SALUTE_WINDOW_TICKS) {
                event = hall.captainLeftAt;
                salutedLeave = event;
            }
            if (event != Long.MIN_VALUE) {
                NpcEntity captainNpc = BarracksDrills.puppet(ctx.mgr(), hall.captain);
                if (captainNpc != null && npc.hasLineOfSight(captainNpc)) {
                    saluteAt = now + npc.getRandom().nextInt(SALUTE_STAGGER_TICKS + 1);
                }
            }
        }
        // Not in the middle of a slash, a block or a pose: the salute waits for it to end.
        if (now >= saluteAt && now >= slashEndsAt && now >= busyUntil && practiceShieldUntil == Long.MIN_VALUE) {
            saluteAt = Long.MAX_VALUE;
            if (captain && hall.holders(now).size() <= 1) return; // nobody to answer
            salute(npc, now);
        }
    }

    // =========================================================================
    // The captain's visit
    // =========================================================================

    /**
     * A captain's inspection, on duty: into the barracks, then the daily visit
     * with a guard drilling there (lowest level first, as at its own drill),
     * then back to work. Nobody to spar with within
     * {@value #INSPECTION_WAIT_TICKS} ticks of getting there, or an alarm or
     * alert, sends it straight back.
     */
    private Status tickInspection(Context ctx) {
        if (barracks == null || !barracks.isValid()
                || GuardAlertSystem.isVillageAlarmed(ctx.mgr(), ctx.level())
                || GuardAlertSystem.isOnAlert(ctx.npc())) {
            return Status.DONE;
        }
        long now = ctx.level().getGameTime();
        BarracksDrills.Hall hall = BarracksDrills.hall(barracks);
        if (!arrived) {
            BlockPos entry = BarracksDrills.entryCell(barracks);
            if (entry == null) return Status.DONE;
            if (!moveTo(ctx, entry, 0.6f)) return Status.RUNNING;
            if (moveFailed(ctx, entry)) return Status.DONE;
            arrived = true;
            inspectionUntil = now + INSPECTION_WAIT_TICKS;
            return Status.RUNNING;
        }
        boolean onVisit = myVisit != null;
        if (captainVisit(ctx, hall, now)) {
            if (++unpaidVisitTicks >= XP_SLICE_TICKS) payExperience(ctx);
            return Status.RUNNING;
        }
        // The visit is over, or none came about: back on duty.
        if (onVisit || now >= inspectionUntil) return Status.DONE;
        ctx.npc().holdStillForAnimation();
        return Status.RUNNING;
    }

    /**
     * The captain's side of its daily visit: start one when it is time, walk to
     * the guard, spar, part. True while it is on a visit (its own drill waits).
     */
    private boolean captainVisit(Context ctx, BarracksDrills.Hall hall, long now) {
        NpcEntity npc = ctx.npc();
        if (myVisit != null && hall.visit != myVisit) {
            // The guard left (or the visit went stale): back to the stand.
            endVisit(ctx, hall);
            return false;
        }
        if (myVisit == null) {
            if (hall.visit != null || now < nextVisitTry) return false;
            nextVisitTry = now + VISIT_TRY_TICKS;
            // The daily spar, else the daily kick test (at its own drill only).
            if (!startVisit(ctx, hall, now) && (inspection || !startKickTest(ctx, hall, now))) return false;
        }
        BarracksDrills.Visit visit = myVisit;
        visit.captainSeen = now;
        NpcEntity partner = BarracksDrills.puppet(ctx.mgr(), visit.guard);
        if (partner == null || visit.stale(now)) {
            endVisit(ctx, hall);
            return false;
        }
        BarracksDrills.renew(barracks, visit.captainCell, ctx.model().npcId, now);
        RandomSource random = npc.getRandom();

        switch (visit.stage) {
            case WALKING -> {
                if (!walked) {
                    if (!moveTo(ctx, visit.captainCell, VillagerModel.WALK_SPEED)) return true;
                    if (moveFailed(ctx, visit.captainCell)) {
                        endVisit(ctx, hall);
                        return false;
                    }
                    walked = true;
                }
                // The last half step: its exact spot, half a block of air from the guard.
                if (!settle(ctx, visit.captainStandAt.x, visit.captainStandAt.z)) return true;
                walked = false;
                visit.stage = BarracksDrills.Visit.Stage.SPARRING;
                visit.sparEnd = now + SPAR_MIN_TICKS + random.nextInt(SPAR_MAX_TICKS - SPAR_MIN_TICKS + 1);
                visit.nextExchangeAt = now + 20;
                npc.showBubble(ThoughtIcon.EMOTE_GREETING, true, 40);
                visit.reply = ThoughtIcon.EMOTE_GREETING;
                visit.replyAt = now + 20;
                return true;
            }
            case SPARRING -> {
                npc.holdStillForAnimation();
                GuardCombatRules.face(npc, partner.getEyePosition());
                if (visit.kickTest) {
                    // One kick into the guard's shield, a moment to let it land, then the salutes.
                    if (visit.exchange == 0 && now >= visit.nextExchangeAt) {
                        visit.nextExchange(now, npc.attackAnimationTicks(attackTicks), guardSwingTicks(ctx, visit.guard), true);
                    } else if (visit.exchange > 0 && now >= visit.exchangeEnd + 10) {
                        visit.stage = BarracksDrills.Visit.Stage.ENDING;
                        visit.endingAt = now;
                        npc.setShieldRaised(false);
                        npc.showBubble(ThoughtIcon.EMOTE_PRIDE, false, THOUGHT_TICKS);
                        return true;
                    }
                    playRole(ctx, visit, partner, now);
                    return true;
                }
                if (now >= visit.exchangeEnd && visit.nextExchangeAt == Long.MAX_VALUE) {
                    if (now >= visit.sparEnd) {
                        visit.stage = BarracksDrills.Visit.Stage.ENDING;
                        visit.endingAt = now;
                        npc.setShieldRaised(false);
                        npc.showBubble(ThoughtIcon.EMOTE_PRIDE, false, THOUGHT_TICKS);
                        return true;
                    }
                    visit.nextExchangeAt = now + EXCHANGE_PAUSE_MIN
                            + random.nextInt(EXCHANGE_PAUSE_MAX - EXCHANGE_PAUSE_MIN + 1);
                    if (visit.exchange % TALK_EVERY_EXCHANGES == 0) {
                        // A word between exchanges, and the guard answers it.
                        npc.showBubble(ThoughtIcon.EMOTE_TALKING, true, 40);
                        visit.reply = random.nextBoolean() ? ThoughtIcon.EMOTE_QUESTION : ThoughtIcon.EMOTE_AGREE;
                        visit.replyAt = now + 25;
                    }
                }
                if (now >= visit.nextExchangeAt) {
                    // The captain strikes on odd exchanges; now and then the strike is a kick.
                    boolean kick = visit.exchange % 2 == 0 && random.nextInt(SPAR_KICK_ODDS) == 0;
                    visit.nextExchange(now, npc.attackAnimationTicks(attackTicks), guardSwingTicks(ctx, visit.guard), kick);
                }
                playRole(ctx, visit, partner, now);
                return true;
            }
            case ENDING -> {
                npc.holdStillForAnimation();
                GuardCombatRules.face(npc, partner.getEyePosition());
                if (!visitSaluted && now >= visit.endingAt + VISIT_ANSWER_AFTER) {
                    visitSaluted = true;
                    salute(npc, now);
                }
                if (now >= visit.endingAt + VISIT_PARTING_TICKS) {
                    endVisit(ctx, hall);
                    return false;
                }
                return true;
            }
        }
        return true;
    }

    /**
     * Starts today's visit if it is time: a quarter to three quarters through
     * the window (rolled daily), during a rest, with the lowest-level guard
     * drilling here (random among equals). No guard by 90% of the window, no
     * visit today.
     */
    private boolean startVisit(Context ctx, BarracksDrills.Hall hall, long now) {
        NpcEntity npc = ctx.npc();
        UUID self = ctx.model().npcId;
        long day = EveningSchedule.dayIndex(ctx.level().getDayTime());
        if (npc.getBrainData().contains(TAG_VISIT_DAY) && npc.getBrainData().getLong(TAG_VISIT_DAY) == day) return false;
        if (!inspection) {
            // Its own drill: at the day's rolled moment. An inspection has
            // already picked its moment by coming over.
            double progress = BarracksDrills.windowProgress(ctx);
            double at = VISIT_EARLIEST
                    + EveningSchedule.sharedRoll(self, day, SALT_VISIT, VISIT_SPREAD_PERCENT + 1) / 100.0;
            if (progress < at || progress > VISIT_LATEST) return false;
        }
        if (hall.phase(now, npc.getRandom()).set()) return false;

        UUID partner = null;
        BlockPos partnerCell = null;
        int lowest = Integer.MAX_VALUE;
        int ties = 0;
        for (Map.Entry<UUID, BlockPos> e : hall.holders(now).entrySet()) {
            if (e.getKey().equals(self)) continue;
            VillagerModel model = BarracksDrills.model(ctx.mgr(), e.getKey());
            if (model == null || model.puppet() == null || model.puppet().getProfession() != Profession.GUARD) continue;
            if (!(model.brain().currentActivity() instanceof DrillActivity drill)
                    || drill.barracks != barracks || !drill.arrived || drill.myVisit != null
                    || !e.getValue().equals(drill.spot.cell())) continue;
            // A guard fighting a partner already has someone to spar with, and
            // one about to go on duty would be called away half-way through.
            if (drill.inBout() || drill.windowEndsAt - now < SPAR_MAX_TICKS + VISIT_SPARE_TICKS) continue;
            // Lowest level first, but a guard with a shield to spar with before any without.
            int level = model.brain().guardSkillLevel() + (hasShield(model.puppet()) ? 0 : 1000);
            if (level < lowest) {
                lowest = level;
                ties = 1;
                partner = e.getKey();
                partnerCell = e.getValue();
            } else if (level == lowest && npc.getRandom().nextInt(++ties) == 0) {
                partner = e.getKey();
                partnerCell = e.getValue();
            }
        }
        if (partner == null) return false;
        if (!beginVisit(ctx, hall, partner, partnerCell, false, now)) return false;
        npc.getBrainData().putLong(TAG_VISIT_DAY, day);
        return true;
    }

    /**
     * Starts today's kick test if it is time: some moment between 15% and 85%
     * through the window (rolled daily), during a rest. Any guard drilling
     * here will do -- at a stand, alone or with a partner -- one with a shield
     * to kick into first, at random among them.
     */
    private boolean startKickTest(Context ctx, BarracksDrills.Hall hall, long now) {
        NpcEntity npc = ctx.npc();
        UUID self = ctx.model().npcId;
        long day = EveningSchedule.dayIndex(ctx.level().getDayTime());
        if (npc.getBrainData().contains(TAG_KICK_TEST_DAY) && npc.getBrainData().getLong(TAG_KICK_TEST_DAY) == day) {
            return false;
        }
        double at = KICK_TEST_EARLIEST
                + EveningSchedule.sharedRoll(self, day, SALT_KICK_TEST, KICK_TEST_SPREAD_PERCENT + 1) / 100.0;
        if (BarracksDrills.windowProgress(ctx) < at) return false;
        if (hall.phase(now, npc.getRandom()).set()) return false;

        List<Map.Entry<UUID, BlockPos>> shielded = new ArrayList<>();
        List<Map.Entry<UUID, BlockPos>> bare = new ArrayList<>();
        for (Map.Entry<UUID, BlockPos> e : hall.holders(now).entrySet()) {
            if (e.getKey().equals(self)) continue;
            VillagerModel model = BarracksDrills.model(ctx.mgr(), e.getKey());
            if (model == null || model.puppet() == null || model.puppet().getProfession() != Profession.GUARD) continue;
            if (!(model.brain().currentActivity() instanceof DrillActivity drill)
                    || drill.barracks != barracks || !drill.arrived || drill.myVisit != null
                    || !e.getValue().equals(drill.spot.cell())) continue;
            // Not one about to leave, nor a pair saluting goodbye.
            if (drill.windowEndsAt - now < VISIT_SPARE_TICKS || drill.spot.pair() != null
                    && (drill.spot.pair().parted || drill.spot.pair().stage == BarracksDrills.Pair.Stage.PARTED)) continue;
            (hasShield(model.puppet()) ? shielded : bare).add(e);
        }
        List<Map.Entry<UUID, BlockPos>> pool = shielded.isEmpty() ? bare : shielded;
        if (pool.isEmpty()) return false;
        Map.Entry<UUID, BlockPos> pick = pool.get(npc.getRandom().nextInt(pool.size()));
        if (!beginVisit(ctx, hall, pick.getKey(), pick.getValue(), true, now)) return false;
        npc.getBrainData().putLong(TAG_KICK_TEST_DAY, day);
        return true;
    }

    /** Sets the captain off on a visit to {@code partner}: a spar, or a kick test. False with nowhere to stand. */
    private boolean beginVisit(Context ctx, BarracksDrills.Hall hall, UUID partner, BlockPos partnerCell,
                               boolean kickTest, long now) {
        NpcEntity npc = ctx.npc();
        UUID self = ctx.model().npcId;
        if (ctx.mgr().getGraph() == null) return false;
        BarracksDrills.SparSpot at = BarracksDrills.sparringSpot(
                barracks, ctx.mgr().getGraph(), partnerCell, self, now, npc.getRandom());
        if (at == null) return false;

        myVisit = new BarracksDrills.Visit(self, partner, partnerCell, at.cell(), at.standAt(), kickTest, now);
        hall.visit = myVisit;
        // The tile between them is kept clear too, so nobody walks in.
        hall.claim(at.cell(), self, now, null, at.area());
        shieldInPack = hasShield(npc);
        visitSaluted = false;
        lastSwungExchange = -1;
        lastStruckExchange = -1;
        npc.setShieldRaised(false);
        resetMove();
        walked = false;
        return true;
    }

    /** The guard's side: stop its own drill, face the captain, block and strike in turn. */
    private boolean guardVisit(Context ctx, BarracksDrills.Hall hall, long now) {
        NpcEntity npc = ctx.npc();
        BarracksDrills.Visit visit = hall.visit;
        if (visit == null || !visit.guard.equals(ctx.model().npcId)) {
            if (myVisit != null) {
                myVisit = null;
                npc.setShieldRaised(false);
                // Back to its own drill from the next set on, not half-way through this one.
                readySince = now;
            }
            return false;
        }
        if (myVisit != visit) {
            myVisit = visit;
            shieldInPack = hasShield(npc);
            // Its own drill stops here: no second slash or practice block left hanging.
            followUpAt = Long.MAX_VALUE;
            practiceShieldUntil = Long.MIN_VALUE;
            visitSaluted = false;
            visitThanked = false;
            lastSwungExchange = -1;
            lastStruckExchange = -1;
        }
        visit.guardSeen = now;
        NpcEntity captainNpc = BarracksDrills.puppet(ctx.mgr(), visit.captain);
        if (captainNpc == null || visit.stale(now)) {
            // The captain died, unloaded or dropped the visit without ending it.
            clearVisit(ctx, hall);
            readySince = now;
            return false;
        }
        npc.holdStillForAnimation();
        GuardCombatRules.face(npc, captainNpc.getEyePosition());
        if (visit.stage == BarracksDrills.Visit.Stage.SPARRING) {
            playRole(ctx, visit, captainNpc, now);
        } else if (npc.isShieldRaised()) {
            npc.setShieldRaised(false);
        }
        if (visit.reply != null && now >= visit.replyAt) {
            npc.showBubble(visit.reply, true, 40);
            visit.reply = null;
            visit.replyAt = Long.MAX_VALUE;
        }
        if (visit.stage == BarracksDrills.Visit.Stage.ENDING) {
            if (!visitSaluted && now >= visit.endingAt + VISIT_SALUTE_AFTER) {
                visitSaluted = true;
                salute(npc, now);
            }
            if (!visitThanked && now >= visit.endingAt + VISIT_THANKS_AFTER) {
                visitThanked = true;
                npc.showBubble(ThoughtIcon.EMOTE_ADMIRATION, false, THOUGHT_TICKS);
            }
        }
        return true;
    }

    /**
     * This defender's part in the current exchange: the attacker swings (no
     * damage, no knockback); the defender holds its shield up from the start of
     * the exchange until just past the blow. A pose only -- no guard meter.
     */
    private void playRole(Context ctx, BarracksDrills.Visit visit, NpcEntity partner, long now) {
        if (visit.exchange <= 0) return;
        NpcEntity npc = ctx.npc();
        boolean attacking = captain == visit.captainAttacks;
        if (attacking) {
            if (npc.isShieldRaised()) npc.setShieldRaised(false);
            if (lastSwungExchange != visit.exchange && now >= visit.swingAt) {
                lastSwungExchange = visit.exchange;
                if (visit.kick) {
                    npc.setAnimation(VillagerAnimationState.KICK);
                } else {
                    npc.swing(InteractionHand.MAIN_HAND);
                    npc.startAttackAnimation(attackTicks);
                }
            }
            if (lastStruckExchange != visit.exchange && now >= visit.strikeAt) {
                lastStruckExchange = visit.exchange;
                if (visit.kick) {
                    kickBlockSound(ctx, partner);
                    // The guard did not see that one coming.
                    visit.reply = ThoughtIcon.EMOTE_SURPRISED;
                    visit.replyAt = now + 4;
                } else {
                    sparBlockSound(ctx, partner);
                }
            }
        } else {
            // The whole block clip, the arm up in front as the blow lands.
            boolean up = shieldInPack && now >= visit.exchangeStart && now < visit.raiseUntil;
            if (up != npc.isShieldRaised()) npc.raiseShieldForPractice(up);
        }
    }

    /** The guard's fighting swing as it plays on screen (slower while sad). */
    private static int guardSwingTicks(Context ctx, UUID guard) {
        VillagerModel model = BarracksDrills.model(ctx.mgr(), guard);
        int ticks = CombatRank.GUARD.attackTicks(model != null ? model.brain().guardSkillLevel() : 1);
        return model != null && model.puppet() != null ? model.puppet().attackAnimationTicks(ticks) : ticks;
    }

    /** Ends this captain's visit and sends it back to its own stand. */
    private void endVisit(Context ctx, BarracksDrills.Hall hall) {
        clearVisit(ctx, hall);
        // Walking back: any gesture still showing would hold its legs still as it went.
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        returning = true;
        arrived = false;
        readySince = Long.MAX_VALUE;
        resetMove();
        walked = false;
    }

    /** Takes down a visit this defender is part of: the hall's record and the captain's sparring spot. */
    private void clearVisit(Context ctx, BarracksDrills.Hall hall) {
        if (myVisit == null) return;
        if (hall.visit == myVisit) hall.visit = null;
        if (captain && barracks != null) {
            BarracksDrills.release(barracks, myVisit.captainCell, ctx.model().npcId);
        }
        myVisit = null;
        ctx.npc().setShieldRaised(false);
    }

    // =========================================================================
    // Upkeep
    // =========================================================================

    /** Whether this spot's armor stand still stands where the post was found. */
    private boolean standStillThere(Context ctx) {
        BarracksBuilding.Post post = spot.post();
        Entity entity = ctx.level().getEntity(post.standId());
        return entity instanceof ArmorStand stand && stand.isAlive()
                && stand.blockPosition().equals(post.standPos());
    }

    /**
     * Pays the ticks drilled so far, in proportion: no partial minute is lost.
     * A stand tick pays in full, shadow fighting a partner half, alone a
     * quarter; time on the captain's visit counts as stand time for both.
     */
    private void payExperience(Context ctx) {
        if (unpaidTicks + unpaidVisitTicks <= 0) return;
        float perMinute = GuardBalance.DRILL_EXPERIENCE_PER_MINUTE;
        ctx.brain().onDefenderDrill(ctx, (unpaidTicks + unpaidVisitTicks) / (float) MINUTE_TICKS,
                perMinute * (unpaidStandTicks + unpaidVisitTicks) / MINUTE_TICKS);
        unpaidTicks = 0;
        unpaidVisitTicks = 0;
        unpaidStandTicks = 0f;
    }

    @Override
    public void stop(Context ctx) {
        payExperience(ctx);
        ctx.model().stopMoving();
        ctx.npc().setShieldRaised(false);
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.npc().setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        if (barracks == null) return;
        BarracksDrills.Hall hall = BarracksDrills.hall(barracks);
        clearVisit(ctx, hall);
        if (captain && ctx.model().npcId.equals(hall.captain)) {
            if (farewellUntil == Long.MIN_VALUE) hall.captainLeftAt = ctx.level().getGameTime();
            hall.captain = null;
        }
        if (spot != null) {
            BarracksDrills.release(barracks, spot.cell(), ctx.model().npcId);
            if (spot.pair() != null) BarracksDrills.leavePair(spot.pair(), ctx.model().npcId);
        }
    }

    @Override
    public String name() {
        return captain ? "drill_lead" : "drill";
    }
}

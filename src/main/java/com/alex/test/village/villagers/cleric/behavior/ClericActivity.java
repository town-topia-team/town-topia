package com.alex.test.village.villagers.cleric.behavior;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.EveningSchedule;
import com.alex.test.village.brain.FetchFromStorageActivity;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.VillagerBrain;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.building.TownHallBuilding;
import com.alex.test.village.chat.VillageDamageAlerts;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.villagers.cleric.ClericBalance;
import com.alex.test.village.villagers.cleric.settings.ClericSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.supplies.StorageSupplyRestocker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * The cleric's working day. Healing always comes first; the rest of the day
 * fills itself in, each part behind its own setting:
 *
 * <ol>
 *   <li><b>Heal.</b> Walks (runs, if they are badly hurt) to within 8 blocks
 *       of the patient with a clear line of sight, stops, and throws a healing
 *       orb ({@link HealingCast}), again and again until they are well. Who
 *       comes first, and who is left for now, is {@link ClericCare}'s call.</li>
 *   <li><b>Bring food</b> to a villager carrying none who cannot get any or is
 *       hungry: from its own spare food, or from storage first, handed over in
 *       a short chat ({@link CareTalk}).</li>
 *   <li><b>Cheer up</b> the saddest villager nobody has cheered up today, in a
 *       short chat that lifts its mood.</li>
 *   <li><b>Praying:</b> once a day, in the morning, it reads from a book
 *       in the Town Hall for half a minute and ends with a blessing; villagers
 *       nearby feel a little better, once a day each.</li>
 *   <li><b>Rounds:</b> otherwise it walks between the places people get hurt
 *       (the mine, the barracks, the forge...) and waits beside each door, so
 *       it is already near when someone does. Clerics book their stops and
 *       visits so they cover different places ({@link ClericRounds}). A few
 *       stops make a work session; the brain then decides again, so meals and
 *       rest still fit in.</li>
 * </ol>
 *
 * <p>While it walks to someone who needs it, it is not stopped for a chat;
 * on its rounds it may be.
 */
public final class ClericActivity extends WorkActivity {

    private enum Duty { NONE, HEAL, FEED, CHEER, PRAYER, ROUNDS, STANDBY, RETREAT }

    /** On call ({@link ClericCallOut}): a monster this close sends the cleric back. */
    private static final double KEEP_AWAY_DIST = 6.0;
    /** A throw under way is only broken off for a monster this close (squared). */
    private static final double CAST_BREAK_DIST_SQ = 4.0 * 4.0;
    private static final int KEEP_AWAY_CHECK_TICKS = 5;
    /** How far it backs away from a monster that came too close. */
    private static final double RETREAT_DIST = 8.0;
    /** Backing away never takes longer than this before it looks again. */
    private static final int RETREAT_MAX_TICKS = 60;
    /** How far behind a fighting guard it stands to throw (the throw reaches 8). */
    private static final double STANDOFF_DIST = 6.0;
    /** How far behind the nearest guard it waits while standing by. */
    private static final double STANDBY_DIST = 7.0;
    /** The spot behind a guard is worked out again this often: the fight moves. */
    private static final int STANDOFF_RECHECK_TICKS = 20;
    /** Close enough to its standby spot: it stops and watches. */
    private static final double STANDBY_SETTLED_SQ = 2.5 * 2.5;
    /** Farther than this from its standby spot it runs. */
    private static final double STANDBY_RUN_SQ = 20.0 * 20.0;

    /** How often a cleric busy with something else looks for someone hurt. */
    private static final int PATIENT_CHECK_TICKS = 20;
    /** Ticks between forced re-paths while following a moving patient. */
    private static final int REPATH_INTERVAL = 30;
    /** Squared drift of the patient from the last walk goal that forces a re-path. */
    private static final double REPATH_DRIFT_SQ = 9.0;
    /** Arrived beside the patient yet still no clear throw for this long: leave it for now. */
    private static final int NO_SIGHT_GIVE_UP_TICKS = 60;
    /** Walking to someone for longer than this means the way is not working out. */
    private static final int APPROACH_GIVE_UP_TICKS = 20 * 60;
    /** A patient skipped for standing near a monster is looked at again after this. */
    private static final long DANGER_SKIP_TICKS = 20L * 10L;

    /** How close, on the level, the cleric stands to talk. */
    private static final double TALK_DIST = 2.6;
    /** From this close it takes the last steps straight at the villager. */
    private static final double FINAL_STEP_DIST = 4.5;
    /** A villager that will not stop for the chat is tried this many times. */
    private static final int TALK_REFUSALS = 6;
    /** After a failed trip for food, bringing food is left alone this long. */
    private static final long FOOD_TRIP_COOLDOWN_TICKS = 20L * 120L;

    /** Stops on the rounds per work session. */
    private static final int ROUNDS_STOPS_PER_SESSION = 4;
    /** Ticks waited at each stop: 8 to 14 seconds. */
    private static final int ROUNDS_WAIT_MIN = 160;
    private static final int ROUNDS_WAIT_SPREAD = 120;
    /** The last steps onto a stop's spot end this close to its middle, or after this long. */
    private static final double SETTLE_DIST = 0.4;
    private static final int SETTLE_TICKS = 30;

    /** The morning: from sunrise until 10 o'clock. */
    private static final long MORNING_END_DAY_TIME = 4000L;
    /** Reading in the Town Hall before the blessing: 30 seconds. */
    private static final int PRAYER_READ_TICKS = 600;
    /** Villagers this close while it reads feel better at once; at the end, this far. */
    private static final double PRAYER_NEAR = 8.0;
    private static final double BLESSING_RADIUS = 12.0;
    private static final float PRAYER_WELLBEING = 3f;
    private static final String TAG_PRAYER_DAY = "ClericPrayerDay";

    /** Food trips that failed, by cleric, until when bringing food rests. */
    private static final java.util.Map<UUID, Long> FOOD_TRIP_REST = new java.util.HashMap<>();

    private Duty duty = Duty.NONE;
    @Nullable private UUID patientId;
    @Nullable private HealingCast cast;
    @Nullable private CareTalk talk;
    @Nullable private FetchFromStorageActivity fetch;
    @Nullable private MoveActivity move;
    @Nullable private BlockPos moveGoal;
    private float moveSpeed;
    private int repathTimer;
    /** What {@link #move} said on its last tick. */
    private MoveActivity.Status moveStatus = MoveActivity.Status.RUNNING;
    /**
     * Running for this trip. Set once the patient is badly hurt (or the villager
     * fed is starving) and kept until the duty ends: health bobbing about the line
     * switched the run on and off, and each switch changed the speed and started
     * the route over.
     */
    private boolean hurrying;
    private int dutyTicks;
    private int noSightTicks;
    private int patientCheck;

    @Nullable private NpcEntity visitee;
    private int talkRefusals;

    @Nullable private ClericRounds.Stop roundsStop;
    /** Ticks spent on the last steps onto the stop's spot; -1 while still walking there. */
    private int roundsSettle = -1;
    private int roundsWait = -1;
    private int roundsStops;

    @Nullable private BlockPos prayerSpot;
    private int prayerTicks = -1;

    private boolean workedThisSession;
    private String shownLabel = "";

    /** Staying out in a fight this tick ({@link ClericCallOut#staysOut}). */
    private boolean onCall;
    /** Goes into fights for guards and keeps its distance: on call, or a field medic out on a night call. */
    private boolean brave;
    /**
     * Got out of bed (or off its evening) for a badly hurt guard: heals only,
     * and goes back once nobody has needed it for half a minute.
     */
    private final boolean nightCall;
    private int nightIdleTicks;

    public ClericActivity() {
        this(false);
    }

    private ClericActivity(boolean nightCall) {
        this.nightCall = nightCall;
    }

    /** The cleric's work for a night call ({@link ClericCallOut#nightCallReason}). */
    public static ClericActivity nightCall() {
        return new ClericActivity(true);
    }

    public boolean isNightCall() {
        return nightCall;
    }
    private int keepAwayCheck;
    private int blessCheck;
    @Nullable private BlockPos retreatGoal;
    /** The spot behind the guard it is healing or standing by, worked out every so often. */
    @Nullable private BlockPos standoff;
    private int standoffAge;
    /** The guard it is standing by behind, picked again with the spot. */
    @Nullable private NpcEntity standbyGuard;
    /** Ticks to wait after a failed route to its standby spot. */
    private int standbyRetry;

    // =========================================================================
    // WorkActivity contract
    // =========================================================================

    @Override
    protected Status doTick(Context ctx) {
        ProfessionSettings settings = ClericCare.settings(ctx.npc());
        if (!settings.getBool(ClericSettingsSchema.WORK_ENABLED)) return Status.DONE;
        int level = ctx.brain().clericSkillLevel();

        onCall = ClericCallOut.staysOut(ctx, false);
        // Out of bed for a hurt guard at night: as brave as on call, if fit and allowed.
        brave = onCall || (nightCall && ClericCallOut.braveOnNightCall(ctx.npc(), ctx.level().getGameTime()));
        // The field medic keeps its distance: a monster close by sends it back
        // a few blocks, mid-throw only if the monster is right on top of it.
        if (brave && duty != Duty.RETREAT && --keepAwayCheck <= 0) {
            keepAwayCheck = KEEP_AWAY_CHECK_TICKS;
            Mob close = ClericCallOut.nearestMonster(ctx.level(), ctx.npc(), KEEP_AWAY_DIST);
            if (close != null && (cast == null || ctx.npc().distanceToSqr(close) < CAST_BREAK_DIST_SQ)) {
                beginRetreat(ctx, close);
            }
        }
        // The battle blessing, once guards are close (BattleBlessing).
        if (onCall && cast == null && talk == null && duty != Duty.RETREAT && --blessCheck <= 0) {
            blessCheck = PATIENT_CHECK_TICKS;
            if (BattleBlessing.tryBless(ctx, settings, level)) {
                workedThisSession = true;
                diary(ctx, "CLERIC battle blessing");
            }
        }

        if (cast != null) {
            if (cast.tick(ctx)) {
                cast.stop(ctx, false);
                cast = null;
                workedThisSession = true;
                // The same patient again straight away if it still needs it,
                // unless someone much worse off turned up during the throw.
                patientCheck = 0;
            }
            return Status.RUNNING;
        }
        // Its visit stays booked through the food trip and the chat.
        if (visitee != null) ClericCare.claimVisit(ctx.npc(), visitee, ctx.level().getGameTime());
        if (talk != null) {
            if (talk.tick(ctx)) finishTalk(ctx, level);
            return Status.RUNNING;
        }

        // Someone hurt comes before everything else, and someone much worse
        // off before the patient it is on its way to: it used to see nobody
        // else until that patient was well.
        if (duty != Duty.RETREAT && --patientCheck <= 0) {
            patientCheck = PATIENT_CHECK_TICKS;
            LivingEntity patient = ClericCare.pickPatient(ctx, settings, level, false, brave);
            if (patient != null && (duty != Duty.HEAL || worseOffThanPatient(ctx, patient))
                    && !feedingFirst(patient)) {
                if (duty == Duty.HEAL) {
                    diary(ctx, "CLERIC leaving its patient for " + ClericCare.nameOf(patient) + ", who is worse off");
                }
                beginHeal(ctx, patient);
            }
        }

        if (fetch != null) {
            tickFoodTrip(ctx);
            return Status.RUNNING;
        }

        dutyTicks++;
        switch (duty) {
            case HEAL -> tickHeal(ctx, settings, level);
            case FEED, CHEER -> tickVisit(ctx, level);
            case PRAYER -> tickPrayer(ctx, level);
            case ROUNDS -> tickRounds(ctx);
            case STANDBY -> tickStandby(ctx);
            case RETREAT -> tickRetreat(ctx);
            case NONE -> {
                if (!chooseDuty(ctx, settings)) return endSession(ctx);
            }
        }
        return Status.RUNNING;
    }

    @Override
    protected void doStop(Context ctx) {
        // However the night call ends, the next one waits: a minute, or five if it healed nobody.
        if (nightCall) {
            ClericCallOut.nightCallEnded(ctx.npc(), ctx.level().getGameTime(), workedThisSession);
            diary(ctx, "CLERIC night call over");
        }
        if (cast != null) cast.stop(ctx, true);
        cast = null;
        if (talk != null) talk.stop(ctx);
        talk = null;
        if (fetch != null) fetch.stop(ctx);
        fetch = null;
        leaveDuty(ctx);
        ctx.npc().setHoldingOrb(false);
        ctx.npc().setRunning(false);
        ctx.npc().clearDisplayItem();
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.model().stopMoving();
    }

    @Override
    public boolean isMovingPhase() {
        return move != null || fetch != null;
    }

    /** On its way to someone hurt, hungry or sad it does not stop to chat; on its rounds it may. */
    @Override
    public boolean allowsChatWhileWalking() {
        return duty == Duty.ROUNDS || duty == Duty.PRAYER || duty == Duty.NONE;
    }

    /** A throw or a chat in progress is finished before routine business (a snack, a top-up) cuts in. */
    @Override
    public boolean allowsRoutineInterruption() {
        return cast == null && talk == null && duty != Duty.RETREAT;
    }

    // =========================================================================
    // Choosing what to do
    // =========================================================================

    private boolean chooseDuty(Context ctx, ProfessionSettings settings) {
        // A fight on: no food runs, chats or rounds; it waits behind the guards.
        if (onCall) {
            beginStandby(ctx);
            return true;
        }
        // A night call heals and nothing else; half a minute with nobody to
        // heal and it goes back to bed.
        if (nightCall) {
            if (++nightIdleTicks > ClericCallOut.QUIET_TICKS) return false;
            label(ctx, "on call");
            if (nightIdleTicks % 40 == 0) lookAtSomeoneNear(ctx);
            return true;
        }
        long now = ctx.level().getGameTime();
        if (settings.getBool(ClericSettingsSchema.BRING_FOOD) && !foodTripsResting(ctx, now)) {
            NpcEntity hungry = ClericCare.pickFoodRecipient(ctx, false);
            if (hungry != null) {
                beginVisit(ctx, Duty.FEED, hungry);
                return true;
            }
        }
        if (settings.getBool(ClericSettingsSchema.CHEER_UP)) {
            NpcEntity sad = ClericCare.pickSadVillager(ctx);
            if (sad != null) {
                beginVisit(ctx, Duty.CHEER, sad);
                return true;
            }
        }
        if (settings.getBool(ClericSettingsSchema.MORNING_PRAYER) && prayerDue(ctx)) {
            beginPrayer(ctx);
            return true;
        }
        if (settings.getBool(ClericSettingsSchema.ROUNDS) && roundsStops < ROUNDS_STOPS_PER_SESSION) {
            beginRounds(ctx);
            return duty == Duty.ROUNDS;
        }
        return false;
    }

    private Status endSession(Context ctx) {
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        return workedThisSession ? Status.DONE : Status.FAILED;
    }

    /** Drops whatever duty is under way: walk, claim, pose. Not a throw or chat. */
    private void leaveDuty(Context ctx) {
        cancelMove(ctx);
        if (patientId != null) ClericCare.release(ctx.npc(), patientId);
        patientId = null;
        if (visitee != null) ClericCare.releaseVisit(ctx.npc(), visitee.getUUID());
        visitee = null;
        if (roundsStop != null) ClericRounds.release(ctx.npc());
        roundsStop = null;
        roundsSettle = -1;
        // Called away before it began to read: another cleric may still lead the prayer.
        if (duty == Duty.PRAYER && prayerTicks < 0) ClericCare.releasePrayer(ctx.mgr().getVillageId(), ctx.npc());
        roundsWait = -1;
        prayerSpot = null;
        retreatGoal = null;
        standoff = null;
        standoffAge = 0;
        standbyGuard = null;
        standbyRetry = 0;
        VillagerAnimationState pose = ctx.npc().getAnimation();
        if (pose == VillagerAnimationState.READ || pose == VillagerAnimationState.CAST) {
            ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        }
        prayerTicks = -1;
        ctx.npc().setRunning(false);
        hurrying = false;
        duty = Duty.NONE;
        dutyTicks = 0;
        noSightTicks = 0;
        talkRefusals = 0;
    }

    // =========================================================================
    // Healing
    // =========================================================================

    private void beginHeal(Context ctx, LivingEntity patient) {
        if (fetch != null) {
            fetch.stop(ctx);
            fetch = null;
        }
        leaveDuty(ctx);
        nightIdleTicks = 0;
        duty = Duty.HEAL;
        patientId = patient.getUUID();
        ClericCare.claim(ctx.npc(), patient, ctx.level().getGameTime());
        diary(ctx, String.format("CLERIC patient %s (hp %.1f/%.1f, %.1f blocks)", ClericCare.nameOf(patient),
                patient.getHealth(), patient.getMaxHealth(), Math.sqrt(ctx.npc().distanceToSqr(patient))));
        // Players in town were just told this villager was hurt: say help is on the way.
        if (patient instanceof NpcEntity npc && ClericCare.brainOf(ctx.mgr(), npc) != null
                && VillageDamageAlerts.recentlyAlertedHurt(ctx.mgr(), npc.getNpcId(),
                        ctx.level().getGameTime(), 20L * 30L)) {
            VillageDamageAlerts.announceHelpComing(ctx.mgr(), ctx.level(), ctx.npc(), npc);
        }
    }

    private void tickHeal(Context ctx, ProfessionSettings settings, int level) {
        LivingEntity patient = ClericCare.resolve(ctx, patientId);
        int startAt = ClericCare.startHealing(ctx.npc(), settings);
        if (patient == null || !ClericCare.needsHealing(ctx, patient, startAt, level)) {
            // Well again, gone or dead: look for the next one straight away.
            leaveDuty(ctx);
            patientCheck = 0;
            return;
        }
        long now = ctx.level().getGameTime();
        ClericCare.claim(ctx.npc(), patient, now);
        String name = ClericCare.nameOf(patient);

        if (ClericCare.inThrowRange(ctx.npc(), patient)) {
            cancelMove(ctx);
            label(ctx, "healing " + name);
            cast = new HealingCast(ctx, patient, level, settings);
            noSightTicks = 0;
            return;
        }
        // The field medic goes to a guard in a fight anyway: not to the guard,
        // but to a spot a few blocks behind it, away from the monster.
        Mob threat = ClericCallOut.nearestMonster(ctx.level(), patient, ClericCare.DANGER_RADIUS);
        if (threat != null && brave && ClericCare.isDefender(patient)) {
            if (dutyTicks > APPROACH_GIVE_UP_TICKS) {
                giveUpBriefly(ctx, patient.getUUID(), name, "the walk took too long");
                return;
            }
            if (standoff == null || --standoffAge <= 0) {
                standoffAge = STANDOFF_RECHECK_TICKS;
                standoff = ClericCallOut.standoffSpot(ctx.level(), patient, threat, ctx.npc(), STANDOFF_DIST);
            }
            if (standoff == null) {
                diary(ctx, "CLERIC no safe spot behind " + name + "; looking again in a moment");
                ClericCare.markUnreachable(ctx.npc(), patient.getUUID(), now, DANGER_SKIP_TICKS / 2);
                leaveDuty(ctx);
                return;
            }
            if (ClericCare.badlyHurt(patient)) hurrying = true;
            label(ctx, (hurrying ? "running to " : "going to ") + name);
            MoveActivity.Status status = walkTo(ctx, standoff,
                    hurrying ? ClericBalance.RUN_SPEED : VillagerModel.WALK_SPEED, hurrying);
            if (status == MoveActivity.Status.FAILED) {
                giveUpBriefly(ctx, patient.getUUID(), name, "no way behind them");
            } else if (status == MoveActivity.Status.DONE) {
                if (++noSightTicks > NO_SIGHT_GIVE_UP_TICKS) giveUpBriefly(ctx, patient.getUUID(), name, "no clear throw");
            }
            return;
        }
        // Walking up to a fight only ends in running away from it and back.
        // Not for the field medic and a guard whose only monsters are out of
        // its sight (in a cave below, behind a wall): it walks straight up.
        if (!(brave && ClericCare.isDefender(patient)) && ClericCare.dangerNear(ctx.level(), patient)) {
            diary(ctx, "CLERIC " + name + " has a monster close by; waiting for it to be safe");
            ClericCare.markUnreachable(ctx.npc(), patient.getUUID(), now, DANGER_SKIP_TICKS);
            leaveDuty(ctx);
            return;
        }
        if (dutyTicks > APPROACH_GIVE_UP_TICKS) {
            giveUpOn(ctx, patient.getUUID(), name, "the walk took too long");
            return;
        }

        if (ClericCare.badlyHurt(patient)) hurrying = true;
        label(ctx, (hurrying ? "running to " : "going to ") + name);
        MoveActivity.Status status = walkTo(ctx, patient.blockPosition(),
                hurrying ? ClericBalance.RUN_SPEED : VillagerModel.WALK_SPEED, hurrying);
        if (status == MoveActivity.Status.FAILED) {
            giveUpOn(ctx, patient.getUUID(), name, "no way there");
        } else if (status == MoveActivity.Status.DONE) {
            // Beside them, yet no clear throw: a fence, a window, a corner.
            if (++noSightTicks > NO_SIGHT_GIVE_UP_TICKS) giveUpOn(ctx, patient.getUUID(), name, "no clear throw");
        }
    }

    /**
     * Bringing food to a villager comes before healing that same one, unless it
     * is nearly dead: food ends its starving, a heal only uses one of its three (10-19).
     */
    private boolean feedingFirst(LivingEntity patient) {
        return duty == Duty.FEED && patient == visitee && !ClericCare.nearlyDead(patient);
    }

    /** Whether {@code other} is so much worse off than the current patient that the cleric goes to it instead. */
    private boolean worseOffThanPatient(Context ctx, LivingEntity other) {
        if (other.getUUID().equals(patientId)) return false;
        LivingEntity patient = ClericCare.resolve(ctx, patientId);
        return patient == null || ClericCare.clearlyWorse(ctx.npc(), other, patient,
                ClericCare.settings(ctx.npc()).getBool(ClericSettingsSchema.GUARDS_FIRST));
    }

    /** A guard in a fight it could not get behind: the fight moves, so it looks again in ten seconds, not a minute. */
    private void giveUpBriefly(Context ctx, UUID who, String name, String why) {
        diary(ctx, "CLERIC could not get to " + name + " in the fight (" + why + "); looking again in 10 s");
        ClericCare.markUnreachable(ctx.npc(), who, ctx.level().getGameTime(), DANGER_SKIP_TICKS);
        leaveDuty(ctx);
    }

    private void giveUpOn(Context ctx, UUID who, String name, String why) {
        diary(ctx, "CLERIC could not reach " + name + " (" + why + "); leaving them for a minute");
        ClericCare.markUnreachable(ctx.npc(), who, ctx.level().getGameTime(), ClericCare.UNREACHABLE_TICKS);
        leaveDuty(ctx);
    }

    // =========================================================================
    // Bringing food and cheering up
    // =========================================================================

    private void beginVisit(Context ctx, Duty kind, NpcEntity who) {
        leaveDuty(ctx);
        duty = kind;
        visitee = who;
        ClericCare.claimVisit(ctx.npc(), who, ctx.level().getGameTime());
        diary(ctx, "CLERIC " + (kind == Duty.FEED ? "bringing food to " : "going to cheer up ")
                + ClericCare.nameOf(who));
        if (kind == Duty.FEED && spareFood(ctx) <= 0) {
            int want = ClericBalance.FOOD_KEPT + ClericBalance.FOOD_GIVEN
                    - StorageSupplyRestocker.countCarriedFood(ctx);
            fetch = FetchFromStorageActivity.forResource(StorageBuilding.ResourceType.FOOD,
                    Math.max(1, want), "cleric_food");
            label(ctx, "getting food for " + ClericCare.nameOf(who));
        }
    }

    private void tickFoodTrip(Context ctx) {
        Status status = fetch.tickOuter(ctx);
        if (status == Status.RUNNING) return;
        fetch.stop(ctx);
        fetch = null;
        if (status == Status.FAILED || spareFood(ctx) <= 0) {
            diary(ctx, "CLERIC found no food to bring; resting that for two minutes");
            FOOD_TRIP_REST.put(ctx.npc().getUUID(), ctx.level().getGameTime() + FOOD_TRIP_COOLDOWN_TICKS);
            leaveDuty(ctx);
        }
    }

    private boolean foodTripsResting(Context ctx, long now) {
        Long until = FOOD_TRIP_REST.get(ctx.npc().getUUID());
        return until != null && until > now && until - now <= FOOD_TRIP_COOLDOWN_TICKS;
    }

    /** Food it can give away and still keep its own few. */
    private static int spareFood(Context ctx) {
        return StorageSupplyRestocker.countCarriedFood(ctx) - ClericBalance.FOOD_KEPT;
    }

    private void tickVisit(Context ctx, int level) {
        NpcEntity who = visitee;
        VillagerBrain brain = who == null ? null : ClericCare.brainOf(ctx.mgr(), who);
        long now = ctx.level().getGameTime();
        long day = EveningSchedule.dayIndex(ctx.level().getDayTime());
        if (who == null || brain == null || !who.isAlive()
                || (duty == Duty.FEED && !brain.wantsFoodBrought(who))
                || (duty == Duty.CHEER && (brain.needs().wellbeing > com.alex.test.village.stats.WellbeingSystem.SAD_THRESHOLD + 5f
                        || ClericCare.cheeredToday(who, day)))) {
            leaveDuty(ctx);
            return;
        }
        String name = ClericCare.nameOf(who);
        if (dutyTicks > APPROACH_GIVE_UP_TICKS) {
            giveUpOn(ctx, who.getUUID(), name, "the walk took too long");
            return;
        }
        double dx = who.getX() - ctx.npc().getX();
        double dz = who.getZ() - ctx.npc().getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        boolean level0 = Math.abs(who.getY() - ctx.npc().getY()) < 1.0;

        if (flat <= TALK_DIST && level0 && ctx.npc().hasLineOfSight(who)) {
            cancelMove(ctx);
            if (!brain.isFreeToChat()) {
                // Busy for a moment (eating, a chat of its own): wait close by.
                if (++talkRefusals > TALK_REFUSALS * 20) giveUpOn(ctx, who.getUUID(), name, "it would not stop");
                return;
            }
            CareTalk next = duty == Duty.FEED
                    ? CareTalk.feed(who, brain, giftStack(ctx))
                    : CareTalk.cheer(who, brain, ClericBalance.cheerUpAmount(level));
            if (next.begin(ctx)) {
                talk = next;
                label(ctx, (duty == Duty.FEED ? "feeding " : "cheering up ") + name);
            } else if (++talkRefusals > TALK_REFUSALS * 20) {
                giveUpOn(ctx, who.getUUID(), name, "it would not stop");
            }
            return;
        }
        if (duty == Duty.FEED && brain.isStarving()) hurrying = true;
        boolean hurry = hurrying;
        label(ctx, (duty == Duty.FEED ? "bringing food to " : "visiting ") + name);
        if (flat <= FINAL_STEP_DIST && level0 && ctx.npc().hasLineOfSight(who)) {
            // The last steps straight at them: the walk stops up to two tiles
            // short, a little too far to talk across.
            if (move != null) cancelMove(ctx);
            // Still running for these steps when in a hurry: they are taken at
            // the run's pace, and with the walk clip the legs fell behind.
            ctx.npc().setRunning(hurry);
            ctx.npc().getMoveControl().setWantedPosition(who.getX(), who.getY(), who.getZ(),
                    hurry ? ClericBalance.RUN_SPEED : VillagerModel.WALK_SPEED);
            return;
        }
        MoveActivity.Status status = walkTo(ctx, who.blockPosition(),
                hurry ? ClericBalance.RUN_SPEED : VillagerModel.WALK_SPEED, hurry);
        if (status == MoveActivity.Status.FAILED) giveUpOn(ctx, who.getUUID(), name, "no way there");
    }

    /** What the cleric holds up and hands over: its first spare food, as many as it gives. */
    private static ItemStack giftStack(Context ctx) {
        for (ItemStack stack : ctx.npc().getInventory().getSlots()) {
            if (!stack.isEmpty() && ctx.npc().getInventory().mayUse(stack)
                    && VillageItemOwnership.villagerFood(stack, ctx.npc()) != null) {
                return stack.copyWithCount(Math.min(ClericBalance.FOOD_GIVEN, Math.max(1, spareFood(ctx))));
            }
        }
        return ItemStack.EMPTY;
    }

    private void finishTalk(Context ctx, int level) {
        CareTalk done = talk;
        talk = null;
        done.stop(ctx);
        NpcEntity who = done.partner();
        String name = ClericCare.nameOf(who);
        long now = ctx.level().getGameTime();
        if (done.succeeded()) {
            workedThisSession = true;
            if (done.kind() == CareTalk.Kind.FEED) {
                ClericCare.markFed(who, now);
                ctx.brain().onClericCare(ctx, ClericBalance.POINTS_FOOD_DELIVERED, "brought food to " + name);
            } else {
                ClericCare.markCheered(who, EveningSchedule.dayIndex(ctx.level().getDayTime()));
                ctx.brain().onClericCare(ctx, ClericBalance.POINTS_CHEERED_UP, "cheered up " + name);
            }
        } else {
            diary(ctx, "CLERIC chat with " + name + " was broken off");
            ClericCare.markUnreachable(ctx.npc(), who.getUUID(), now, DANGER_SKIP_TICKS * 3);
        }
        leaveDuty(ctx);
    }

    // =========================================================================
    // Praying
    // =========================================================================

    private static boolean prayerDue(Context ctx) {
        long dayTime = Math.floorMod(ctx.level().getDayTime(), (long) EveningSchedule.DAY_TICKS);
        if (dayTime >= MORNING_END_DAY_TIME) return false;
        long day = EveningSchedule.dayIndex(ctx.level().getDayTime());
        if (ctx.npc().getBrainData().contains(TAG_PRAYER_DAY)
                && ctx.npc().getBrainData().getLong(TAG_PRAYER_DAY) == day) {
            return false;
        }
        // Another cleric leads it today; this one keeps the rounds covered.
        if (ClericCare.prayerTakenByOther(ctx.mgr().getVillageId(), ctx.npc(), day)) return false;
        for (Building b : ctx.mgr().getBuildings()) {
            if (b instanceof TownHallBuilding && b.isValid()) return true;
        }
        return false;
    }

    private void beginPrayer(Context ctx) {
        leaveDuty(ctx);
        duty = Duty.PRAYER;
        prayerSpot = ctx.mgr().getTownHallSpot(ctx.npc().blockPosition());
        // Whatever happens next, it does not try again today.
        long day = EveningSchedule.dayIndex(ctx.level().getDayTime());
        ctx.npc().getBrainData().putLong(TAG_PRAYER_DAY, day);
        ClericCare.claimPrayer(ctx.mgr().getVillageId(), ctx.npc(), day);
        if (prayerSpot == null) {
            leaveDuty(ctx);
            return;
        }
        label(ctx, "going to pray");
        diary(ctx, "CLERIC morning prayer at " + prayerSpot.toShortString());
    }

    private void tickPrayer(Context ctx, int level) {
        if (prayerTicks < 0) {
            MoveActivity.Status status = walkTo(ctx, prayerSpot, VillagerModel.WALK_SPEED, false);
            if (status == MoveActivity.Status.FAILED) {
                diary(ctx, "CLERIC could not reach the Town Hall to pray");
                leaveDuty(ctx);
            } else if (status == MoveActivity.Status.DONE) {
                cancelMove(ctx);
                prayerTicks = 0;
                label(ctx, "praying");
            }
            return;
        }
        ctx.npc().holdStillForAnimation();
        if (ctx.npc().getAnimation() != VillagerAnimationState.READ) {
            ctx.npc().setAnimation(VillagerAnimationState.READ);
        }
        long day = EveningSchedule.dayIndex(ctx.level().getDayTime());
        if (prayerTicks % 20 == 0) blessVillagersNear(ctx, PRAYER_NEAR, day, false);
        if (++prayerTicks < PRAYER_READ_TICKS) return;

        // The blessing that ends it.
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        NpcEntity cleric = ctx.npc();
        ctx.level().sendParticles(ParticleTypes.HAPPY_VILLAGER, cleric.getX(), cleric.getY(1.2), cleric.getZ(),
                40, 2.5, 1.0, 2.5, 0.0);
        ctx.level().sendParticles(ParticleTypes.HEART, cleric.getX(), cleric.getY(1.6), cleric.getZ(),
                6, 0.6, 0.4, 0.6, 0.0);
        ctx.level().playSound(null, cleric.getX(), cleric.getY(), cleric.getZ(),
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1.5f, 1.0f);
        int blessed = blessVillagersNear(ctx, BLESSING_RADIUS, day, true);
        workedThisSession = true;
        ctx.brain().onClericCare(ctx, ClericBalance.POINTS_MORNING_PRAYER,
                "morning prayer (" + blessed + " blessed)");
        leaveDuty(ctx);
    }

    /**
     * Villagers within {@code radius} feel a little better, once a day each;
     * returns how many. Not those asleep (a night-shift guard sleeping by
     * day): they would wake to find the day's blessing already used.
     */
    private int blessVillagersNear(Context ctx, double radius, long day, boolean showAll) {
        int count = 0;
        AABB box = ctx.npc().getBoundingBox().inflate(radius, 4.0, radius);
        for (NpcEntity npc : ctx.level().getEntitiesOfClass(NpcEntity.class, box,
                e -> e != ctx.npc() && e.isAlive() && !e.isSleeping() && !ClericCare.isPreview(e))) {
            VillagerBrain brain = ClericCare.brainOf(ctx.mgr(), npc);
            if (brain == null || !ClericCare.blessOncePerDay(npc, day)) continue;
            brain.needs().addWellbeing(PRAYER_WELLBEING);
            npc.showBubble(ThoughtIcon.STATUS_HAPPY, false, 40);
            count++;
        }
        return count;
    }

    // =========================================================================
    // Rounds
    // =========================================================================

    private void beginRounds(Context ctx) {
        leaveDuty(ctx);
        ClericRounds.Stop stop = ClericRounds.pick(ctx);
        if (stop == null) {
            roundsStops = ROUNDS_STOPS_PER_SESSION;
            return;
        }
        duty = Duty.ROUNDS;
        roundsStop = stop;
        label(ctx, "on rounds");
    }

    private void tickRounds(Context ctx) {
        long now = ctx.level().getGameTime();
        ClericRounds.claim(ctx.npc(), roundsStop, now);
        if (roundsWait < 0) {
            if (roundsSettle < 0) {
                MoveActivity.Status status = walkTo(ctx, roundsStop.stand(), VillagerModel.WALK_SPEED, false);
                if (status == MoveActivity.Status.RUNNING) return;
                cancelMove(ctx);
                if (status == MoveActivity.Status.FAILED) {
                    // It used to try the same stop again next session.
                    diary(ctx, "CLERIC could not reach rounds stop " + roundsStop.stand().toShortString()
                            + "; leaving it for five minutes");
                    ClericRounds.unreachable(ctx.npc(), roundsStop, now);
                    roundsStops++;
                    leaveDuty(ctx);
                    return;
                }
                roundsSettle = 0;
            }
            // The walk counts as there up to two blocks short, which beside a
            // door can be the doorway itself: the last steps straight onto the
            // spot, or as close as it gets in a second and a half.
            BlockPos spot = roundsStop.stand();
            double dx = spot.getX() + 0.5 - ctx.npc().getX();
            double dz = spot.getZ() + 0.5 - ctx.npc().getZ();
            if (dx * dx + dz * dz > SETTLE_DIST * SETTLE_DIST && ++roundsSettle < SETTLE_TICKS) {
                ctx.npc().getMoveControl().setWantedPosition(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5,
                        VillagerModel.WALK_SPEED);
                return;
            }
            roundsWait = ROUNDS_WAIT_MIN + ctx.npc().getRandom().nextInt(ROUNDS_WAIT_SPREAD);
            ctx.npc().setAnimation(VillagerAnimationState.IDLE);
            return;
        }
        // Waiting at the stop, keeping an eye on whoever is about.
        if (roundsWait % 40 == 0) lookAtSomeoneNear(ctx);
        if (--roundsWait > 0) return;
        workedThisSession = true;
        ClericRounds.finished(ctx.npc(), roundsStop);
        roundsStops++;
        ctx.brain().onClericCare(ctx, ClericBalance.POINTS_ROUNDS_STOP, "rounds stop");
        leaveDuty(ctx);
    }

    private void lookAtSomeoneNear(Context ctx) {
        AABB box = ctx.npc().getBoundingBox().inflate(8.0, 3.0, 8.0);
        List<NpcEntity> near = ctx.level().getEntitiesOfClass(NpcEntity.class, box,
                e -> e != ctx.npc() && e.isAlive());
        if (near.isEmpty()) return;
        NpcEntity who = near.get(ctx.npc().getRandom().nextInt(near.size()));
        ctx.npc().getLookControl().setLookAt(who, 30f, 30f);
    }

    // =========================================================================
    // In a fight: standing by and keeping its distance
    // =========================================================================

    private void beginStandby(Context ctx) {
        leaveDuty(ctx);
        duty = Duty.STANDBY;
        label(ctx, "standing by");
        diary(ctx, "CLERIC standing by for the fight");
    }

    /**
     * Waiting a few blocks behind the guard nearest a fight (or, with the bell
     * rung and no fight in sight, the nearest guard), ready for the next one
     * hurt: the patient check above takes it from here. Ends once the
     * emergency is over or it is too hurt to stay out.
     */
    private void tickStandby(Context ctx) {
        if (!onCall) {
            leaveDuty(ctx);
            return;
        }
        // Which guard, and the spot behind it, once a second: looking every
        // tick searched round every guard in the village for monsters, per cleric.
        if (--standoffAge <= 0) {
            standoffAge = STANDOFF_RECHECK_TICKS;
            NpcEntity guard = ClericCallOut.nearestFight(ctx);
            if (guard == null) guard = ClericCallOut.nearestDefender(ctx);
            standbyGuard = guard;
            if (guard != null) {
                Mob threat = ClericCallOut.nearestMonster(ctx.level(), guard, ClericCare.DANGER_RADIUS);
                BlockPos spot = ClericCallOut.standoffSpot(ctx.level(), guard, threat, ctx.npc(), STANDBY_DIST);
                if (spot != null) standoff = spot;
            }
        }
        NpcEntity guard = standbyGuard;
        if (guard == null || !guard.isAlive()) {
            cancelMove(ctx);
            if (dutyTicks % 40 == 0) lookAtSomeoneNear(ctx);
            return;
        }
        // A route there just failed: wait a second rather than ask for a new
        // one every tick.
        if (standbyRetry > 0) {
            standbyRetry--;
            if (dutyTicks % 20 == 0) ctx.npc().getLookControl().setLookAt(guard, 30f, 30f);
            return;
        }
        if (standoff == null || ClericCallOut.flatDistSqr(ctx.npc(), standoff) <= STANDBY_SETTLED_SQ) {
            cancelMove(ctx);
            if (dutyTicks % 20 == 0) ctx.npc().getLookControl().setLookAt(guard, 30f, 30f);
            return;
        }
        boolean run = ClericCallOut.flatDistSqr(ctx.npc(), standoff) > STANDBY_RUN_SQ;
        MoveActivity.Status status = walkTo(ctx, standoff,
                run ? ClericBalance.RUN_SPEED : VillagerModel.WALK_SPEED, run);
        if (status == MoveActivity.Status.FAILED) {
            // That spot cannot be walked to: stay put, and a new spot is
            // worked out when the second is up.
            cancelMove(ctx);
            standoff = null;
            standbyRetry = STANDOFF_RECHECK_TICKS;
            standoffAge = STANDOFF_RECHECK_TICKS;
        }
    }

    /** A monster came too close: break off and back away from it, at a run. */
    private void beginRetreat(Context ctx, Mob from) {
        if (cast != null) {
            cast.stop(ctx, true);
            cast = null;
        }
        if (talk != null) {
            talk.stop(ctx);
            talk = null;
        }
        if (fetch != null) {
            fetch.stop(ctx);
            fetch = null;
        }
        leaveDuty(ctx);
        duty = Duty.RETREAT;
        retreatGoal = ClericCallOut.standoffSpot(ctx.level(), ctx.npc(), from, ctx.npc(), RETREAT_DIST);
        if (retreatGoal == null) {
            // Nowhere clear behind it: no longer brave for a few seconds, so
            // the brain sends it for shelter like anyone else. It used to run
            // to the nearest guard, mostly the one fighting this very monster.
            diary(ctx, "CLERIC cornered by " + from.getName().getString() + "; running for shelter");
            ClericCallOut.markCornered(ctx.npc(), ctx.level().getGameTime());
            duty = Duty.NONE;
            return;
        }
        label(ctx, "backing away");
        diary(ctx, "CLERIC backing away from " + from.getName().getString());
    }

    private void tickRetreat(Context ctx) {
        MoveActivity.Status status = walkTo(ctx, retreatGoal, ClericBalance.RUN_SPEED, true);
        if (status != MoveActivity.Status.RUNNING || dutyTicks > RETREAT_MAX_TICKS) {
            leaveDuty(ctx);
            patientCheck = 0;
            // Cornered (no route away): a second before it tries again, not a
            // new route every tick.
            keepAwayCheck = status == MoveActivity.Status.FAILED ? STANDOFF_RECHECK_TICKS : 0;
        }
    }

    // =========================================================================
    // Walking
    // =========================================================================

    /**
     * Walks or runs toward {@code goal}, asking for a new route when the goal
     * has drifted more than three blocks, has moved at all once
     * {@link #REPATH_INTERVAL} ticks are up, or the last walk has ended.
     *
     * <p>The new route is asked for without stopping first: the walk under way
     * carries on until the new one is ready (VillagerModel swaps them over), as
     * a flee's does. Stopping the old walk halted the cleric while the route was
     * worked out, every 1.5 s and whenever the patient moved: a stop-start run.
     * Not while swimming out of water, which the walk under way already handles.
     */
    private MoveActivity.Status walkTo(Context ctx, BlockPos goal, float speed, boolean running) {
        ctx.npc().setRunning(running);
        // The throw pose is held between throws; walking off ends it.
        if (ctx.npc().getAnimation() == VillagerAnimationState.CAST) {
            ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        }
        boolean stale = move == null || moveGoal == null
                || (!ctx.model().isEscapingWater()
                        && (moveSpeed != speed || moveGoal.distSqr(goal) > REPATH_DRIFT_SQ
                                || (--repathTimer <= 0 && (!moveGoal.equals(goal)
                                        || moveStatus != MoveActivity.Status.RUNNING))));
        if (stale) {
            moveGoal = goal;
            moveSpeed = speed;
            repathTimer = REPATH_INTERVAL;
            move = new MoveActivity(goal, speed);
        }
        MoveActivity.Status status = move.tickOuter(ctx);
        moveStatus = status;
        if (status != MoveActivity.Status.RUNNING) {
            ctx.npc().setRunning(false);
        }
        return status;
    }

    private void cancelMove(Context ctx) {
        if (move != null) {
            move.stop(ctx);
            move = null;
        }
        moveGoal = null;
        ctx.npc().setRunning(false);
    }

    // =========================================================================
    // Misc
    // =========================================================================

    /** What it is doing, over its head after its name; only sent when it changes. */
    private void label(Context ctx, String text) {
        if (text.equals(shownLabel)) return;
        shownLabel = text;
        ctx.npc().setActivityLabel(text);
    }

    private static void diary(Context ctx, String message) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + message);
    }

    /**
     * Brain pre-check: anything at all to do. With rounds on there always is;
     * otherwise someone to heal, feed or cheer up, or the morning prayer.
     */
    public static boolean hasWork(Context ctx) {
        ProfessionSettings settings = ClericCare.settings(ctx.npc());
        if (!settings.getBool(ClericSettingsSchema.WORK_ENABLED)) return false;
        if (settings.getBool(ClericSettingsSchema.ROUNDS) || ClericCallOut.staysOut(ctx, false)) return true;
        int level = ctx.brain().clericSkillLevel();
        if (ClericCare.pickPatient(ctx, settings, level, false) != null) return true;
        if (settings.getBool(ClericSettingsSchema.BRING_FOOD) && ClericCare.pickFoodRecipient(ctx, false) != null) {
            return true;
        }
        if (settings.getBool(ClericSettingsSchema.CHEER_UP) && ClericCare.pickSadVillager(ctx) != null) return true;
        return settings.getBool(ClericSettingsSchema.MORNING_PRAYER) && prayerDue(ctx);
    }

    /**
     * Work worth cutting leisure short for: someone hurt, or a villager with an
     * empty hunger bar and nothing to eat.
     */
    public static boolean hasUrgentWork(Context ctx) {
        ProfessionSettings settings = ClericCare.settings(ctx.npc());
        if (!settings.getBool(ClericSettingsSchema.WORK_ENABLED)) return false;
        if (ClericCallOut.staysOut(ctx, false)) return true;
        int level = ctx.brain().clericSkillLevel();
        if (ClericCare.pickPatient(ctx, settings, level, false) != null) return true;
        return settings.getBool(ClericSettingsSchema.BRING_FOOD) && ClericCare.pickFoodRecipient(ctx, true) != null;
    }
}

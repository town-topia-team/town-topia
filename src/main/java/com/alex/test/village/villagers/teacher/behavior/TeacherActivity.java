package com.alex.test.village.villagers.teacher.behavior;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.EveningSchedule;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.SchoolBuilding;
import com.alex.test.village.social.MemoryTopic;
import com.alex.test.village.social.ProfessionTopics;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.teacher.PupilRecord;
import com.alex.test.village.villagers.teacher.SchoolClasses;
import com.alex.test.village.villagers.teacher.SchoolDay;
import com.alex.test.village.villagers.teacher.SchoolSeats;
import com.alex.test.village.villagers.teacher.SchoolSubjects;
import com.alex.test.village.villagers.teacher.TeacherBalance;
import com.alex.test.village.villagers.teacher.settings.TeacherSettingsSchema;
import com.alex.test.village.villagers.guard.behavior.GuardAlertSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The Teacher's working day, by the school timetable ({@link SchoolDay}).
 *
 * <p>Each teacher has its own class at its own lectern ({@link SchoolClasses}).
 * One stretch of work runs from a bell to lunch, or from the bell after lunch
 * to the home bell:
 * <ul>
 *   <li><b>Bell</b>: at the start of each part of the day the teacher rings a
 *       hand bell at the school door (a held bell and its sound, never a bell
 *       block: a player ringing one sounds the village alarm). Children
 *       playing nearby drop what they are doing and come.</li>
 *   <li><b>Lessons</b>: each lesson is about one job ({@link SchoolSubjects}).
 *       The teacher moves through stages: explaining with the job's tool held
 *       up, reading aloud, walking the rows to a child, taking a child's
 *       question, quiet work. Tired or sad children sometimes daydream and
 *       learn less until the teacher comes over.</li>
 *   <li><b>Recess</b>: the class goes out to the yard to play (tag, a circle
 *       dance, or free play) while the teacher watches from outside the door.</li>
 *   <li><b>Field trip</b>: once a week lesson 3 is a walk to a workplace that
 *       matches the subject, to watch, and back.</li>
 *   <li><b>Reading hour</b>: everyone reads; bookshelves in the school help.</li>
 *   <li><b>After school</b>: the teacher marks work at its lectern for a minute.
 *       A village without children gets this once a day as lesson preparation.</li>
 * </ul>
 *
 * <p>Each lesson unit pays every child in class ({@link PupilRecord}, at most a
 * day's limit) and the teacher its own experience (its 1..100 track).
 */
public final class TeacherActivity extends WorkActivity {

    private static final int UNIT_TICKS = TeacherBalance.UNIT_TICKS;
    /** Longest wait for the first child once the class has opened. */
    private static final int OPENING_WAIT_TICKS = 1200;
    /** Longest an empty class stays open for children said to be on their way. */
    private static final int MAX_EMPTY_WAIT_TICKS = 2400;
    /** After a class nobody came to, no new one for this long; doubled for each one in a row, up to 4x. */
    private static final int QUIET_AFTER_EMPTY_TICKS = 2400;
    /** After it could not reach its school or its spot: one try a minute, never longer. */
    private static final int PAUSE_AFTER_FAILED_TICKS = 1200;
    /** After-school marking, or lesson preparation in a village without children. */
    private static final int MARKING_TICKS = 1200;
    private static final int MARKING_RECHECK_TICKS = 100;
    private static final int RING_TICKS = 50;
    /** The bell counts as rung for this part of the day if it rang this soon after it began. */
    private static final int BELL_GRACE_TICKS = 200;
    /** A field trip goes no further than this from the school door. */
    private static final int TRIP_RANGE = 64;
    private static final int TRIP_GATHER_TICKS = 600;
    private static final int TRIP_WATCH_TICKS = 1200;
    /** A workplace counts as busy, worth more as a trip, with one of its workers this near its door. */
    private static final int TRIP_WORKER_NEAR = 16;
    /** The class watches a worker from this far off, so it does not stand in the way. */
    private static final int TRIP_WATCH_DISTANCE = 4;
    /** Brain data: the job last week's field trip was about, so this week's is likely another. */
    private static final String TAG_LAST_TRIP = "TeacherLastTrip";
    /** Two teachers in one school: the bell counts as rung for both this soon after. */
    private static final int SHARED_BELL_TICKS = 40;
    private static final int YARD_DISTANCE = 3;

    private enum State { TO_BELL, RING, TO_STAND, CLASS, TO_YARD, YARD, TRIP_OUT, TRIP_WAIT, TRIP_WATCH, MARKING }

    private enum Stage { EXPLAIN, READ_ALOUD, WALK_ROWS, QUESTION, QUIET_WORK }

    @Nullable private MoveActivity move;
    @Nullable private SchoolBuilding school;
    @Nullable private SchoolClasses.ClassKey key;
    @Nullable private BlockPos stand;
    @Nullable private BlockPos yardSpot;
    @Nullable private BlockPos tripSpot;
    /** What the trip watches: the workplace door, or where the worker was. */
    @Nullable private BlockPos tripLook;
    /** The worker the trip watches, when one is at it. */
    @Nullable private UUID tripWorker;
    private String tripName = "";
    private State state = State.TO_STAND;
    /** Standing inside the school this tick (for the alarm). */
    private boolean indoors;
    /** The alarm kept this recess indoors: the class reads in its seats. */
    private boolean indoorRecess;
    /** Where to go once the bell has rung. */
    private State afterBell = State.TO_STAND;
    private SchoolDay.Phase phase = SchoolDay.Phase.OFF;
    private boolean recessOn;
    private int shelves;
    private int level = 1;

    private int unitTimer = UNIT_TICKS;
    private int ringTicks;
    private int stateTicks;
    private int emptyTicks;
    private int waitedTicks;
    private int centreTimer;
    private boolean pupilsCame;
    private boolean taughtThisSession;
    private int lastPaidCount = -1;
    private Vec3 lookAt = Vec3.ZERO;

    @Nullable private Stage stage;
    private int stageTicks;
    private int stageLength;
    @Nullable private UUID stageKid;
    /** WALK_ROWS: 0 walking over, 1 beside the child, 2 walking back. */
    private int rowStep;

    // =========================================================================
    // WorkActivity contract
    // =========================================================================

    @Override
    protected void start(Context ctx) {
        key = SchoolClasses.resolveTeacherClass(ctx, true);
        if (key == null) {
            pauseAWhile(ctx, "no free lectern in a school it can reach");
            return;
        }
        school = SchoolClasses.usableSchool(ctx, key);
        if (school == null) {
            key = null;
            pauseAWhile(ctx, "cannot get into its school");
            return;
        }
        SchoolSeats.Layout layout = SchoolSeats.layout(ctx.level(), ctx.mgr().getGraph(), school,
                key.lectern(), SchoolSeats.otherLecternsInUse(ctx.level(), school, key.lectern()));
        stand = layout.teacherStand();
        lookAt = layout.classCentre();
        if (stand == null) {
            pauseAWhile(ctx, "no room to stand beside lectern " + key.lectern().toShortString());
            key = null;
            return;
        }
        level = ctx.brain().teacherSkillLevel();
        recessOn = settings(ctx).getBool(TeacherSettingsSchema.RECESS);
        shelves = countShelves(ctx, school);
        phase = phaseNow(ctx);

        boolean pupils = SchoolClasses.anyPupilFor(ctx, school, key);
        if (!phase.atSchool() || !pupils) {
            state = State.MARKING;
            walkTo(ctx, stand);
            diary(ctx, "TEACHER heading to lectern " + key.lectern().toShortString()
                    + (pupils ? " to mark today's work" : " to prepare lessons (no children)"));
            return;
        }

        SchoolClasses.open(ctx.level(), key, me(ctx));
        // A fresh class starts in its seats, whatever an old one left behind.
        SchoolClasses.setMode(ctx.level(), key, SchoolClasses.Mode.CLASS);
        if (phase == SchoolDay.Phase.RECESS && alarmed(ctx)) {
            indoorRecess = true;
            ringIfDue(ctx, State.TO_STAND);
        } else if (phase == SchoolDay.Phase.RECESS) {
            beginRecess(ctx);
            ringIfDue(ctx, State.TO_YARD);
        } else {
            if (phase.isLesson()) beginLesson(ctx);
            if (phase == SchoolDay.Phase.LESSON_3 && planTrip(ctx)) {
                ringIfDue(ctx, State.TRIP_OUT);
            } else {
                ringIfDue(ctx, State.TO_STAND);
            }
        }
        diary(ctx, "TEACHER off to school for " + phase.name().toLowerCase(Locale.ROOT) + ", lectern "
                + key.lectern().toShortString());
    }

    @Override
    protected Status doTick(Context ctx) {
        if (!settings(ctx).getBool(TeacherSettingsSchema.WORK_ENABLED)) return finish(ctx);
        if (key == null || school == null || stand == null) return finish(ctx);
        if (!school.isValid() || !school.getFunctionalBlocks().contains(key.lectern())) {
            diary(ctx, "TEACHER school or lectern gone, ending the lesson");
            return finish(ctx);
        }
        stateTicks++;
        indoors = isIndoors(ctx);
        if (state == State.MARKING) return tickMarking(ctx);

        SchoolClasses.open(ctx.level(), key, me(ctx));
        SchoolDay.Phase now = phaseNow(ctx);
        if (now != phase) {
            SchoolDay.Phase was = phase;
            phase = now;
            Status changed = onPhaseChange(ctx, was, now);
            if (changed != null) return changed;
        }

        // The alarm went while the class was out at recess (or on its way out):
        // everybody back inside.
        if (SchoolClasses.mode(ctx.level(), key) == SchoolClasses.Mode.RECESS && alarmed(ctx)) {
            callClassIn(ctx);
            return Status.RUNNING;
        }

        if (move != null) {
            Status status = move.tickOuter(ctx);
            if (status == Status.RUNNING) {
                if (state == State.TRIP_OUT) payUnit(ctx);
                return Status.RUNNING;
            }
            move.stop(ctx);
            move = null;
            if (status == Status.FAILED && state != State.CLASS) {
                return arriveFailed(ctx);
            }
            arrived(ctx);
            return Status.RUNNING;
        }

        return switch (state) {
            case RING -> tickRing(ctx);
            case CLASS -> tickClass(ctx);
            case YARD -> tickYard(ctx);
            case TRIP_WAIT, TRIP_WATCH -> tickTrip(ctx);
            default -> {
                // A walk that ended with nothing next: carry on from where it stands.
                arrived(ctx);
                yield Status.RUNNING;
            }
        };
    }

    @Override
    public void doStop(Context ctx) {
        if (move != null) { move.stop(ctx); move = null; }
        if (key != null) SchoolClasses.close(ctx.level(), key, me(ctx));
        ctx.npc().clearDisplayItem();
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.model().stopMoving();
    }

    @Override
    public boolean isMovingPhase() {
        return move != null;
    }

    /** A class is open (in its seats, out at recess or on a trip): it carries on while a player looks. */
    public boolean isHoldingClass() {
        return key != null && state != State.MARKING;
    }

    /** The children are waiting: no stopping for a chat. */
    @Override
    public boolean allowsChatWhileWalking() {
        return false;
    }

    /**
     * The alarm goes while the teacher is in the school (teaching, walking the
     * rows, ringing at the door, marking): the class stays put, indoors. Out at
     * recess it calls the class back in instead of running off alone; on a
     * field trip everybody shelters on their own.
     */
    @Override
    public boolean sheltersInPlace() {
        if (key == null || school == null) return false;
        return switch (state) {
            case TO_YARD, YARD -> true;
            case TRIP_OUT, TRIP_WAIT, TRIP_WATCH -> false;
            case TO_STAND -> indoors || indoorRecess;
            default -> indoors;
        };
    }

    // =========================================================================
    // The timetable
    // =========================================================================

    @Nullable
    private Status onPhaseChange(Context ctx, SchoolDay.Phase was, SchoolDay.Phase now) {
        if (move != null) { move.stop(ctx); move = null; }
        endStage(ctx);
        boolean onTrip = state == State.TRIP_OUT || state == State.TRIP_WAIT || state == State.TRIP_WATCH;
        switch (now) {
            case LESSON_1 -> beginLesson(ctx);
            case RECESS -> {
                if (alarmed(ctx)) {
                    // Nobody goes out while the alarm is on: the class reads in its seats.
                    indoorRecess = true;
                    SchoolClasses.setSubject(ctx.level(), key, null);
                    diary(ctx, "TEACHER alarm: recess indoors, the class reads");
                } else {
                    beginRecess(ctx);
                    ring(ctx, State.TO_YARD);
                }
            }
            case LESSON_2 -> {
                SchoolClasses.setMode(ctx.level(), key, SchoolClasses.Mode.CLASS);
                if (was == SchoolDay.Phase.RECESS && !indoorRecess) giveRecessJoy(ctx);
                indoorRecess = false;
                beginLesson(ctx);
                ring(ctx, State.TO_STAND);
            }
            case LESSON_3 -> {
                beginLesson(ctx);
                ring(ctx, State.TO_STAND);
            }
            case READING -> {
                if (onTrip) {
                    // The trip runs on into reading hour; walk back where it left off.
                    if (state == State.TRIP_OUT) walkTo(ctx, tripSpot);
                } else {
                    SchoolClasses.setSubject(ctx.level(), key, null);
                    diary(ctx, "TEACHER reading hour");
                    if (state == State.TO_STAND) walkTo(ctx, stand);
                }
            }
            case LUNCH -> {
                ringNow(ctx);
                diary(ctx, "TEACHER lunch bell, class off until " + SchoolDay.clock(SchoolDay.LESSON_3_START));
                return finish(ctx);
            }
            case AFTER, OFF -> {
                ringNow(ctx);
                SchoolClasses.close(ctx.level(), key, me(ctx));
                diary(ctx, "TEACHER home bell");
                proudOrSad(ctx);
                if (!SchoolClasses.preparedOn(ctx.npc(), today(ctx)) && now == SchoolDay.Phase.AFTER
                        && isAtSchool(ctx)) {
                    state = State.MARKING;
                    stateTicks = 0;
                    walkTo(ctx, stand);
                    return Status.RUNNING;
                }
                return finish(ctx);
            }
            default -> { }
        }
        resumeWalk(ctx);
        return null;
    }

    /** A part of the day began mid-walk (the walk was dropped): set off again for where it was going. */
    private void resumeWalk(Context ctx) {
        if (move != null) return;
        BlockPos target = switch (state) {
            case TO_STAND, CLASS -> ctx.npc().blockPosition().equals(stand) ? null : stand;
            case TO_YARD -> yardSpot;
            case TO_BELL -> school.entryTile();
            case TRIP_OUT -> tripSpot;
            default -> null;
        };
        if (target != null) walkTo(ctx, target);
    }

    private SchoolDay.Phase phaseNow(Context ctx) {
        return SchoolDay.phase(ctx.level().getDayTime(), recessOn);
    }

    private void beginLesson(Context ctx) {
        Profession subject = SchoolSubjects.pick(ctx.mgr(), ctx.npc().getRandom(),
                settings(ctx).getBool(TeacherSettingsSchema.VILLAGE_NEEDS));
        SchoolClasses.setSubject(ctx.level(), key, subject);
        ThoughtIcon icon = ProfessionTopics.shopTalk(subject);
        ctx.brain().showIcon(icon, 100, ctx.level());
        for (UUID id : SchoolClasses.pupilsIn(ctx.level(), key)) {
            VillagerModel kid = ctx.mgr().getModel(id);
            if (kid != null && ctx.npc().getRandom().nextFloat() < 0.3f) kid.brain().showIcon(icon, 60, ctx.level());
        }
        diary(ctx, "TEACHER " + phase.name().toLowerCase(Locale.ROOT) + ": " + SchoolSubjects.lessonName(subject));
    }

    // =========================================================================
    // Bell
    // =========================================================================

    /** Rings at the door unless this part of the day's bell has already rung, then goes on to {@code next}. */
    private void ringIfDue(Context ctx, State next) {
        int into = SchoolDay.ticksInto(ctx.level().getDayTime(), recessOn);
        if (SchoolClasses.bellRecent(ctx.level(), key.school(), into + BELL_GRACE_TICKS)) {
            goOn(ctx, next);
        } else {
            ring(ctx, next);
        }
    }

    /** Walks to the door and rings (or rings where it stands, if in class), then goes on to {@code next}. */
    private void ring(Context ctx, State next) {
        afterBell = next;
        if (!ringsTheBell(ctx)) {
            goOn(ctx, next);
            return;
        }
        BlockPos door = school.entryTile();
        if (isAt(ctx, door, 2) || state == State.CLASS || state == State.YARD) {
            startRinging(ctx);
        } else {
            state = State.TO_BELL;
            stateTicks = 0;
            walkTo(ctx, door);
        }
    }

    private void startRinging(Context ctx) {
        state = State.RING;
        stateTicks = 0;
        ringTicks = 0;
        ctx.model().stopMoving();
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.npc().holdDisplayItem(new ItemStack(Items.BELL), ctx.level().getGameTime() + RING_TICKS + 5);
    }

    private Status tickRing(Context ctx) {
        if (ringTicks % 16 == 0 && ringTicks < 40) {
            ctx.npc().swing(InteractionHand.MAIN_HAND);
            ctx.level().playSound(null, ctx.npc().blockPosition(), SoundEvents.BELL_BLOCK,
                    SoundSource.NEUTRAL, 1.6f, 1.5f);
        }
        if (++ringTicks < RING_TICKS) return Status.RUNNING;
        ctx.npc().clearDisplayItem();
        SchoolClasses.bellRung(ctx.level(), key.school());
        diary(ctx, "TEACHER rang the bell");
        goOn(ctx, afterBell);
        return Status.RUNNING;
    }

    /** One quick ring where it stands: lunch and home time. */
    private void ringNow(Context ctx) {
        if (!ringsTheBell(ctx)) return;
        ctx.npc().holdDisplayItem(new ItemStack(Items.BELL), ctx.level().getGameTime() + 30);
        ctx.npc().swing(InteractionHand.MAIN_HAND);
        ctx.level().playSound(null, ctx.npc().blockPosition(), SoundEvents.BELL_BLOCK,
                SoundSource.NEUTRAL, 1.6f, 1.5f);
        SchoolClasses.bellRung(ctx.level(), key.school());
    }

    /**
     * One bell per school. With two classes open there, the teacher with the
     * smaller id rings it, and a bell that has only just rung counts for
     * both: the other teacher goes straight on.
     */
    private boolean ringsTheBell(Context ctx) {
        if (SchoolClasses.bellRecent(ctx.level(), key.school(), SHARED_BELL_TICKS)) return false;
        UUID me = me(ctx);
        for (SchoolClasses.ClassKey other : SchoolClasses.openClasses(ctx.level())) {
            if (other.equals(key) || !other.school().equals(key.school())) continue;
            UUID teacher = SchoolClasses.teacherOf(ctx.level(), other);
            if (teacher != null && teacher.compareTo(me) < 0) return false;
        }
        return true;
    }

    private void goOn(Context ctx, State next) {
        stateTicks = 0;
        switch (next) {
            case TO_YARD -> {
                state = State.TO_YARD;
                if (yardSpot == null || isAt(ctx, yardSpot, 1)) arrived(ctx);
                else walkTo(ctx, yardSpot);
            }
            case TRIP_OUT -> {
                state = State.TRIP_OUT;
                SchoolClasses.startTrip(ctx.level(), key, tripSpot, tripLook, tripWorker);
                walkTo(ctx, tripSpot);
            }
            default -> {
                state = State.TO_STAND;
                if (ctx.npc().blockPosition().equals(stand)) arrived(ctx);
                else walkTo(ctx, stand);
            }
        }
    }

    // =========================================================================
    // Walking
    // =========================================================================

    private void walkTo(Context ctx, BlockPos where) {
        if (move != null) move.stop(ctx);
        ctx.npc().clearDisplayItem();
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        move = new MoveActivity(where, VillagerModel.WALK_SPEED, false, true);
    }

    private void arrived(Context ctx) {
        stateTicks = 0;
        switch (state) {
            case TO_BELL -> startRinging(ctx);
            case TO_STAND -> {
                state = State.CLASS;
                unitTimer = UNIT_TICKS;
                ctx.model().stopMoving();
                diary(ctx, "TEACHER in class at " + stand.toShortString());
            }
            case TO_YARD -> {
                state = State.YARD;
                ctx.model().stopMoving();
            }
            case TRIP_OUT -> {
                state = State.TRIP_WAIT;
                ctx.model().stopMoving();
                diary(ctx, "TEACHER field trip reached " + tripName);
            }
            case CLASS -> {
                // A walk along the rows ended.
                if (stage == Stage.WALK_ROWS) rowArrived(ctx);
            }
            case MARKING -> ctx.npc().setAnimation(VillagerAnimationState.READ);
            default -> { }
        }
    }

    private Status arriveFailed(Context ctx) {
        switch (state) {
            case TO_BELL -> {
                // Could not get to the door: ring where it stands.
                startRinging(ctx);
                return Status.RUNNING;
            }
            case TRIP_OUT -> {
                diary(ctx, "TEACHER could not reach the field trip, back to class");
                SchoolClasses.setMode(ctx.level(), key, SchoolClasses.Mode.CLASS);
                goOn(ctx, State.TO_STAND);
                return Status.RUNNING;
            }
            case TO_YARD -> {
                state = State.YARD;
                return Status.RUNNING;
            }
            default -> {
                // Not straight back: walking at a spot it cannot reach all day is the old pacing.
                pauseAWhile(ctx, "could not reach its spot " + stand.toShortString());
                return finish(ctx);
            }
        }
    }

    // =========================================================================
    // Class
    // =========================================================================

    private Status tickClass(Context ctx) {
        List<UUID> here = SchoolClasses.pupilsIn(ctx.level(), key);
        if (here.isEmpty()) {
            waitedTicks++;
            if (SchoolClasses.coming(ctx.level(), key) > 0) emptyTicks = 0;
            else emptyTicks++;
            int patience = settings(ctx).getInt(TeacherSettingsSchema.IDLE_TOLERANCE_UNITS) * UNIT_TICKS;
            if (!pupilsCame) patience = Math.max(patience, OPENING_WAIT_TICKS);
            if (emptyTicks >= patience || waitedTicks >= MAX_EMPTY_WAIT_TICKS) {
                if (!pupilsCame) restAWhile(ctx, "nobody came to class");
                else diary(ctx, "TEACHER class empty, lesson over");
                return finish(ctx);
            }
        } else {
            emptyTicks = 0;
            waitedTicks = 0;
            if (!pupilsCame) SchoolClasses.classFilled(ctx.level(), me(ctx));
            pupilsCame = true;
        }

        if (phase == SchoolDay.Phase.READING || phase == SchoolDay.Phase.RECESS) {
            // Reading hour, or a recess kept indoors by the alarm (which teaches nothing).
            tickReadingHour(ctx);
        } else if (phase.isLesson()) {
            tickStage(ctx, here);
        } else {
            // Waiting for the first lesson: face the children as they come in.
            faceClass(ctx, here);
        }
        payUnit(ctx);
        return Status.RUNNING;
    }

    private void tickReadingHour(Context ctx) {
        if (stage != null) endStage(ctx);
        if (ctx.npc().getAnimation() != VillagerAnimationState.READ) {
            ctx.npc().clearDisplayItem();
            ctx.npc().setAnimation(VillagerAnimationState.READ);
        }
        SchoolClasses.setCue(ctx.level(), key, SchoolClasses.CueKind.READING, null, 40);
    }

    private void tickStage(Context ctx, List<UUID> here) {
        if (stage == null || ++stageTicks >= stageLength) {
            if (stage == Stage.WALK_ROWS && rowStep < 2) {
                // Still out among the rows: walk back first.
                rowStep = 2;
                stageTicks = 0;
                stageLength = 600;
                walkTo(ctx, stand);
                return;
            }
            nextStage(ctx, here);
            return;
        }
        switch (stage) {
            case EXPLAIN -> faceClass(ctx, here);
            case READ_ALOUD -> lookDown(ctx);
            case QUIET_WORK -> lookAtLectern(ctx);
            case QUESTION -> {
                lookAtKid(ctx, stageKid);
                if (stageTicks == 40) ctx.npc().nod();
                if (stageTicks == stageLength - 1) answered(ctx, stageKid);
            }
            case WALK_ROWS -> {
                if (rowStep == 1) {
                    lookAtKid(ctx, stageKid);
                    if (stageTicks % 60 == 30) ctx.npc().nod();
                } else if (rowStep == 2) {
                    // Back at the lectern after the walk.
                    stageLength = 0;
                }
            }
        }
    }

    private void nextStage(Context ctx, List<UUID> here) {
        endStage(ctx);
        rollDaydreams(ctx, here);
        int roll = ctx.npc().getRandom().nextInt(100);
        Stage next;
        if (here.isEmpty()) next = roll < 60 ? Stage.EXPLAIN : Stage.QUIET_WORK;
        else if (roll < 30) next = Stage.EXPLAIN;
        else if (roll < 45) next = Stage.READ_ALOUD;
        else if (roll < 65) next = Stage.WALK_ROWS;
        else if (roll < 85) next = Stage.QUESTION;
        else next = Stage.QUIET_WORK;

        stage = next;
        stageTicks = 0;
        stageKid = null;
        NpcEntity npc = ctx.npc();
        long now = ctx.level().getGameTime();
        switch (next) {
            case EXPLAIN -> {
                stageLength = 160 + npc.getRandom().nextInt(140);
                Profession subject = SchoolClasses.subject(ctx.level(), key);
                npc.holdDisplayItem(new ItemStack(SchoolSubjects.prop(subject)), now + stageLength);
                npc.setAnimation(VillagerAnimationState.ARM_ON_HIP);
                SchoolClasses.setCue(ctx.level(), key, SchoolClasses.CueKind.EXPLAIN, null, stageLength);
            }
            case READ_ALOUD -> {
                stageLength = 200 + npc.getRandom().nextInt(100);
                npc.setAnimation(VillagerAnimationState.READ);
                SchoolClasses.setCue(ctx.level(), key, SchoolClasses.CueKind.READ_ALOUD, null, stageLength);
            }
            case QUIET_WORK -> {
                stageLength = 200 + npc.getRandom().nextInt(100);
                npc.setAnimation(VillagerAnimationState.READ);
                SchoolClasses.setCue(ctx.level(), key, SchoolClasses.CueKind.QUIET_WORK, null, stageLength);
            }
            case QUESTION -> {
                stageKid = pickKid(ctx, here, false);
                stageLength = 70;
                npc.setAnimation(VillagerAnimationState.IDLE);
                SchoolClasses.setCue(ctx.level(), key, SchoolClasses.CueKind.QUESTION, stageKid, stageLength);
                VillagerModel kid = stageKid != null ? ctx.mgr().getModel(stageKid) : null;
                if (kid != null) kid.brain().showIcon(ThoughtIcon.EMOTE_QUESTION, 50, ctx.level());
            }
            case WALK_ROWS -> {
                stageKid = pickKid(ctx, here, true);
                BlockPos beside = stageKid != null ? besideKid(ctx, stageKid) : null;
                if (beside == null) {
                    stage = Stage.EXPLAIN;
                    stageLength = 160;
                    npc.setAnimation(VillagerAnimationState.ARM_ON_HIP);
                    return;
                }
                stageLength = 600;
                rowStep = 0;
                SchoolClasses.setCue(ctx.level(), key, SchoolClasses.CueKind.WALK_ROWS, stageKid, stageLength);
                walkTo(ctx, beside);
            }
        }
    }

    private void endStage(Context ctx) {
        if (stage == null) return;
        ctx.npc().clearDisplayItem();
        if (ctx.npc().getAnimation() != VillagerAnimationState.IDLE) {
            ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        }
        stage = null;
        stageKid = null;
    }

    /** A walk along the rows ended: beside the child, or back at the lectern. */
    private void rowArrived(Context ctx) {
        if (rowStep == 0) {
            rowStep = 1;
            stageTicks = 0;
            stageLength = 120;
            ctx.npc().setAnimation(VillagerAnimationState.THINK);
            if (stageKid != null && SchoolClasses.isDaydreaming(ctx.level(), stageKid)) {
                SchoolClasses.wakeUp(ctx.level(), stageKid);
                VillagerModel kid = ctx.mgr().getModel(stageKid);
                if (kid != null) kid.brain().showIcon(ThoughtIcon.EMOTE_SURPRISED, 40, ctx.level());
            }
        } else {
            stageLength = 0;
        }
    }

    /** A child the teacher turns to: one daydreaming first when walking the rows. */
    @Nullable
    private UUID pickKid(Context ctx, List<UUID> here, boolean daydreamersFirst) {
        if (here.isEmpty()) return null;
        if (daydreamersFirst) {
            for (UUID id : here) if (SchoolClasses.isDaydreaming(ctx.level(), id)) return id;
        }
        List<UUID> awake = new ArrayList<>();
        for (UUID id : here) if (!SchoolClasses.isDaydreaming(ctx.level(), id)) awake.add(id);
        List<UUID> pool = awake.isEmpty() ? here : awake;
        return pool.get(ctx.npc().getRandom().nextInt(pool.size()));
    }

    /** A free floor tile next to the child, inside the school. */
    @Nullable
    private BlockPos besideKid(Context ctx, UUID kidId) {
        VillagerModel kid = ctx.mgr().getModel(kidId);
        NpcEntity puppet = kid != null ? kid.puppet() : null;
        NavGraph graph = ctx.mgr().getGraph();
        if (puppet == null || graph == null) return null;
        BlockPos at = puppet.isChairSeated() && puppet.seatedChairPos() != null
                ? puppet.seatedChairPos() : puppet.blockPosition();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos tile = at.relative(d);
            if (!school.getNavMeshBlocks().contains(tile)) continue;
            NavNode node = graph.nodeAt(tile.getX(), tile.getY(), tile.getZ());
            if (node == null || !node.isRoutable()) continue;
            if (someoneOn(ctx, tile)) continue;
            return tile;
        }
        return null;
    }

    private static boolean someoneOn(Context ctx, BlockPos tile) {
        for (VillagerModel m : ctx.mgr().getVillagers()) {
            NpcEntity other = m.puppet();
            if (other != null && other != ctx.npc() && other.isAlive()
                    && other.blockPosition().equals(tile)) return true;
        }
        return false;
    }

    /** The child who asked gets its answer: a little extra learning, and a bright idea. */
    private void answered(Context ctx, @Nullable UUID kidId) {
        VillagerModel kid = kidId != null ? ctx.mgr().getModel(kidId) : null;
        NpcEntity puppet = kid != null ? kid.puppet() : null;
        if (puppet == null) return;
        PupilRecord.learn(puppet.getBrainData(), kid.brain().skills(), today(ctx),
                TeacherBalance.unitGain(level) * TeacherBalance.QUESTION_UNITS, TeacherBalance.dailyCap(level));
        kid.brain().showIcon(ThoughtIcon.EMOTE_IDEA, 50, ctx.level());
    }

    /** Tired or sad children may drift off at the start of a stage. */
    private void rollDaydreams(Context ctx, List<UUID> here) {
        float chance = TeacherBalance.daydreamChance(level);
        for (UUID id : here) {
            VillagerModel kid = ctx.mgr().getModel(id);
            if (kid == null || kid.brain().needs().wellbeing >= TeacherBalance.DAYDREAM_WELLBEING) continue;
            if (SchoolClasses.isDaydreaming(ctx.level(), id)) continue;
            if (ctx.npc().getRandom().nextFloat() >= chance) continue;
            SchoolClasses.daydream(ctx.level(), id, ctx.level().getGameTime() + 400 + ctx.npc().getRandom().nextInt(400));
            kid.brain().showIcon(ThoughtIcon.EMOTE_BOREDOM, 60, ctx.level());
        }
    }

    // =========================================================================
    // Paying the class
    // =========================================================================

    /** Every lesson unit of paid class time: the children learn, the teacher practises. */
    private void payUnit(Context ctx) {
        if (--unitTimer > 0) return;
        unitTimer = UNIT_TICKS;
        if (!phase.pays()) return;
        List<UUID> here = SchoolClasses.pupilsIn(ctx.level(), key);
        if (here.isEmpty()) return;

        boolean trip = SchoolClasses.mode(ctx.level(), key) == SchoolClasses.Mode.TRIP;
        Profession subject = SchoolClasses.subject(ctx.level(), key);
        float base = TeacherBalance.unitGain(level) * TeacherBalance.crowdShare(level, here.size());
        if (phase == SchoolDay.Phase.READING && !trip) base *= TeacherBalance.readingShare(level, shelves);
        float cap = TeacherBalance.dailyCap(level);
        long day = today(ctx);
        int paid = 0;
        for (UUID id : here) {
            VillagerModel kid = ctx.mgr().getModel(id);
            NpcEntity puppet = kid != null ? kid.puppet() : null;
            if (puppet == null || kid.brain().profession() != Profession.CHILD) continue;
            float share = base;
            if (SchoolClasses.isAtBack(ctx.level(), id)) share *= TeacherBalance.BACK_OF_CLASS_SHARE;
            if (SchoolClasses.isDaydreaming(ctx.level(), id)) share *= TeacherBalance.DAYDREAM_SHARE;
            PupilRecord.learn(puppet.getBrainData(), kid.brain().skills(), day, share, cap);
            if (subject != null) PupilRecord.taught(puppet.getBrainData(), id, subject, trip ? 2f : 1f);
            PupilRecord.taughtBy(puppet.getBrainData(), level);
            paid++;
        }
        if (paid > 0) {
            taughtThisSession = true;
            ctx.brain().onTeacherUnit(ctx, TeacherBalance.unitWeight(paid));
            level = ctx.brain().teacherSkillLevel();
        }
        if (paid != lastPaidCount) {
            lastPaidCount = paid;
            diary(ctx, "TEACHER teaching " + paid + " child(ren)"
                    + (subject != null ? " " + SchoolSubjects.lessonName(subject) : ""));
        }
    }

    // =========================================================================
    // Recess
    // =========================================================================

    private void beginRecess(Context ctx) {
        endStage(ctx);
        yardSpot = outsideDoor(ctx, school, YARD_DISTANCE, 1);
        BlockPos centre = outsideDoor(ctx, school, YARD_DISTANCE + 3, 0);
        if (centre == null) centre = yardSpot != null ? yardSpot : school.entryTile();
        List<UUID> kids = new ArrayList<>(SchoolClasses.pupilsIn(ctx.level(), key));
        SchoolClasses.Game game = SchoolClasses.Game.FREE;
        UUID it = null, chased = null;
        if (kids.size() >= 2) {
            int roll = ctx.npc().getRandom().nextInt(100);
            game = roll < 45 ? SchoolClasses.Game.TAG : roll < 75 ? SchoolClasses.Game.CIRCLE : SchoolClasses.Game.FREE;
            it = kids.get(ctx.npc().getRandom().nextInt(kids.size()));
            for (UUID k : kids) if (!k.equals(it)) { chased = k; break; }
        }
        SchoolClasses.startRecess(ctx.level(), key, game, centre, it, chased);
        SchoolClasses.setSubject(ctx.level(), key, null);
        diary(ctx, "TEACHER recess: " + game.name().toLowerCase(Locale.ROOT) + " with " + kids.size() + " child(ren)");
    }

    private Status tickYard(Context ctx) {
        // Watch the children play.
        if (--centreTimer <= 0) {
            centreTimer = 20;
            SchoolClasses.Recess recess = SchoolClasses.recess(ctx.level(), key);
            if (recess != null) lookAt = Vec3.atCenterOf(recess.centre);
        }
        ctx.npc().getLookControl().setLookAt(lookAt.x, lookAt.y, lookAt.z, 20f, 20f);
        return Status.RUNNING;
    }

    /** Recess cheers the children up a little. */
    private void giveRecessJoy(Context ctx) {
        for (UUID id : SchoolClasses.pupilsIn(ctx.level(), key)) {
            VillagerModel kid = ctx.mgr().getModel(id);
            if (kid == null) continue;
            kid.brain().needs().wellbeing = Math.min(100f,
                    kid.brain().needs().wellbeing + TeacherBalance.RECESS_WELLBEING);
        }
    }

    /** A walkable spot {@code out} blocks out of the school door and {@code side} to its right. */
    @Nullable
    static BlockPos outsideDoor(Context ctx, SchoolBuilding school, int out, int side) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        Direction outward = school.getInteriorDirection().getOpposite();
        BlockPos guess = school.getDoorPos().relative(outward, out).relative(outward.getClockWise(), side);
        NavNode node = graph.nearestNonAvoided(guess, 3, 3);
        if (node == null) return null;
        BlockPos spot = node.feetPos();
        if (school.getNavMeshBlocks().contains(spot)) return null;
        return ctx.model().canReach(graph, spot) ? spot : null;
    }

    // =========================================================================
    // Field trip
    // =========================================================================

    /** One possible field trip: the job, where the class stands, and what it watches. */
    private record TripPlan(Profession subject, BlockPos spot, BlockPos look, String place,
                            @Nullable UUID worker) {}

    /**
     * Once a week lesson 3 is a walk to watch a job; true when today is the day
     * and there is somewhere to go. Every job with a workplace in reach can
     * come up, and jobs without one (farming, woodcutting, nature...) when one
     * of their workers is busy outdoors nearby. The pick is random: jobs the
     * village needs come up more often with "Teaching What's Needed" on,
     * a place where somebody is working right now twice as often, and last
     * week's job half as often. Never while the alarm is on.
     */
    private boolean planTrip(Context ctx) {
        if (!settings(ctx).getBool(TeacherSettingsSchema.FIELD_TRIPS) || alarmed(ctx)) return false;
        long day = today(ctx);
        if (Math.floorMod(day + key.lectern().asLong(), 7L) != 0) return false;
        boolean villageNeeds = settings(ctx).getBool(TeacherSettingsSchema.VILLAGE_NEEDS);
        Map<Profession, Integer> workers = SchoolSubjects.workerCounts(ctx.mgr());
        Profession last = Profession.byName(ctx.npc().getBrainData().getString(TAG_LAST_TRIP));
        List<TripPlan> plans = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        int total = 0;
        for (Profession subject : SchoolSubjects.TEACHABLE) {
            TripPlan plan = tripTo(ctx, subject);
            if (plan == null) continue;
            int weight = SchoolSubjects.needWeight(workers, subject, villageNeeds)
                    * (plan.worker() != null ? 2 : 1) * (subject == last ? 1 : 2);
            plans.add(plan);
            weights.add(weight);
            total += weight;
        }
        if (plans.isEmpty()) return false;
        int roll = ctx.npc().getRandom().nextInt(total);
        TripPlan pick = plans.getLast();
        for (int i = 0; i < plans.size(); i++) {
            roll -= weights.get(i);
            if (roll < 0) {
                pick = plans.get(i);
                break;
            }
        }
        tripSpot = pick.spot();
        tripLook = pick.look();
        tripWorker = pick.worker();
        tripName = pick.place();
        ctx.npc().getBrainData().putString(TAG_LAST_TRIP, pick.subject().name());
        SchoolClasses.setSubject(ctx.level(), key, pick.subject());
        ctx.brain().showIcon(ProfessionTopics.shopTalk(pick.subject()), 100, ctx.level());
        diary(ctx, "TEACHER field trip today: " + SchoolSubjects.lessonName(pick.subject()) + " at "
                + pick.place() + " " + pick.spot().toShortString() + " (" + plans.size() + " choices"
                + (pick.worker() != null ? ", someone at work there" : "") + ")");
        return true;
    }

    /** Where a trip about {@code subject} would go, or null when there is nowhere in reach. */
    @Nullable
    private TripPlan tripTo(Context ctx, Profession subject) {
        BlockPos from = school.getDoorPos();
        Building place = SchoolSubjects.workplace(ctx.mgr(), subject, from, TRIP_RANGE);
        if (place != null) {
            BlockPos spot = outsideBuilding(ctx, place);
            if (spot != null) {
                VillagerModel worker = workerNear(ctx, subject, place.getDoorPos(), TRIP_WORKER_NEAR, false);
                return new TripPlan(subject, spot, place.getDoorPos(), "the " + place.getDisplayName(),
                        worker != null ? worker.npcId : null);
            }
        }
        // No workplace to visit: watch one of the job's workers busy outdoors.
        VillagerModel worker = workerNear(ctx, subject, from, TRIP_RANGE, true);
        if (worker == null || worker.puppet() == null) return null;
        BlockPos at = worker.puppet().blockPosition();
        BlockPos spot = watchSpot(ctx, at, from);
        if (spot == null) return null;
        return new TripPlan(subject, spot, at, "a " + subject.displayName() + " at work", worker.npcId);
    }

    /**
     * The nearest villager doing {@code subject} within {@code range} of
     * {@code around} and working right now; with {@code outdoorsOnly}, not
     * inside any building.
     */
    @Nullable
    private static VillagerModel workerNear(Context ctx, Profession subject, BlockPos around, int range,
                                            boolean outdoorsOnly) {
        VillagerModel best = null;
        double bestDist = (double) range * range;
        for (VillagerModel model : ctx.mgr().getVillagers()) {
            if (model.brain().profession() != subject) continue;
            if (!(model.brain().currentActivity() instanceof WorkActivity)) continue;
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.isSleeping()) continue;
            double d = npc.blockPosition().distSqr(around);
            if (d >= bestDist) continue;
            if (outdoorsOnly && insideAnyBuilding(ctx, npc.blockPosition())) continue;
            bestDist = d;
            best = model;
        }
        return best;
    }

    private static boolean insideAnyBuilding(Context ctx, BlockPos pos) {
        for (Building b : ctx.mgr().getBuildings()) {
            if (b.getNavMeshBlocks().contains(pos)) return true;
        }
        return false;
    }

    /** A reachable outdoor spot a few blocks from a worker, on the school's side, to watch from. */
    @Nullable
    private static BlockPos watchSpot(Context ctx, BlockPos worker, BlockPos school) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        double dx = school.getX() - worker.getX();
        double dz = school.getZ() - worker.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        BlockPos guess = len < 1.0 ? worker
                : worker.offset((int) Math.round(dx / len * TRIP_WATCH_DISTANCE), 0,
                        (int) Math.round(dz / len * TRIP_WATCH_DISTANCE));
        NavNode node = graph.nearestNonAvoided(guess, 3, 3);
        if (node == null) return null;
        BlockPos spot = node.feetPos();
        if (insideAnyBuilding(ctx, spot)) return null;
        return ctx.model().canReach(graph, spot) ? spot : null;
    }

    /** The worker the trip came to watch, while it is near enough to see. */
    @Nullable
    private NpcEntity tripWorkerPuppet(Context ctx) {
        VillagerModel model = tripWorker != null ? ctx.mgr().getModel(tripWorker) : null;
        NpcEntity npc = model != null ? model.puppet() : null;
        return npc != null && npc.isAlive() && npc.distanceToSqr(ctx.npc()) < 24 * 24 ? npc : null;
    }

    @Nullable
    private static BlockPos outsideBuilding(Context ctx, Building place) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        Direction outward = place.getInteriorDirection().getOpposite();
        NavNode node = graph.nearestNonAvoided(place.getDoorPos().relative(outward, 2), 3, 3);
        if (node == null) return null;
        BlockPos spot = node.feetPos();
        return ctx.model().canReach(graph, spot) ? spot : null;
    }

    private Status tickTrip(Context ctx) {
        payUnit(ctx);
        if (state == State.TRIP_WAIT) {
            ctx.npc().getLookControl().setLookAt(Vec3.atCenterOf(school.getDoorPos()));
            int here = SchoolClasses.pupilsIn(ctx.level(), key).size();
            int all = SchoolClasses.headcount(ctx.level(), key);
            if (here >= all || stateTicks >= TRIP_GATHER_TICKS) {
                state = State.TRIP_WATCH;
                stateTicks = 0;
                Profession subject = SchoolClasses.subject(ctx.level(), key);
                ctx.npc().holdDisplayItem(new ItemStack(SchoolSubjects.prop(subject)),
                        ctx.level().getGameTime() + TRIP_WATCH_TICKS);
                ctx.npc().setAnimation(VillagerAnimationState.ARM_ON_HIP);
                diary(ctx, "TEACHER field trip: " + here + " child(ren) watching");
            }
            return Status.RUNNING;
        }
        // Watching: face the worker (or the workplace), now and then turn to the children.
        if (stateTicks % 200 < 140) {
            NpcEntity worker = tripWorkerPuppet(ctx);
            if (worker != null) ctx.npc().getLookControl().setLookAt(worker, 20f, 20f);
            else ctx.npc().getLookControl().setLookAt(Vec3.atCenterOf(tripLook));
        } else {
            faceClass(ctx, SchoolClasses.pupilsIn(ctx.level(), key));
        }
        if (stateTicks < TRIP_WATCH_TICKS) return Status.RUNNING;
        diary(ctx, "TEACHER field trip over, back to school");
        SchoolClasses.setMode(ctx.level(), key, SchoolClasses.Mode.CLASS);
        ctx.npc().clearDisplayItem();
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        state = State.TO_STAND;
        walkTo(ctx, stand);
        return Status.RUNNING;
    }

    // =========================================================================
    // Marking and preparation
    // =========================================================================

    private Status tickMarking(Context ctx) {
        if (move != null) {
            Status status = move.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            move.stop(ctx);
            move = null;
            if (status == Status.FAILED) {
                pauseAWhile(ctx, "could not reach its spot " + stand.toShortString());
                return finish(ctx);
            }
            stateTicks = 0;
            ctx.npc().setAnimation(VillagerAnimationState.READ);
            return Status.RUNNING;
        }
        lookAtLectern(ctx);
        if (ctx.npc().getAnimation() != VillagerAnimationState.READ) {
            ctx.npc().setAnimation(VillagerAnimationState.READ);
        }
        if (stateTicks % MARKING_RECHECK_TICKS == 0 && phaseNow(ctx).atSchool()
                && SchoolClasses.anyPupilFor(ctx, school, key)) {
            diary(ctx, "TEACHER a child can come now, opening class");
            return finish(ctx);
        }
        if (stateTicks < MARKING_TICKS) return Status.RUNNING;
        SchoolClasses.markPrepared(ctx.npc(), today(ctx));
        taughtThisSession = true;
        diary(ctx, "TEACHER work marked and lessons ready for tomorrow");
        return finish(ctx);
    }

    // =========================================================================
    // Looking
    // =========================================================================

    /** Faces the middle of the children in class, or of the class's seats while it waits. */
    private void faceClass(Context ctx, List<UUID> here) {
        if (--centreTimer <= 0) {
            centreTimer = 20;
            double x = 0, y = 0, z = 0;
            int n = 0;
            for (UUID id : here) {
                VillagerModel model = ctx.mgr().getModel(id);
                NpcEntity pupil = model != null ? model.puppet() : null;
                if (pupil == null) continue;
                Vec3 eye = pupil.getEyePosition();
                x += eye.x; y += eye.y; z += eye.z;
                n++;
            }
            if (n > 0) lookAt = new Vec3(x / n, y / n, z / n);
        }
        ctx.npc().getLookControl().setLookAt(lookAt.x, lookAt.y, lookAt.z, 20f, 20f);
    }

    private void lookAtKid(Context ctx, @Nullable UUID kidId) {
        VillagerModel kid = kidId != null ? ctx.mgr().getModel(kidId) : null;
        NpcEntity puppet = kid != null ? kid.puppet() : null;
        if (puppet != null) ctx.npc().getLookControl().setLookAt(puppet, 20f, 20f);
    }

    private void lookAtLectern(Context ctx) {
        BlockPos lectern = key.lectern();
        ctx.npc().getLookControl().setLookAt(lectern.getX() + 0.5, lectern.getY() + 0.9,
                lectern.getZ() + 0.5, 20f, 20f);
    }

    private void lookDown(Context ctx) {
        NpcEntity npc = ctx.npc();
        double yaw = Math.toRadians(npc.getYRot());
        Vec3 eye = npc.getEyePosition();
        npc.getLookControl().setLookAt(eye.x - Math.sin(yaw) * 0.7, eye.y - 0.7, eye.z + Math.cos(yaw) * 0.7,
                20f, 20f);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Status finish(Context ctx) {
        if (key != null) SchoolClasses.close(ctx.level(), key, me(ctx));
        endStage(ctx);
        ctx.npc().clearDisplayItem();
        if (ctx.npc().getAnimation() == VillagerAnimationState.READ) {
            ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        }
        return taughtThisSession ? Status.DONE : Status.FAILED;
    }

    /** A proud face after a day with children in class, a sad one after a day without. */
    private void proudOrSad(Context ctx) {
        if (pupilsCame) {
            ctx.brain().rememberAndShow(MemoryTopic.SCHOOL_DAY, ThoughtIcon.EMOTE_PRIDE, 60, ctx.level());
        } else {
            ctx.brain().showIcon(ThoughtIcon.EMOTE_HEARTBREAK, 60, ctx.level());
        }
    }

    private static boolean alarmed(Context ctx) {
        return GuardAlertSystem.isVillageAlarmed(ctx.mgr(), ctx.level());
    }

    /** The alarm went during recess: the class goes back to its seats and reads until lesson 2. */
    private void callClassIn(Context ctx) {
        indoorRecess = true;
        SchoolClasses.setMode(ctx.level(), key, SchoolClasses.Mode.CLASS);
        diary(ctx, "TEACHER alarm: calling the class back inside");
        state = State.TO_STAND;
        stateTicks = 0;
        walkTo(ctx, stand);
    }

    /** On the school's floor (or its doorway tile). */
    private boolean isIndoors(Context ctx) {
        BlockPos at = ctx.npc().blockPosition();
        return school.getNavMeshBlocks().contains(at) || at.equals(school.entryTile());
    }

    private boolean isAtSchool(Context ctx) {
        return school.getNavMeshBlocks().contains(ctx.npc().blockPosition())
                || isAt(ctx, school.entryTile(), 2);
    }

    private static boolean isAt(Context ctx, BlockPos where, int r) {
        BlockPos at = ctx.npc().blockPosition();
        return Math.abs(at.getX() - where.getX()) <= r && Math.abs(at.getZ() - where.getZ()) <= r
                && Math.abs(at.getY() - where.getY()) <= 1;
    }

    private static UUID me(Context ctx) {
        return ctx.model().npcId;
    }

    private static ProfessionSettings settings(Context ctx) {
        return ProfessionSettings.of(ctx.npc(), TeacherSettingsSchema.SCHEMA);
    }

    private static int countShelves(Context ctx, SchoolBuilding school) {
        java.util.Set<BlockPos> shelves = new java.util.HashSet<>();
        for (BlockPos tile : school.getNavMeshBlocks()) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos pos = tile.relative(d).above(dy);
                    if (ctx.level().getBlockState(pos).is(Blocks.BOOKSHELF)) shelves.add(pos);
                }
            }
            if (shelves.size() >= TeacherBalance.MAX_SHELVES) break;
        }
        return shelves.size();
    }

    /** No new class for a while, longer each time in a row it happens. */
    private static void restAWhile(Context ctx, String why) {
        long wait = SchoolClasses.quietAfterEmpty(ctx.level(), ctx.model().npcId, QUIET_AFTER_EMPTY_TICKS);
        diary(ctx, "TEACHER " + why + ", no class for " + wait / 20 + " s");
    }

    /** Could not get somewhere: a short break that does not grow, then it tries again. */
    private static void pauseAWhile(Context ctx, String why) {
        SchoolClasses.quietFor(ctx.level(), ctx.model().npcId, PAUSE_AFTER_FAILED_TICKS);
        diary(ctx, "TEACHER " + why + ", trying again in " + PAUSE_AFTER_FAILED_TICKS / 20 + " s");
    }

    private static long today(Context ctx) {
        return EveningSchedule.dayIndex(ctx.level().getDayTime());
    }

    private static void diary(Context ctx, String message) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + message);
    }

    // =========================================================================
    // Village queries
    // =========================================================================

    /**
     * Cheap pre-check for brain scoring: the teacher is switched on and has a
     * lectern it can reach, and either it is school time with a child who can
     * come to class, or today's marking (lesson preparation, with no
     * children) is still to do.
     */
    public static boolean hasWork(Context ctx) {
        String reason = noWorkReason(ctx);
        if (SchoolClasses.noteNoWork(ctx.level(), ctx.model().npcId, reason)) {
            diary(ctx, "TEACHER no work: " + reason);
        }
        return reason == null;
    }

    /**
     * Why this teacher has no work right now, in a few words; null when it has.
     * Claims a lectern on the way, so a new teacher belongs to a school at once.
     */
    @Nullable
    public static String noWorkReason(Context ctx) {
        // First, so the teacher holds a lectern even while switched off or resting.
        SchoolClasses.ClassKey key = SchoolClasses.resolveTeacherClass(ctx, true);
        if (!settings(ctx).getBool(TeacherSettingsSchema.WORK_ENABLED)) return "switched off";
        if (key == null) return "no free lectern in any school";
        SchoolBuilding school = SchoolClasses.usableSchool(ctx, key);
        if (school == null) return "cannot reach its school from " + ctx.npc().blockPosition().toShortString();
        if (SchoolClasses.isQuiet(ctx.level(), ctx.model().npcId)) return "resting after the last try";
        SchoolDay.Phase phase = SchoolDay.phase(ctx.level().getDayTime(),
                settings(ctx).getBool(TeacherSettingsSchema.RECESS));
        boolean pupils = SchoolClasses.anyPupilFor(ctx, school, key);
        if (phase.atSchool() && pupils) return null;
        if (SchoolClasses.preparedOn(ctx.npc(), today(ctx))) return "lessons already prepared today";
        if (!pupils || phase == SchoolDay.Phase.AFTER) return null;
        return "not school time (" + phase.name().toLowerCase(Locale.ROOT) + ")";
    }

    /**
     * School time with children to teach and the class not open yet: worth
     * leaving a stroll for, so the bell rings on time.
     */
    public static boolean hasUrgentWork(Context ctx) {
        if (!settings(ctx).getBool(TeacherSettingsSchema.WORK_ENABLED)) return false;
        SchoolDay.Phase phase = SchoolDay.phase(ctx.level().getDayTime(),
                settings(ctx).getBool(TeacherSettingsSchema.RECESS));
        if (!phase.atSchool() || SchoolClasses.isQuiet(ctx.level(), ctx.model().npcId)) return false;
        // Also for a new teacher with no lectern yet: it takes one now.
        SchoolClasses.ClassKey key = SchoolClasses.resolveTeacherClass(ctx, true);
        if (key == null || SchoolClasses.teacherOf(ctx.level(), key) != null) return false;
        SchoolBuilding school = SchoolClasses.usableSchool(ctx, key);
        return school != null && SchoolClasses.anyPupilFor(ctx, school, key);
    }
}

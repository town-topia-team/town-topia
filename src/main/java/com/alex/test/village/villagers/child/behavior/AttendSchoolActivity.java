package com.alex.test.village.villagers.child.behavior;

import com.alex.test.animation.HurryGait;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillageChairs;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.building.SchoolBuilding;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.villagers.teacher.SchoolClasses;
import com.alex.test.village.villagers.teacher.SchoolDay;
import com.alex.test.village.villagers.teacher.SchoolSeats;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * A child's "work": its part in the school day, while a teacher is teaching
 * its class (see {@link SchoolClasses}).
 *
 * <ul>
 *   <li><b>In class</b>: it takes its own seat, the same one every day while
 *       it is free (a chair facing the lectern, or a place in a row,
 *       {@link SchoolSeats}), and follows the teacher: it thinks during quiet
 *       work, puts its hands up when it is its turn to ask, and reads in
 *       reading hour. A tired or sad child sometimes daydreams and looks
 *       away until the teacher comes over.</li>
 *   <li><b>Recess</b>: out to the yard for tag (run after whoever it is
 *       chasing, or run away), a circle dance, or free play.</li>
 *   <li><b>Field trip</b>: walks to the workplace with the class and watches.</li>
 * </ul>
 *
 * <p>A child goes only while a class is open: its own class when that one is,
 * otherwise the open class with the fewest children. A child whose class has
 * two or more children more than another open class moves over to that one
 * for good, so two classes even out. When the bell has just rung it jogs.
 *
 * <p>A sitting in class ends when the teacher closes the class, or after
 * {@value #SESSION_TICKS} ticks so the brain can see to food; a child with the
 * class still open sits straight back down, on the same chair.
 */
public final class AttendSchoolActivity extends WorkActivity {

    /** Longest one sitting lasts before the brain gets a look in. */
    private static final int SESSION_TICKS = 2400;
    private static final double SIT_REACH_SQ = 4.0;
    private static final int MAX_WALK_FAILURES = 2;
    /** Pushed further than this from a standing place: walk back to it. */
    private static final double OFF_SEAT_DIST_SQ = 2.25;
    /** A bell this recent hurries a child along. */
    private static final int LATE_BELL_TICKS = 600;
    private static final float WALK_SPEED = VillagerModel.WALK_SPEED;
    private static final float JOG_SPEED = 0.6f;
    private static final float RUN_SPEED = 0.65f;
    private static final double TAG_REACH = 1.6;
    /** The new "it" counts to three before chasing. */
    private static final int TAG_COUNT_TICKS = 40;
    private static final int YARD_RADIUS = 7;

    @Nullable private SchoolClasses.ClassKey key;
    @Nullable private SchoolBuilding school;
    private List<SchoolSeats.Seat> seats = List.of();
    @Nullable private SchoolSeats.Seat seat;
    private SchoolClasses.Mode mode = SchoolClasses.Mode.CLASS;
    private boolean begun;
    private boolean arrived;
    private boolean late;
    private int walkFailures;
    private int ticksInClass;

    /** Recess and field trip: where it is heading. */
    @Nullable private BlockPos spot;
    private int spotTimer;
    private boolean tripFailed;
    @Nullable private VillagerAnimationState shown;
    private int showFor;
    @Nullable private Vec3 dreamLook;
    private int dreamLookTimer;
    private final Random random = new Random();

    @Override
    protected void start(Context ctx) {
        begun = true;
        key = SchoolClasses.pickClassFor(ctx);
        if (key == null) return;
        school = SchoolClasses.usableSchool(ctx, key);
        if (school == null) {
            key = null;
            return;
        }
        SchoolClasses.enrolIfNeeded(ctx, key);
        seats = SchoolSeats.layout(ctx.level(), ctx.mgr().getGraph(), school, key.lectern(),
                SchoolSeats.otherLecternsInUse(ctx.level(), school, key.lectern())).seats();
        random.setSeed(ctx.npc().getRandom().nextLong());
        mode = SchoolClasses.mode(ctx.level(), key);
        late = SchoolClasses.bellRecent(ctx.level(), key.school(), LATE_BELL_TICKS);

        // Back from a break and still on one of the class's chairs: stay seated.
        BlockPos sittingOn = ctx.npc().isChairSeated() ? ctx.npc().seatedChairPos() : null;
        if (sittingOn != null) {
            if (mode == SchoolClasses.Mode.CLASS) {
                for (SchoolSeats.Seat s : seats) {
                    if (s.chair() == null || !s.chair().pos().equals(sittingOn)) continue;
                    seat = s;
                    arrived = true;
                    claim(ctx);
                    return;
                }
            }
            ctx.npc().standUpFromChair();
        }
        if (mode == SchoolClasses.Mode.CLASS) {
            seat = pickSeat(ctx, false);
            if (seat != null) {
                diary(ctx, "SCHOOL heading to class at lectern " + key.lectern().toShortString()
                        + (seat.chair() != null ? ", desk " : ", place ") + seat.key().toShortString()
                        + (seat.overflow() ? " (at the back)" : "") + (late ? ", late" : ""));
            }
        } else {
            diary(ctx, "SCHOOL joining the class " + (mode == SchoolClasses.Mode.RECESS ? "at recess" : "on its field trip"));
        }
    }

    @Override
    protected Status doTick(Context ctx) {
        if (key == null || school == null) return Status.FAILED;
        if (!school.isValid() || SchoolClasses.teacherOf(ctx.level(), key) == null) {
            diary(ctx, "SCHOOL class over");
            if (ticksInClass > 0) ctx.brain().remember(com.alex.test.village.social.MemoryTopic.SCHOOL_DAY, ctx.level());
            return Status.DONE;
        }
        SchoolClasses.Mode now = SchoolClasses.mode(ctx.level(), key);
        // A child that could not get to the field trip waits in class until the
        // others are back, instead of setting off again every time it looks.
        if (now != SchoolClasses.Mode.TRIP) tripFailed = false;
        else if (tripFailed) now = SchoolClasses.Mode.CLASS;
        if (now != mode) changeMode(ctx, now);

        return switch (mode) {
            case CLASS -> tickClass(ctx);
            case RECESS -> tickRecess(ctx);
            case TRIP -> tickTrip(ctx);
        };
    }

    /** Until {@link #start} has run a fresh sitting keeps the chair, so it can adopt it. */
    @Override
    public boolean keepsChairSeat() {
        return !begun || (mode == SchoolClasses.Mode.CLASS && seat != null && seat.chair() != null);
    }

    @Override
    protected void doStop(Context ctx) {
        release(ctx);
        SchoolClasses.leave(ctx.level(), ctx.model().npcId);
        ctx.model().stopMoving();
        ctx.npc().stopHurry();
        resetPose(ctx);
    }

    @Override
    public boolean isMovingPhase() {
        return !arrived;
    }

    /** Class has started: no stopping for a chat on the way. */
    @Override
    public boolean allowsChatWhileWalking() {
        return false;
    }

    /**
     * The alarm goes during school: the child stays with its class, which
     * stays indoors (and comes back in from recess). Out on a field trip it
     * shelters on its own.
     */
    @Override
    public boolean sheltersInPlace() {
        return key != null && mode != SchoolClasses.Mode.TRIP;
    }

    private void changeMode(Context ctx, SchoolClasses.Mode next) {
        mode = next;
        release(ctx);
        if (ctx.npc().isChairSeated()) ctx.npc().standUpFromChair();
        resetPose(ctx);
        ctx.model().stopMoving();
        resetMove();
        arrived = false;
        spot = null;
        spotTimer = 0;
        walkFailures = 0;
        if (next == SchoolClasses.Mode.CLASS) {
            seat = pickSeat(ctx, false);
            late = true;
        }
    }

    // =========================================================================
    // In class
    // =========================================================================

    private Status tickClass(Context ctx) {
        if (seat == null) {
            seat = pickSeat(ctx, false);
            if (seat == null) return Status.FAILED;
        }
        claim(ctx);
        SchoolClasses.checkIn(ctx.level(), ctx.model().npcId, key, arrived, seat.overflow());

        if (!arrived) {
            if (late) ctx.npc().hurry(HurryGait.JOG);
            if (!moveTo(ctx, seat.walkTo(), late ? JOG_SPEED : WALK_SPEED)) return Status.RUNNING;
            ctx.npc().stopHurry();
            if (moveFailed(ctx, seat.walkTo())) {
                release(ctx);
                if (++walkFailures > MAX_WALK_FAILURES) {
                    diary(ctx, "SCHOOL could not reach a seat in class");
                    return Status.FAILED;
                }
                seat = pickSeat(ctx, false);
                resetMove();
                return seat == null ? Status.FAILED : Status.RUNNING;
            }
            if (seat.chair() != null && !sitDown(ctx)) {
                // The chair was taken or out of reach after all: stand in a row instead.
                release(ctx);
                seat = pickSeat(ctx, true);
                resetMove();
                return seat == null ? Status.FAILED : Status.RUNNING;
            }
            arrived = true;
            late = false;
            ctx.model().stopMoving();
            ctx.npc().getBrainData().putLong(SchoolClasses.TAG_PUPIL_SEAT, seat.key().asLong());
            diary(ctx, "SCHOOL in class at " + seat.key().toShortString());
            return Status.RUNNING;
        }

        if (seat.chair() != null && !ctx.npc().isChairSeated()) {
            // Knocked off the chair: finish the sitting standing in a row.
            release(ctx);
            arrived = false;
            seat = pickSeat(ctx, true);
            resetMove();
            return seat == null ? Status.DONE : Status.RUNNING;
        }
        if (seat.chair() == null && ctx.npc().blockPosition().distSqr(seat.tile()) > OFF_SEAT_DIST_SQ) {
            // Pushed out of its place: walk back.
            arrived = false;
            resetMove();
            return Status.RUNNING;
        }

        followTeacher(ctx);
        if (++ticksInClass >= SESSION_TICKS) return Status.DONE;
        return Status.RUNNING;
    }

    /** Plays its part in the teacher's current stage and looks where a pupil would. */
    private void followTeacher(Context ctx) {
        ServerLevel level = ctx.level();
        UUID me = ctx.model().npcId;
        if (SchoolClasses.isDaydreaming(level, me)) {
            show(ctx, rest(ctx));
            if (--dreamLookTimer <= 0 || dreamLook == null) {
                dreamLookTimer = 60 + random.nextInt(60);
                double a = random.nextDouble() * Math.PI * 2;
                Vec3 eye = ctx.npc().getEyePosition();
                dreamLook = new Vec3(eye.x + Math.cos(a) * 3, eye.y + 0.5, eye.z + Math.sin(a) * 3);
            }
            ctx.npc().getLookControl().setLookAt(dreamLook.x, dreamLook.y, dreamLook.z, 10f, 10f);
            return;
        }
        SchoolClasses.Cue cue = SchoolClasses.cue(level, key);
        SchoolDay.Phase phase = SchoolDay.phase(level.getDayTime(), true);
        VillagerAnimationState want = switch (cue.kind()) {
            case READING -> VillagerAnimationState.READ;
            case QUIET_WORK -> VillagerAnimationState.THINK;
            case QUESTION -> me.equals(cue.pupil()) ? VillagerAnimationState.ARM_ON_HIP : rest(ctx);
            default -> phase == SchoolDay.Phase.READING ? VillagerAnimationState.READ : rest(ctx);
        };
        show(ctx, want);
        if (want == VillagerAnimationState.READ) return;
        lookAtTeacher(ctx);
    }

    private VillagerAnimationState rest(Context ctx) {
        return ctx.npc().isChairSeated() ? VillagerAnimationState.CHAIR_SIT : VillagerAnimationState.IDLE;
    }

    private void show(Context ctx, VillagerAnimationState state) {
        if (ctx.npc().getAnimation() != state) ctx.npc().setAnimation(state);
        shown = state;
    }

    /** Back to plain sitting or standing, whatever stage pose it had on. */
    private void resetPose(Context ctx) {
        VillagerAnimationState current = ctx.npc().getAnimation();
        if (current == VillagerAnimationState.READ || current == VillagerAnimationState.THINK
                || current == VillagerAnimationState.ARM_ON_HIP || current == VillagerAnimationState.DANCE
                || current == VillagerAnimationState.WAVE) {
            ctx.npc().setAnimation(rest(ctx));
        }
        shown = null;
    }

    // =========================================================================
    // Recess
    // =========================================================================

    private Status tickRecess(Context ctx) {
        SchoolClasses.checkIn(ctx.level(), ctx.model().npcId, key, true, false);
        SchoolClasses.Recess recess = SchoolClasses.recess(ctx.level(), key);
        if (recess == null) return Status.RUNNING;
        switch (recess.game) {
            case TAG -> tickTag(ctx, recess);
            case CIRCLE -> tickCircle(ctx, recess);
            default -> tickFreePlay(ctx, recess);
        }
        return Status.RUNNING;
    }

    private void tickTag(Context ctx, SchoolClasses.Recess recess) {
        UUID me = ctx.model().npcId;
        long now = ctx.level().getGameTime();
        List<UUID> playing = SchoolClasses.pupilsIn(ctx.level(), key);
        if (playing.size() < 2) {
            // Nobody to play tag with any more.
            tickFreePlay(ctx, recess);
            return;
        }
        checkTagRoles(recess, playing, now);
        if (me.equals(recess.it)) {
            NpcEntity target = puppetOf(ctx, recess.chased);
            if (target == null) {
                recess.chased = someoneElse(playing, me, null);
                return;
            }
            if (now - recess.taggedAt < TAG_COUNT_TICKS) {
                // Counting to three.
                ctx.model().stopMoving();
                ctx.npc().getLookControl().setLookAt(target, 20f, 20f);
                return;
            }
            ctx.npc().hurry(HurryGait.RUN);
            if (ctx.npc().distanceToSqr(target) < TAG_REACH * TAG_REACH) {
                // Tagged: the chased child is "it" now, and runs after someone
                // else; not straight back at the one who caught it, unless
                // there are only the two of them.
                UUID caught = recess.chased;
                recess.it = caught;
                UUID next = someoneElse(playing, caught, me);
                recess.chased = next != null ? next : me;
                recess.taggedAt = now;
                ctx.model().stopMoving();
                ctx.brain().showIcon(ThoughtIcon.EMOTE_LAUGHTER, 40, ctx.level());
                VillagerModel other = ctx.mgr().getModel(caught);
                if (other != null) other.brain().showIcon(ThoughtIcon.EMOTE_SURPRISED, 40, ctx.level());
                return;
            }
            if (--spotTimer <= 0 || !ctx.model().isMoving()) {
                spotTimer = 10;
                ctx.model().requestMoveTo(ctx.mgr().getGraph(), target.blockPosition(), RUN_SPEED);
            }
            return;
        }
        if (me.equals(recess.chased)) {
            NpcEntity it = puppetOf(ctx, recess.it);
            if (it == null) return;
            double d = ctx.npc().distanceToSqr(it);
            if (d < 64.0 && now - recess.taggedAt >= TAG_COUNT_TICKS / 2) {
                ctx.npc().hurry(HurryGait.RUN);
                boolean needSpot = spot == null || !ctx.model().isMoving()
                        || it.blockPosition().distSqr(spot) < 9.0;
                if (needSpot) {
                    spot = fleeSpot(ctx, recess, it.blockPosition());
                    if (spot != null) ctx.model().requestMoveTo(ctx.mgr().getGraph(), spot, RUN_SPEED);
                }
            } else {
                ctx.npc().getLookControl().setLookAt(it, 20f, 20f);
            }
            return;
        }
        tickFreePlay(ctx, recess);
    }

    /** The best of a few yard spots: the furthest from whoever is chasing. */
    @Nullable
    private BlockPos fleeSpot(Context ctx, SchoolClasses.Recess recess, BlockPos from) {
        BlockPos best = null;
        double bestDist = -1;
        for (int i = 0; i < 6; i++) {
            BlockPos s = yardSpot(ctx, recess);
            if (s == null) continue;
            double d = s.distSqr(from);
            if (d > bestDist) {
                bestDist = d;
                best = s;
            }
        }
        return best;
    }

    private void tickCircle(Context ctx, SchoolClasses.Recess recess) {
        if (spot == null) {
            List<UUID> ring = new ArrayList<>(SchoolClasses.pupilsIn(ctx.level(), key));
            ring.sort(null);
            int i = Math.max(0, ring.indexOf(ctx.model().npcId));
            int n = Math.max(1, ring.size());
            double a = 2 * Math.PI * i / n;
            double r = 1.5 + 0.25 * n;
            BlockPos guess = BlockPos.containing(recess.centre.getX() + 0.5 + Math.cos(a) * r,
                    recess.centre.getY(), recess.centre.getZ() + 0.5 + Math.sin(a) * r);
            NavGraph graph = ctx.mgr().getGraph();
            NavNode node = graph != null ? graph.nearestNonAvoided(guess, 2, 2) : null;
            spot = node != null ? node.feetPos() : recess.centre;
            resetMove();
        }
        if (!arrived) {
            if (!moveTo(ctx, spot, WALK_SPEED)) return;
            arrived = true;
        }
        ctx.npc().getLookControl().setLookAt(Vec3.atCenterOf(recess.centre).add(0, 0.5, 0));
        show(ctx, VillagerAnimationState.DANCE);
    }

    private void tickFreePlay(Context ctx, SchoolClasses.Recess recess) {
        if (spot == null || (!ctx.model().isMoving() && --spotTimer <= 0)) {
            spotTimer = 80 + random.nextInt(120);
            resetPose(ctx);
            spot = yardSpot(ctx, recess);
            if (spot != null) ctx.model().requestMoveTo(ctx.mgr().getGraph(), spot, WALK_SPEED);
            return;
        }
        if (!ctx.model().isMoving() && shown == null) {
            int roll = random.nextInt(100);
            if (roll < 30) show(ctx, VillagerAnimationState.DANCE);
            else if (roll < 45) show(ctx, VillagerAnimationState.WAVE);
            else shown = VillagerAnimationState.IDLE;
        }
    }

    /** A walkable spot in the yard outside the school. */
    @Nullable
    private BlockPos yardSpot(Context ctx, SchoolClasses.Recess recess) {
        NavGraph graph = ctx.mgr().getGraph();
        for (int i = 0; i < 4; i++) {
            BlockPos s = randomNodeNear(graph, recess.centre, YARD_RADIUS, 2, random);
            if (s == null) return null;
            if (school.getNavMeshBlocks().contains(s)) continue;
            if (!ctx.model().canReach(graph, s)) continue;
            return s;
        }
        return null;
    }

    /**
     * Whoever is "it" or being chased has left recess (gone to eat, say): the
     * game goes on with children still in the yard, instead of one child
     * staring after a friend who left, or chasing it home.
     */
    private void checkTagRoles(SchoolClasses.Recess recess, List<UUID> playing, long now) {
        if (recess.it == null || !playing.contains(recess.it)) {
            recess.it = playing.get(random.nextInt(playing.size()));
            recess.chased = null;
            recess.taggedAt = now;
        }
        if (recess.chased == null || recess.chased.equals(recess.it) || !playing.contains(recess.chased)) {
            recess.chased = someoneElse(playing, recess.it, null);
        }
    }

    @Nullable
    private UUID someoneElse(List<UUID> playing, @Nullable UUID not, @Nullable UUID notEither) {
        List<UUID> kids = new ArrayList<>();
        for (UUID id : playing) {
            if (id.equals(not) || id.equals(notEither)) continue;
            kids.add(id);
        }
        return kids.isEmpty() ? null : kids.get(random.nextInt(kids.size()));
    }

    @Nullable
    private static NpcEntity puppetOf(Context ctx, @Nullable UUID id) {
        VillagerModel model = id != null ? ctx.mgr().getModel(id) : null;
        NpcEntity puppet = model != null ? model.puppet() : null;
        return puppet != null && puppet.isAlive() ? puppet : null;
    }

    // =========================================================================
    // Field trip
    // =========================================================================

    private Status tickTrip(Context ctx) {
        BlockPos target = SchoolClasses.tripSpot(ctx.level(), key);
        if (target == null) {
            // No trip after all: wait in class for the others (doTick moves it over).
            tripFailed = true;
            return Status.RUNNING;
        }
        SchoolClasses.checkIn(ctx.level(), ctx.model().npcId, key, arrived, false);
        if (spot == null) {
            BlockPos near = randomNodeNear(ctx.mgr().getGraph(), target, 3, 2, random);
            spot = near != null && ctx.model().canReach(ctx.mgr().getGraph(), near) ? near : target;
            resetMove();
        }
        if (!arrived) {
            if (!moveTo(ctx, spot, WALK_SPEED)) return Status.RUNNING;
            if (moveFailed(ctx, spot)) {
                // Back to its seat until the trip is over (doTick moves it over).
                diary(ctx, "SCHOOL could not keep up on the field trip, waiting in class");
                tripFailed = true;
                resetMove();
                return Status.RUNNING;
            }
            arrived = true;
            ctx.model().stopMoving();
            diary(ctx, "SCHOOL on the field trip");
        }
        watchTrip(ctx);
        return Status.RUNNING;
    }

    /**
     * Out on the trip: watches the worker the class came to see (or the
     * workplace), now and then looks to the teacher, and is sometimes
     * impressed.
     */
    private void watchTrip(Context ctx) {
        UUID workerId = SchoolClasses.tripWorker(ctx.level(), key);
        NpcEntity worker = puppetOf(ctx, workerId);
        BlockPos look = SchoolClasses.tripLook(ctx.level(), key);
        boolean toTeacher = (ctx.npc().tickCount + ctx.model().npcId.hashCode()) % 200 >= 150;
        if (!toTeacher && worker != null && worker.distanceToSqr(ctx.npc()) < 24 * 24) {
            ctx.npc().getLookControl().setLookAt(worker, 20f, 20f);
        } else if (!toTeacher && look != null) {
            ctx.npc().getLookControl().setLookAt(Vec3.atCenterOf(look));
        } else {
            lookAtTeacher(ctx);
        }
        if (++spotTimer % 100 == 0 && random.nextInt(4) == 0) {
            ctx.brain().showIcon(ThoughtIcon.EMOTE_ADMIRATION, 40, ctx.level());
        }
    }

    // =========================================================================
    // Seats
    // =========================================================================

    /**
     * This child's own seat when it is free, else the first free seat (desks
     * first, then the rows from the front), else a place at the back. An own
     * place at the back only counts while nothing better is free: a child
     * once squeezed to the back (where it learns less) moves up when a seat
     * opens.
     */
    @Nullable
    private SchoolSeats.Seat pickSeat(Context ctx, boolean standingOnly) {
        CompoundTag data = ctx.npc().getBrainData();
        boolean hasOwn = data.contains(SchoolClasses.TAG_PUPIL_SEAT);
        long own = hasOwn ? data.getLong(SchoolClasses.TAG_PUPIL_SEAT) : 0L;
        SchoolSeats.Seat first = null;
        SchoolSeats.Seat back = null;
        SchoolSeats.Seat ownBack = null;
        for (SchoolSeats.Seat s : seats) {
            if (standingOnly && s.chair() != null) continue;
            if (!isFree(ctx, s)) continue;
            boolean mine = hasOwn && s.key().asLong() == own;
            if (mine && !s.overflow()) return take(ctx, s);
            if (s.overflow()) {
                if (mine) ownBack = s;
                if (back == null) back = s;
            } else if (first == null) {
                first = s;
            }
        }
        SchoolSeats.Seat pick = first != null ? first : ownBack != null ? ownBack : back;
        return pick != null ? take(ctx, pick) : null;
    }

    private SchoolSeats.Seat take(Context ctx, SchoolSeats.Seat s) {
        seat = s;
        claim(ctx);
        return s;
    }

    private boolean isFree(Context ctx, SchoolSeats.Seat s) {
        UUID me = ctx.npc().getUUID();
        if (s.chair() != null) return VillageChairs.isFreeFor(ctx.level(), s.chair().pos(), me);
        if (!SchoolClasses.isSeatFreeFor(ctx.level(), s.tile(), me)) return false;
        for (VillagerModel m : ctx.mgr().getVillagers()) {
            NpcEntity other = m.puppet();
            if (other != null && other != ctx.npc() && other.isAlive()
                    && other.blockPosition().equals(s.tile())) return false;
        }
        return true;
    }

    private void claim(Context ctx) {
        if (seat == null || mode != SchoolClasses.Mode.CLASS) return;
        ServerLevel level = ctx.level();
        UUID me = ctx.npc().getUUID();
        if (seat.chair() != null) VillageChairs.claim(level, seat.chair().pos(), me);
        else SchoolClasses.claimSeat(level, seat.tile(), me);
    }

    private void release(Context ctx) {
        if (seat == null) return;
        ServerLevel level = ctx.level();
        UUID me = ctx.npc().getUUID();
        if (seat.chair() != null) VillageChairs.release(level, seat.chair().pos(), me);
        else SchoolClasses.releaseSeat(level, seat.tile(), me);
    }

    private boolean sitDown(Context ctx) {
        BlockPos chair = seat.chair().pos();
        if (ctx.npc().distanceToSqr(chair.getCenter()) > SIT_REACH_SQ) return false;
        if (!chair.equals(ctx.npc().seatedChairPos())
                && !VillageChairs.isFreeFor(ctx.level(), chair, ctx.npc().getUUID())) return false;
        return ctx.npc().sitOnChair(chair);
    }

    /** Looks at the teacher, or at the lectern while the teacher is still on its way. */
    private void lookAtTeacher(Context ctx) {
        UUID teacherId = SchoolClasses.teacherOf(ctx.level(), key);
        NpcEntity puppet = puppetOf(ctx, teacherId);
        if (puppet != null && puppet.distanceToSqr(ctx.npc()) < 24 * 24) {
            ctx.npc().getLookControl().setLookAt(puppet, 20f, 20f);
        } else {
            BlockPos lectern = key.lectern();
            ctx.npc().getLookControl().setLookAt(lectern.getX() + 0.5, lectern.getY() + 1.2,
                    lectern.getZ() + 0.5, 20f, 20f);
        }
    }

    private static void diary(Context ctx, String message) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + message);
    }

    // =========================================================================
    // Village queries
    // =========================================================================

    /** Cheap pre-check for brain scoring: a class this child can reach is open right now. */
    public static boolean hasWork(Context ctx) {
        return SchoolClasses.pickClassFor(ctx) != null;
    }

    /** The school bell has just rung for a class this child can go to: leave the game. */
    public static boolean hasUrgentWork(Context ctx) {
        SchoolClasses.ClassKey key = SchoolClasses.pickClassFor(ctx);
        return key != null && SchoolClasses.bellRecent(ctx.level(), key.school(), LATE_BELL_TICKS);
    }
}

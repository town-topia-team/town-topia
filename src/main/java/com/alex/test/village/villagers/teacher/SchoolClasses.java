package com.alex.test.village.villagers.teacher;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.EveningSchedule;
import com.alex.test.village.brain.VillagerPause;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.SchoolBuilding;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Classes at the village schools: which teacher owns which lectern, which
 * classes are open right now, and which children are sitting in them.
 *
 * <p>A class is one lectern in one school. A teacher claims a lectern for
 * good (saved in its brain data, like a bed claim) and opens its class while
 * it is teaching or walking over to teach; a child only goes to school while
 * a class is open, and checks in to it every tick it sits there. The teacher
 * pays the children checked in to its own class, so who counts as a pupil no
 * longer depends on which floor tile somebody happens to stand on: a child on
 * a chair counts, an adult walking through does not.
 *
 * <p>Open classes and check-ins live only in memory and expire on their own
 * when nobody refreshes them, so an activity that ends without cleaning up
 * cannot hold a class open. What must survive a reload is saved with the
 * villager: the teacher's lectern, and each child's class and seat.
 */
public final class SchoolClasses {

    /** One class: a lectern in a school. */
    public record ClassKey(UUID school, BlockPos lectern) {}

    /** A class stays open this long after its teacher last said so. */
    public static final int OPEN_GRACE_TICKS = 100;
    /** A pupil not heard from for this long has left the class. */
    public static final int PRESENCE_TICKS = 40;
    private static final int SEAT_CLAIM_TICKS = 40;
    /**
     * A teacher keeps its own lectern while its school is out of reach for
     * this long (a moved block, a map hiccup) before it takes a free one it
     * can reach instead.
     */
    public static final int SWAP_SCHOOL_AFTER_TICKS = 6000;

    public static final String TAG_TEACHER_SCHOOL  = "TeacherSchool";
    public static final String TAG_TEACHER_LECTERN = "TeacherLectern";
    public static final String TAG_PREP_DAY        = "TeacherPrepDay";
    public static final String TAG_PUPIL_SCHOOL    = "SchoolId";
    public static final String TAG_PUPIL_LECTERN   = "SchoolLectern";
    public static final String TAG_PUPIL_SEAT      = "SchoolSeat";

    private record Open(UUID teacher, long until) {}
    private record Presence(ClassKey key, long seenAt, boolean arrived, boolean back) {}
    private record SeatClaim(UUID owner, long refreshedAt) {}

    /** Where an open class is: in its seats, out in the yard, or on a field trip. */
    public enum Mode { CLASS, RECESS, TRIP }

    /** What the teacher is doing right now, so the children can play their part. */
    public enum CueKind { NONE, EXPLAIN, READ_ALOUD, WALK_ROWS, QUESTION, QUIET_WORK, READING }

    /** The teacher's current lesson stage; {@code pupil} is the child it is about, if any. */
    public record Cue(CueKind kind, @Nullable UUID pupil, long until) {
        static final Cue NONE = new Cue(CueKind.NONE, null, 0L);
    }

    /** Recess games. */
    public enum Game { FREE, TAG, CIRCLE }

    /** One recess: the game, who is "it" and who is being chased, and the circle's middle. */
    public static final class Recess {
        public final Game game;
        public final BlockPos centre;
        @Nullable public UUID it;
        @Nullable public UUID chased;
        /** When the last tag happened: the new "it" counts to three before chasing. */
        public long taggedAt;

        Recess(Game game, BlockPos centre) {
            this.game = game;
            this.centre = centre;
        }
    }

    private static final class ClassInfo {
        Mode mode = Mode.CLASS;
        Cue cue = Cue.NONE;
        @Nullable Recess recess;
        @Nullable BlockPos tripSpot;
        /** What the trip watches: the workplace door, or where its worker was. */
        @Nullable BlockPos tripLook;
        /** The worker the trip watches, if one was at it. */
        @Nullable UUID tripWorker;
        @Nullable Profession subject;
    }

    private static final class State {
        final Map<ClassKey, Open> open = new HashMap<>();
        final Map<UUID, Presence> pupils = new HashMap<>();
        final Map<BlockPos, SeatClaim> seats = new HashMap<>();
        final Map<UUID, Long> quietUntil = new HashMap<>();
        final Map<UUID, Integer> emptyStreak = new HashMap<>();
        final Map<ClassKey, ClassInfo> info = new HashMap<>();
        final Map<UUID, Long> lastBell = new HashMap<>();
        final Map<UUID, Long> daydreamUntil = new HashMap<>();
        /** The day each teacher's run of empty classes belongs to: a new day starts it over. */
        final Map<UUID, Long> streakDay = new HashMap<>();
        /** Each teacher's lectern as last seen, so it still counts while the teacher is far away. */
        final Map<UUID, ClassKey> knownClaims = new HashMap<>();
        /** Since when a teacher's own school has been out of its reach. */
        final Map<UUID, Long> outOfReachSince = new HashMap<>();
        /** Why a teacher last had no work, so its diary says so once, not every check. */
        final Map<UUID, String> noWorkReason = new HashMap<>();
    }

    private static final Map<ServerLevel, State> STATES = new WeakHashMap<>();

    private SchoolClasses() {}

    private static State state(ServerLevel level) {
        return STATES.computeIfAbsent(level, l -> new State());
    }

    // =========================================================================
    // Teachers: lectern claims
    // =========================================================================

    /** The class this teacher has claimed, as saved; null when it has none. */
    @Nullable
    public static ClassKey savedTeacherClass(NpcEntity npc) {
        CompoundTag data = npc.getBrainData();
        if (!data.hasUUID(TAG_TEACHER_SCHOOL) || !data.contains(TAG_TEACHER_LECTERN)) return null;
        return new ClassKey(data.getUUID(TAG_TEACHER_SCHOOL),
                BlockPos.of(data.getLong(TAG_TEACHER_LECTERN)));
    }

    private static void saveTeacherClass(ServerLevel level, UUID me, NpcEntity npc, @Nullable ClassKey key) {
        CompoundTag data = npc.getBrainData();
        state(level).outOfReachSince.remove(me);
        if (key == null) {
            state(level).knownClaims.remove(me);
            data.remove(TAG_TEACHER_SCHOOL);
            data.remove(TAG_TEACHER_LECTERN);
            return;
        }
        data.putUUID(TAG_TEACHER_SCHOOL, key.school());
        data.putLong(TAG_TEACHER_LECTERN, key.lectern().asLong());
        state(level).knownClaims.put(me, key);
    }

    /**
     * The lectern a teacher of this village holds, also while it is far away
     * (unloaded): then its lectern as last seen this session.
     */
    @Nullable
    public static ClassKey claimOf(VillageManager mgr, VillagerModel model) {
        NpcEntity npc = model.puppet();
        ServerLevel level = mgr.getLevel();
        if (npc != null) {
            ClassKey key = savedTeacherClass(npc);
            if (level != null) {
                if (key != null) state(level).knownClaims.put(model.npcId, key);
                else state(level).knownClaims.remove(model.npcId);
            }
            return key;
        }
        return level != null ? state(level).knownClaims.get(model.npcId) : null;
    }

    /**
     * Who owns which lectern and how many children each class has, read from
     * the loaded villagers in one pass. A lectern two teachers saved belongs
     * to the one with the smallest id, so it settles the same way every time.
     */
    private record Roster(Map<ClassKey, UUID> owners, Map<ClassKey, Integer> pupils) {
        int pupilsOf(ClassKey key) { return pupils.getOrDefault(key, 0); }
    }

    private static Roster roster(VillageManager mgr) {
        Map<ClassKey, UUID> owners = new HashMap<>();
        Map<ClassKey, Integer> pupils = new HashMap<>();
        for (VillagerModel model : mgr.getVillagers()) {
            Profession profession = model.brain().profession();
            if (profession != Profession.TEACHER && profession != Profession.CHILD) continue;
            NpcEntity npc = model.puppet();
            if (npc != null && !npc.isAlive()) continue;
            if (profession == Profession.TEACHER) {
                // A teacher far away still holds its lectern, so its children stay enrolled.
                ClassKey key = claimOf(mgr, model);
                if (key != null) owners.merge(key, model.npcId, (a, b) -> a.compareTo(b) <= 0 ? a : b);
            } else if (npc != null) {
                ClassKey key = savedEnrolment(npc);
                if (key != null) pupils.merge(key, 1, Integer::sum);
            }
        }
        return new Roster(owners, pupils);
    }

    /** The teacher whose class {@code key} is, among the loaded teachers. */
    @Nullable
    public static UUID lecternOwner(VillageManager mgr, ClassKey key) {
        return roster(mgr).owners().get(key);
    }

    /** A school this villager can walk into, with {@code key}'s lectern still standing. */
    @Nullable
    public static SchoolBuilding usableSchool(Activity.Context ctx, ClassKey key) {
        Building building = ctx.mgr().getBuilding(key.school());
        if (!(building instanceof SchoolBuilding school) || !school.isValid()) return null;
        if (!school.getFunctionalBlocks().contains(key.lectern())) return null;
        if (!ctx.model().canReach(ctx.mgr().getGraph(), school.entryTile())) return null;
        return school;
    }

    /**
     * The class this teacher teaches. Its saved lectern while that still
     * stands and is its own, even if the school is out of reach for now; only
     * after {@link #SWAP_SCHOOL_AFTER_TICKS} out of reach does it take a free
     * lectern it can reach instead. Without one of its own: the nearest free
     * lectern it can reach, else the nearest free lectern at all, so the
     * teacher belongs to a school as soon as there is one. Whether it can
     * walk there now is for the caller ({@link #usableSchool}).
     *
     * <p>With {@code save} the answer is written to the teacher's brain data;
     * without it this is only a question.
     */
    @Nullable
    public static ClassKey resolveTeacherClass(Activity.Context ctx, boolean save) {
        NpcEntity npc = ctx.npc();
        ServerLevel level = ctx.level();
        UUID me = ctx.model().npcId;
        Roster roster = roster(ctx.mgr());
        ClassKey saved = savedTeacherClass(npc);
        boolean savedMine = saved != null && lecternStands(ctx.mgr(), saved) && me.equals(roster.owners().get(saved));
        if (savedMine) {
            if (usableSchool(ctx, saved) != null) {
                if (save) state(level).outOfReachSince.remove(me);
                return saved;
            }
            long now = level.getGameTime();
            Long since = state(level).outOfReachSince.get(me);
            if (since == null) {
                if (save) state(level).outOfReachSince.put(me, now);
                return saved;
            }
            if (now - since < SWAP_SCHOOL_AFTER_TICKS) return saved;
        }

        NavGraph graph = ctx.mgr().getGraph();
        ClassKey reachable = null;
        ClassKey any = null;
        double reachDist = Double.MAX_VALUE;
        double anyDist = Double.MAX_VALUE;
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof SchoolBuilding school) || !school.isValid()) continue;
            List<BlockPos> lecterns = school.getLecterns();
            if (lecterns.isEmpty()) continue;
            boolean canReach = ctx.model().canReach(graph, school.entryTile());
            for (BlockPos lectern : lecterns) {
                ClassKey key = new ClassKey(school.getItemFrameId(), lectern);
                if (key.equals(saved)) continue;
                UUID owner = roster.owners().get(key);
                if (owner != null && !owner.equals(me)) continue;
                double d = lectern.distSqr(npc.blockPosition());
                if (canReach && d < reachDist) {
                    reachDist = d;
                    reachable = key;
                }
                if (d < anyDist) {
                    anyDist = d;
                    any = key;
                }
            }
        }
        // Long out of reach with no free lectern it can get to: it stays where it was.
        ClassKey best = reachable != null ? reachable : savedMine ? saved : any;
        if (save && best != null && !best.equals(saved)) saveTeacherClass(level, me, npc, best);
        else if (save && best == null && saved != null) saveTeacherClass(level, me, npc, null);
        return best;
    }

    private static boolean lecternStands(VillageManager mgr, ClassKey key) {
        return mgr.getBuilding(key.school()) instanceof SchoolBuilding school
                && school.getFunctionalBlocks().contains(key.lectern());
    }

    /** The child's class still exists: its lectern stands and a teacher owns it. */
    private static boolean enrolmentHolds(VillageManager mgr, Roster roster, @Nullable ClassKey enrolled) {
        return enrolled != null && lecternStands(mgr, enrolled) && roster.owners().containsKey(enrolled);
    }

    /**
     * Whether a child would come to {@code key} if it opened now: a child who
     * can walk to the school and has no class of its own, is in this one, is
     * in one that is shut for now, or is in one with two or more children
     * more than this one (it would move over to even them out).
     */
    public static boolean anyPupilFor(Activity.Context ctx, SchoolBuilding school, ClassKey key) {
        VillageManager mgr = ctx.mgr();
        NavGraph graph = mgr.getGraph();
        Roster roster = null;
        for (VillagerModel model : mgr.getVillagers()) {
            if (model.brain().profession() != Profession.CHILD) continue;
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive()) continue;
            if (!model.canReach(graph, school.entryTile())) continue;
            if (roster == null) roster = roster(mgr);
            ClassKey enrolled = savedEnrolment(npc);
            if (key.equals(enrolled) || !enrolmentHolds(mgr, roster, enrolled)) return true;
            if (teacherOf(ctx.level(), enrolled) == null) return true;
            if (roster.pupilsOf(key) < roster.pupilsOf(enrolled) - 1) return true;
        }
        return false;
    }

    // =========================================================================
    // Open classes
    // =========================================================================

    /** The teacher is teaching (or on its way to teach) {@code key}: keep it open a little longer. */
    public static void open(ServerLevel level, ClassKey key, UUID teacher) {
        state(level).open.put(key, new Open(teacher, level.getGameTime() + OPEN_GRACE_TICKS));
    }

    /** The teacher is done with {@code key}; a class held by somebody else is left alone. */
    public static void close(ServerLevel level, ClassKey key, UUID teacher) {
        State state = state(level);
        Open open = state.open.get(key);
        if (open != null && open.teacher().equals(teacher)) {
            state.open.remove(key);
            state.info.remove(key);
        }
    }

    private static ClassInfo info(ServerLevel level, ClassKey key) {
        return state(level).info.computeIfAbsent(key, k -> new ClassInfo());
    }

    /** Where the class is: seats, yard or field trip. */
    public static Mode mode(ServerLevel level, ClassKey key) {
        ClassInfo info = state(level).info.get(key);
        return info != null ? info.mode : Mode.CLASS;
    }

    public static void setMode(ServerLevel level, ClassKey key, Mode mode) {
        ClassInfo info = info(level, key);
        info.mode = mode;
        if (mode != Mode.RECESS) info.recess = null;
        if (mode != Mode.TRIP) {
            info.tripSpot = null;
            info.tripLook = null;
            info.tripWorker = null;
        }
    }

    /** The teacher's lesson stage, while it lasts. */
    public static Cue cue(ServerLevel level, ClassKey key) {
        ClassInfo info = state(level).info.get(key);
        if (info == null || info.cue.until() < level.getGameTime()) return Cue.NONE;
        return info.cue;
    }

    public static void setCue(ServerLevel level, ClassKey key, CueKind kind, @Nullable UUID pupil, int ticks) {
        info(level, key).cue = new Cue(kind, pupil, level.getGameTime() + ticks);
    }

    /** Starts a recess game; {@code it} and {@code chased} are only used by tag. */
    public static Recess startRecess(ServerLevel level, ClassKey key, Game game, BlockPos centre,
                                     @Nullable UUID it, @Nullable UUID chased) {
        ClassInfo info = info(level, key);
        info.mode = Mode.RECESS;
        Recess recess = new Recess(game, centre);
        recess.it = it;
        recess.chased = chased;
        recess.taggedAt = level.getGameTime();
        info.recess = recess;
        return recess;
    }

    @Nullable
    public static Recess recess(ServerLevel level, ClassKey key) {
        ClassInfo info = state(level).info.get(key);
        return info != null && info.mode == Mode.RECESS ? info.recess : null;
    }

    /** The class's field trip destination, while it is out on one. */
    @Nullable
    public static BlockPos tripSpot(ServerLevel level, ClassKey key) {
        ClassInfo info = state(level).info.get(key);
        return info != null && info.mode == Mode.TRIP ? info.tripSpot : null;
    }

    /** Out on a field trip to {@code spot}, to watch {@code worker} (if any) or {@code look}. */
    public static void startTrip(ServerLevel level, ClassKey key, BlockPos spot, @Nullable BlockPos look,
                                 @Nullable UUID worker) {
        ClassInfo info = info(level, key);
        info.mode = Mode.TRIP;
        info.tripSpot = spot.immutable();
        info.tripLook = look != null ? look.immutable() : null;
        info.tripWorker = worker;
    }

    /** What the class on a field trip is there to watch; null when not out on one. */
    @Nullable
    public static BlockPos tripLook(ServerLevel level, ClassKey key) {
        ClassInfo info = state(level).info.get(key);
        return info != null && info.mode == Mode.TRIP ? info.tripLook : null;
    }

    /** The worker the class on a field trip is watching, if there is one. */
    @Nullable
    public static UUID tripWorker(ServerLevel level, ClassKey key) {
        ClassInfo info = state(level).info.get(key);
        return info != null && info.mode == Mode.TRIP ? info.tripWorker : null;
    }

    /** This lesson's subject; null in reading hour or between lessons. */
    @Nullable
    public static Profession subject(ServerLevel level, ClassKey key) {
        ClassInfo info = state(level).info.get(key);
        return info != null ? info.subject : null;
    }

    public static void setSubject(ServerLevel level, ClassKey key, @Nullable Profession subject) {
        info(level, key).subject = subject;
    }

    // =========================================================================
    // Bells and daydreams
    // =========================================================================

    /** The school's bell has just rung. */
    public static void bellRung(ServerLevel level, UUID school) {
        state(level).lastBell.put(school, level.getGameTime());
    }

    /** Whether {@code school}'s bell rang within the last {@code ticks}. */
    public static boolean bellRecent(ServerLevel level, UUID school, int ticks) {
        Long at = state(level).lastBell.get(school);
        return at != null && level.getGameTime() - at <= ticks && level.getGameTime() >= at;
    }

    /** This child is miles away until {@code until}. */
    public static void daydream(ServerLevel level, UUID pupil, long until) {
        state(level).daydreamUntil.put(pupil, until);
    }

    public static boolean isDaydreaming(ServerLevel level, UUID pupil) {
        Long until = state(level).daydreamUntil.get(pupil);
        return until != null && level.getGameTime() < until;
    }

    /** The teacher came over: back with the class. */
    public static void wakeUp(ServerLevel level, UUID pupil) {
        state(level).daydreamUntil.remove(pupil);
    }

    /** The teacher of {@code key} while it is open, else null. */
    @Nullable
    public static UUID teacherOf(ServerLevel level, ClassKey key) {
        Open open = state(level).open.get(key);
        return open != null && open.until() >= level.getGameTime() ? open.teacher() : null;
    }

    /** Every class open right now, in any village on this level. */
    public static List<ClassKey> openClasses(ServerLevel level) {
        State state = state(level);
        long now = level.getGameTime();
        state.open.values().removeIf(open -> open.until() < now);
        return new ArrayList<>(state.open.keySet());
    }

    // =========================================================================
    // Children: enrolment and check-in
    // =========================================================================

    /** The class this child is enrolled in, as saved; null when it has none. */
    @Nullable
    public static ClassKey savedEnrolment(NpcEntity npc) {
        CompoundTag data = npc.getBrainData();
        if (!data.hasUUID(TAG_PUPIL_SCHOOL) || !data.contains(TAG_PUPIL_LECTERN)) return null;
        return new ClassKey(data.getUUID(TAG_PUPIL_SCHOOL), BlockPos.of(data.getLong(TAG_PUPIL_LECTERN)));
    }

    /**
     * The open class this child should go to, or null when none it can reach
     * is open. Its own class when that is open, unless another open class has
     * fewer children than its own would have without it: then it moves over,
     * so two classes even out. With its own class shut, or none yet, the open
     * class with the fewest children, nearest first.
     */
    @Nullable
    public static ClassKey pickClassFor(Activity.Context ctx) {
        ServerLevel level = ctx.level();
        List<ClassKey> reachable = new ArrayList<>();
        for (ClassKey key : openClasses(level)) {
            Building building = ctx.mgr().getBuilding(key.school());
            if (!(building instanceof SchoolBuilding school) || !school.isValid()) continue;
            if (!ctx.model().canReach(ctx.mgr().getGraph(), school.entryTile())) continue;
            reachable.add(key);
        }
        if (reachable.isEmpty()) return null;

        Roster roster = roster(ctx.mgr());
        ClassKey enrolled = savedEnrolment(ctx.npc());
        ClassKey best = null;
        int bestLoad = Integer.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        for (ClassKey key : reachable) {
            if (key.equals(enrolled)) continue;
            int load = roster.pupilsOf(key);
            double d = ctx.mgr().getBuilding(key.school()).getDoorPos().distSqr(ctx.npc().blockPosition());
            if (load < bestLoad || (load == bestLoad && d < bestDist)) {
                best = key;
                bestLoad = load;
                bestDist = d;
            }
        }
        if (enrolled != null && reachable.contains(enrolled)) {
            int othersInMine = roster.pupilsOf(enrolled) - 1;
            return best != null && bestLoad < othersInMine ? best : enrolled;
        }
        return best;
    }

    /**
     * Makes {@code key} this child's own class when it has none yet, when its
     * old one has lost its teacher or lectern, or when it left its own open
     * class to even the classes out. A class that is only shut for now keeps
     * the child: it goes back there next time.
     */
    public static void enrolIfNeeded(Activity.Context ctx, ClassKey key) {
        NpcEntity npc = ctx.npc();
        ClassKey enrolled = savedEnrolment(npc);
        if (key.equals(enrolled)) return;
        if (enrolmentHolds(ctx.mgr(), roster(ctx.mgr()), enrolled)
                && teacherOf(ctx.level(), enrolled) == null) return;
        CompoundTag data = npc.getBrainData();
        data.putUUID(TAG_PUPIL_SCHOOL, key.school());
        data.putLong(TAG_PUPIL_LECTERN, key.lectern().asLong());
        data.remove(TAG_PUPIL_SEAT);
    }

    /** The child is in class (or, with {@code arrived} false, on its way): refresh every tick. */
    public static void checkIn(ServerLevel level, UUID pupil, ClassKey key, boolean arrived, boolean back) {
        state(level).pupils.put(pupil, new Presence(key, level.getGameTime(), arrived, back));
    }

    /** The child is standing at the back: no seat was left for it. */
    public static boolean isAtBack(ServerLevel level, UUID pupil) {
        Presence p = state(level).pupils.get(pupil);
        return p != null && p.back();
    }

    /** Where the child checked in last, while it is still in or on its way to a class. */
    @Nullable
    public static ClassKey classOf(ServerLevel level, UUID pupil) {
        Presence p = state(level).pupils.get(pupil);
        return p != null && level.getGameTime() - p.seenAt() <= PRESENCE_TICKS ? p.key() : null;
    }

    /** The child has left whatever class it was in. */
    public static void leave(ServerLevel level, UUID pupil) {
        state(level).pupils.remove(pupil);
    }

    /** Children sitting in {@code key} right now. */
    public static List<UUID> pupilsIn(ServerLevel level, ClassKey key) {
        List<UUID> out = new ArrayList<>();
        long now = level.getGameTime();
        for (Map.Entry<UUID, Presence> entry : state(level).pupils.entrySet()) {
            Presence p = entry.getValue();
            if (p.arrived() && p.key().equals(key) && now - p.seenAt() <= PRESENCE_TICKS) out.add(entry.getKey());
        }
        return out;
    }

    /** Children on their way to {@code key} right now. */
    public static int coming(ServerLevel level, ClassKey key) {
        int count = 0;
        long now = level.getGameTime();
        for (Presence p : state(level).pupils.values()) {
            if (!p.arrived() && p.key().equals(key) && now - p.seenAt() <= PRESENCE_TICKS) count++;
        }
        return count;
    }

    /** Children in or on their way to {@code key}. */
    public static int headcount(ServerLevel level, ClassKey key) {
        int count = 0;
        long now = level.getGameTime();
        for (Presence p : state(level).pupils.values()) {
            if (p.key().equals(key) && now - p.seenAt() <= PRESENCE_TICKS) count++;
        }
        return count;
    }

    /**
     * Teaching or sitting in a class in {@code school} right now. Crowding
     * leaves these out: a class is meant to be full.
     */
    public static boolean isInClass(ServerLevel level, UUID npcId, UUID school) {
        State state = state(level);
        long now = level.getGameTime();
        Presence p = state.pupils.get(npcId);
        if (p != null && p.arrived() && p.key().school().equals(school)
                && now - p.seenAt() <= PRESENCE_TICKS) return true;
        for (Map.Entry<ClassKey, Open> entry : state.open.entrySet()) {
            Open open = entry.getValue();
            if (open.teacher().equals(npcId) && open.until() >= now
                    && entry.getKey().school().equals(school)) return true;
        }
        return false;
    }

    // =========================================================================
    // Standing places (chairs use VillageChairs)
    // =========================================================================

    public static boolean isSeatFreeFor(ServerLevel level, BlockPos tile, UUID who) {
        SeatClaim claim = state(level).seats.get(tile);
        return claim == null || claim.owner().equals(who)
                || VillagerPause.stale(claim.owner(), claim.refreshedAt(), SEAT_CLAIM_TICKS, level.getGameTime());
    }

    /** Take or refresh a standing place. Call every tick it is still wanted. */
    public static void claimSeat(ServerLevel level, BlockPos tile, UUID who) {
        state(level).seats.put(tile.immutable(), new SeatClaim(who, level.getGameTime()));
    }

    public static void releaseSeat(ServerLevel level, BlockPos tile, UUID who) {
        Map<BlockPos, SeatClaim> seats = state(level).seats;
        SeatClaim claim = seats.get(tile);
        if (claim != null && claim.owner().equals(who)) seats.remove(tile);
    }

    // =========================================================================
    // Teachers: quiet spells and preparation days
    // =========================================================================

    /**
     * Nobody came to this teacher's class, or it could not get there: no new
     * lesson for a while. Each such class in a row doubles the wait, up to
     * four times {@code baseTicks}; a class children came to starts it over.
     * Returns the wait.
     */
    public static long quietAfterEmpty(ServerLevel level, UUID teacher, long baseTicks) {
        State state = state(level);
        long day = EveningSchedule.dayIndex(level.getDayTime());
        Long streakDay = state.streakDay.put(teacher, day);
        if (streakDay == null || streakDay != day) state.emptyStreak.remove(teacher);
        int streak = state.emptyStreak.merge(teacher, 1, Integer::sum);
        long wait = baseTicks << Math.min(streak - 1, 2);
        state.quietUntil.put(teacher, level.getGameTime() + wait);
        return wait;
    }

    /**
     * A short break that does not grow: the teacher could not get somewhere,
     * which says nothing about whether children will come next time.
     */
    public static void quietFor(ServerLevel level, UUID teacher, long ticks) {
        state(level).quietUntil.put(teacher, level.getGameTime() + ticks);
    }

    /**
     * Records why this teacher has no work right now (null: it has). True
     * when the reason is new, so the diary only says it once.
     */
    public static boolean noteNoWork(ServerLevel level, UUID teacher, @Nullable String reason) {
        Map<UUID, String> reasons = state(level).noWorkReason;
        String was = reason == null ? reasons.remove(teacher) : reasons.put(teacher, reason);
        return reason != null && !reason.equals(was);
    }

    /** Why this teacher last had no work; null when it had. */
    @Nullable
    public static String noWorkReason(ServerLevel level, UUID teacher) {
        return state(level).noWorkReason.get(teacher);
    }

    /** Children came to this teacher's class. */
    public static void classFilled(ServerLevel level, UUID teacher) {
        state(level).emptyStreak.remove(teacher);
    }

    public static boolean isQuiet(ServerLevel level, UUID teacher) {
        Long until = state(level).quietUntil.get(teacher);
        return until != null && level.getGameTime() < until;
    }

    /** Whether this teacher has already prepared lessons on village day {@code day}. */
    public static boolean preparedOn(NpcEntity npc, long day) {
        CompoundTag data = npc.getBrainData();
        return data.contains(TAG_PREP_DAY) && data.getLong(TAG_PREP_DAY) == day;
    }

    public static void markPrepared(NpcEntity npc, long day) {
        npc.getBrainData().putLong(TAG_PREP_DAY, day);
    }
}

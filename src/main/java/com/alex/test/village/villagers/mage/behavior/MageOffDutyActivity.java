package com.alex.test.village.villagers.mage.behavior;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.VillagerBrain;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.building.ArcheryRangeBuilding;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.LibraryBuilding;
import com.alex.test.village.building.MageTowerBuilding;
import com.alex.test.village.stats.VillagerStats;
import com.alex.test.village.villagers.archer.behavior.ArcheryLanes;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.GuardBalance;
import com.alex.test.village.villagers.guard.behavior.BarracksDrills;
import com.alex.test.village.villagers.guard.behavior.GuardAlertSystem;
import com.alex.test.village.villagers.guard.behavior.combat.GuardCombatRules;
import com.alex.test.village.villagers.mage.MageBalance;
import com.alex.test.village.villagers.mage.MageCastTiming;
import com.alex.test.village.villagers.mage.MageSettingsBridge;
import com.alex.test.village.villagers.mage.settings.MageSettingsSchema;
import com.alex.test.village.villagers.registry.VillagerRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The mage's off-duty day (mage plan round 5): in the window the guards drill
 * in (see {@link BarracksDrills#inWindow}), it either studies or
 * practises, taking turns when it can do both.
 *
 * <ul>
 *   <li><b>Study</b> at a Mage Tower's enchanting table or lectern, or at a
 *       Library's bookshelf (the enchanting tables there are the enchanter's),
 *       one mage to each: it stands at the station and motes of light drift to its
 *       hands from the bookshelves, the way they drift to an enchanting
 *       table. Nothing is held. A Mage Tower teaches faster than a Library,
 *       the more bookshelves the faster, and its focus crystals glow. Study
 *       goes on after dark: a day-shift mage studies between its shift and bed.</li>
 *   <li><b>Practice</b> at an Archery Range lane, shared with the archers
 *       ({@link ArcheryLanes}): it casts practice bolts at the target, the
 *       bolt clip and the glow and all, and harms nothing.</li>
 * </ul>
 *
 * <p>Both pay into the mage's skill like an archer's range practice (a
 * defender's drill, {@link GuardBalance#DRILL_EXPERIENCE_PER_MINUTE}). A
 * session lasts {@value #SESSION_TICKS} ticks; the brain may start another.
 * It ends at once at an alarm or an alert, when the mage goes on duty, at
 * nightfall (practice only), when the window closes, or when it gets hungry. "Studying" and "Practising" in
 * its settings switch each off, at once.
 */
public final class MageOffDutyActivity extends Activity {

    private enum Kind { STUDY, PRACTICE }

    /** Free stations in one building it tries to find a way to, nearest first. */
    private static final int STATIONS_TRIED = 3;
    /** Two minutes of study or practice; then the brain looks again. */
    private static final int SESSION_TICKS = 2400;
    private static final int MINUTE_TICKS = 1200;
    /** No place to study or practise, or it could not get there: not tried again for this long. */
    private static final int RETRY_TICKS = 1200;
    private static final long MAX_WAIT_TICKS = 24000L;
    /** A Library teaches this share of a Mage Tower. */
    private static final float LIBRARY_PAY = 0.75f;
    /** How often a mote drifts from a shelf to the studying mage. */
    private static final int MOTE_EVERY = 4;
    /** Now and then it stops to think about what it read. */
    private static final int THINK_EVERY = 300;
    /** How often a studying mage's tower crystal glows, sends light to its hands, and chimes. */
    private static final int CRYSTAL_GLOW_EVERY = 8;
    private static final int CRYSTAL_STREAM_EVERY = 48;
    private static final int CRYSTAL_CHIME_EVERY = 240;
    /** The light gathers in the hands over this many ticks before a practice bolt. */
    private static final int GLOW_TICKS = 10;

    /** Mage npc id -> game time before which off-duty time is not tried again. */
    private static final Map<UUID, Long> NOT_BEFORE = new HashMap<>();
    /** Mage npc id -> what it did last time, so it takes turns when it can do both. */
    private static final Map<UUID, Kind> LAST = new HashMap<>();

    private final Kind kind;
    private final BlockPos stand;
    /** What it faces: the study station, or the practice target. */
    private final BlockPos facing;
    private final List<BlockPos> shelves;
    /** A Mage Tower's focus crystals, which glow while it studies there; empty in a Library. */
    private final List<BlockPos> crystals;
    private final float pay;
    @Nullable private MoveActivity move;
    private boolean arrived;
    private int sessionTicks;
    private int unpaidTicks;
    /** Practice: ticks to the next bolt's release, and the rest after it. */
    private int castTicks;
    private int castTotal;
    private int recoverTicks;

    private MageOffDutyActivity(Kind kind, BlockPos stand, BlockPos facing, List<BlockPos> shelves,
                                List<BlockPos> crystals, float pay) {
        this.kind = kind;
        this.stand = stand;
        this.facing = facing;
        this.shelves = shelves;
        this.crystals = crystals;
        this.pay = pay;
    }

    /** Study or practice for this off-duty mage now, its place found (and a lane claimed); null if not now. */
    @Nullable
    public static Activity offDutyFor(Activity.Context ctx) {
        NpcEntity npc = ctx.npc();
        if (npc.getProfession() != Profession.MAGE) return null;
        if (!MageSettingsBridge.setting(npc, MageSettingsSchema.WORK_ENABLED)) return null;
        // The off-duty window is the guards' drill window: before a night shift,
        // or after a day shift until bed. A player's skipped night keeps it in bed.
        if (!BarracksDrills.inWindow(ctx) || VillagerBrain.heldAsleepAfterSkip(ctx)) return null;
        UUID self = ctx.model().npcId;
        long now = ctx.level().getGameTime();
        Long until = NOT_BEFORE.get(self);
        if (until != null && now < until && until - now <= MAX_WAIT_TICKS) return null;

        boolean study = MageSettingsBridge.setting(npc, MageSettingsSchema.STUDY);
        // Studying is indoors, by lamplight if need be; the range is outdoors, by daylight only.
        boolean practice = MageSettingsBridge.setting(npc, MageSettingsSchema.RANGE_PRACTICE)
                && !VillagerBrain.isNight(ctx);
        // Take turns: whichever it did not do last comes first.
        boolean practiceFirst = LAST.get(self) == Kind.STUDY;
        MageOffDutyActivity session = null;
        if (practiceFirst && practice) session = practiceSession(ctx);
        if (session == null && study) session = studySession(ctx);
        if (session == null && !practiceFirst && practice) session = practiceSession(ctx);
        if (session == null) {
            waitBeforeRetry(ctx);
            return null;
        }
        LAST.put(self, session.kind);
        return session;
    }

    /**
     * At the nearest Mage Tower's free station, or else at a Library's
     * bookshelf (its enchanting tables are the enchanter's), claimed; null if
     * there is neither.
     */
    @Nullable
    private static MageOffDutyActivity studySession(Activity.Context ctx) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        UUID self = ctx.model().npcId;
        BlockPos from = ctx.npc().blockPosition();
        MageOffDutyActivity best = null;
        double bestSq = Double.MAX_VALUE;
        boolean bestIsTower = false;
        for (Building building : ctx.mgr().getBuildings()) {
            if (!building.isValid()) continue;
            List<BlockPos> stations;
            List<BlockPos> shelves;
            List<BlockPos> crystals;
            float pay;
            boolean tower;
            if (building instanceof MageTowerBuilding mageTower) {
                stations = mageTower.getStudyStations();
                shelves = mageTower.getBookshelves();
                crystals = mageTower.getCrystals();
                // More bookshelves, faster study (MageTowerBuilding#studyPay).
                pay = mageTower.studyPay();
                tower = true;
            } else if (building instanceof LibraryBuilding library) {
                stations = library.getBookshelves();
                shelves = stations;
                crystals = List.of();
                pay = LIBRARY_PAY;
                tower = false;
            } else {
                continue;
            }
            // A Mage Tower always beats a Library; then the nearer one.
            if (bestIsTower && !tower) continue;
            // Nearest free station first; a big Library's shelves are not all walked to on paper.
            List<BlockPos> free = new ArrayList<>();
            for (BlockPos station : stations) {
                if (!ctx.mgr().getClaims().isClaimedByOther(station, self)) free.add(station);
            }
            free.sort(Comparator.comparingDouble(station -> station.distSqr(from)));
            for (int i = 0; i < Math.min(free.size(), STATIONS_TRIED); i++) {
                BlockPos station = free.get(i);
                double d = station.distSqr(from);
                if (best != null && bestIsTower == tower && d >= bestSq) break;
                BlockPos stand = building.getWalkTargetBeside(station, graph, from);
                if (stand == null || ctx.model().hasRecentMoveFailure(stand)) continue;
                best = new MageOffDutyActivity(Kind.STUDY, stand, station, shelves, crystals, pay);
                bestSq = d;
                bestIsTower = tower;
                break;
            }
        }
        if (best == null || !ctx.mgr().getClaims().claim(best.facing, self)) return null;
        return best;
    }

    /** A free Archery Range lane, claimed; null if none is free. */
    @Nullable
    private static MageOffDutyActivity practiceSession(Activity.Context ctx) {
        UUID self = ctx.model().npcId;
        BlockPos from = ctx.npc().blockPosition();
        ArcheryRangeBuilding.Lane best = null;
        double bestSq = Double.MAX_VALUE;
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof ArcheryRangeBuilding range) || !range.isValid()) continue;
            for (ArcheryRangeBuilding.Lane lane : range.getLanes()) {
                if (!ArcheryLanes.isFree(ctx.level(), lane.target(), self)) continue;
                if (ctx.model().hasRecentMoveFailure(lane.stand())) continue;
                double d = lane.stand().distSqr(from);
                if (d < bestSq) {
                    bestSq = d;
                    best = lane;
                }
            }
        }
        if (best == null || !ArcheryLanes.claim(ctx.level(), best.target(), best.stand(), self)) return null;
        return new MageOffDutyActivity(Kind.PRACTICE, best.stand(), best.target(), List.of(), List.of(), 1f);
    }

    private static void waitBeforeRetry(Activity.Context ctx) {
        long now = ctx.level().getGameTime();
        NOT_BEFORE.put(ctx.model().npcId, now + RETRY_TICKS);
        if (NOT_BEFORE.size() > 256) NOT_BEFORE.values().removeIf(until -> until < now);
        if (LAST.size() > 256) LAST.clear();
    }

    @Override
    protected Status tick(Context ctx) {
        NpcEntity npc = ctx.npc();
        VillagerStats needs = ctx.brain().needs();
        if (npc.getProfession() != Profession.MAGE
                || !stillWanted(npc)
                || VillagerRegistry.shouldWorkNow(npc.getProfession(), ctx)
                || GuardAlertSystem.isVillageAlarmed(ctx.mgr(), ctx.level())
                || GuardAlertSystem.isOnAlert(npc)
                || !BarracksDrills.inWindow(ctx)
                || kind == Kind.PRACTICE && VillagerBrain.isNight(ctx)
                || !needs.hungerRules().canRoutineWork(needs)) {
            return Status.DONE;
        }
        if (kind == Kind.PRACTICE && !ArcheryLanes.renew(ctx.level(), facing, stand, ctx.model().npcId)) {
            // Someone else has the lane now (this mage stood frozen too long).
            return Status.DONE;
        }

        if (!arrived) {
            if (move == null) move = new MoveActivity(stand, VillagerModel.WALK_SPEED, true);
            Status status = move.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            move.stop(ctx);
            move = null;
            if (status == Status.FAILED) {
                waitBeforeRetry(ctx);
                return Status.FAILED;
            }
            arrived = true;
            ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " MAGE "
                    + (kind == Kind.STUDY ? "studying" : "practising at the range"));
        }

        npc.holdStillForAnimation();
        GuardCombatRules.face(npc, Vec3.atCenterOf(facing));
        sessionTicks++;
        unpaidTicks++;
        if (unpaidTicks >= MINUTE_TICKS) payOut(ctx);

        if (kind == Kind.STUDY) tickStudy(ctx, npc);
        else tickPractice(ctx, npc);
        return sessionTicks >= SESSION_TICKS ? Status.DONE : Status.RUNNING;
    }

    /** Its settings still allow this session: they can change while it studies or practises. */
    private boolean stillWanted(NpcEntity npc) {
        if (!MageSettingsBridge.setting(npc, MageSettingsSchema.WORK_ENABLED)) return false;
        return MageSettingsBridge.setting(npc,
                kind == Kind.STUDY ? MageSettingsSchema.STUDY : MageSettingsSchema.RANGE_PRACTICE);
    }

    /** Standing at the station, the motes drifting in, a moment's thought now and then. */
    private void tickStudy(Context ctx, NpcEntity npc) {
        if (!shelves.isEmpty() && sessionTicks % MOTE_EVERY == 0) {
            BlockPos shelf = shelves.get(npc.getRandom().nextInt(shelves.size()));
            Vec3 hands = MageSpells.handAt(npc, new MageCastTiming.Hand(1.1, 0.35, 0.0));
            MageSpells.studyMotes(ctx.level(), Vec3.atCenterOf(shelf), hands);
        }
        if (sessionTicks % THINK_EVERY == 0 && npc.getAnimation() == VillagerAnimationState.IDLE) {
            npc.setAnimation(VillagerAnimationState.THINK);
        }
        // In a Mage Tower its focus crystals wake: a soft glow, now and then a
        // thread of light to the mage's hands and a chime.
        if (!crystals.isEmpty() && sessionTicks % CRYSTAL_GLOW_EVERY == 0) {
            BlockPos crystal = crystals.get(npc.getRandom().nextInt(crystals.size()));
            MageSpells.crystalGlow(ctx.level(), crystal);
            if (sessionTicks % CRYSTAL_STREAM_EVERY == 0) {
                Vec3 hands = MageSpells.handAt(npc, new MageCastTiming.Hand(1.1, 0.35, 0.0));
                MageSpells.crystalStream(ctx.level(), Vec3.atCenterOf(crystal), hands,
                        sessionTicks % CRYSTAL_CHIME_EVERY == 0);
            }
        }
    }

    /** Practice bolts at the target: the bolt's clip, the glow, the release, a rest. */
    private void tickPractice(Context ctx, NpcEntity npc) {
        int level = ctx.brain().defenderSkillLevel();
        if (castTicks > 0) {
            if (castTicks <= GLOW_TICKS) {
                float charge = 1f - (castTicks - 1) / (float) Math.min(GLOW_TICKS, Math.max(1, castTotal));
                MageSpells.chargeGlow(ctx.level(),
                        MageSpells.handAt(npc, MageCastTiming.castingHand(MageCastTiming.Clip.BOLT)),
                        MageSpells.colourFor(com.alex.test.village.villagers.mage.MageSpellPlanner.Spell.ARCANE_BOLT),
                        charge);
            }
            if (--castTicks > 0) return;
            Vec3 hand = MageSpells.handAt(npc, MageCastTiming.castingHand(MageCastTiming.Clip.BOLT));
            MageSpells.practiceBolt(ctx.level(), hand, targetFace(hand));
            recoverTicks = MageBalance.recoveryTicks(level);
            return;
        }
        if (recoverTicks > 0) {
            recoverTicks--;
            return;
        }
        castTicks = MageCastTiming.releaseTicks(MageCastTiming.Clip.BOLT, level);
        castTotal = castTicks;
        npc.startWorkAnimation(VillagerAnimationState.MAGE_BOLT,
                MageCastTiming.clipSpeed(MageCastTiming.Clip.BOLT, castTicks));
    }

    /** The middle of the target's face that looks at the mage. */
    private Vec3 targetFace(Vec3 from) {
        Vec3 centre = Vec3.atCenterOf(facing);
        Vec3 toward = from.subtract(centre);
        double ax = Math.abs(toward.x), ay = Math.abs(toward.y), az = Math.abs(toward.z);
        if (ax >= ay && ax >= az) return centre.add(Math.signum(toward.x) * 0.51, 0, 0);
        if (az >= ay) return centre.add(0, 0, Math.signum(toward.z) * 0.51);
        return centre.add(0, Math.signum(toward.y) * 0.51, 0);
    }

    /** The time spent so far, paid to the mage's skill like a drill. */
    private void payOut(Context ctx) {
        if (unpaidTicks <= 0) return;
        float minutes = unpaidTicks / (float) MINUTE_TICKS;
        ctx.brain().onDefenderDrill(ctx, minutes, minutes * GuardBalance.DRILL_EXPERIENCE_PER_MINUTE * pay);
        unpaidTicks = 0;
    }

    @Override
    public void stop(Context ctx) {
        // Time spent before it was cut short still counts.
        if (arrived) payOut(ctx);
        if (move != null) {
            move.stop(ctx);
            move = null;
        }
        if (kind == Kind.PRACTICE) ArcheryLanes.release(ctx.level(), facing, ctx.model().npcId);
        else ctx.mgr().getClaims().release(facing, ctx.model().npcId);
        NpcEntity npc = ctx.npc();
        VillagerAnimationState state = npc.getAnimation();
        if (state.isMageCast() || state == VillagerAnimationState.THINK) {
            npc.setAnimation(VillagerAnimationState.IDLE);
        }
        ctx.model().stopMoving();
    }

    @Override
    public String name() {
        return kind == Kind.STUDY ? "mage_study" : "mage_practice";
    }
}

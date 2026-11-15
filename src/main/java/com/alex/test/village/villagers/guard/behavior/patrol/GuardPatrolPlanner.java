package com.alex.test.village.villagers.guard.behavior.patrol;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.EveningActivity;
import com.alex.test.village.brain.EveningSchedule;
import com.alex.test.village.brain.GoHomeActivity;
import com.alex.test.village.brain.LibraryActivity;
import com.alex.test.village.brain.OpenGround;
import com.alex.test.village.building.TavernBuilding;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.TownHallBuilding;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Chooses patrol destinations around villages and protected players.
 *
 * <h3>Guards patrol in pairs</h3>
 * Each guard picked its own random anchor, so a five-guard village had five
 * lone guards scattered across it -- and a lone guard meeting anything worse
 * than a zombie is a dead guard. Guards are now paired off by a stable rule
 * (sort by id, pair 1st-2nd, 3rd-4th, and so on) and the second of each pair
 * takes its anchor from where its partner is rather than from the village at
 * large. Nothing is saved: the sorted list is made again every second (and at
 * once when a guard is missing from it or its partner is gone), so pairing
 * survives guards being hired, killed or reassigned, and an odd guard out
 * simply patrols alone.
 */
public final class GuardPatrolPlanner {

    /**
     * How far from its partner a following guard will pick its next step.
     *
     * <p>Close enough that the two can reach the same fight, wide enough that
     * they do not spend the night standing on each other.
     */
    private static final int PARTNER_PATROL_RADIUS = 6;

    private GuardPatrolPlanner() {}

    @Nullable
    public static BlockPos playerPatrolPoint(Activity.Context ctx, BlockPos playerPos) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;

        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        BlockPos guardPos = ctx.npc().blockPosition();
        for (NavNode node : graph.nodesInBox(playerPos, 10, 6)) {
            if (node.isAvoidedSurface()) continue;

            BlockPos pos = node.feetPos();
            if (Math.abs(pos.getX() - playerPos.getX()) > 10
                    || Math.abs(pos.getZ() - playerPos.getZ()) > 10
                    || Math.abs(pos.getY() - playerPos.getY()) > 6) {
                continue;
            }

            double playerDistSq = pos.distSqr(playerPos);
            if (playerDistSq < 4.0 * 4.0 || playerDistSq > 10.0 * 10.0) continue;
            double stepDist = Math.sqrt(pos.distSqr(guardPos));
            double playerDist = Math.sqrt(playerDistSq);
            double score = -Math.abs(playerDist - 6.5D) * 5.0D
                    - Math.max(0.0D, stepDist - 12.0D)
                    + ctx.npc().getRandom().nextDouble() * 12.0D;
            if (best == null || score > bestScore) {
                best = pos;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * The guard this one patrols with, or {@code null} if it patrols alone.
     *
     * <p>Sorted by id so both halves of a pair agree on who they are paired
     * with without either having to be told.
     */
    @Nullable
    public static NpcEntity patrolPartner(Activity.Context ctx) {
        // Only guards are paired (archers and mages ask too, and never had one).
        if (!ctx.npc().isAlive() || !ctx.npc().getProfession().isGuardLike()) return null;
        Roster roster = roster(ctx, false);
        int partner = roster.partnerIndex(ctx.npc());
        if (partner < 0) return null;
        NpcEntity other = roster.sorted.get(partner);
        if (other.isAlive() && other.getProfession().isGuardLike()) return other;
        // The partner died or changed job since the list was made: make it again now.
        roster = roster(ctx, true);
        partner = roster.partnerIndex(ctx.npc());
        if (partner < 0) return null;
        other = roster.sorted.get(partner);
        // Made earlier this tick and the partner went since: alone until next tick.
        return other.isAlive() && other.getProfession().isGuardLike() ? other : null;
    }

    /** True for the guard of a pair that follows the other one's lead (the second of the pair). */
    public static boolean isPatrolFollower(Activity.Context ctx) {
        if (patrolPartner(ctx) == null) return false;
        Integer index = roster(ctx, false).index.get(ctx.npc().getNpcId());
        return index != null && index % 2 == 1;
    }

    /** How long a village's guard list is kept before it is made again, in ticks. */
    private static final int ROSTER_TICKS = 20;

    /**
     * A village's living guards sorted by id, with each one's place: the pairs.
     * Patrolling guards ask for their partner every tick, and listing and
     * sorting every guard on each ask cost hundreds of sorts a tick in a big
     * village. Kept up to {@link #ROSTER_TICKS}; made again sooner when a guard
     * is not on it or its partner is gone. Server thread only.
     */
    private static final class Roster {
        long builtAt = Long.MIN_VALUE;
        final List<NpcEntity> sorted = new ArrayList<>();
        final java.util.Map<java.util.UUID, Integer> index = new java.util.HashMap<>();

        /** Place of {@code guard}'s partner: pair 0-1, 2-3, ...; -1 alone or not listed. */
        int partnerIndex(NpcEntity guard) {
            Integer at = index.get(guard.getNpcId());
            if (at == null || sorted.get(at) != guard) return -1;
            int partner = (at % 2 == 0) ? at + 1 : at - 1;
            return partner < sorted.size() ? partner : -1;
        }
    }

    private static final java.util.Map<com.alex.test.village.VillageManager, Roster> ROSTERS =
            new java.util.WeakHashMap<>();

    private static Roster roster(Activity.Context ctx, boolean stale) {
        long now = ctx.level().getGameTime();
        Roster roster = ROSTERS.computeIfAbsent(ctx.mgr(), m -> new Roster());
        Integer at = roster.index.get(ctx.npc().getNpcId());
        boolean listed = at != null && roster.sorted.get(at) == ctx.npc();
        boolean due = now - roster.builtAt >= ROSTER_TICKS || now < roster.builtAt;
        // At most once a tick, however many guards find it out of date.
        if ((due || stale || !listed) && roster.builtAt != now) rebuild(ctx, roster, now);
        return roster;
    }

    private static void rebuild(Activity.Context ctx, Roster roster, long now) {
        record Keyed(String key, NpcEntity guard) {}
        List<Keyed> guards = new ArrayList<>();
        for (VillagerModel model : ctx.mgr().getVillagers()) {
            NpcEntity other = model.puppet();
            if (other != null && other.isAlive() && other.getProfession().isGuardLike()) {
                guards.add(new Keyed(other.getNpcId().toString(), other));
            }
        }
        // By id text, as before, so the pairs stay the same ones.
        guards.sort(java.util.Comparator.comparing(Keyed::key));
        roster.sorted.clear();
        roster.index.clear();
        for (Keyed keyed : guards) {
            roster.index.put(keyed.guard().getNpcId(), roster.sorted.size());
            roster.sorted.add(keyed.guard());
        }
        roster.builtAt = now;
    }

    @Nullable
    public static BlockPos villagePatrolPoint(Activity.Context ctx) {
        // The follower of a pair patrols where its partner is, not where the
        // village is -- which is what keeps the two of them together.
        if (isPatrolFollower(ctx)) {
            NpcEntity partner = patrolPartner(ctx);
            if (partner != null) {
                BlockPos beside = randomNodeNear(ctx,
                        partner.blockPosition(), PARTNER_PATROL_RADIUS, new Random());
                if (beside != null) return beside;
            }
        }

        BlockPos eveningWatch = eveningWatchPoint(ctx);
        if (eveningWatch != null) return eveningWatch;

        List<BlockPos> anchors = new ArrayList<>();
        for (Building building : ctx.mgr().getBuildings()) {
            if (!building.isValid()) continue;
            // A tower top is the lookout's, not a patrol stop: the patrol would climb it.
            if (building instanceof com.alex.test.village.building.WatchtowerBuilding) continue;
            anchors.add(building.getDoorPos());
            if (!building.getNavMeshBlocks().isEmpty()) {
                int skip = ctx.npc().getRandom().nextInt(Math.max(1, building.getNavMeshBlocks().size()));
                int i = 0;
                for (BlockPos pos : building.getNavMeshBlocks()) {
                    if (i++ == skip) {
                        anchors.add(pos);
                        break;
                    }
                }
            }
        }
        for (VillagerModel model : ctx.mgr().getVillagers()) {
            NpcEntity other = model.puppet();
            if (other != null && other.isAlive() && !other.getProfession().isGuardLike()) {
                anchors.add(other.blockPosition());
            }
        }
        if (anchors.isEmpty()) anchors.add(townHallOrSelf(ctx));

        BlockPos anchor = anchors.get(ctx.npc().getRandom().nextInt(anchors.size()));
        BlockPos node = randomNodeNear(ctx, anchor, 14, new Random());
        if (node != null) return node;
        return nearestVillageNode(ctx, anchor, 16, 6, anchor);
    }

    /** Gathering hours on the world clock: the earliest gathering to the latest walk home. */
    private static final int EVENING_WATCH_START = EveningSchedule.HEAD_OUT_MIN;
    private static final int EVENING_WATCH_END   = EveningSchedule.LEAVE_MAX + 800;
    /** Chance a patrol leg during gathering hours watches the tavern or a walk home. */
    private static final float EVENING_WATCH_CHANCE = 0.5f;
    private static final int EVENING_WATCH_RADIUS = 6;

    /**
     * During the evening gathering, about half of a guard's ordinary village
     * patrol legs go to the tavern door or to a villager walking to it or home
     * from it: the exposed part of the evening. Only the village patrol is
     * biased; player orders, posts and alerts are untouched. Null when there is
     * no tavern or busy library to watch, or the roll says patrol as usual.
     */
    @Nullable
    private static BlockPos eveningWatchPoint(Activity.Context ctx) {
        long t = EveningSchedule.timeOfDay(ctx.level().getDayTime());
        if (t < EVENING_WATCH_START || t > EVENING_WATCH_END) return null;
        if (ctx.npc().getRandom().nextFloat() >= EVENING_WATCH_CHANCE) return null;

        List<BlockPos> anchors = new ArrayList<>();
        for (Building building : ctx.mgr().getBuildings()) {
            if (building instanceof TavernBuilding && building.isValid()) {
                // Twice: the door is the one place every guest passes.
                anchors.add(building.getDoorPos());
                anchors.add(building.getDoorPos());
            }
        }
        // A library with evening readers in it is watched too, once per library.
        java.util.Set<java.util.UUID> busyLibraries = new java.util.HashSet<>();
        for (VillagerModel model : ctx.mgr().getVillagers()) {
            NpcEntity other = model.puppet();
            if (other == null || !other.isAlive()) continue;
            if (model.brain().currentActivity() instanceof LibraryActivity visit
                    && visit.library().isValid()
                    && busyLibraries.add(visit.library().getItemFrameId())) {
                anchors.add(visit.library().getDoorPos());
            }
        }
        if (anchors.isEmpty()) return null;
        for (VillagerModel model : ctx.mgr().getVillagers()) {
            NpcEntity other = model.puppet();
            if (other == null || !other.isAlive() || !model.isMoving()) continue;
            Activity activity = model.brain().currentActivity();
            boolean travelling = (activity instanceof EveningActivity e && !e.isHangingAround())
                    || (activity instanceof LibraryActivity visit && !visit.isSettled())
                    || (activity instanceof GoHomeActivity home && !home.isResting());
            if (travelling) anchors.add(other.blockPosition());
        }
        BlockPos anchor = anchors.get(ctx.npc().getRandom().nextInt(anchors.size()));
        BlockPos node = randomNodeNear(ctx, anchor, EVENING_WATCH_RADIUS, new Random());
        return node != null ? node : nearestVillageNode(ctx, anchor, 8, 4, null);
    }

    @Nullable
    public static BlockPos nearestVillageNode(
            Activity.Context ctx, BlockPos destination, int xzRadius, int yRadius) {
        return nearestVillageNode(ctx, destination, xzRadius, yRadius, null);
    }

    @Nullable
    private static BlockPos nearestVillageNode(
            Activity.Context ctx,
            BlockPos destination,
            int xzRadius,
            int yRadius,
            @Nullable BlockPos fallback) {
        NavGraph graph = ctx.mgr().getGraph();
        NavNode node = graph != null ? graph.nearestNonAvoided(destination, xzRadius, yRadius) : null;
        return node != null ? node.feetPos() : fallback;
    }

    private static BlockPos townHallOrSelf(Activity.Context ctx) {
        for (Building building : ctx.mgr().getBuildings()) {
            if (building instanceof TownHallBuilding && building.isValid()) return building.getDoorPos();
        }
        return ctx.npc().blockPosition();
    }

    @Nullable
    private static BlockPos randomNodeNear(Activity.Context ctx, BlockPos center, int radius, Random random) {
        // Patrols stay out of ranches, like every stroll.
        return OpenGround.randomSpot(ctx.mgr(), ctx.mgr().getGraph(),
                center, radius, 6, random, node -> !node.isAvoidedSurface());
    }
}

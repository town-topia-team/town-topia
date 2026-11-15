package com.alex.test.village.villagers.archer.behavior;

import com.alex.test.DynamicVillages;
import com.alex.test.village.VillagerModel;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.building.BuildingAccess;
import com.alex.test.village.brain.Activity.Context;
import com.alex.test.village.brain.Activity.Status;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.building.WatchtowerBuilding;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.chat.VillageDamageAlerts;
import com.alex.test.village.villagers.archer.settings.ArcherSettingsSchema;
import com.alex.test.village.villagers.guard.behavior.GuardAlertSystem;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * An archer keeping watch on a Watchtower: it climbs to a spot on the
 * platform, stands there looking out over the village, and every half minute
 * or so steps to another spot. While it is up there it sees farther (see
 * {@link WatchtowerDuty#spotBonus}); fighting from the tower is
 * {@code ArcherActivity}'s, which keeps a lookout on its platform.
 *
 * <p>Owned by {@code ArcherActivity}, which must call {@link #stop} however
 * the watch ends, so the tower is freed for the next lookout.
 */
final class LookoutWatch {

    private static final float WALK_SPEED = VillagerModel.WALK_SPEED;
    /** Time at one spot before stepping to another. */
    private static final int SPOT_TICKS = 400;
    private static final int SPOT_JITTER_TICKS = 400;
    private static final int LOOK_TICKS = 40;
    private static final int LOOK_JITTER_TICKS = 40;
    /** How far out it looks: over the village, not at its feet. */
    private static final double LOOK_DISTANCE = 16.0D;
    private static final double ARRIVED_SQ = 1.5D * 1.5D;
    private static final ResourceLocation SURE_FOOTED =
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "lookout_sure_footed");
    /** Knockback resistance while on the platform: most of a blow is shrugged off. */
    private static final double SURE_FOOTED_AMOUNT = 0.8D;

    private static final int BELL_CHECK_TICKS = 20;
    /** Arm's reach to the bell, from the eyes, like a player's. */
    private static final double BELL_REACH_SQ = 4.0D * 4.0D;

    private final WatchtowerBuilding tower;
    private int bellCheck;
    /** The platform spot by the bell, while there is reason to ring it and the lookout is not there yet. */
    @Nullable private BlockPos bellSpot;
    @Nullable private BlockPos spot;
    @Nullable private MoveActivity move;
    private long nextSpotAt;
    private int lookTimer;
    private boolean stopped;
    /** Spots on the platform this lookout found it cannot walk to from where it stood. */
    private final java.util.Set<BlockPos> badSpots = new java.util.HashSet<>();
    /** The walk to the bell failed: it is rung from wherever the lookout stands. */
    private boolean ringFromHere;
    /** Platform spots reachable from the village, worked out once per version of the village map. */
    @Nullable private List<BlockPos> reachableSpots;
    @Nullable private NavGraph reachableFor;
    private long reachableVersion = Long.MIN_VALUE;
    /** The cells the watch spots sit in: the list is redone only when one of them changes. */
    private long[] reachableCells = new long[0];

    LookoutWatch(WatchtowerBuilding tower) {
        this.tower = tower;
    }

    WatchtowerBuilding tower() {
        return tower;
    }

    /** Whether it is on its platform now (and not still climbing). */
    boolean isUp(NpcEntity npc) {
        return tower.isOnPlatform(npc.blockPosition());
    }

    /**
     * RUNNING while on watch, or on the way up; DONE when the watch is over:
     * the tower is gone or too low now, the claim was lost (relieved), or the
     * way up failed.
     */
    Status tick(Context ctx) {
        NpcEntity npc = ctx.npc();
        if (!tower.isValid() || !tower.isHighEnough()) return Status.DONE;
        if (!WatchtowerDuty.renew(ctx.level(), tower, ctx.model().npcId)) return Status.DONE;
        long now = ctx.level().getGameTime();

        if (bellSpot != null && !badSpots.contains(bellSpot) && !bellSpot.equals(spot)) {
            // Trouble: over to the bell to ring it (see tickBell).
            spot = bellSpot;
            nextSpotAt = now + SPOT_TICKS;
            if (move != null) move.stop(ctx);
            move = null;
        }
        if (spot == null || (move == null && now >= nextSpotAt && isUp(npc))) {
            spot = pickSpot(ctx);
            if (spot == null) return Status.DONE;
            nextSpotAt = now + SPOT_TICKS + npc.getRandom().nextInt(SPOT_JITTER_TICKS);
            if (move != null) move.stop(ctx);
            move = null;
        }

        if (npc.blockPosition().distSqr(spot) > ARRIVED_SQ) {
            if (move == null) move = new MoveActivity(spot, WALK_SPEED, true, true);
            Status status = move.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            move.stop(ctx);
            move = null;
            if (status == Status.FAILED || npc.blockPosition().distSqr(spot) > ARRIVED_SQ) {
                if (isUp(npc)) {
                    // Already up, so the tower is fine: only this spot is out of
                    // reach (a ledge, a corner behind a post). Another is picked.
                    // It used to count as a failed climb: the lookout left, and
                    // every archer stayed away from the tower for a minute.
                    badSpots.add(spot);
                    if (spot.equals(bellSpot)) ringFromHere = true;
                    spot = null;
                    return Status.RUNNING;
                }
                // No way up: another archer may manage, this one stops trying.
                return Status.FAILED;
            }
        }

        if (--lookTimer <= 0) {
            lookTimer = LOOK_TICKS + npc.getRandom().nextInt(LOOK_JITTER_TICKS);
            lookOut(npc);
        }
        return Status.RUNNING;
    }

    /**
     * Every tick of the watch, fighting or not (the owner calls it): on the
     * platform the lookout sees farther, may call out what it sees, and is hard
     * to knock back; anywhere else none of that. It used to be marked only
     * while keeping watch, so the sight bonus and call-outs lapsed two seconds
     * into every fight, and the footing stayed on if it ran off the tower.
     */
    void tickFooting(NpcEntity npc, long now) {
        boolean up = isUp(npc);
        if (up) WatchtowerDuty.markOnPlatform(npc, tower.spotBonus(), now);
        setSureFooted(npc, up);
    }

    /** Stops the walk across the platform: the lookout goes elsewhere for a moment, and {@link #tick} walks back. */
    void pauseMove(Context ctx) {
        if (move == null) return;
        move.stop(ctx);
        move = null;
    }

    /**
     * The platform spots a villager can walk to from the village, less those
     * this lookout already failed to reach. The platform is every floor near
     * the token, and a bit of roof or a ledge in that box can be cut off from
     * the rest; the lookout picked those at random and could not get there.
     */
    List<BlockPos> usableSpots(Context ctx) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return List.of();
        if (reachableSpots == null || reachableFor != graph
                || graph.changedSince(reachableVersion, reachableCells)) {
            List<BlockPos> spots = new ArrayList<>();
            List<BlockPos> watchSpots = tower.watchSpots();
            long[] cells = new long[watchSpots.size()];
            int i = 0;
            for (BlockPos pos : watchSpots) {
                cells[i++] = NavGraph.cellKey(pos.getX(), pos.getY(), pos.getZ());
                NavNode node = graph.nodeAt(pos.getX(), pos.getY(), pos.getZ());
                if (BuildingAccess.isAccessibleNode(graph, ctx.mgr().getBellPos(), node)) spots.add(pos);
            }
            reachableSpots = spots;
            reachableFor = graph;
            reachableVersion = graph.version();
            reachableCells = cells;
        }
        if (badSpots.isEmpty()) return reachableSpots;
        List<BlockPos> spots = new ArrayList<>(reachableSpots);
        spots.removeAll(badSpots);
        return spots;
    }

    /** Another spot on the platform than this one, picked at random; the only one if that is all. */
    @Nullable
    private BlockPos pickSpot(Context ctx) {
        List<BlockPos> spots = usableSpots(ctx);
        if (spots.isEmpty()) return null;
        if (spots.size() == 1) return spots.get(0);
        BlockPos pick;
        int tries = 0;
        do {
            pick = spots.get(ctx.npc().getRandom().nextInt(spots.size()));
        } while (pick.equals(spot) && ++tries < 8);
        return pick;
    }

    /** A glance out over the village, away from the middle of the platform and down at the ground. */
    private void lookOut(NpcEntity npc) {
        double angle = npc.getRandom().nextDouble() * Math.PI * 2.0D;
        double x = npc.getX() + Math.cos(angle) * LOOK_DISTANCE;
        double z = npc.getZ() + Math.sin(angle) * LOOK_DISTANCE;
        double y = npc.getY() - tower.getHeightAboveGround();
        npc.getLookControl().setLookAt(x, y, z, 10f, 20f);
    }

    /**
     * Real trouble, the tower's bell: a raid, or a monster inside the village
     * that has just hurt a villager who is not a defender, or a player. A
     * guard trading blows with a zombie is not reason enough, and neither are
     * monsters merely standing about the village at night: the bell used to
     * ring for any three, most nights, and the alarm then kept every off-duty
     * defender out of bed until morning. Ringing it is the same as a player
     * ringing a bell: every defender comes out, everyone else goes indoors,
     * and vanilla's bell lights up nearby raiders; players in the village are
     * told in chat. Only from the platform, only when the alarm is not on
     * already, at most every two minutes, and not when the archer's settings
     * say no. Looked at once a second, also while the lookout is fighting.
     *
     * <p>A lookout that is not {@code fighting} first walks over to the bell
     * (it used to ring it from the far side of the platform); one in the
     * middle of a fight rings it from where it stands, rather than late.
     */
    void tickBell(Context ctx, boolean fighting) {
        if (--bellCheck > 0) return;
        bellCheck = BELL_CHECK_TICKS;
        bellSpot = null;
        NpcEntity npc = ctx.npc();
        BlockPos bell = tower.getBell();
        if (bell == null || !isUp(npc) || !ctx.level().isLoaded(bell)) return;
        if (!ProfessionSettings.of(npc, ArcherSettingsSchema.SCHEMA).getBool(ArcherSettingsSchema.LOOKOUT_RINGS_BELL)) {
            return;
        }
        // Short raid attacks only: ringing all through a horde night would keep everyone
        // sheltering, out of bed; then it rings when someone is hurt, as any other night.
        // (A held raid alarm counts as "alarmed", so the raid is checked first.)
        // A Bad Omen raid nobody has rung for yet: the lookout sees it from up here.
        boolean raid = ctx.mgr().raids().holdsAlarm() || ctx.mgr().raids().needsWarningBell();
        if (!raid && GuardAlertSystem.isVillageAlarmed(ctx.mgr(), ctx.level())) return;
        // Village claims 11a: a player the owner listed as Hostile inside the border rings it too.
        boolean hostile = !raid && com.alex.test.village.claims.HostileWatch.anyInside(ctx.mgr());
        if (!raid && !hostile && GuardAlertSystem.threatsHurtingPeopleInside(ctx.mgr(), ctx.level()) == 0) return;
        if (!WatchtowerDuty.mayRing(ctx.level(), tower)) return;
        if (!(ctx.level().getBlockState(bell).getBlock() instanceof BellBlock bellBlock)) return;
        if (!fighting && !ringFromHere && npc.getEyePosition().distanceToSqr(Vec3.atCenterOf(bell)) > BELL_REACH_SQ) {
            BlockPos byBell = spotNearest(ctx, bell);
            // Not at the spot nearest the bell yet: walk there first (tick does).
            // Already there and still out of reach, or that walk failed: rung
            // from where it stands all the same.
            if (byBell != null && npc.blockPosition().distSqr(byBell) > ARRIVED_SQ) {
                bellSpot = byBell;
                return;
            }
        }
        ringFromHere = false;
        npc.getLookControl().setLookAt(bell.getX() + 0.5D, bell.getY() + 0.5D, bell.getZ() + 0.5D, 30f, 30f);
        npc.swing(InteractionHand.MAIN_HAND);
        bellBlock.attemptToRing(npc, ctx.level(), bell, null);
        GuardAlertSystem.soundTowerAlarm(ctx.mgr(), ctx.level());
        WatchtowerDuty.markRung(ctx.level(), tower);
        VillageDamageAlerts.announceTowerBell(ctx.mgr(), ctx.level(), npc, raid);
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " LOOKOUT rang the tower bell ("
                + (raid ? "raid" : hostile ? "hostile player inside" : "monsters hurting villagers") + ")");
    }

    /** The platform spot the lookout can walk to nearest {@code pos}, or null when there is none. */
    @Nullable
    private BlockPos spotNearest(Context ctx, BlockPos pos) {
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos tile : usableSpots(ctx)) {
            double d = tile.distSqr(pos);
            if (d < bestSq) {
                bestSq = d;
                best = tile;
            }
        }
        return best;
    }

    /**
     * Braced on the platform, a lookout is hard to knock back: a skeleton's
     * arrow must not push it off an edge with no railing. A transient
     * modifier, so it is never saved and goes with the watch.
     */
    private static void setSureFooted(NpcEntity npc, boolean on) {
        AttributeInstance knockback = npc.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (knockback == null) return;
        boolean has = knockback.hasModifier(SURE_FOOTED);
        if (on && !has) {
            knockback.addTransientModifier(new AttributeModifier(SURE_FOOTED, SURE_FOOTED_AMOUNT,
                    AttributeModifier.Operation.ADD_VALUE));
        } else if (!on && has) {
            knockback.removeModifier(SURE_FOOTED);
        }
    }

    /** Ends the watch: the move is stopped and the tower freed for the next lookout. */
    void stop(Context ctx) {
        if (stopped) return;
        stopped = true;
        if (move != null) {
            move.stop(ctx);
            move = null;
        }
        setSureFooted(ctx.npc(), false);
        WatchtowerDuty.release(ctx.level(), tower, ctx.model().npcId);
    }
}

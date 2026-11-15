package com.alex.test.village.visitors;

import com.alex.test.animation.HurryGait;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.VanillaNavCount;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.FleeActivity;
import com.alex.test.village.social.MemoryTopic;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.villagers.guard.behavior.GuardAlertSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * The slice of {@link com.alex.test.village.brain.VillagerBrain} travellers
 * (merchants, nomads) get: its "attacked / monster spotted" override.
 *
 * <p>Like the Mayor, a traveller owns a private {@link VillagerModel} whose
 * brain holds its memory and thought bubbles. Inside a village it runs the
 * real {@link FleeActivity} over the village nav graph: the run into the
 * safest building and the wait there until it is clear, as a villager does
 * (merchants and nomads hide in the village's houses too). Outside the graph the
 * model would drag the traveller back across the border, so there it falls
 * back to vanilla navigation away from the threat.
 */
public final class TravelerThreatResponse {

    // Same timings as VillagerBrain's attacked window and FleeActivity.
    private static final int RECENT_ATTACK_TICKS = 60;
    private static final int THREAT_SCAN_INTERVAL_TICKS = 10;
    private static final int FLEE_TICKS = 100;
    private static final int FLEE_RANGE = 12;
    private static final int FLEE_Y_RANGE = 6;
    private static final double FLEE_SPEED = 0.7D;

    private final NpcEntity traveler;
    @Nullable private VillagerModel model;

    // Village flee: the real FleeActivity.
    @Nullable private FleeActivity villageFlee;
    @Nullable private VillageManager fleeVillage;

    // Wilderness flee: vanilla navigation.
    private int wildFleeTicksLeft;
    private int wildRepathTimer;
    private Vec3 wildFleeFrom = Vec3.ZERO;

    public TravelerThreatResponse(NpcEntity traveler) {
        this.traveler = traveler;
    }

    /** Returns true while the traveller is fleeing and its own logic should pause. */
    public boolean tick(ServerLevel level) {
        if (villageFlee == null && wildFleeTicksLeft <= 0 && !tryStartFlee(level)) return false;
        return villageFlee != null ? tickVillageFlee(level) : tickWildFlee();
    }

    private boolean tryStartFlee(ServerLevel level) {
        VillageManager village = null;
        boolean attacked = false;
        LivingEntity threat = traveler.getLastHurtByMob();
        if (threat != null && threat.isAlive()
                && traveler.tickCount - traveler.getLastHurtByMobTimestamp() < RECENT_ATTACK_TICKS) {
            attacked = true;
        } else {
            if (traveler.tickCount % THREAT_SCAN_INTERVAL_TICKS != 0) return false;
            village = villageAt(level);
            threat = village != null
                    ? GuardAlertSystem.detectThreatForVillager(village, level, traveler) : null;
        }
        if (threat == null) return false;

        if (attacked) {
            model().brain().remember(MemoryTopic.DANGER, level);
            if (traveler.getHealth() < traveler.getMaxHealth() * 0.5f) {
                model().brain().remember(MemoryTopic.INJURY, level);
            }
            village = villageAt(level);
        }
        traveler.getNavigation().stop();

        NavGraph graph = village != null ? village.getGraph() : null;
        if (graph != null && !graph.isEmpty() && graph.isInsideBounds(traveler.blockPosition())) {
            fleeVillage = village;
            villageFlee = FleeActivity.toShelter(threat);
        } else {
            model().brain().rememberAndShow(MemoryTopic.DANGER,
                    ThoughtIcon.STATUS_FLEEING, FLEE_TICKS, level);
            wildFleeFrom = threat.position();
            wildFleeTicksLeft = FLEE_TICKS;
            wildRepathTimer = 0;
        }
        return true;
    }

    private boolean tickVillageFlee(ServerLevel level) {
        VillagerModel model = model();
        Activity.Context ctx = new Activity.Context(model.brain(), model, fleeVillage, level, traveler);
        model.tick(fleeVillage.getGraph());
        // Hit again on the way or while hiding: the flee re-aims (it handles each hit once).
        LivingEntity attacker = traveler.getLastHurtByMob();
        if (attacker != null && attacker.isAlive()
                && traveler.tickCount - traveler.getLastHurtByMobTimestamp() < RECENT_ATTACK_TICKS) {
            villageFlee.onAttacked(ctx, attacker);
        }
        if (villageFlee.tickOuter(ctx) == Activity.Status.RUNNING) return true;
        villageFlee.stop(ctx);
        villageFlee = null;
        fleeVillage = null;
        return false;
    }

    private boolean tickWildFlee() {
        if (--wildFleeTicksLeft <= 0) return endWildFlee();
        traveler.hurry(HurryGait.RUN);
        if (--wildRepathTimer <= 0 || traveler.getNavigation().isDone()) {
            wildRepathTimer = 20;
            Vec3 target = DefaultRandomPos.getPosAway(traveler, FLEE_RANGE, FLEE_Y_RANGE, wildFleeFrom);
            if (target != null) VanillaNavCount.note(VanillaNavCount.Site.WILD_FLEE, traveler);
            if (target == null || !traveler.getNavigation().moveTo(
                    target.x, target.y, target.z, FLEE_SPEED)) {
                return endWildFlee();
            }
        }
        return true;
    }

    private boolean endWildFlee() {
        wildFleeTicksLeft = 0;
        traveler.stopHurry();
        traveler.getNavigation().stop();
        return false;
    }

    @Nullable
    private VillageManager villageAt(ServerLevel level) {
        return VillageManagerRegistry.get(level.getServer()).findVillageAt(level, traveler.blockPosition());
    }

    /**
     * The traveller's movement model. Its walks ({@link TravelerGraphWalk})
     * run on this same model, so a flee and a walk never steer it against
     * each other.
     */
    public VillagerModel movement() { return model(); }

    private VillagerModel model() {
        if (model == null) {
            model = new VillagerModel(traveler.getNpcId());
            model.bind(traveler);
        }
        return model;
    }
}

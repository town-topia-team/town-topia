package com.alex.test.village.villagers.rancher;

import com.alex.test.Tr;
import com.alex.test.village.NavGraph;
import com.alex.test.village.VillageManager;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.ButcheryBuilding;
import com.alex.test.village.building.RanchBuilding;
import com.alex.test.village.villagers.butcher.lead.LedAnimals;
import com.alex.test.village.villagers.butcher.lead.RanchStrays;
import com.alex.test.village.villagers.rancher.settings.RancherSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The round-up: loose cows, sheep, pigs and chickens near a pen that a
 * rancher leads in on its lead, the same walk that brings strays home
 * ({@code BringBackStrayActivity}).
 *
 * <p>Loose means no pen's: not a member of any ranch, not standing in one or
 * in a Butchery, on no lead, grown, and unnamed (a name tag is a player's
 * pet). Only species the pen still has room for are fetched, so a full pen
 * costs nothing and the butcher is not handed a surplus.
 *
 * <p>Each pen scans for loose animals every {@link #SCAN_INTERVAL} ticks out
 * to {@link #MAX_RANGE}; each rancher then keeps those within its own
 * Round-up range.
 */
public final class RanchRoundUp {

    /** The furthest the Round-up range setting goes; every scan covers this much. */
    public static final int MAX_RANGE = 64;
    /** How far above and below the gate the scan looks. */
    private static final int SCAN_HEIGHT = 12;
    /** How often each pen looks for loose animals. */
    private static final long SCAN_INTERVAL = 100L;

    private RanchRoundUp() {}

    /**
     * Why an animal outside a pen is or is not led in: the rules' answers ({@link #whyNot},
     * {@link #whyUnreachable}) and the Ranch window's own (strays, the lead, a walk under way, nobody to
     * fetch it). Later ones are further along, so with several Ranchers the furthest is shown.
     */
    public enum Reason {
        NOT_RANCH, DEAD, IN_PEN, NO_RANCHER, WORK_OFF, ROUND_UP_OFF, BABY, NAMED, SADDLED, ON_LEAD, MEMBER,
        OTHER_PEN, IN_BUTCHERY, UNTICKED, TOO_FAR, FAR_STRAY, NO_ROOM, CLAIMED, FAILED_LATELY, NO_MAP,
        OUTSIDE_BORDER, WALK_FAILED, NO_PATH, NO_LEAD, LEADING, WILL_BRING_BACK, WILL_ROUND_UP
    }

    /** A reason and its numbers: TOO_FAR and FAR_STRAY the distance and the range, NO_ROOM the pen's room per kind. */
    public record Why(Reason reason, int a, int b) {
        public static Why of(Reason reason) {
            return new Why(reason, 0, 0);
        }
    }

    /**
     * The nearest loose animal {@code ctx}'s villager can lead into a pen with
     * room for it, or null: Rounding Up off, nothing loose, or nothing
     * reachable.
     */
    @Nullable
    public static RanchStrays.Stray candidateFor(Activity.Context ctx, ProfessionSettings settings) {
        if (!settings.getBool(RancherSettingsSchema.ROUNDUP_ENABLED)) return null;
        if (!(ctx.level() instanceof ServerLevel level)) return null;
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        List<RanchBuilding> ranches = ranches(ctx);
        if (ranches.isEmpty()) return null;

        long now = level.getGameTime();
        UUID me = ctx.npc().getUUID();
        RanchStrays.Stray best = null;
        double bestDist = Double.MAX_VALUE;
        for (RanchBuilding ranch : ranches) {
            scan(ctx, level, ranch, ranches);
            if (ranch.looseNearby().isEmpty()) continue;
            Map<EntityType<?>, Integer> room = roomIn(level, ranch, me, now);
            for (UUID id : ranch.looseNearby()) {
                if (!(level.getEntity(id) instanceof Animal animal)) continue;
                if (whyNot(ctx, settings, ranches, ranch, animal, room) != null) continue;
                double dist = ctx.npc().distanceToSqr(animal);
                if (dist >= bestDist) continue;
                if (!ctx.model().canReach(graph, animal.blockPosition())) continue;
                bestDist = dist;
                best = new RanchStrays.Stray(ranch, animal);
            }
        }
        return best;
    }

    /**
     * Why {@code ctx}'s rancher would not round {@code animal} up into
     * {@code ranch} now; null when nothing stands in the way but a path to it
     * (checked last, as it costs the most: see {@link #whyUnreachable}). The
     * one list of rules, shared by the rancher, the Ranch window and
     * {@code /villager ranch} ({@link RanchOutside}); {@link #text} words it.
     *
     * @param room places left per species, from {@link #roomIn}
     */
    @Nullable
    public static Why whyNot(Activity.Context ctx, ProfessionSettings settings, List<RanchBuilding> ranches,
                             RanchBuilding ranch, Animal animal, Map<EntityType<?>, Integer> room) {
        ServerLevel level = (ServerLevel) ctx.level();
        long now = level.getGameTime();
        EntityType<?> type = animal.getType();
        if (RanchAnimals.breedFoodFor(type) == null) return Why.of(Reason.NOT_RANCH);
        if (!animal.isAlive()) return Why.of(Reason.DEAD);
        if (ranch.isPenTile(level, animal.blockPosition())) return Why.of(Reason.IN_PEN);
        if (!settings.getBool(RancherSettingsSchema.ROUNDUP_ENABLED)) return Why.of(Reason.ROUND_UP_OFF);
        if (animal.isBaby()) return Why.of(Reason.BABY);
        if (animal.hasCustomName()) return Why.of(Reason.NAMED);
        if (RanchStrays.isSaddled(animal)) return Why.of(Reason.SADDLED);
        if (animal.isLeashed() || LedAnimals.isLed(animal)) return Why.of(Reason.ON_LEAD);
        for (RanchBuilding other : ranches) {
            if (other.isMember(animal.getUUID(), now)) return Why.of(Reason.MEMBER);
            if (other.isPenTile(level, animal.blockPosition())) return Why.of(Reason.OTHER_PEN);
        }
        if (inButchery(ctx.mgr(), animal)) return Why.of(Reason.IN_BUTCHERY);
        if (!isAllowed(settings, type)) return Why.of(Reason.UNTICKED);
        int range = settings.getInt(RancherSettingsSchema.ROUNDUP_RANGE);
        double dist = Math.sqrt(animal.distanceToSqr(ranch.getGatePos().getCenter()));
        if (dist > range) return new Why(Reason.TOO_FAR, (int) Math.round(dist), range);
        if (room.getOrDefault(type, 0) <= 0) return new Why(Reason.NO_ROOM, ranch.capacityPerSpecies(), 0);
        UUID me = ctx.npc().getUUID();
        if (RanchClaims.isClaimedByOther(animal.getUUID(), me, now)) return Why.of(Reason.CLAIMED);
        if (!RanchClaims.isFree(animal.getUUID(), me, now)) return Why.of(Reason.FAILED_LATELY);
        return null;
    }

    /** Why {@code ctx}'s villager cannot walk to {@code animal} on the village map; null when it can. */
    @Nullable
    public static Why whyUnreachable(Activity.Context ctx, Animal animal) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return Why.of(Reason.NO_MAP);
        BlockPos at = animal.blockPosition();
        if (!graph.isInsideBounds(at)) return Why.of(Reason.OUTSIDE_BORDER);
        if (ctx.model().isGoalBlacklisted(at)) return Why.of(Reason.WALK_FAILED);
        if (!ctx.model().canReach(graph, at)) return Why.of(Reason.NO_PATH);
        return null;
    }

    /** Green: it is being fetched or will be; red: the player can change something; gold: it waits. */
    public static RanchStatus.Tone tone(Reason reason) {
        return switch (reason) {
            case LEADING, WILL_BRING_BACK, WILL_ROUND_UP -> RanchStatus.Tone.GOOD;
            case NO_RANCHER, WORK_OFF, ROUND_UP_OFF, UNTICKED, NO_MAP, OUTSIDE_BORDER, NO_PATH, NO_LEAD -> RanchStatus.Tone.BLOCKED;
            default -> RanchStatus.Tone.WAIT;
        };
    }

    /** The reason in the player's language: the same words in the window and in chat. */
    public static MutableComponent text(Why why) {
        return switch (why.reason()) {
            case NOT_RANCH -> Tr.c("screen.dynamicvillages.ranch.out.not_ranch", "Not a ranch animal (only cows, sheep, pigs and chickens)");
            case DEAD -> Tr.c("screen.dynamicvillages.ranch.out.dead", "Dead");
            case IN_PEN -> Tr.c("screen.dynamicvillages.ranch.out.in_pen", "Already in the pen");
            case NO_RANCHER -> Tr.c("screen.dynamicvillages.ranch.out.no_rancher", "No Rancher to fetch it");
            case WORK_OFF -> Tr.c("screen.dynamicvillages.ranch.why.work_off", "The Rancher's Work switch is off");
            case ROUND_UP_OFF -> Tr.c("screen.dynamicvillages.ranch.out.round_up_off", "Rounding Up is off in the Rancher's Settings");
            case BABY -> Tr.c("screen.dynamicvillages.ranch.out.baby", "A baby: no lead goes on one");
            case NAMED -> Tr.c("screen.dynamicvillages.ranch.out.named", "Named: a player's pet, left alone");
            case SADDLED -> Tr.c("screen.dynamicvillages.ranch.out.saddled", "Saddled: a player's ride, left alone");
            case ON_LEAD -> Tr.c("screen.dynamicvillages.ranch.out.on_lead", "On a lead: tied up or led by someone");
            case MEMBER -> Tr.c("screen.dynamicvillages.ranch.out.member", "Belongs to a pen: brought back as a stray instead");
            case OTHER_PEN -> Tr.c("screen.dynamicvillages.ranch.out.other_pen", "In another pen");
            case IN_BUTCHERY -> Tr.c("screen.dynamicvillages.ranch.out.in_butchery", "In the Butchery");
            case UNTICKED -> Tr.c("screen.dynamicvillages.ranch.why.unticked", "Unticked in the Rancher's Managed animals");
            case TOO_FAR -> Tr.c("screen.dynamicvillages.ranch.out.too_far", "%s blocks from the gate, past the Round up range (%s)", why.a(), why.b());
            case FAR_STRAY -> Tr.c("screen.dynamicvillages.ranch.out.far_stray", "%s blocks from the gate: strays are fetched from up to %s", why.a(), why.b());
            case NO_ROOM -> Tr.c("screen.dynamicvillages.ranch.out.no_room", "No room in the pen for another (it holds %s of each)", why.a());
            case CLAIMED -> Tr.c("screen.dynamicvillages.ranch.out.claimed", "Another villager is fetching it");
            case FAILED_LATELY -> Tr.c("screen.dynamicvillages.ranch.out.failed_lately", "A walk to it failed lately: tries again within a minute");
            case NO_MAP -> Tr.c("screen.dynamicvillages.ranch.out.no_map", "The village has no map yet");
            case OUTSIDE_BORDER -> Tr.c("screen.dynamicvillages.ranch.out.outside_border", "Outside the village border: villagers only walk inside it");
            case WALK_FAILED -> Tr.c("screen.dynamicvillages.ranch.out.walk_failed", "A walk there failed lately");
            case NO_PATH -> Tr.c("screen.dynamicvillages.ranch.out.no_path", "No path to it on the village map");
            case NO_LEAD -> Tr.c("screen.dynamicvillages.ranch.out.no_lead", "No lead to lead it with: put one in a storage chest");
            case LEADING -> Tr.c("screen.dynamicvillages.ranch.out.leading", "A villager is leading it on a lead now");
            case WILL_BRING_BACK -> Tr.c("screen.dynamicvillages.ranch.out.will_bring_back", "Will be brought back");
            case WILL_ROUND_UP -> Tr.c("screen.dynamicvillages.ranch.out.will_round_up", "Will be rounded up");
        };
    }

    /** Whether the animal can still be led in: alive, on no lead, not saddled, outside the pen,
     * within {@code range} of its gate. */
    public static boolean fetchable(RanchBuilding ranch, Animal animal, double range) {
        return animal.isAlive() && !animal.isBaby() && !animal.isLeashed() && !LedAnimals.isLed(animal)
                && !RanchStrays.isSaddled(animal)
                && animal.distanceToSqr(ranch.getGatePos().getCenter()) <= range * range
                && !ranch.isPenTile((ServerLevel) animal.level(), animal.blockPosition());
    }

    /**
     * Places per species still free in {@code ranch}: its capacity, less the
     * animals inside and those another villager is bringing in now (loose
     * ones of that kind claimed by someone else).
     */
    public static Map<EntityType<?>, Integer> roomIn(ServerLevel level, RanchBuilding ranch, UUID me, long now) {
        Map<EntityType<?>, Integer> room = new HashMap<>();
        for (EntityType<?> type : List.of(EntityType.COW, EntityType.SHEEP, EntityType.PIG, EntityType.CHICKEN)) {
            room.put(type, ranch.capacityPerSpecies());
        }
        for (Animal animal : ranch.animalsInside(level)) room.computeIfPresent(animal.getType(), (t, n) -> n - 1);
        for (UUID id : ranch.looseNearby()) {
            if (!RanchClaims.isClaimedByOther(id, me, now)) continue;
            if (level.getEntity(id) instanceof Animal animal) room.computeIfPresent(animal.getType(), (t, n) -> n - 1);
        }
        return room;
    }

    /** Every {@link #SCAN_INTERVAL} ticks per pen: the loose animals within {@link #MAX_RANGE} of its gate. */
    private static void scan(Activity.Context ctx, ServerLevel level, RanchBuilding ranch, List<RanchBuilding> ranches) {
        if (!ranch.looseScanDue(level.getGameTime(), SCAN_INTERVAL)) return;
        AABB area = new AABB(ranch.getGatePos()).inflate(MAX_RANGE, SCAN_HEIGHT, MAX_RANGE);
        List<UUID> loose = new ArrayList<>();
        for (Animal animal : level.getEntitiesOfClass(Animal.class, area, Entity::isAlive)) {
            if (RanchAnimals.breedFoodFor(animal.getType()) == null) continue;
            if (!fetchable(ranch, animal, MAX_RANGE)) continue;
            if (isLoose(ctx, level, animal, ranches)) loose.add(animal.getUUID());
        }
        ranch.setLooseNearby(loose);
    }

    /** No pen's and no player's: not a member of a ranch, not in a pen or a Butchery, unnamed and unsaddled. */
    private static boolean isLoose(Activity.Context ctx, ServerLevel level, Animal animal, List<RanchBuilding> ranches) {
        if (animal.hasCustomName() || RanchStrays.isSaddled(animal)) return false;
        long now = level.getGameTime();
        for (RanchBuilding ranch : ranches) {
            if (ranch.isMember(animal.getUUID(), now)) return false;
            if (ranch.isPenTile(level, animal.blockPosition())) return false;
        }
        return !inButchery(ctx.mgr(), animal);
    }

    /**
     * In a Butchery room: the Butcher's now, whoever's it was. Asks the animal's own cell and the cell
     * its feet are in ({@code NavGraph.feetCell}), as the butcher does: on a stair's lower step or a
     * bottom slab one of them is not the room tile. Also the Ranch window's test ({@code RanchOutside}).
     */
    public static boolean inButchery(VillageManager village, Animal animal) {
        BlockPos raw = animal.blockPosition();
        BlockPos feet = NavGraph.feetCell(animal);
        for (Building building : village.getBuildings()) {
            if (building instanceof ButcheryBuilding butchery
                    && (butchery.getNavMeshBlocks().contains(raw) || butchery.getNavMeshBlocks().contains(feet))) {
                return true;
            }
        }
        return false;
    }

    public static List<RanchBuilding> ranches(Activity.Context ctx) {
        List<RanchBuilding> ranches = new ArrayList<>();
        for (Building building : ctx.mgr().getBuildings()) {
            if (building instanceof RanchBuilding ranch && ranch.isValid()) ranches.add(ranch);
        }
        return ranches;
    }

    private static boolean isAllowed(ProfessionSettings settings, EntityType<?> type) {
        return settings.isAllowed(RancherSettingsSchema.ALLOWED_ANIMAL_TYPES, EntityType.getKey(type).toString());
    }
}

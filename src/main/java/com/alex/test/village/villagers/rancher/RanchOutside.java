package com.alex.test.village.villagers.rancher;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.RanchBuilding;
import com.alex.test.village.villagers.butcher.lead.LeadSupply;
import com.alex.test.village.villagers.butcher.lead.LedAnimals;
import com.alex.test.village.villagers.butcher.lead.RanchStrays;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.rancher.RanchRoundUp.Reason;
import com.alex.test.village.villagers.rancher.RanchRoundUp.Why;
import com.alex.test.village.villagers.rancher.behavior.RancherActivity;
import com.alex.test.village.villagers.rancher.settings.RancherSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The animals outside one pen that the Ranch window and {@code /villager ranch} list (round 5 of
 * docs/plans/2026-10-24-ranch-window.md): its strays (its own animals that got out) and loose cows,
 * sheep, pigs and chickens within {@link RanchRoundUp#MAX_RANGE} of the gate, each with why it is or is
 * not led in. Every loaded Rancher of the village is asked; an animal shows the furthest any gets.
 * Reads only: the stray rules copy {@code RanchStrays.strayFor} and {@code RancherActivity.leadJob}.
 */
public final class RanchOutside {

    /** At most this many are listed, strays first, then by distance from the gate. */
    public static final int MAX_LISTED = 24;
    private static final int SCAN_HEIGHT = 12;

    /**
     * One animal outside. Position and look as the window's animals ({@code wool} -1 when none);
     * {@code stray}: this pen's own, {@code letGo}: a villager gave up a walk and let it go out there;
     * {@code distance} from the gate in whole blocks; {@code leader} the villager leading it, else "";
     * {@code entityId} its network id (the window draws the client's copy).
     */
    public record Outsider(String type, float x, float y, float z, int facing, boolean baby, int wool,
                           boolean stray, boolean letGo, int distance, Why why, String leader, int entityId) {}

    /** The listed ones and how many more there were. */
    public record Result(List<Outsider> listed, int more) {}

    public static final Result NONE = new Result(List.of(), 0);

    private RanchOutside() {}

    /**
     * {@code leadInStorage} answers whether storage has a lead the Rancher may take; asked only for a
     * Rancher that carries none and only once nothing else stands in the way.
     */
    public static Result compute(ServerLevel level, VillageManager village, RanchBuilding ranch,
                                 Predicate<Activity.Context> leadInStorage) {
        long now = level.getGameTime();
        List<Activity.Context> ranchers = new ArrayList<>();
        for (VillagerModel model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.getProfession() != Profession.RANCHER || model.brain() == null) {
                continue;
            }
            ranchers.add(new Activity.Context(model.brain(), model, village, level, npc));
        }
        List<RanchBuilding> ranches = new ArrayList<>();
        for (Building building : village.getBuildings()) {
            if (building instanceof RanchBuilding other && other.isValid()) ranches.add(other);
        }

        // This pen's strays (a let-go one may be far off), then every ranch animal near the gate.
        Map<UUID, Animal> found = new LinkedHashMap<>();
        for (UUID id : ranch.strays().keySet()) {
            if (level.getEntity(id) instanceof Animal animal && animal.isAlive()) found.put(id, animal);
        }
        AABB area = new AABB(ranch.getGatePos()).inflate(RanchRoundUp.MAX_RANGE, SCAN_HEIGHT, RanchRoundUp.MAX_RANGE);
        for (Animal animal : level.getEntitiesOfClass(Animal.class, area, Entity::isAlive)) {
            if (RanchAnimals.breedFoodFor(animal.getType()) != null) found.putIfAbsent(animal.getUUID(), animal);
        }

        // Which are out and ours to list; the reasons only for those listed (a big farm nearby has many).
        Vec3 gate = ranch.getGatePos().getCenter();
        List<Animal> out = new ArrayList<>();
        for (Animal animal : found.values()) {
            if (ranch.isPenTile(level, animal.blockPosition()) || inButchery(village, animal)) continue;
            if (ranch.isMember(animal.getUUID(), now) || looseHere(level, ranches, animal, now)) out.add(animal);
        }
        out.sort(Comparator.comparing((Animal animal) -> !ranch.isMember(animal.getUUID(), now))
                .thenComparingDouble(animal -> animal.distanceToSqr(gate)));
        int listed = Math.min(MAX_LISTED, out.size());
        Map<UUID, Map<EntityType<?>, Integer>> rooms = new HashMap<>();
        List<Outsider> outsiders = new ArrayList<>();
        for (Animal animal : out.subList(0, listed)) {
            boolean stray = ranch.isMember(animal.getUUID(), now);
            Why why = verdict(level, ranches, ranch, ranchers, animal, stray, rooms, leadInStorage, now);
            outsiders.add(outsider(animal, stray, ranch.wasLetGo(animal.getUUID()), gate, why, leader(level, animal)));
        }
        return new Result(List.copyOf(outsiders), out.size() - listed);
    }

    /** Loose, as the round-up counts it: in no pen and no pen's member. */
    private static boolean looseHere(ServerLevel level, List<RanchBuilding> ranches, Animal animal, long now) {
        for (RanchBuilding other : ranches) {
            if (other.isMember(animal.getUUID(), now) || other.isPenTile(level, animal.blockPosition())) return false;
        }
        return true;
    }

    /** In a Butchery room: the Butcher's now, whoever's it was (the round-up's own test, both cells). */
    private static boolean inButchery(VillageManager village, Animal animal) {
        return RanchRoundUp.inButchery(village, animal);
    }

    private static Why verdict(ServerLevel level, List<RanchBuilding> ranches, RanchBuilding ranch,
                               List<Activity.Context> ranchers, Animal animal, boolean stray,
                               Map<UUID, Map<EntityType<?>, Integer>> rooms, Predicate<Activity.Context> leadInStorage,
                               long now) {
        if (!leader(level, animal).isEmpty()) return Why.of(Reason.LEADING);
        if (ranchers.isEmpty()) return Why.of(Reason.NO_RANCHER);
        Why furthest = null;
        for (Activity.Context ctx : ranchers) {
            ProfessionSettings settings = ProfessionSettings.of(ctx.npc(), RancherSettingsSchema.SCHEMA);
            Why why;
            if (!settings.getBool(RancherSettingsSchema.WORK_ENABLED)) {
                why = Why.of(Reason.WORK_OFF);
            } else {
                why = stray ? strayWhyNot(ranch, settings, ctx, animal, now)
                        : RanchRoundUp.whyNot(ctx, settings, ranches, ranch, animal,
                                rooms.computeIfAbsent(ctx.npc().getUUID(),
                                        me -> RanchRoundUp.roomIn(level, ranch, me, now)));
                if (why == null) why = RanchRoundUp.whyUnreachable(ctx, animal);
                if (why == null && !LeadSupply.hasLead(ctx.npc()) && !leadInStorage.test(ctx)) why = Why.of(Reason.NO_LEAD);
                if (why == null) return Why.of(stray ? Reason.WILL_BRING_BACK : Reason.WILL_ROUND_UP);
            }
            if (furthest == null || why.reason().ordinal() > furthest.reason().ordinal()) furthest = why;
        }
        return furthest;
    }

    /**
     * Why this Rancher would not bring {@code ranch}'s stray back now, but for a path and a lead: the
     * checks of {@code RanchStrays.strayFor} with the Rancher's Managed animals, in the same order.
     */
    @Nullable
    private static Why strayWhyNot(RanchBuilding ranch, ProfessionSettings settings, Activity.Context ctx,
                                   Animal animal, long now) {
        if (!RancherActivity.isAllowedSpecies(settings, animal.getType())) return Why.of(Reason.UNTICKED);
        if (animal.isBaby()) return Why.of(Reason.BABY);
        if (RanchStrays.isSaddled(animal)) return Why.of(Reason.SADDLED);
        if (animal.isLeashed() || LedAnimals.isLed(animal)) return Why.of(Reason.ON_LEAD);
        double range = ranch.wasLetGo(animal.getUUID()) ? RanchStrays.LET_GO_RANGE : RanchStrays.RANGE;
        double dist = Math.sqrt(animal.distanceToSqr(ranch.getGatePos().getCenter()));
        if (dist > range) return new Why(Reason.FAR_STRAY, (int) Math.round(dist), (int) range);
        UUID me = ctx.npc().getUUID();
        if (RanchClaims.isClaimedByOther(animal.getUUID(), me, now)) return Why.of(Reason.CLAIMED);
        if (!RanchClaims.isFree(animal.getUUID(), me, now)) return Why.of(Reason.FAILED_LATELY);
        return null;
    }

    /** The villager leading it on a lead now, by name; "" when none. */
    private static String leader(ServerLevel level, Animal animal) {
        LedAnimals.Lead lead = LedAnimals.leadOf(animal);
        if (lead == null || !(level.getEntity(lead.holder()) instanceof NpcEntity npc)) return "";
        return npc.givenName() != null ? npc.givenName() : "?";
    }

    private static Outsider outsider(Animal animal, boolean stray, boolean letGo, Vec3 gate, Why why, String leader) {
        int wool = animal instanceof Sheep sheep && !sheep.isSheared() ? sheep.getColor().getId() : -1;
        return new Outsider(BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).toString(),
                sixteenth(animal.getX()), sixteenth(animal.getY()), sixteenth(animal.getZ()),
                Direction.fromYRot(animal.getYRot()).get2DDataValue(), animal.isBaby(), wool, stray, letGo,
                (int) Math.round(Math.sqrt(animal.distanceToSqr(gate))), why, leader, animal.getId());
    }

    private static float sixteenth(double value) {
        return Math.round(value * 16) / 16f;
    }
}

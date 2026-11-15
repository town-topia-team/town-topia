package com.alex.test.village.villagers.butcher.lead;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.RanchBuilding;
import com.alex.test.village.villagers.butcher.behavior.ButcherActivity;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.rancher.RanchClaims;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Saddleable;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Ranch animals that got out: through a gate left open, or let go outside
 * when a butcher gave up a walk. A rancher (or an idle butcher) leads each
 * one back in ({@link BringBackStrayActivity}).
 *
 * <p>A stray is a member of the pen ({@link RanchBuilding#isMember}) found
 * outside it within {@link #RANGE} blocks of the gate, on no lead; one a
 * villager let go ({@link #letGo}) is fetched from up to {@link #LET_GO_RANGE}.
 * Named animals count too: they are the village's, they just are not culled.
 * A saddled one is a player's ride: left be until the saddle comes off.
 * One that stays out of reach for a day is forgotten.
 */
public final class RanchStrays {

    /** Strays further than this from the gate are not looked for. */
    public static final double RANGE = 32.0;
    /** A stray a villager let go is fetched from this far: as far as a Butchery may be, plus the usual range. */
    public static final double LET_GO_RANGE = ButcherActivity.MAX_BUTCHERY_DIST + RANGE;
    /** A stray out for a whole day is forgotten. */
    public static final long FORGET_TICKS = 24000L;
    /** How often each pen looks for its strays. */
    private static final long SCAN_INTERVAL = 100L;

    /** A stray to fetch and the pen it belongs in. */
    public record Stray(RanchBuilding ranch, Animal animal) {}

    private RanchStrays() {}

    /**
     * An animal was let go (a walk given up, a lead lost) by a villager that
     * does not know its pen. If it belongs to a pen it is handed to
     * {@link #letGo}; one no pen has seen lately is left be.
     */
    public static void onLetGo(VillageManager mgr, Level level, Animal animal) {
        if (!(level instanceof ServerLevel sl) || !animal.isAlive()) return;
        long now = sl.getGameTime();
        for (Building building : mgr.getBuildings()) {
            if (!(building instanceof RanchBuilding ranch) || !ranch.isValid()) continue;
            if (!ranch.isMember(animal.getUUID(), now)) continue;
            letGo(ranch, animal, now);
            return;
        }
    }

    /**
     * {@code ranch}'s animal was let go here by a villager: a walk given up, a
     * lead lost, or one left waiting in the Butchery handed back. Outside the
     * pen it is a stray from now on, even if it has not been inside lately,
     * and it is fetched from up to {@link #LET_GO_RANGE} of the gate.
     */
    public static void letGo(RanchBuilding ranch, Animal animal, long now) {
        if (!animal.isAlive() || !(animal.level() instanceof ServerLevel level)) return;
        if (!ranch.isPenTile(level, animal.blockPosition())) ranch.addLetGoStray(animal.getUUID(), now);
    }

    /**
     * The nearest stray {@code ctx}'s villager can fetch now: alive, on no
     * lead, near its gate, not claimed by anyone else, and reachable on the
     * village map. Null when there is none.
     */
    @Nullable
    public static Stray strayFor(Activity.Context ctx) {
        return strayFor(ctx, animal -> true);
    }

    /** As {@link #strayFor(Activity.Context)}, only strays {@code wanted} accepts (a rancher's Managed animals). */
    @Nullable
    public static Stray strayFor(Activity.Context ctx, Predicate<Animal> wanted) {
        if (!(ctx.level() instanceof ServerLevel level)) return null;
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        long now = level.getGameTime();
        UUID me = ctx.npc().getUUID();
        Stray best = null;
        double bestDist = Double.MAX_VALUE;
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof RanchBuilding ranch) || !ranch.isValid()) continue;
            scan(ctx.mgr(), level, ranch);
            for (UUID id : ranch.strays().keySet()) {
                if (!(level.getEntity(id) instanceof Animal animal) || !fetchable(ranch, animal)) continue;
                if (!wanted.test(animal)) continue;
                if (!RanchClaims.isFree(id, me, now)) continue;
                if (!ctx.model().canReach(graph, animal.blockPosition())) continue;
                double dist = ctx.npc().distanceToSqr(animal);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = new Stray(ranch, animal);
                }
            }
        }
        return best;
    }

    /**
     * Whether the stray can be led now: alive, grown (a lead does not go on a
     * baby; it follows its mother back), outside, near the gate (further off
     * for one a villager let go), on no lead, not saddled.
     */
    public static boolean fetchable(RanchBuilding ranch, Animal animal) {
        double range = ranch.wasLetGo(animal.getUUID()) ? LET_GO_RANGE : RANGE;
        return animal.isAlive() && !animal.isBaby() && !animal.isLeashed() && !LedAnimals.isLed(animal)
                && !isSaddled(animal)
                && animal.distanceToSqr(ranch.getGatePos().getCenter()) <= range * range
                && !ranch.isPenTile((ServerLevel) animal.level(), animal.blockPosition());
    }

    /** A player's ride: no villager leads, rounds up or pushes back a saddled animal. */
    public static boolean isSaddled(Animal animal) {
        return animal instanceof Saddleable ride && ride.isSaddled();
    }

    /** It is home: inside its pen again. A later escape is an ordinary stray. */
    public static void markHome(RanchBuilding ranch, Animal animal) {
        ranch.removeStray(animal.getUUID());
    }

    /**
     * Every {@link #SCAN_INTERVAL} ticks per pen: members found outside become
     * strays, strays seen inside again are home, and strays out a whole day
     * (or gone from the world) are forgotten.
     */
    private static void scan(VillageManager mgr, ServerLevel level, RanchBuilding ranch) {
        long now = level.getGameTime();
        if (!ranch.strayScanDue(now, SCAN_INTERVAL)) return;
        ranch.animalsInside(level); // refreshes who is inside, and clears strays that came home
        AABB area = new AABB(ranch.getGatePos()).inflate(RANGE);
        for (Animal animal : level.getEntitiesOfClass(Animal.class, area, Entity::isAlive)) {
            if (LedAnimals.isLed(animal)) continue;
            if (!ranch.isMember(animal.getUUID(), now)) continue;
            if (!ranch.isPenTile(level, animal.blockPosition())) ranch.addStray(animal.getUUID(), now);
        }
        List<UUID> forgotten = new ArrayList<>();
        for (Iterator<Map.Entry<UUID, Long>> it = ranch.strays().entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> entry = it.next();
            Entity entity = level.getEntity(entry.getKey());
            boolean dead = entity != null && !entity.isAlive();
            if (dead || now - entry.getValue() > FORGET_TICKS) forgotten.add(entry.getKey());
        }
        for (UUID id : forgotten) {
            Entity entity = level.getEntity(id);
            String what = entity == null ? "an animal" : entity.getType().getDescription().getString();
            boolean died = entity != null && !entity.isAlive();
            ranch.forget(id);
            if (died) continue; // nothing to tell: it is gone
            DynamicVillages.LOGGER.info("[Ranch] stray {} ({}) forgotten after a day out.", what, id);
            diaryOfRancher(mgr, level, "RANCH stray " + what + (entity instanceof Animal a && isSaddled(a)
                    ? " forgotten: a player's ride for a day" : " forgotten: out of reach for a day"));
        }
    }

    /** One line in the first rancher's diary, so the player can find out where the animal went. */
    private static void diaryOfRancher(VillageManager mgr, ServerLevel level, String line) {
        for (VillagerModel model : mgr.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc != null && npc.getProfession() == Profession.RANCHER) {
                model.brain().diary().log(VillagerDiary.ts(level) + " " + line);
                return;
            }
        }
    }
}

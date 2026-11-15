package com.alex.test.village.villagers.butcher.lead;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.brain.VillagerPause;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Every animal a villager is leading right now, server side.
 *
 * <p>A led animal is leashed to its villager the vanilla way, so the rope
 * shows, but vanilla's follow, pull and snap are switched off (see
 * {@code PathfinderMobLeashMixin}) and its own goals cannot move it: the
 * villager's {@link LeadWalk} steers it instead.
 *
 * <p>The safety net: whoever leads an animal must tick its walk every tick.
 * A lead not ticked for 40 ticks (the activity ended badly, the chunk
 * unloaded, the villager died) is released here, so an animal can never stay
 * leashed, or with its AI switched off, for good. A villager that is only
 * paused ({@link VillagerPause}: a player has its screen open) keeps hold,
 * and its animal waits on the spot. Every release goes through
 * {@link #release}, which drops no lead item: the lead never left the
 * villager's pack.
 */
public final class LedAnimals {

    /** Ticks a lead may go untouched before the safety net lets the animal go. */
    private static final long STALE_TICKS = 40;

    /** One led animal. */
    public static final class Lead {
        final UUID animal;
        final UUID holder;
        final ResourceKey<Level> dimension;
        final LeadWalk walk = new LeadWalk();
        long lastTicked;
        /** {@code /vtest lead}: nobody else ticks this walk, so the safety net does. */
        boolean debugFollow;

        Lead(UUID animal, UUID holder, ResourceKey<Level> dimension, long now) {
            this.animal = animal;
            this.holder = holder;
            this.dimension = dimension;
            this.lastTicked = now;
        }

        public UUID holder() { return holder; }
        public LeadWalk walk() { return walk; }
    }

    private static final Map<UUID, Lead> LEADS = new HashMap<>();

    private LedAnimals() {}

    /**
     * Leashes {@code animal} to {@code npc} and takes over its walking.
     * Refuses an animal already on any leash, a baby, a dead one, or one
     * already led.
     */
    public static boolean start(Animal animal, NpcEntity npc) {
        if (!(animal.level() instanceof ServerLevel level)) return false;
        if (!animal.isAlive() || animal.isBaby() || animal.isLeashed() || LEADS.containsKey(animal.getUUID())) {
            return false;
        }
        animal.setLeashedTo(npc, true);
        if (animal.getLeashHolder() != npc) return false;
        Lead lead = new Lead(animal.getUUID(), npc.getUUID(), level.dimension(), level.getGameTime());
        lead.walk.restartFrom(animal.position());
        LEADS.put(animal.getUUID(), lead);
        animal.getNavigation().stop();
        animal.clearRestriction();
        animal.resetLove();
        animal.setLastHurtByMob(null);
        holdStill(animal);
        return true;
    }

    public static boolean isLed(Entity animal) {
        return LEADS.containsKey(animal.getUUID());
    }

    /** Whether {@code npc} is the one leading {@code animal}. */
    public static boolean isLedBy(Entity animal, NpcEntity npc) {
        Lead lead = LEADS.get(animal.getUUID());
        return lead != null && lead.holder.equals(npc.getUUID());
    }

    @Nullable
    public static Lead leadOf(Entity animal) {
        return LEADS.get(animal.getUUID());
    }

    /** The trail {@code animal} walks, or null when it is not led. */
    @Nullable
    public static LeadWalk trailOf(Entity animal) {
        Lead lead = LEADS.get(animal.getUUID());
        return lead == null ? null : lead.walk;
    }

    /**
     * Steers {@code animal} one tick along {@code npc}'s footsteps and keeps
     * its lead alive for the safety net. The leading activity calls this
     * every tick.
     */
    public static LeadWalk.Result tickWalk(NpcEntity npc, Animal animal) {
        Lead lead = LEADS.get(animal.getUUID());
        if (lead == null || !lead.holder.equals(npc.getUUID())) return LeadWalk.Result.LOST;
        lead.lastTicked = animal.level().getGameTime();
        holdStill(animal);
        return lead.walk.tick(npc, animal);
    }

    /** Marks a lead as {@code /vtest lead}'s: the safety net ticks its walk itself. */
    public static void setDebugFollow(Animal animal, boolean on) {
        Lead lead = LEADS.get(animal.getUUID());
        if (lead != null) lead.debugFollow = on;
    }

    /**
     * Lets {@code animal} go: leash off without dropping a lead item, its own
     * goals and walking back on. The one way any lead ends.
     */
    public static void release(Animal animal, String reason) {
        Lead lead = LEADS.remove(animal.getUUID());
        if (animal.isLeashed() && animal.getLeashHolder() instanceof NpcEntity) {
            animal.dropLeash(true, false);
        }
        animal.goalSelector.enableControlFlag(Goal.Flag.MOVE);
        animal.goalSelector.enableControlFlag(Goal.Flag.JUMP);
        animal.getNavigation().stop();
        if (lead != null && animal.level() instanceof ServerLevel level
                && level.getEntity(lead.holder) instanceof NpcEntity npc) {
            diary(npc, level, "LEAD released " + animal.getType().getDescription().getString() + ": " + reason);
        }
    }

    /** Drops {@code animal} from the list without touching it: it is leaving the world. */
    public static void forget(Animal animal) {
        LEADS.remove(animal.getUUID());
    }

    /** Releases every animal {@code npc} leads; for its death or removal. */
    public static void releaseAllHeldBy(NpcEntity npc, String reason) {
        if (LEADS.isEmpty() || !(npc.level() instanceof ServerLevel level)) return;
        for (Lead lead : new ArrayList<>(LEADS.values())) {
            if (!lead.holder.equals(npc.getUUID())) continue;
            if (level.getEntity(lead.animal) instanceof Animal animal) release(animal, reason);
            else LEADS.remove(lead.animal);
        }
    }

    /**
     * Every animal led in {@code level} right now, with its villager, both loaded. For
     * {@code FarExtras}: past the simulation distance it runs their ticks. Walks a copy, so the
     * action may end a lead.
     */
    public static void forEachLed(ServerLevel level, java.util.function.BiConsumer<Animal, NpcEntity> action) {
        if (LEADS.isEmpty()) return;
        for (Lead lead : new ArrayList<>(LEADS.values())) {
            if (!lead.dimension.equals(level.dimension())) continue;
            if (level.getEntity(lead.animal) instanceof Animal animal
                    && level.getEntity(lead.holder) instanceof NpcEntity npc) {
                action.accept(animal, npc);
            }
        }
    }

    /**
     * The safety net, every tick per level. Does nothing while nothing is led.
     */
    public static void tick(ServerLevel level) {
        if (LEADS.isEmpty()) return;
        long now = level.getGameTime();
        List<Lead> leads = new ArrayList<>(LEADS.values());
        for (Lead lead : leads) {
            if (!lead.dimension.equals(level.dimension())) continue;
            Entity animalEntity = level.getEntity(lead.animal);
            Entity holderEntity = level.getEntity(lead.holder);
            if (!(animalEntity instanceof Animal animal) || !animal.isAlive()) {
                LEADS.remove(lead.animal);
                if (animalEntity instanceof Animal gone) release(gone, "animal gone");
                continue;
            }
            if (!(holderEntity instanceof NpcEntity npc) || !npc.isAlive()) {
                release(animal, "villager gone");
                continue;
            }
            if (animal.getLeashHolder() != npc) {
                release(animal, "leash lost");
                continue;
            }
            if (lead.debugFollow) {
                lead.lastTicked = now;
                holdStill(animal);
                LeadWalk.Result result = lead.walk.tick(npc, animal);
                if (result == LeadWalk.Result.STUCK || result == LeadWalk.Result.LOST) {
                    release(animal, "debug follow " + result.name().toLowerCase(Locale.ROOT));
                }
                continue;
            }
            // Its villager is paused (a player has its screen open): still
            // holding the rope, so the animal waits on the spot.
            if (VillagerPause.covers(lead.holder, now) && now - lead.lastTicked > 1) {
                holdStill(animal);
                LeadWalk.standStill(animal);
                continue;
            }
            if (now - lead.lastTicked > STALE_TICKS) {
                DynamicVillages.LOGGER.info("[Lead] {} led by {} not walked for {} ticks; letting it go.",
                        animal.getType().getDescription().getString(), npc.getUUID(), now - lead.lastTicked);
                release(animal, "nobody walked it");
                continue;
            }
            holdStill(animal);
        }
    }

    /** {@code /vtest lead stop}: lets every debug-followed animal go. Returns how many. */
    public static int stopDebugFollows(net.minecraft.server.MinecraftServer server) {
        int stopped = 0;
        for (Lead lead : new ArrayList<>(LEADS.values())) {
            if (!lead.debugFollow) continue;
            ServerLevel level = server.getLevel(lead.dimension);
            if (level != null && level.getEntity(lead.animal) instanceof Animal animal) {
                release(animal, "debug follow stopped");
            } else {
                LEADS.remove(lead.animal);
            }
            stopped++;
        }
        return stopped;
    }

    /** Server stopping: the next world starts with nothing led. */
    public static void clear() {
        LEADS.clear();
    }

    /**
     * Just before a led animal's own tick: its goals off again. Vanilla turns
     * them back on at the end of every fifth tick, and an animal that ticked
     * before its villager got one tick of its own wandering or grazing first:
     * a twitch off the trail every quarter second.
     */
    public static void beforeTick(Entity entity) {
        if (LEADS.isEmpty() || entity.level().isClientSide) return;
        if (entity instanceof Animal animal && LEADS.containsKey(animal.getUUID())) holdStill(animal);
    }

    /**
     * Its own goals off: vanilla turns these back on every few ticks
     * ({@code Mob.updateControlFlags}), so this runs every tick it is led.
     */
    private static void holdStill(Animal animal) {
        animal.goalSelector.disableControlFlag(Goal.Flag.MOVE);
        animal.goalSelector.disableControlFlag(Goal.Flag.JUMP);
        animal.getNavigation().stop();
    }

    private static void diary(NpcEntity npc, ServerLevel level, String line) {
        var model = npc.resolveVillageModel(level);
        if (model != null) model.brain().diary().log(VillagerDiary.ts(level) + " " + line);
    }
}

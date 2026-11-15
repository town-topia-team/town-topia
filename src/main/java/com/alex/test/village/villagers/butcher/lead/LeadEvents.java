package com.alex.test.village.villagers.butcher.lead;

import com.alex.test.entity.NpcEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Ticks {@link LedAnimals}' safety net, and lets animals go the moment their
 * villager dies: before vanilla's "holder dead" rule could drop a lead item.
 * Registered on the game bus by {@link com.alex.test.DynamicVillages}.
 */
public final class LeadEvents {

    private LeadEvents() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (com.alex.test.village.stuckwatch.StuckWatch.worldHeld(level)) return;
        LedAnimals.tick(level);
        LeadDoorways.tickCloseLater(level);
        RanchGateGuard.tick(level);
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Pre event) {
        LedAnimals.beforeTick(event.getEntity());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled() || event.getEntity().level().isClientSide) return;
        if (event.getEntity() instanceof NpcEntity npc) {
            LedAnimals.releaseAllHeldBy(npc, "villager died");
        } else if (event.getEntity() instanceof Animal animal && LedAnimals.isLed(animal)) {
            LedAnimals.release(animal, "killed on the way");
        }
    }

    /**
     * A villager unloaded, removed or moved to another dimension mid-walk:
     * vanilla would see its leash holder gone on the animal's next tick and
     * drop a lead item, a second lead. Let the animal go first. An animal
     * leaving (unloaded) is just forgotten: it was saved without its leash.
     */
    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            if (event.getEntity() instanceof NpcEntity npc) relinkLater(npc);
            return;
        }
        if (event.getEntity() instanceof NpcEntity npc) {
            LedAnimals.releaseAllHeldBy(npc, "villager left");
        } else if (event.getEntity() instanceof Animal animal && LedAnimals.isLed(animal)) {
            LedAnimals.forget(animal);
        }
    }

    /**
     * Client side: a villager's copy left the player's world (it walked out
     * of tracking range, 80 blocks, while its animal, tracked to 160, stayed).
     * Vanilla keeps the rope tied to that copy for good and never looks the
     * holder up again, so the rope hung frozen where the villager was last
     * seen, often by the pen. Each animal on its rope goes back to waiting
     * for the villager's id; the new copy is picked up when it comes back
     * ({@code Leashable.getLeashHolder}), and no rope shows until then.
     */
    private static void relinkLater(NpcEntity npc) {
        AABB around = npc.getBoundingBox().inflate(24.0);
        for (Animal animal : npc.level().getEntitiesOfClass(Animal.class, around,
                a -> a.isLeashed() && a.getLeashHolder() == npc)) {
            animal.setDelayedLeashHolderId(npc.getId());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LedAnimals.clear();
        com.alex.test.village.DoorHolds.clear();
        LeadDoorways.clearCloseLater();
    }
}

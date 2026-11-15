package com.alex.test.village.villagers.guard.alert;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.building.Building;
import com.alex.test.village.villagers.guard.settings.GuardMobPolicy;
import com.alex.test.village.villagers.guard.settings.GuardSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Shared guard-threat policy that is independent of alert assignment state. */
public final class GuardThreatRules {

    private static final double MAX_OFF_VILLAGE_CHASE_DIST_SQ = 32.0 * 32.0;
    /** How long a village's "which friendly mobs are turned hostile" answer is reused. */
    private static final int VILLAGE_POLICY_TTL_TICKS = 100;

    private record VillagePolicy(long until, Set<ResourceLocation> turnedHostile) {}

    private static final Map<UUID, VillagePolicy> VILLAGE_POLICIES = new HashMap<>();

    private GuardThreatRules() {}

    public static boolean isTooFarFromVillage(Activity.Context ctx, BlockPos pos) {
        if (ctx.mgr().contains(pos)) return false;
        for (Building building : ctx.mgr().getBuildings()) {
            if (building.getDoorPos().distSqr(pos) <= MAX_OFF_VILLAGE_CHASE_DIST_SQ) return false;
        }
        for (VillagerModel model : ctx.mgr().getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc != null && npc.isAlive()
                    && npc.blockPosition().distSqr(pos) <= MAX_OFF_VILLAGE_CHASE_DIST_SQ) {
                return false;
            }
        }
        return true;
    }

    /** Default village threat policy, for code that does not know which village it is in. */
    public static boolean isHostileMonster(@Nullable Entity entity) {
        if (!GuardMobPolicy.isGuardAttackCandidate(entity)) return false;
        return GuardMobPolicy.defaultAllowed(entity.getType());
    }

    /**
     * Whether villagers of {@code manager} treat {@code entity} as a threat: the
     * default hostile mobs, plus any normally-friendly one -- a creeper -- that
     * one of the village's defenders has been set to fight. Turning creepers on
     * for a guard is the player saying creepers are enemies here, so villagers
     * flee them, report them, and creepers may go for villagers.
     */
    public static boolean isHostileMonster(@Nullable VillageManager manager, @Nullable Entity entity) {
        if (!GuardMobPolicy.isGuardAttackCandidate(entity)) return false;
        if (GuardMobPolicy.defaultAllowed(entity.getType())) return true;
        return manager != null
                && villageTurnedHostile(manager, entity.level().getGameTime(), EntityType.getKey(entity.getType()));
    }

    /** Whether a defender of the village has the normally-friendly mob {@code id} ticked on. */
    public static boolean villageTurnedHostile(VillageManager manager, long now, ResourceLocation id) {
        VillagePolicy policy = VILLAGE_POLICIES.get(manager.getVillageId());
        if (policy == null || now >= policy.until() || now < policy.until() - VILLAGE_POLICY_TTL_TICKS) {
            Set<ResourceLocation> turned = new HashSet<>();
            for (VillagerModel model : manager.getVillagers()) {
                NpcEntity npc = model.puppet();
                if (npc == null || !npc.getProfession().isDefender()) continue;
                GuardSettings settings = GuardSettings.of(npc);
                if (!settings.attackHostileMobs()) continue;
                for (ResourceLocation friendly : GuardMobPolicy.defaultDisabled()) {
                    if (settings.isMobAllowed(friendly)) turned.add(friendly);
                }
            }
            policy = new VillagePolicy(now + VILLAGE_POLICY_TTL_TICKS, Set.copyOf(turned));
            VILLAGE_POLICIES.put(manager.getVillageId(), policy);
        }
        return policy.turnedHostile().contains(id);
    }

    /** Forgets cached village policies; call when a guard's mob settings change. */
    public static void invalidateVillagePolicies() {
        VILLAGE_POLICIES.clear();
    }

    public static boolean isHostileMonsterForGuard(NpcEntity guard, @Nullable Entity entity) {
        if (!GuardMobPolicy.isGuardAttackCandidate(entity)) return false;
        if (entity.isInWaterOrBubble()) return false;
        GuardSettings settings = GuardSettings.of(guard);
        return settings.attackHostileMobs()
                && settings.isMobAllowed(entity.getType());
    }

    /**
     * Whether {@code guard} should fight {@code entity} in self-defence: any
     * hostile mob that hurt it within {@code memoryTicks}, whatever the guard's
     * allow list or "attack hostile mobs" setting says. Those settings decide
     * what a guard goes looking for, not whether it lets itself be beaten.
     */
    public static boolean isSelfDefenseTarget(NpcEntity guard, @Nullable Entity entity, int memoryTicks) {
        if (entity == null || entity != guard.getLastHurtByMob()) return false;
        if (guard.tickCount - guard.getLastHurtByMobTimestamp() > memoryTicks) return false;
        return GuardMobPolicy.isHostileCreature(entity) && !entity.isInWaterOrBubble();
    }
}

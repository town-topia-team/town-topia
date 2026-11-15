package com.alex.test.village.villagers.guard.settings;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.VillagerVillageLink;
import com.alex.test.village.villagers.guard.GuardSwordUtils;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/** Applies persistent Guard settings changes to live equipment state. */
public final class GuardSettingsChangeHandler {

    private GuardSettingsChangeHandler() {}

    public static void onSwordSettingChanged(NpcEntity villager) {
        if (villager.level().isClientSide || !villager.isAlive()) return;
        if (!(villager.level() instanceof ServerLevel level)) return;

        VillageManager manager = resolveManager(villager, level);
        if (manager != null && manager.isDormantOrReconciling()) return;

        GuardSwordUtils.equipBestAllowedSword(
                villager,
                GuardSettings.of(villager).getAllowedSwords());
    }

    public static void onBehaviorSettingChanged(NpcEntity villager) {
        if (villager.level().isClientSide || !villager.isAlive()) return;
        // "Attack hostile mobs" decides whether this defender's creeper tick counts village-wide.
        com.alex.test.village.villagers.guard.alert.GuardThreatRules.invalidateVillagePolicies();
        if (!(villager.level() instanceof ServerLevel level)) return;

        VillageManager manager = resolveManager(villager, level);
        if (manager == null) return;
        if (manager.isDormantOrReconciling()) return;

        VillagerModel model = manager.getModel(villager.getNpcId());
        if (model == null) return;

        model.brain().onGuardBehaviorSettingsChanged(manager, level, villager);
    }

    @Nullable
    private static VillageManager resolveManager(NpcEntity villager, ServerLevel level) {
        VillagerVillageLink link = villager.getVillageLink();
        if (link == null) return null;

        VillageManager manager =
                VillageManagerRegistry.get(level.getServer()).get(link.villageId());
        if (manager != null) return manager;
        return VillageManager.INSTANCE.getVillageId().equals(link.villageId())
                ? VillageManager.INSTANCE
                : null;
    }
}

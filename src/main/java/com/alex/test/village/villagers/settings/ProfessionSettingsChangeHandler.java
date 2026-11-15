package com.alex.test.village.villagers.settings;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.VillagerVillageLink;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/**
 * Applies a generic profession-settings change to the live activity
 * immediately, mirroring {@code GuardSettingsChangeHandler}/
 * {@code LumberjackSettingsChangeHandler}'s village-resolution shape but
 * without any profession-specific logic -- see
 * {@link com.alex.test.village.brain.VillagerBrain#onProfessionSettingsChanged}.
 */
public final class ProfessionSettingsChangeHandler {

    private ProfessionSettingsChangeHandler() {}

    public static void onSettingsChanged(NpcEntity villager) {
        if (villager.level().isClientSide || !villager.isAlive()) return;
        if (!(villager.level() instanceof ServerLevel level)) return;

        VillageManager manager = resolveManager(villager, level);
        if (manager == null) return;
        if (manager.isDormantOrReconciling()) return;

        VillagerModel model = manager.getModel(villager.getNpcId());
        if (model == null || model.brain() == null) return;

        model.brain().onProfessionSettingsChanged(manager, level, villager);
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

package com.alex.test.village.villagers.lumberjack.settings;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.VillagerVillageLink;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/** Applies persistent Lumberjack settings changes to live activity state. */
public final class LumberjackSettingsChangeHandler {

    private LumberjackSettingsChangeHandler() {}

    public static void onWorkSettingChanged(NpcEntity villager, boolean enabled) {
        apply(villager, enabled, null);
    }

    public static void onTreeSettingChanged(
            NpcEntity villager, ResourceLocation treeType, boolean enabled) {
        apply(villager, LumberjackSettings.of(villager).works(),
                enabled ? null : treeType);
    }

    private static void apply(
            NpcEntity villager,
            boolean workEnabled,
            @Nullable ResourceLocation disabledTreeType) {
        if (!(villager.level() instanceof ServerLevel level)) return;

        VillageManager manager = resolveManager(villager, level);
        if (manager == null) return;
        if (manager.isDormantOrReconciling()) return;

        VillagerModel model = manager.getModel(villager.getNpcId());
        if (model == null) return;

        model.brain().onLumberjackSettingsChanged(
                manager, level, villager, workEnabled, disabledTreeType);
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

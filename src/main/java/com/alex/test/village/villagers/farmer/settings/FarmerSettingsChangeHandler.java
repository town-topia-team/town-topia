package com.alex.test.village.villagers.farmer.settings;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.VillagerVillageLink;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/** Applies Farmer setting changes to live work and reservation state. */
public final class FarmerSettingsChangeHandler {

    private FarmerSettingsChangeHandler() {}

    public static void onSettingsChanged(NpcEntity farmer) {
        if (!(farmer.level() instanceof ServerLevel level)) return;
        VillageManager manager = resolveManager(farmer, level);
        if (manager == null || manager.isDormantOrReconciling()) return;
        VillagerModel model = manager.getModel(farmer.getNpcId());
        if (model == null) return;
        model.brain().onProfessionSettingsChanged(manager, level, farmer);
    }

    @Nullable
    private static VillageManager resolveManager(NpcEntity farmer, ServerLevel level) {
        VillagerVillageLink link = farmer.getVillageLink();
        if (link == null) return null;
        VillageManager manager = VillageManagerRegistry.get(level.getServer()).get(link.villageId());
        if (manager != null) return manager;
        return VillageManager.INSTANCE.getVillageId().equals(link.villageId())
                ? VillageManager.INSTANCE : null;
    }
}

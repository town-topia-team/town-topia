package com.alex.test.village.villagers.rancher;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.RanchBuilding;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * A pen's animal killed by a Butcher: a "culled" line in that pen's recent happenings (Ranch window
 * round 7). Listened for here, so the butcher's own code is not touched. The pen is the one the
 * animal was seen in within the last 5 minutes ({@link RanchBuilding#isMember}), which still holds
 * on its walk to the Butchery.
 */
@EventBusSubscriber(modid = DynamicVillages.MODID)
public final class RanchButcherKills {

    /** How far from the dead animal its village's bell may be. */
    private static final int VILLAGE_REACH = 160;

    private RanchButcherKills() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof Animal animal)) return;
        if (!(animal.level() instanceof ServerLevel level) || level.getServer() == null) return;
        if (!(event.getSource().getEntity() instanceof NpcEntity butcher) || butcher.getProfession() != Profession.BUTCHER) return;
        VillageManager village = VillageManagerRegistry.get(level.getServer())
                .findNearestVillage(level, animal.blockPosition(), VILLAGE_REACH);
        if (village == null) return;
        long now = level.getGameTime();
        for (Building building : village.getBuildings()) {
            if (!(building instanceof RanchBuilding ranch) || !ranch.isValid() || !ranch.isMember(animal.getUUID(), now)) continue;
            VillagerModel model = village.getModel(butcher.getNpcId());
            String who = model != null ? VillageManager.displayName(model) : butcher.getName().getString();
            RanchHappenings.note(ranch, RanchHappenings.Kind.BUTCHER_TOOK, who, animal.getType(), 0, now);
            return;
        }
    }
}

package com.alex.test.village.raid;

import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.building.StorageInventory;
import com.alex.test.village.building.WatchtowerBuilding;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.visitors.MerchantPricing;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;

/**
 * What a village is worth to raiders and how well it is defended, counted
 * from the live village once, when a raid's warning starts.
 *
 * @param villagers grown villagers (children do not count)
 * @param wealth    storage contents in emeralds at today's base prices
 * @param defense   {@link RaidMath#defense} of its guards, captains, archers and towers
 */
public record RaidCensus(int villagers, double wealth, double defense, int defenders) {

    public static RaidCensus of(VillageManager village, ServerLevel level) {
        int villagers = 0;
        int guards = 0, guardLevels = 0;
        int captains = 0, captainLevels = 0;
        int archers = 0, archerLevels = 0;
        int mages = 0;
        for (VillagerModel model : village.getVillagers()) {
            if (model.brain() == null) continue;
            Profession job = model.brain().profession();
            if (job == Profession.CHILD) continue;
            villagers++;
            if (job == Profession.CAPTAIN) {
                captains++;
                captainLevels += model.brain().defenderSkillLevel();
            } else if (job == Profession.GUARD) {
                guards++;
                guardLevels += model.brain().defenderSkillLevel();
            } else if (job == Profession.ARCHER) {
                archers++;
                archerLevels += model.brain().defenderSkillLevel();
            } else if (job == Profession.MAGE) {
                // Counted with the archers: the other defender that fights from range.
                mages++;
                archerLevels += model.brain().defenderSkillLevel();
            }
        }
        int towers = 0;
        double wealth = 0;
        for (Building building : village.getBuildings()) {
            if (building instanceof WatchtowerBuilding && archers > 0) towers++;
            if (building instanceof StorageBuilding storage) wealth += valueOf(storage, level);
        }
        double defense = RaidMath.defense(guards, guardLevels, captains, captainLevels,
                archers + mages, archerLevels, towers);
        return new RaidCensus(villagers, wealth, defense, guards + captains + archers + mages);
    }

    /**
     * Guards, archers and captains standing now (one whose body is not loaded
     * counts as standing): the Bad Omen raid is lost when too few are left.
     */
    public static int defendersAlive(VillageManager village) {
        int count = 0;
        for (VillagerModel model : village.getVillagers()) {
            if (model.brain() == null) continue;
            Profession job = model.brain().profession();
            if (!job.isDefender()) continue;
            com.alex.test.entity.NpcEntity npc = model.puppet();
            if (npc == null || npc.isAlive()) count++;
        }
        return count;
    }

    private static double valueOf(StorageBuilding storage, ServerLevel level) {
        double value = 0;
        for (StorageInventory inventory : storage.inventories(level)) {
            for (int slot = 0; slot < inventory.size(); slot++) {
                ItemStack stack = inventory.peek(slot);
                if (stack.isEmpty()) continue;
                int price = MerchantPricing.fullStackPrice(stack.getItem());
                if (price <= 0) continue;
                value += price * (double) stack.getCount() / Math.max(1, stack.getMaxStackSize());
            }
        }
        return value;
    }

    /** Easy raids are smaller and hard ones bigger; Peaceful has none (see {@link RaidManager}). */
    public static double difficultyScale(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> 0.7;
            case HARD -> 1.3;
            default -> 1.0;
        };
    }
}

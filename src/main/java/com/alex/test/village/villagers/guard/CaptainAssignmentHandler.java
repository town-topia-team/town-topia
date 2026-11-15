package com.alex.test.village.villagers.guard;

import com.alex.test.ModItems;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.building.BarracksBuilding;
import com.alex.test.village.building.Building;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.core.ProfessionSkill;
import com.alex.test.village.villagers.guard.settings.GuardSettings;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Assignment into the Captain profession: the barracks' elite guard, one per
 * valid Barracks. A guard promoted to captain brings its experience with it.
 *
 * <p>Every captain holds its place, loaded or not: the village remembers who
 * was a captain when last seen ({@link VillageManager#captainIds}). No new
 * captain without a barracks that lacks one.
 */
public final class CaptainAssignmentHandler {

    private CaptainAssignmentHandler() {}

    public static boolean isCaptainToken(ItemStack stack) {
        return stack.is(ModItems.CAPTAIN_TOKEN.get());
    }

    /** First assignment from jobless: the guard's starter sword, then the captain's job. */
    public static boolean assignFirstTime(NpcEntity villager) {
        if (villager.getProfession() != Profession.NONE) return false;
        if (!GuardAssignmentHandler.ensureStarterSword(villager)) return false;
        villager.setProfession(Profession.CAPTAIN);
        GuardAssignmentHandler.wakeAssignedGuard(villager);
        GuardSwordUtils.equipBestAllowedSword(villager, GuardSettings.of(villager).getAllowedSwords());
        return villager.getProfession() == Profession.CAPTAIN;
    }

    /** Valid barracks in the village: one captain each. */
    public static int barracksCount(@Nullable VillageManager village) {
        if (village == null) return 0;
        int count = 0;
        for (Building building : village.getBuildings()) {
            if (building instanceof BarracksBuilding && building.isValid()) count++;
        }
        return count;
    }

    /** Every captain in the village, loaded or not, leaving out {@code except}. */
    public static int captainCount(@Nullable VillageManager village, @Nullable NpcEntity except) {
        if (village == null) return 0;
        Set<UUID> captains = village.captainIds();
        if (except != null) captains.remove(except.getNpcId());
        return captains.size();
    }

    /** How many valid barracks still lack a captain. */
    public static int openCaptaincies(@Nullable VillageManager village) {
        return Math.max(0, barracksCount(village) - captainCount(village, null));
    }

    /** Whether a barracks still lacks a captain, so {@code candidate} may become one. */
    public static boolean hasOpenBarracks(@Nullable VillageManager village, @Nullable NpcEntity candidate) {
        return captainCount(village, candidate) < barracksCount(village);
    }

    /**
     * A guard promoted to captain keeps its experience: the captain slot is
     * raised to the level of the guard slot it had before the switch. The
     * brain's skill sync then turns that level into captain experience.
     */
    public static void carryOverGuardLevel(NpcEntity villager, List<ProfessionSkill> skillsBefore) {
        int guardLevel = 0;
        for (ProfessionSkill skill : skillsBefore) {
            if (skill.profession() == Profession.GUARD) guardLevel = skill.level();
        }
        if (guardLevel <= 0) return;
        for (ProfessionSkill skill : villager.getProfessionSkills()) {
            if (skill.profession() == Profession.CAPTAIN && skill.level() < guardLevel) {
                villager.setProfessionSkillLevel(Profession.CAPTAIN, guardLevel);
            }
        }
    }
}

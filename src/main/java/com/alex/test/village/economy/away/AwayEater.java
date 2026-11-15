package com.alex.test.village.economy.away;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.stats.HungerSystem;
import com.alex.test.village.stats.VillagerStats;
import com.alex.test.village.stats.WellbeingBalance;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.core.ProfessionSkill;

/**
 * One villager through a day's meals and health ({@link AwayHealth},
 * {@link AwayMeals}): its copy swapped into the body, so {@link #needs} and
 * the body's pack are the copy's.
 */
final class AwayEater {

    final AwayVillager villager;
    final NpcEntity npc;
    final VillagerStats needs;
    /** A job with its work on: work effort and the work's toll on mood in the work hours. */
    final boolean works;
    /** Extra hunger a working tick costs, at its skill (live {@code HungerSystem.workExertionPerTick}). */
    final float exertion;
    /** Wellbeing a working tick costs, by its personality (live {@code WellbeingBalance.workDrain}). */
    final float workDrain;
    /** Sleeps in its own bed (the village gave it one): the full sleep gain. */
    final boolean hasBed;
    /** Health in the copy; the body gets it back at the day's end, at least 1. */
    float health;
    /** Fancy servings ({@code FoodCatalog.isRationed}) it may still have today. */
    int rationedLeft = MealShare.RATIONED_PER_DAY;

    AwayEater(AwayVillager villager, VillageManager mgr) {
        this.villager = villager;
        this.npc = villager.body();
        this.needs = villager.model().brain().needs();
        Profession profession = villager.profession();
        this.works = profession != Profession.NONE && AwayVillager.workEnabled(npc);
        this.exertion = works ? HungerSystem.workExertionPerTick(profession, skill(npc, profession)) : 0f;
        // Children are at school or play in the work hours: effort, but no work toll (as live).
        this.workDrain = works && profession != Profession.CHILD
                ? WellbeingBalance.workDrain(npc.personality().workFatigue) : 0f;
        this.hasBed = mgr.getAssignedBed(villager.id) != null;
        this.health = npc.getHealth();
    }

    private static int skill(NpcEntity npc, Profession profession) {
        for (ProfessionSkill slot : npc.getProfessionSkills()) {
            if (slot.profession() == profession) return slot.level();
        }
        return 1;
    }
}

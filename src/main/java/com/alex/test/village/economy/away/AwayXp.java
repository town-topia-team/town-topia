package com.alex.test.village.economy.away;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.brain.VillagerBrain;
import com.alex.test.village.stats.VillagerSkills;
import com.alex.test.village.tokens.VillageFood;
import com.alex.test.village.villagers.cook.CookBalance;
import com.alex.test.village.villagers.cook.CookBonuses;
import com.alex.test.village.villagers.cook.CookPlanner;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.core.ProfessionSkill;
import com.alex.test.village.villagers.farmer.FarmerBalance;
import com.alex.test.village.villagers.farmer.FarmerExperience;
import com.alex.test.village.villagers.miner.MinerBalance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;

/**
 * The one skill payer of the away calculator: job by job, on the day's real
 * number, so a level-up changes speed and yield at once and the daily limit
 * is the live one ({@code DailyJobExperience}, on the copy's brain data).
 * Round 3: the farmer; round 4: the cook; round 5: the rancher; round 6: the butcher; round 7: the
 * lumberjack; round 8: the miner; round 9: the blacksmith; round 10: the
 * enchanter; round 11: the teacher. The other jobs learn nothing away.
 *
 * <p>Live copy: {@code VillagerBrain.onFarmerJob} (claim, add, show the new
 * level, save) and the work session's intelligence
 * ({@code FarmerBalance.INTELLIGENCE_PER_WORK_SESSION}, once per session of
 * up to {@code ACTIONS_PER_WORK_SESSION} jobs). No thoughts, memories or
 * diary lines: those would land on the real villager.
 */
public final class AwayXp {

    private AwayXp() {}

    /** One farmer job (a till, plant, harvest or pick) on {@code ctx}'s day. */
    public static void farmerJob(AwayContext ctx) {
        NpcEntity npc = ctx.npc();
        // As live: a villager that does not hold the farmer skill learns nothing from it.
        if (slot(npc, Profession.FARMER) == null) return;
        float raw = FarmerExperience.claim(npc.getBrainData(), ctx.day.calendarDay(), 1);
        if (raw <= 0f) return;
        VillagerSkills skills = ctx.model().brain().skills();
        skills.addWorkExperience(Profession.FARMER, raw);
        ProfessionSkill slot = slot(npc, Profession.FARMER);
        if (slot != null) skills.setFarmerLevelAtLeast(slot.level());
        npc.setProfessionSkillLevel(Profession.FARMER, skills.level(Profession.FARMER));
        skills.save(npc.getBrainData());
    }

    /** The day's work sessions: a session is up to 24 jobs, as live ends one to deposit. */
    public static void farmerSessions(AwayContext ctx, int jobsToday) {
        if (jobsToday <= 0) return;
        int sessions = (jobsToday + FarmerBalance.ACTIONS_PER_WORK_SESSION - 1) / FarmerBalance.ACTIONS_PER_WORK_SESSION;
        VillagerSkills skills = ctx.model().brain().skills();
        skills.addWorkIntelligence(sessions * FarmerBalance.INTELLIGENCE_PER_WORK_SESSION);
        skills.save(ctx.npc().getBrainData());
    }

    /**
     * One finished cook batch of {@code result} on {@code ctx}'s day, as
     * {@code VillagerBrain.onCookBatchCompleted}: the dish's lessons
     * ({@code CookPlanner.lessons}), half for a dish's batches past the day's
     * first {@code REPEAT_FULL_BATCHES}, the first batch of a dish never made a
     * lesson of its own, the day's full pay ({@code COOK_BATCHES}), and the dish
     * counted toward the Signature Dish ({@code CookBonuses.recordDish}).
     */
    public static void cookBatch(AwayContext ctx, Item result) {
        NpcEntity npc = ctx.npc();
        if (slot(npc, Profession.COOK) == null) return;
        CompoundTag data = npc.getBrainData();
        long day = ctx.day.calendarDay();
        if (!data.contains(VillagerBrain.TAG_COOK_DISH_DAY) || data.getLong(VillagerBrain.TAG_COOK_DISH_DAY) != day) {
            data.putLong(VillagerBrain.TAG_COOK_DISH_DAY, day);
            data.put(VillagerBrain.TAG_COOK_DISH_COUNTS, new CompoundTag());
        }
        String key = BuiltInRegistries.ITEM.getKey(result).toString();
        CompoundTag counts = data.getCompound(VillagerBrain.TAG_COOK_DISH_COUNTS);
        int madeToday = counts.getInt(key);
        counts.putInt(key, madeToday + 1);
        data.put(VillagerBrain.TAG_COOK_DISH_COUNTS, counts);
        double lessons = CookPlanner.lessons(result)
                * (madeToday < CookBalance.REPEAT_FULL_BATCHES ? 1.0 : CookBalance.REPEAT_SHARE);
        boolean dish = VillageFood.isDish(result);
        CompoundTag learned = data.getCompound(VillagerBrain.TAG_COOK_DISHES_LEARNED);
        if (dish && !learned.contains(key)) {
            learned.putBoolean(key, true);
            data.put(VillagerBrain.TAG_COOK_DISHES_LEARNED, learned);
            lessons += CookBalance.NEW_DISH_LESSONS;
        }
        float raw = VillagerBrain.COOK_BATCHES.claim(data, day, lessons);
        if (dish) CookBonuses.recordDish(data, result);
        VillagerSkills skills = ctx.model().brain().skills();
        skills.addWorkExperience(Profession.COOK, raw);
        ProfessionSkill slot = slot(npc, Profession.COOK);
        if (slot != null) skills.setCookLevelAtLeast(slot.level());
        npc.setProfessionSkillLevel(Profession.COOK, skills.level(Profession.COOK));
        skills.save(data);
    }

    /**
     * A finished work session's intelligence ({@code VillagerBrain.awardWorkExperience}),
     * for a job that is paid per batch, tree or kill: once for a day it worked (my call;
     * live ends a session whenever there is nothing left to do).
     */
    public static void workSession(AwayContext ctx) {
        workSessions(ctx, 1);
    }

    /** {@link #workSession} for {@code sessions} finished sessions (the rancher: one per 16 jobs, as live). */
    public static void workSessions(AwayContext ctx, int sessions) {
        if (sessions <= 0) return;
        VillagerSkills skills = ctx.model().brain().skills();
        skills.addWorkIntelligence(sessions * VillagerBrain.INTELLIGENCE_PER_WORK_CYCLE);
        skills.save(ctx.npc().getBrainData());
    }

    /**
     * One rancher job worth {@code jobs} (one for a shear, egg, hide or milking, half per
     * animal fed) on {@code ctx}'s day, as {@code VillagerBrain.onRancherJob}: in full for
     * the day's first {@code FULL_DAY_JOBS}, a fifth after ({@code RANCHER_JOBS}).
     */
    public static void rancherJob(AwayContext ctx, float jobs) {
        NpcEntity npc = ctx.npc();
        ProfessionSkill slot = slot(npc, Profession.RANCHER);
        if (slot == null) return;
        float raw = VillagerBrain.RANCHER_JOBS.claim(npc.getBrainData(), ctx.day.calendarDay(), jobs);
        VillagerSkills skills = ctx.model().brain().skills();
        skills.addWorkExperience(Profession.RANCHER, raw);
        skills.setRancherLevelAtLeast(slot.level());
        npc.setProfessionSkillLevel(Profession.RANCHER, skills.level(Profession.RANCHER));
        skills.save(npc.getBrainData());
    }

    /**
     * One kill at the chopping block on {@code ctx}'s day, as {@code VillagerBrain.onButcherKill}:
     * in full for the day's first {@code FULL_DAY_KILLS}, a fifth after ({@code BUTCHER_KILLS}).
     */
    public static void butcherKill(AwayContext ctx) {
        NpcEntity npc = ctx.npc();
        ProfessionSkill slot = slot(npc, Profession.BUTCHER);
        if (slot == null) return;
        float raw = VillagerBrain.BUTCHER_KILLS.claim(npc.getBrainData(), ctx.day.calendarDay(), 1f);
        VillagerSkills skills = ctx.model().brain().skills();
        skills.addWorkExperience(Profession.BUTCHER, raw);
        skills.setButcherLevelAtLeast(slot.level());
        npc.setProfessionSkillLevel(Profession.BUTCHER, skills.level(Profession.BUTCHER));
        skills.save(npc.getBrainData());
    }

    /**
     * One felled tree on {@code ctx}'s day, as {@code VillagerBrain.onLumberjackTreeFelled}: in full
     * for the day's first {@code FULL_DAY_TREES}, a fifth after ({@code LUMBERJACK_TREES}), and a work
     * cycle's intelligence for every tree (live pays the lumberjack per tree, not per session).
     */
    public static void lumberjackTree(AwayContext ctx) {
        NpcEntity npc = ctx.npc();
        VillagerSkills skills = ctx.model().brain().skills();
        ProfessionSkill slot = slot(npc, Profession.LUMBERJACK);
        if (slot != null) {
            float raw = VillagerBrain.LUMBERJACK_TREES.claim(npc.getBrainData(), ctx.day.calendarDay(), 1f);
            skills.addWorkExperience(Profession.LUMBERJACK, raw);
            skills.setLumberjackLevelAtLeast(slot.level());
            npc.setProfessionSkillLevel(Profession.LUMBERJACK, skills.level(Profession.LUMBERJACK));
        }
        skills.addWorkIntelligence(VillagerBrain.INTELLIGENCE_PER_WORK_CYCLE);
        skills.save(npc.getBrainData());
    }

    /**
     * One broken block on {@code ctx}'s day, as {@code VillagerBrain.onMinerBlockMined}: its
     * swinging ticks ({@code payTicksForBlock}, half again for ore) with the pickaxe that broke it,
     * in full for the day's first {@code FULL_DAY_MINING_TICKS}, a fifth after ({@code MINER_TICKS}).
     * Intelligence comes per stint ({@link #workSessions}), as live.
     */
    public static void minerBlock(AwayContext ctx, boolean ore, int blockTicks) {
        NpcEntity npc = ctx.npc();
        // As live syncMinerSkill: without the miner skill the experience is cleared again.
        ProfessionSkill slot = slot(npc, Profession.MINER);
        if (slot == null) return;
        float raw = VillagerBrain.MINER_TICKS.claim(npc.getBrainData(), ctx.day.calendarDay(),
                MinerBalance.payTicksForBlock(blockTicks, ore));
        VillagerSkills skills = ctx.model().brain().skills();
        skills.addWorkExperience(Profession.MINER, raw);
        skills.setMinerLevelAtLeast(slot.level());
        npc.setProfessionSkillLevel(Profession.MINER, skills.level(Profession.MINER));
        skills.save(npc.getBrainData());
    }

    /**
     * Forge points for one finished job or furnace load on {@code ctx}'s day, as
     * {@code VillagerBrain.onBlacksmithExperience}: in full for the day's first
     * {@code FULL_DAY_POINTS}, a fifth after ({@code BLACKSMITH_POINTS}). Paid at once,
     * so a level-up counts for the next job (old bug S7).
     */
    public static void smithPoints(AwayContext ctx, float points) {
        NpcEntity npc = ctx.npc();
        // As live syncBlacksmithSkill: without the blacksmith skill nothing is learned.
        ProfessionSkill slot = slot(npc, Profession.BLACKSMITH);
        if (slot == null || points <= 0f) return;
        float raw = VillagerBrain.BLACKSMITH_POINTS.claim(npc.getBrainData(), ctx.day.calendarDay(), points);
        VillagerSkills skills = ctx.model().brain().skills();
        skills.addWorkExperience(Profession.BLACKSMITH, raw);
        skills.setBlacksmithLevelAtLeast(slot.level());
        npc.setProfessionSkillLevel(Profession.BLACKSMITH, skills.level(Profession.BLACKSMITH));
        skills.save(npc.getBrainData());
    }

    /**
     * Enchanter practice on {@code ctx}'s day, as {@code VillagerBrain.onEnchanterExperience}: a
     * finished book, or a little for paper pressed or a book bound. No daily limit: the day's
     * books pace it, as live. No level-up memory or diary line (they would land on the real villager).
     */
    public static void enchanterPractice(AwayContext ctx, float amount) {
        NpcEntity npc = ctx.npc();
        // As live syncEnchanterSkill: without the enchanter skill nothing is learned.
        ProfessionSkill slot = slot(npc, Profession.ENCHANTER);
        if (slot == null || amount <= 0f) return;
        VillagerSkills skills = ctx.model().brain().skills();
        skills.addWorkExperience(Profession.ENCHANTER, amount);
        skills.setEnchanterLevelAtLeast(slot.level());
        npc.setProfessionSkillLevel(Profession.ENCHANTER, skills.level(Profession.ENCHANTER));
        skills.save(npc.getBrainData());
    }

    /**
     * A school day's lesson units on {@code ctx}'s day, as {@code VillagerBrain.onTeacherUnit}:
     * {@code units} already weighted by the class's size ({@code TeacherBalance.unitWeight}), in
     * full for the day's first {@code FULL_DAY_UNITS}, a fifth after ({@code TEACHER_UNITS}).
     */
    public static void teacherUnits(AwayContext ctx, float units) {
        NpcEntity npc = ctx.npc();
        // As live syncTeacherSkill: without the teacher skill nothing is learned.
        ProfessionSkill slot = slot(npc, Profession.TEACHER);
        if (slot == null || units <= 0f) return;
        float raw = VillagerBrain.TEACHER_UNITS.claim(npc.getBrainData(), ctx.day.calendarDay(), units);
        VillagerSkills skills = ctx.model().brain().skills();
        skills.addWorkExperience(Profession.TEACHER, raw);
        skills.setTeacherLevelAtLeast(slot.level());
        npc.setProfessionSkillLevel(Profession.TEACHER, skills.level(Profession.TEACHER));
        skills.save(npc.getBrainData());
    }

    private static ProfessionSkill slot(NpcEntity npc, Profession profession) {
        for (ProfessionSkill slot : npc.getProfessionSkills()) {
            if (slot.profession() == profession) return slot;
        }
        return null;
    }
}

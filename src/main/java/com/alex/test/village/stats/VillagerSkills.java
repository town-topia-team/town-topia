package com.alex.test.village.stats;

import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.bard.BardBalance;
import com.alex.test.village.villagers.blacksmith.BlacksmithBalance;
import com.alex.test.village.villagers.butcher.ButcherBalance;
import com.alex.test.village.villagers.cleric.ClericBalance;
import com.alex.test.village.villagers.teacher.TeacherBalance;
import com.alex.test.village.villagers.cook.CookBalance;
import com.alex.test.village.villagers.druid.DruidBalance;
import com.alex.test.village.villagers.enchanter.EnchanterBalance;
import com.alex.test.village.villagers.farmer.FarmerBalance;
import com.alex.test.village.villagers.archer.ArcherBalance;
import com.alex.test.village.villagers.mage.MageBalance;
import com.alex.test.village.villagers.guard.CaptainBalance;
import com.alex.test.village.villagers.guard.GuardBalance;
import com.alex.test.village.villagers.lumberjack.LumberjackBalance;
import com.alex.test.village.villagers.miner.MinerBalance;
import com.alex.test.village.villagers.rancher.RancherBalance;
import net.minecraft.nbt.CompoundTag;

import java.util.EnumMap;
import java.util.Map;

/**
 * Per-villager competence: one skill track per profession, plus a shared
 * intelligence stat.
 *
 * <p>The distinction matters and is worth stating, because the two are easy to
 * conflate:
 *
 * <ul>
 *   <li><b>Skill</b> is what this villager is good at. It is earned by doing a
 *       specific job and is tracked per {@link Profession}, so retraining a
 *       veteran farmer into a miner does not carry the farming across — but it
 *       is remembered, and comes straight back if they return to the fields.</li>
 *   <li><b>Intelligence</b> is how far they can learn easily. It is shared across
 *       professions and rises far more slowly. On the 1..100 tracks a level below
 *       it learns a little faster and a level past it ever slower (see
 *       {@link IntelligenceLearning}). It is the stat a school exists to raise.</li>
 * </ul>
 *
 * <p>Skill is deliberately stored as raw experience and exposed as a level: the
 * curve is then a display concern rather than something callers have to agree
 * about, and awarding experience never has to worry about level boundaries.
 */
public final class VillagerSkills {

    /** Legacy cap for professions without a dedicated 100-level track. */
    public static final int MAX_LEVEL = 10;

    /** Lumberjack's cumulative curve; intelligence learning and its soft wall apply separately. */
    public static float lumberjackExperienceForLevel(int level) {
        return LumberjackBalance.experienceForLevel(level);
    }

    /**
     * Experience needed for level 1. Levels follow a square curve from here, so
     * reaching {@link #MAX_LEVEL} costs {@code MAX_LEVEL^2} times this — early
     * levels arrive quickly enough to feel responsive, and the last ones are a
     * long-term goal rather than an afternoon's work.
     *
     * <p>Calibrated against measurement, not taste: a lumberjack completes about
     * four work cycles in a day, which at {@code WORK_EXPERIENCE_PER_CYCLE} and a
     * starting learning rate is ~10 experience per day. Level 1 therefore lands
     * inside the first day, and level {@link #MAX_LEVEL} — a hundred times as
     * expensive — sits roughly a month of village life away, faster as
     * intelligence climbs. At 12 per level, first level took over a day and the
     * cap took four months, which is indistinguishable from the stat not existing.
     */
    private static final float XP_PER_FIRST_LEVEL = 5f;

    /** Skill's effect on work speed: a maxed villager works this much faster. */
    private static final float MAX_SPEED_BONUS = 0.5f;

    public static final String TAG_INTELLIGENCE = "Intelligence";

    private final Map<Profession, Float> experience = new EnumMap<>(Profession.class);

    /** 0..100. Rises slowly from work and conversation; a school is the fast path. */
    private float intelligence;

    // =========================================================================
    // Reading
    // =========================================================================

    public float intelligence() {
        return intelligence;
    }

    public float experience(Profession profession) {
        return experience.getOrDefault(profession, 0f);
    }

    /** Dedicated profession tracks are 1..100; other professions retain 0..10. */
    public int level(Profession profession) {
        float xp = experience(profession);
        if (profession == Profession.FARMER) {
            int level = 1;
            while (level < FarmerBalance.MAX_LEVEL
                    && xp >= FarmerBalance.experienceForLevel(level + 1)) level++;
            return level;
        }
        if (profession == Profession.LUMBERJACK) {
            int level = 1;
            while (level < LumberjackBalance.MAX_LEVEL
                    && xp >= lumberjackExperienceForLevel(level + 1)) level++;
            return level;
        }
        if (profession == Profession.MINER) {
            int level = 1;
            while (level < MinerBalance.MAX_LEVEL
                    && xp >= MinerBalance.experienceForLevel(level + 1)) level++;
            return level;
        }
        if (profession == Profession.DRUID) {
            int level = 1;
            while (level < DruidBalance.MAX_LEVEL
                    && xp >= DruidBalance.experienceForLevel(level + 1)) level++;
            return level;
        }
        if (profession == Profession.COOK) {
            int level = 1;
            while (level < CookBalance.MAX_LEVEL
                    && xp >= CookBalance.experienceForLevel(level + 1)) level++;
            return level;
        }
        if (profession == Profession.BARD) {
            int level = 1;
            while (level < BardBalance.MAX_LEVEL
                    && xp >= BardBalance.experienceForLevel(level + 1)) level++;
            return level;
        }
        if (profession == Profession.GUARD) {
            int level = 1;
            while (level < GuardBalance.MAX_LEVEL
                    && xp >= GuardBalance.experienceForLevel(level + 1)) level++;
            return level;
        }
        if (profession == Profession.CAPTAIN) {
            int level = 1;
            while (level < CaptainBalance.MAX_LEVEL
                    && xp >= CaptainBalance.experienceForLevel(level + 1)) level++;
            return level;
        }
        if (profession == Profession.ARCHER) {
            int level = 1;
            while (level < ArcherBalance.MAX_LEVEL
                    && xp >= ArcherBalance.experienceForLevel(level + 1)) level++;
            return level;
        }
        if (profession == Profession.MAGE) {
            int level = 1;
            while (level < MageBalance.MAX_LEVEL
                    && xp >= MageBalance.experienceForLevel(level + 1)) level++;
            return level;
        }
        if (profession == Profession.BUTCHER) {
            int level = 1;
            while (level < ButcherBalance.MAX_LEVEL
                    && xp >= ButcherBalance.experienceForLevel(level + 1)) level++;
            return level;
        }
        if (profession == Profession.BLACKSMITH) {
            int level = 1;
            while (level < BlacksmithBalance.MAX_LEVEL
                    && xp >= BlacksmithBalance.experienceForLevel(level + 1)) level++;
            return level;
        }
        if (profession == Profession.ENCHANTER) {
            int level = 1;
            while (level < EnchanterBalance.MAX_LEVEL
                    && xp >= EnchanterBalance.experienceForLevel(level + 1)) level++;
            return level;
        }
        if (profession == Profession.CLERIC) {
            int level = 1;
            while (level < ClericBalance.MAX_LEVEL
                    && xp >= ClericBalance.experienceForLevel(level + 1)) level++;
            return level;
        }
        if (profession == Profession.TEACHER) {
            int level = 1;
            while (level < TeacherBalance.MAX_LEVEL
                    && xp >= TeacherBalance.experienceForLevel(level + 1)) level++;
            return level;
        }
        if (profession == Profession.RANCHER) {
            int level = 1;
            while (level < RancherBalance.MAX_LEVEL
                    && xp >= RancherBalance.experienceForLevel(level + 1)) level++;
            return level;
        }
        if (xp <= 0f) return 0;
        int level = (int) Math.sqrt(xp / XP_PER_FIRST_LEVEL);
        return Math.min(MAX_LEVEL, level);
    }

    /**
     * Work-speed multiplier for {@code profession}: 1.0 for a novice rising to
     * {@code 1 + MAX_SPEED_BONUS} at {@link #MAX_LEVEL}.
     *
     * <p>Always at least 1.0 — skill makes a villager better at their job, it
     * never makes them worse than someone who has never done it.
     */
    public float speedMultiplier(Profession profession) {
        // Farmer skill changes the number of strokes per action; the tool sets
        // stroke speed (see ToolSpeed).
        if (profession == Profession.FARMER) return 1f;
        // The axe alone sets chop speed (see ToolSpeed); skill raises log yield and carry size.
        if (profession == Profession.LUMBERJACK) return 1f;
        if (profession == Profession.COOK) {
            return (float) CookBalance.speed(level(profession));
        }
        // Miner skill changes the number of swings per block, never swing speed.
        if (profession == Profession.MINER) return 1f;
        // Druid skill sets how many dances a step takes, in bands (see
        // DruidBalance); the dance itself never gets faster.
        if (profession == Profession.DRUID) return 1f;
        // Bard skill improves the audience and arrangement, not walking pace.
        if (profession == Profession.BARD) return 1f;
        // Guard skill shows in combat (see GuardBalance), not in walking pace.
        if (profession == Profession.GUARD || profession == Profession.CAPTAIN
                || profession == Profession.ARCHER || profession == Profession.MAGE) return 1f;
        // Blacksmith skill shortens the anvil and furnace timers itself (see BlacksmithBalance).
        if (profession == Profession.BLACKSMITH) return 1f;
        // Butcher skill shows in the haul and the blow (see ButcherBalance), not in pace.
        if (profession == Profession.BUTCHER) return 1f;
        // Enchanter skill sets book strength and the daily count itself (see EnchanterBalance).
        if (profession == Profession.ENCHANTER) return 1f;
        // Cleric skill paces the healing throw itself (see ClericBalance).
        if (profession == Profession.CLERIC) return 1f;
        // Teacher skill shows in what the children learn (see TeacherBalance).
        if (profession == Profession.TEACHER) return 1f;
        // Rancher skill sets its own time at each animal (see RancherBalance).
        if (profession == Profession.RANCHER) return 1f;
        return 1f + MAX_SPEED_BONUS * level(profession) / (float) MAX_LEVEL;
    }

    /**
     * How fast this villager learns {@code profession} now: 1x when intelligence
     * matches the level, up to 1.25x ahead of it, ever slower past it
     * ({@link IntelligenceLearning}). The legacy 0..10 tracks always learn at 1x.
     */
    public float learningSpeed(Profession profession) {
        return learningSpeed(profession, level(profession));
    }

    private float learningSpeed(Profession profession, int level) {
        return hasFullTrack(profession) ? IntelligenceLearning.speed(level, intelligence) : 1f;
    }

    /** Professions with a dedicated 1..100 track; the rest still use the legacy 0..10 curve. */
    private static boolean hasFullTrack(Profession profession) {
        return profession == Profession.FARMER || profession == Profession.LUMBERJACK
                || profession == Profession.MINER || profession == Profession.DRUID
                || profession == Profession.COOK || profession == Profession.BARD
                || profession == Profession.GUARD || profession == Profession.CAPTAIN
                || profession == Profession.BLACKSMITH || profession == Profession.ARCHER
                || profession == Profession.CLERIC || profession == Profession.ENCHANTER
                || profession == Profession.TEACHER || profession == Profession.MAGE
                || profession == Profession.BUTCHER || profession == Profession.RANCHER;
    }

    // =========================================================================
    // Earning
    // =========================================================================

    /**
     * Credits a completed job. {@code amount} is in raw experience before the
     * villager's learning rate is applied.
     *
     * @return true when this pushed them up a level, which callers use to decide
     *         whether the moment is worth announcing.
     */
    public boolean addWorkExperience(Profession profession, float amount) {
        if (profession == Profession.NONE || amount <= 0f) return false;
        int before = level(profession);
        // A maxed track stops accumulating rather than growing an unbounded
        // number that nothing will ever read.
        if (before >= (profession == Profession.LUMBERJACK
                ? LumberjackBalance.MAX_LEVEL : profession == Profession.FARMER
                ? FarmerBalance.MAX_LEVEL : profession == Profession.MINER
                ? MinerBalance.MAX_LEVEL : profession == Profession.DRUID
                ? DruidBalance.MAX_LEVEL : profession == Profession.COOK
                ? CookBalance.MAX_LEVEL : profession == Profession.BARD
                ? BardBalance.MAX_LEVEL : profession == Profession.GUARD
                ? GuardBalance.MAX_LEVEL : profession == Profession.CAPTAIN
                ? CaptainBalance.MAX_LEVEL : profession == Profession.ARCHER
                ? ArcherBalance.MAX_LEVEL : profession == Profession.MAGE
                ? MageBalance.MAX_LEVEL : profession == Profession.BUTCHER
                ? ButcherBalance.MAX_LEVEL : profession == Profession.BLACKSMITH
                ? BlacksmithBalance.MAX_LEVEL : profession == Profession.CLERIC
                ? ClericBalance.MAX_LEVEL : profession == Profession.ENCHANTER
                ? EnchanterBalance.MAX_LEVEL : profession == Profession.TEACHER
                ? TeacherBalance.MAX_LEVEL : profession == Profession.RANCHER
                ? RancherBalance.MAX_LEVEL : MAX_LEVEL)) return false;
        experience.merge(profession,
                amount * learningSpeed(profession, before), Float::sum);
        return level(profession) > before;
    }

    /** Sets the new lumberjack track during save migration and initial rolls. */
    public void setLumberjackLevelAtLeast(int level) {
        experience.merge(Profession.LUMBERJACK,
                lumberjackExperienceForLevel(level), Math::max);
    }

    /** Replaces lumberjack experience outright: only for moving a save onto a new curve. */
    public void setLumberjackExperience(float xp) {
        experience.put(Profession.LUMBERJACK, Math.max(0f, xp));
    }

    /** Preserves the visible farmer level when upgrading an existing save. */
    public void setFarmerLevelAtLeast(int level) {
        experience.merge(Profession.FARMER,
                FarmerBalance.experienceForLevel(level), Math::max);
    }

    /** Replaces farmer experience outright: only for moving a save onto a new curve. */
    public void setFarmerExperience(float xp) {
        experience.put(Profession.FARMER, Math.max(0f, xp));
    }

    /** Preserves the visible miner level when upgrading an existing save. */
    public void setMinerLevelAtLeast(int level) {
        experience.merge(Profession.MINER,
                MinerBalance.experienceForLevel(level), Math::max);
    }

    /** Preserves the visible druid level when upgrading an existing save. */
    public void setDruidLevelAtLeast(int level) {
        experience.merge(Profession.DRUID,
                DruidBalance.experienceForLevel(level), Math::max);
    }

    /** Preserves the visible cook level when upgrading an existing save. */
    public void setCookLevelAtLeast(int level) {
        experience.merge(Profession.COOK,
                CookBalance.experienceForLevel(level), Math::max);
    }

    /** Preserves a bard's visible skill when upgrading an existing save. */
    public void setBardLevelAtLeast(int level) {
        experience.merge(Profession.BARD,
                BardBalance.experienceForLevel(level), Math::max);
    }

    /** Sets a captain's track at least this high: syncs and promotions from guard. */
    public void setCaptainLevelAtLeast(int level) {
        experience.merge(Profession.CAPTAIN,
                CaptainBalance.experienceForLevel(level), Math::max);
    }

    /** Preserves a guard's visible skill when upgrading an existing save. */
    public void setGuardLevelAtLeast(int level) {
        experience.merge(Profession.GUARD,
                GuardBalance.experienceForLevel(level), Math::max);
    }

    /** Preserves an archer's visible skill when upgrading an existing save. */
    public void setArcherLevelAtLeast(int level) {
        experience.merge(Profession.ARCHER,
                ArcherBalance.experienceForLevel(level), Math::max);
    }

    /** Preserves a blacksmith's visible skill when upgrading an existing save. */
    public void setBlacksmithLevelAtLeast(int level) {
        experience.merge(Profession.BLACKSMITH,
                BlacksmithBalance.experienceForLevel(level), Math::max);
    }

    /** Preserves a teacher's visible skill when upgrading an existing save. */
    public void setTeacherLevelAtLeast(int level) {
        experience.merge(Profession.TEACHER,
                TeacherBalance.experienceForLevel(level), Math::max);
    }

    /** Preserves a cleric's visible skill when upgrading an existing save. */
    public void setClericLevelAtLeast(int level) {
        experience.merge(Profession.CLERIC,
                ClericBalance.experienceForLevel(level), Math::max);
    }

    /** Preserves a butcher's visible skill when upgrading an existing save. */
    public void setButcherLevelAtLeast(int level) {
        experience.merge(Profession.BUTCHER,
                ButcherBalance.experienceForLevel(level), Math::max);
    }

    /** Preserves a rancher's visible skill when moving onto the 1..100 track. */
    public void setRancherLevelAtLeast(int level) {
        experience.merge(Profession.RANCHER,
                RancherBalance.experienceForLevel(level), Math::max);
    }

    /** Keeps a mage's rolled or recruited level as the floor of its track. */
    public void setMageLevelAtLeast(int level) {
        experience.merge(Profession.MAGE,
                MageBalance.experienceForLevel(level), Math::max);
    }

    /** Preserves an enchanter's visible skill when upgrading an existing save. */
    public void setEnchanterLevelAtLeast(int level) {
        experience.merge(Profession.ENCHANTER,
                EnchanterBalance.experienceForLevel(level), Math::max);
    }

    public void clearExperience(Profession profession) {
        experience.remove(profession);
    }

    /**
     * Raises intelligence. Kept separate from skill because the sources differ:
     * working teaches you your job, whereas talking to people and being taught
     * makes you quicker at learning any of them.
     */
    public void addIntelligence(float amount) {
        intelligence = StatMath.clampPercent(intelligence + amount);
    }

    /**
     * How far work and talk alone take a villager. Past it only books and
     * school teach: villagers reached 100 by farming (fastest while nobody
     * was near, a session's worth per stint), with no Library in the village.
     */
    public static final float WORK_INTELLIGENCE_CAP = 50f;

    /** Intelligence from a work session or a conversation: never past {@link #WORK_INTELLIGENCE_CAP}. */
    public void addWorkIntelligence(float amount) {
        if (amount <= 0f || intelligence >= WORK_INTELLIGENCE_CAP) return;
        intelligence = Math.min(WORK_INTELLIGENCE_CAP, intelligence + amount);
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    // The NBT code lives in VillagerSkillsNbt: handing a CompoundTag to
    // put(String, Tag) here would make the class verifier load Minecraft,
    // and VillagerSkills must still load in plain JUnit tests.

    public void save(CompoundTag tag) {
        VillagerSkillsNbt.save(tag, intelligence, experience);
    }

    public void load(CompoundTag tag) {
        if (tag.contains(TAG_INTELLIGENCE)) {
            intelligence = StatMath.clampPercent(tag.getFloat(TAG_INTELLIGENCE));
        }
        VillagerSkillsNbt.loadExperience(tag, experience);
    }

    /** One-line summary for diaries and debug dumps. */
    public String describe(Profession current) {
        return String.format("int=%.1f %s=lvl%d(%.0fxp) learn=%.2fx",
                intelligence, current.name().toLowerCase(java.util.Locale.ROOT),
                level(current), experience(current), learningSpeed(current));
    }
}

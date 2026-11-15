package com.alex.test.village.villagers.druid;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.village.villagers.miner.OreBlocks;
import com.alex.test.village.villagers.miner.OreHeights;
import com.alex.test.village.villagers.miner.OreHeights.Ore;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared druid balance. One skill level, 1 to 100, drives every effect in five
 * bands that open at levels 1, 25, 50, 75 and 100.
 *
 * <p>All druid work is measured in the same unit, a <em>step</em>: one crop
 * growth stage, one sapling growth stage, or one mine cell grown back. A crop
 * or mine step costs a whole number of dances, and the band decides how many;
 * a sapling instead takes a fixed time per band to become a tree:
 *
 * <pre>
 *   level   dances per step   sapling to tree   unlocks
 *   1       5                 60 s              common ores (coal, copper, iron, lapis, redstone)
 *   25      4                 55 s              torches are kept for the storage room
 *   50      3                 50 s              gold ore
 *   75      2                 45 s              diamond ore, and emerald at any height
 *   100     2                 40 s              ancient debris at Y -48 and below;
 *                                               5% of crop and mine steps do two at once
 * </pre>
 *
 * <p>A 2x2 tree takes twice as long as a single sapling: 120, 110, 100, 90 and 80 seconds.
 *
 * <p>The chance that a regrown block is ore at all climbs smoothly from 0.5%
 * at level 1 to 7% at level 100. Which ore follows the block's height, as
 * vanilla's own mix there ({@link OreHeights}): coal high up, iron around
 * Y 16, redstone and diamond at the bottom. The band only decides which ores
 * may appear; diamond is capped at {@link #DIAMOND_SHARE_CAP} of the ore.
 * Emerald is the druid's own and grows at any height; ancient debris only in
 * the deepest layers.
 *
 * <p>Experience is paid per dance rather than per step. Paying per step would
 * make a master, who finishes steps in less than half the dances a novice
 * needs, level more than twice as fast -- the opposite of each level being
 * harder than the last.
 */
public final class DruidBalance {

    public static final int MAX_LEVEL = 100;

    /** First level of the second, third, fourth and fifth bands. */
    public static final int ADEPT_LEVEL = 25;
    public static final int EXPERT_LEVEL = 50;
    public static final int MASTER_LEVEL = 75;
    public static final int GRANDMASTER_LEVEL = 100;

    /** One loop of the dance, the unit a step is timed and paid in. */
    public static final int DANCE_TICKS = VillagerAnimationState.cycleTicks(VillagerAnimationState.DANCE);

    /** Top band: chance a crop or mine step does two at once. */
    public static final double DOUBLE_STEP_CHANCE = 0.05;

    /** Dances per crop or mine step, by band. */
    private static final int[] DANCES_PER_STEP = {5, 4, 3, 2, 2};

    /** Ticks for a single sapling to become a tree, by band: 60, 55, 50, 45 and 40 seconds. */
    private static final int[] TREE_TICKS = {1200, 1100, 1000, 900, 800};

    /** A 2x2 tree takes twice as long as a single sapling. */
    public static final double TWO_BY_TWO_TIME = 2.0;

    /** Vanilla saplings grow in two stages: ready, then tree. */
    private static final int SAPLING_STAGES = 2;

    /** In-game days to level 100 learning at 1x, dancing a full day every day. */
    public static final int TARGET_DAYS_TO_MAX = 80;

    /**
     * Dances a day that pay in full; every dance after that pays
     * OVER_FULL_DAY_SHARE of one (stats.DailyJobExperience). Busy druids
     * danced 165-204 a day in 10-04 diaries; level 100 ones in smaller
     * villages 140-172, because their work ran out sooner.
     */
    public static final int FULL_DAY_DANCES = 190;

    /** What each dance past the day's full ones still pays: a fifth. */
    public static final float OVER_FULL_DAY_SHARE = 0.2f;

    /**
     * Cumulative experience for level 100: the scale saved experience is
     * measured on (sized in 10-02 as 110 days × the old slowest learning rate
     * 0.6 × a 12,800-tick day × 0.42 spent dancing ÷ 37-tick dances). Changing
     * the days changes what a dance pays, never this.
     */
    public static final float EXPERIENCE_FOR_MAX_LEVEL = 9589.622f;

    /** Raw experience per finished dance at 1x: about 0.63 for 80 days at 190 a day. */
    public static final float EXPERIENCE_PER_DANCE = EXPERIENCE_FOR_MAX_LEVEL
            / (TARGET_DAYS_TO_MAX * (float) FULL_DAY_DANCES);

    /**
     * Share of the total that is linear in the level; the rest is quadratic,
     * so each level costs more than the one before: about 33 experience for
     * level 2 and about 190 for level 100.
     *
     * <p>DRUID_DAY in the diary shows each day's dances against the full day.
     * The target is for a druid learning at 1x; intelligence ahead of the
     * level speeds it up to 1.25x, a level past it slows ever more
     * ({@code IntelligenceLearning}).
     */
    private static final double XP_LINEAR_SHARE = 1.0 / 3.0;

    /** Ore chance for a regrown block: 0.5% at level 1, rising linearly to 7% at level 100. */
    public static final double MIN_ORE_CHANCE = 0.005;
    public static final double MAX_ORE_CHANCE = 0.07;

    /**
     * Most of a regrown mine's ore that may be diamond. Vanilla's own mix at
     * the bottom of the world is a quarter diamond, which made a full regrow
     * there about 15 diamonds against 2 before heights counted; capped, it is
     * about 5, and none above Y 16.
     */
    public static final double DIAMOND_SHARE_CAP = 0.08;

    /** Share of a master's regrown ore that is emerald, at any height. */
    public static final double EMERALD_SHARE = 0.02;

    /** Share of a grandmaster's regrown ore that is ancient debris, deep down only. */
    public static final double DEBRIS_SHARE = 0.01;

    /** Ancient debris only grows back at or below this height. */
    public static final int DEBRIS_MAX_Y = -48;

    private DruidBalance() {}

    /** 1 to 5. */
    public static int band(int level) {
        int skill = Math.clamp(level, 1, MAX_LEVEL);
        if (skill >= GRANDMASTER_LEVEL) return 5;
        if (skill >= MASTER_LEVEL) return 4;
        if (skill >= EXPERT_LEVEL) return 3;
        if (skill >= ADEPT_LEVEL) return 2;
        return 1;
    }

    /** Dances one step costs: five for a novice, down to two. */
    public static int dancesPerStep(int level) {
        return DANCES_PER_STEP[band(level) - 1];
    }

    /**
     * Ticks for one of a sapling's two growth stages, so the whole tree takes
     * exactly its band's time. Measured in ticks rather than whole dances:
     * the tree time is the rule here, and the dance simply stops when the
     * tree stands.
     */
    public static int saplingStageTicks(int level, boolean twoByTwo) {
        double tree = TREE_TICKS[band(level) - 1] * (twoByTwo ? TWO_BY_TWO_TIME : 1.0);
        return (int) Math.round(tree / SAPLING_STAGES);
    }

    /** Whether a crop or mine step does two at once. Top band only. */
    public static boolean rollDoubleStep(int level, RandomSource random) {
        return band(level) == 5 && random.nextDouble() < DOUBLE_STEP_CHANCE;
    }

    /** From the second band a druid keeps the torches it takes out of a mine. */
    public static boolean keepsTorches(int level) {
        return band(level) >= 2;
    }

    /** Chance a regrown block is ore at all: 0.5% at level 1, 7% at level 100, linear between. */
    public static double oreChance(int level) {
        double progress = (Math.clamp(level, 1, MAX_LEVEL) - 1) / (double) (MAX_LEVEL - 1);
        return MIN_ORE_CHANCE + (MAX_ORE_CHANCE - MIN_ORE_CHANCE) * progress;
    }

    /**
     * Cumulative experience to be at {@code level}: level 1 starts at zero and
     * level 100 needs {@link #EXPERIENCE_FOR_MAX_LEVEL}.
     */
    public static float experienceForLevel(int level) {
        double progress = (Math.clamp(level, 1, MAX_LEVEL) - 1) / (double) (MAX_LEVEL - 1);
        return (float) (EXPERIENCE_FOR_MAX_LEVEL
                * (XP_LINEAR_SHARE * progress + (1.0 - XP_LINEAR_SHARE) * progress * progress));
    }

    /**
     * Which ore a regrown block at height {@code y} is, as shares by
     * {@link OreHeights.Ore} ordinal: vanilla's mix there, without the ores
     * this band has not unlocked, with diamond capped. Emerald and ancient
     * debris come on top of this (see {@link #regrownRock}).
     */
    public static double[] oreMix(int level, int y) {
        int band = band(level);
        double[] weights = OreHeights.densities(y);
        if (band < 3) weights[Ore.GOLD.ordinal()] = 0.0;
        if (band < 4) weights[Ore.DIAMOND.ordinal()] = 0.0;
        return OreHeights.atMost(OreHeights.normalise(weights), Ore.DIAMOND, DIAMOND_SHARE_CAP);
    }

    /**
     * The block one regrown cell block becomes: the rock around it, or now
     * and then ore of its height (see {@link #oreMix}).
     *
     * @param y    the block's height
     * @param rock the plain rock to grow, matched to the tunnel walls; ore in
     *             deepslate or tuff comes out as deepslate ore
     */
    public static BlockState regrownRock(int level, int y, BlockState rock, RandomSource random) {
        if (random.nextDouble() >= oreChance(level)) return rock;
        int band = band(level);
        boolean deep = rock.is(Blocks.DEEPSLATE) || rock.is(Blocks.TUFF);
        double special = random.nextDouble();
        if (band >= 4 && special < EMERALD_SHARE) {
            return (deep ? Blocks.DEEPSLATE_EMERALD_ORE : Blocks.EMERALD_ORE).defaultBlockState();
        }
        if (band >= 5 && y <= DEBRIS_MAX_Y && special < EMERALD_SHARE + DEBRIS_SHARE) {
            return Blocks.ANCIENT_DEBRIS.defaultBlockState();
        }
        Ore ore = OreHeights.pick(oreMix(level, y), random.nextDouble());
        if (ore == null) return rock;
        return (deep ? OreBlocks.deepslate(ore) : OreBlocks.stone(ore)).defaultBlockState();
    }
}

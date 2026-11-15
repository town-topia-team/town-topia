package com.alex.test.village.villagers.miner;

import com.alex.test.animation.VillagerAnimationState;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Shared live/offline miner balance.
 *
 * <p>The pickaxe decides how many swings a block takes: wood 5, stone 4, iron
 * 3, diamond 2, netherite and gold 1. Efficiency cuts that count by vanilla's
 * own ratio -- the tier's speed against its speed plus Efficiency's lvl² + 1
 * -- rounded up, never below one swing. The pickaxe and its Efficiency also
 * decide how fast each swing plays, from 1× for a wooden pickaxe up to
 * {@link #MAX_PLAYBACK}× for netherite with Efficiency V. Skill does not change
 * swing speed.
 *
 * <p>Skill drives the rest in five anchors, at levels 1, 25, 50, 75 and 100,
 * with a straight line between them:
 *
 * <pre>
 *   level   scrap ore   vein followed   also
 *   1       2%          6 blocks
 *   25      3%          12 blocks
 *   50      4%          18 blocks
 *   75      5%          24 blocks       ore sense: 25% chance, reach 1
 *   100     5%          24 blocks       ore sense: 50% chance, reach 2
 * </pre>
 *
 * <p>Experience is paid per tick spent swinging rather than per block, so a
 * better pickaxe does not level the miner faster. A day's first 7 minutes of
 * swinging pay in full and the rest a fifth (stats.DailyJobExperience), so a
 * miner swinging a full day every day reaches level 100 in 80 in-game days;
 * MINER_DAY in the diary shows the real time. The target is for a miner learning at 1x; intelligence
 * ahead of the level speeds it up to 1.25x, a level past it slows ever more
 * ({@code IntelligenceLearning}).
 */
public final class MinerBalance {
    public static final int MAX_LEVEL = 100;

    /** Levels the skill-driven numbers are anchored at. */
    private static final int[] ANCHOR_LEVELS = {1, 25, 50, 75, 100};

    /** In-game days to level 100 learning at 1x, swinging a full day every day. */
    public static final int TARGET_DAYS_TO_MAX = 80;
    /**
     * Ticks of swinging a day that pay in full: 7 minutes. A healthy mine
     * gives 7-8 minutes with most pickaxes (a deep mine or netherite about 5);
     * the one full day measured in a mine with water trouble, 4.7.
     */
    public static final int FULL_DAY_MINING_TICKS = 8_400;
    /** What swinging past the day's full time still pays: a fifth. */
    public static final float OVER_FULL_DAY_SHARE = 0.2f;
    /**
     * Cumulative experience for level 100: the scale saved experience is
     * measured on (110 days at the old slowest learning rate, 0.6, paying 1/20
     * a tick). Changing the days changes what a tick pays, never this.
     */
    public static final float EXPERIENCE_FOR_MAX_LEVEL = 21_120f;
    /** Experience per tick of swinging at 1x: about 0.031 (0.63 a second) for 80 days. */
    public static final float EXPERIENCE_PER_MINING_TICK = EXPERIENCE_FOR_MAX_LEVEL
            / (TARGET_DAYS_TO_MAX * (float) FULL_DAY_MINING_TICKS);
    /** Ore teaches more than rock: its blocks pay this much extra, and fill the full day as fast. */
    public static final float ORE_EXPERIENCE_BONUS = 0.5f;

    /** Share of the total that is linear in the level; the rest is quadratic, so each level costs more. */
    private static final double XP_LINEAR_SHARE = 0.38;

    public static final int MINE_CLIP_TICKS =
            VillagerAnimationState.cycleTicks(VillagerAnimationState.MINE);
    private static final float WOODEN_TOOL_SPEED = 2f;
    /** Swing playback of a netherite pickaxe with Efficiency V; nothing plays faster. */
    public static final double MAX_PLAYBACK = 1.4;
    private static final int ANIMATION_FINISH_TICKS = 2;

    private static final double[] SCRAP_ORE_CHANCE = {0.02, 0.03, 0.04, 0.05, 0.05};
    private static final double[] VEIN_BLOCKS = {6, 12, 18, 24, 24};

    /**
     * Most of the scrap mix that may be diamond. Vanilla's mix at the bottom
     * of the world is a quarter diamond; capped, a shaft there gives about one
     * diamond scrap per 400 to 1,000 stone, against one per 2,000 to 5,000 at
     * any height before heights counted, and none above Y 16.
     */
    public static final double SCRAP_DIAMOND_CAP = 0.05;

    /**
     * Least of the scrap mix that is coal, at any height. Vanilla has no coal
     * below Y 0, and a miner in a deep shaft still needs some for torches.
     */
    public static final double SCRAP_COAL_FLOOR = 0.10;

    public static final int MIN_STINT_BLOCKS = 64;
    public static final int MAX_STINT_BLOCKS = 128;
    public static final int ORE_SENSE_LEVEL = 75;
    public static final int ORE_SENSE_WIDE_LEVEL = 100;
    public static final double MIN_ORE_SENSE_CHANCE = 0.25;
    public static final double MAX_ORE_SENSE_CHANCE = 0.50;

    private MinerBalance() {}

    private static double progress(int level) {
        return (Math.clamp(level, 1, MAX_LEVEL) - 1) / (double) (MAX_LEVEL - 1);
    }

    /** Straight line through the anchor values, one per anchor level. */
    private static double anchored(int level, double[] values) {
        int skill = Math.clamp(level, 1, MAX_LEVEL);
        for (int i = 1; i < ANCHOR_LEVELS.length; i++) {
            if (skill <= ANCHOR_LEVELS[i]) {
                double span = (skill - ANCHOR_LEVELS[i - 1])
                        / (double) (ANCHOR_LEVELS[i] - ANCHOR_LEVELS[i - 1]);
                return values[i - 1] + (values[i] - values[i - 1]) * span;
            }
        }
        return values[values.length - 1];
    }

    /**
     * Cumulative experience to be at {@code level}: level 1 starts at zero and
     * level 100 needs {@link #EXPERIENCE_FOR_MAX_LEVEL}.
     */
    public static float experienceForLevel(int level) {
        double progress = progress(level);
        return (float) (EXPERIENCE_FOR_MAX_LEVEL
                * (XP_LINEAR_SHARE * progress + (1.0 - XP_LINEAR_SHARE) * progress * progress));
    }

    /**
     * Swinging ticks one block counts as toward pay and the full day: the
     * ticks it took, half again for ore.
     */
    public static double payTicksForBlock(int blockTicks, boolean ore) {
        return blockTicks * (ore ? 1.0 + ORE_EXPERIENCE_BONUS : 1.0);
    }

    /** Swings a block takes with this pickaxe before Efficiency: wood 5 down to netherite 1. */
    public static int baseSwings(Item pickaxe) {
        if (pickaxe == Items.NETHERITE_PICKAXE || pickaxe == Items.GOLDEN_PICKAXE) return 1;
        if (pickaxe == Items.DIAMOND_PICKAXE) return 2;
        if (pickaxe == Items.IRON_PICKAXE) return 3;
        if (pickaxe == Items.STONE_PICKAXE) return 4;
        return 5;
    }

    /** Vanilla pickaxe mining speed, used only to scale Efficiency. */
    public static float toolSpeed(Item pickaxe) {
        if (pickaxe == Items.GOLDEN_PICKAXE) return 12f;
        if (pickaxe == Items.NETHERITE_PICKAXE) return 9f;
        if (pickaxe == Items.DIAMOND_PICKAXE) return 8f;
        if (pickaxe == Items.IRON_PICKAXE) return 6f;
        if (pickaxe == Items.STONE_PICKAXE) return 4f;
        return WOODEN_TOOL_SPEED;
    }

    /**
     * Vanilla Efficiency: adds lvl² + 1 mining speed. A player's pickaxe can
     * have a part level (Efficiency III at half is 1.5); below level I the
     * bonus rises in a straight line to I's 2.
     */
    public static float efficiencyBonus(double efficiencyLevel) {
        double level = Math.clamp(efficiencyLevel, 0.0, 5.0);
        return (float) (level < 1.0 ? level * 2.0 : level * level + 1.0);
    }

    public static float miningSpeed(Item pickaxe, double efficiencyLevel) {
        return toolSpeed(pickaxe) + efficiencyBonus(efficiencyLevel);
    }

    /**
     * Swings per block: the pickaxe's count, cut by Efficiency in vanilla's
     * proportion (e.g. wood + Efficiency I needs 3, iron + Efficiency II
     * needs 2, diamond + Efficiency III needs 1). Never below one.
     */
    public static int swings(Item pickaxe, double efficiencyLevel) {
        double scaled = baseSwings(pickaxe) * toolSpeed(pickaxe)
                / (double) miningSpeed(pickaxe, efficiencyLevel);
        return Math.max(1, (int) Math.ceil(scaled - 1e-9));
    }

    /**
     * How fast each swing plays: 1× for a wooden pickaxe, rising in a straight
     * line with the pickaxe's mining speed (Efficiency included) to
     * {@link #MAX_PLAYBACK}× at netherite with Efficiency V.
     */
    public static float playbackSpeed(Item pickaxe, double efficiencyLevel) {
        float top = toolSpeed(Items.NETHERITE_PICKAXE) + efficiencyBonus(5);
        double fraction = (miningSpeed(pickaxe, efficiencyLevel) - WOODEN_TOOL_SPEED)
                / (top - WOODEN_TOOL_SPEED);
        return (float) (1.0 + (MAX_PLAYBACK - 1.0) * Math.clamp(fraction, 0.0, 1.0));
    }

    /** Ticks the swings themselves take: every swing at the pickaxe's playback speed. */
    private static int swingTicks(Item pickaxe, double efficiencyLevel) {
        return (int) Math.ceil(swings(pickaxe, efficiencyLevel) * MINE_CLIP_TICKS
                / (double) playbackSpeed(pickaxe, efficiencyLevel) - 1e-9);
    }

    /** Ticks to break one block: the blend, every swing, and a settle. */
    public static int mineTicks(Item pickaxe, double efficiencyLevel) {
        return VillagerAnimationState.WORK_BLEND_TICKS + ANIMATION_FINISH_TICKS
                + swingTicks(pickaxe, efficiencyLevel);
    }

    /**
     * Playback speed to hand to the animation so it ends exactly on its last
     * loop when the block breaks: whole swings over the whole swing time,
     * which is the pickaxe's playback speed to within a tick's rounding.
     */
    public static float animationSpeed(Item pickaxe, double efficiencyLevel) {
        return swings(pickaxe, efficiencyLevel) * MINE_CLIP_TICKS
                / (float) swingTicks(pickaxe, efficiencyLevel);
    }

    /** Chance plain stone gives up a scrap of ore instead of cobblestone: 2% at level 1, 5% from level 75. */
    public static double scrapOreChance(int level) {
        return anchored(level, SCRAP_ORE_CHANCE);
    }

    /**
     * Which ore a scrap from rock at height {@code y} turns out to be, as
     * shares by {@link OreHeights.Ore} ordinal: vanilla's mix there, with
     * diamond capped at {@link #SCRAP_DIAMOND_CAP} and coal kept to at least
     * {@link #SCRAP_COAL_FLOOR}. Never emerald, and never ancient debris: the
     * miner only gets those by digging what a druid grew back into the mine.
     */
    public static double[] scrapMix(int y) {
        double[] mix = OreHeights.atMost(OreHeights.mix(y), OreHeights.Ore.DIAMOND, SCRAP_DIAMOND_CAP);
        return OreHeights.atLeast(mix, OreHeights.Ore.COAL, SCRAP_COAL_FLOOR);
    }

    /** Ore blocks followed off one vein before the miner returns to the plan: 6 at level 1, 24 from level 75. */
    public static int veinBlocks(int level) {
        return (int) Math.round(anchored(level, VEIN_BLOCKS));
    }

    /**
     * Blocks broken in one trip down the mine before walking back to bank.
     * A full pack still ends the stint early.
     */
    public static int stintBlocks(int level) {
        return MIN_STINT_BLOCKS
                + (int) Math.round((MAX_STINT_BLOCKS - MIN_STINT_BLOCKS) * progress(level));
    }

    /** How far past the tunnel wall ore sense reaches: 0 below 75, 1 to 99, 2 at 100. */
    public static int oreSenseRadius(int level) {
        if (level >= ORE_SENSE_WIDE_LEVEL) return 2;
        if (level >= ORE_SENSE_LEVEL) return 1;
        return 0;
    }

    /** Vanilla furnace time for one log to become charcoal. */
    public static final int SMELT_TICKS_PER_CHARCOAL = 200;
    /** One crafting-table job: planks, sticks or torches. */
    public static final int CRAFT_TICKS = 60;
    /** A level 100 miner makes torches this many times faster than a novice. */
    public static final double MAX_TORCH_SPEED = 2.0;

    /** Torch-making speed: 1× at level 1 rising to 2× at level 100. */
    public static double torchMakingSpeed(int level) {
        return 1.0 + (MAX_TORCH_SPEED - 1.0) * progress(level);
    }

    /** Ticks to burn {@code logs} into charcoal at this skill. */
    public static int smeltTicks(int logs, int level) {
        return Math.max(20, (int) Math.round(
                logs * SMELT_TICKS_PER_CHARCOAL / torchMakingSpeed(level)));
    }

    /** Ticks for one crafting-table job at this skill. */
    public static int craftTicks(int level) {
        return Math.max(10, (int) Math.round(CRAFT_TICKS / torchMakingSpeed(level)));
    }

    /** Chance ore sense looks around a broken block: 25% at level 75 rising to 50% at 100. */
    public static double oreSenseChance(int level) {
        if (level < ORE_SENSE_LEVEL) return 0.0;
        double span = (Math.clamp(level, ORE_SENSE_LEVEL, MAX_LEVEL) - ORE_SENSE_LEVEL)
                / (double) (MAX_LEVEL - ORE_SENSE_LEVEL);
        return MIN_ORE_SENSE_CHANCE + (MAX_ORE_SENSE_CHANCE - MIN_ORE_SENSE_CHANCE) * span;
    }
}

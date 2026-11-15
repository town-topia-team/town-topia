package com.alex.test.village.economy.away;

/**
 * Vanilla crop growth as averages, with no dice, so a preview repeats.
 *
 * <p>Vanilla (1.21.1): each tick a chunk section gives {@code randomTickSpeed}
 * random ticks to its 4096 blocks. On a random tick:
 * <ul>
 *   <li>a crop, stem or pitcher (light 9 or more) grows a stage with chance
 *       {@code 1 / ((int)(25 / speed) + 1)}, speed from {@code CropBlock.getGrowthSpeed}
 *       (wet farmland around it, rows of other crops); beetroot and torchflower
 *       skip one random tick in three;</li>
 *   <li>a stem at age 7 instead tries one side at random for its fruit;</li>
 *   <li>a sweet berry bush (light 9 or more above it) and a cocoa pod grow an
 *       age with chance 1 in 5;</li>
 *   <li>sugar cane with air above, in a stack under 3, grows an age every random
 *       tick, and a block at the 16th.</li>
 * </ul>
 * Growth piles up in a carry; whole stages come out, the fraction stays.
 * Pure logic without Minecraft types, so unit tests can use it.
 */
public final class FarmGrowth {

    /** Blocks in a chunk section, which share its random ticks. */
    public static final double SECTION_BLOCKS = 4096.0;
    /** Sweet berries and cocoa: an age on 1 random tick in 5. */
    public static final double BUSH_CHANCE = 0.2;
    /** Beetroot and torchflower grow on 2 random ticks in 3 only. */
    public static final double ONE_IN_THREE_SKIPPED = 2.0 / 3.0;
    /** Sugar cane: random ticks from a new top block to the next block (ages 0 to 15, then it grows). */
    public static final int CANE_TICKS_PER_BLOCK = 16;
    /** Sugar cane grows no higher by itself. */
    public static final int CANE_MAX_HEIGHT = 3;
    /** Stem sides a fruit can be tried on. */
    public static final int STEM_SIDES = 4;

    private FarmGrowth() {}

    /** Random ticks one block gets in {@code ticks}, on average. */
    public static double randomTicks(long ticks, int randomTickSpeed) {
        return Math.max(0L, ticks) * Math.max(0, randomTickSpeed) / SECTION_BLOCKS;
    }

    /** A crop's, stem's or pitcher's chance of a stage on one random tick. */
    public static double cropChance(float growthSpeed) {
        if (!(growthSpeed > 0f)) return 0.0;
        return 1.0 / ((int) (25.0F / growthSpeed) + 1);
    }

    /**
     * A carry plus new growth: the whole stages that come out, at most
     * {@code room}; what stays is {@link Step#carry}. With no room left the
     * fraction is kept too (a crop that is full does not pile up stages).
     */
    public static Step step(double carry, double expected, int room) {
        double total = carry + Math.max(0.0, expected);
        if (room <= 0) return new Step(0, Math.min(total, 1.0 - 1e-9));
        int whole = (int) Math.min(room, Math.floor(total));
        double left = total - whole;
        if (whole == room) left = Math.min(left, 1.0 - 1e-9);
        return new Step(whole, left);
    }

    /** {@link #step}'s answer. */
    public record Step(int stages, double carry) {}

    /**
     * Where a tile's growth starts: a fraction from its position, so a field
     * planted at once does not all ripen in the same tick, and the same tile
     * always starts the same.
     */
    public static double startCarry(long positionKey) {
        long mixed = positionKey * 0x9E3779B97F4A7C15L;
        mixed ^= mixed >>> 31;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 29;
        return (mixed >>> 11) / (double) (1L << 53);
    }

    /**
     * A stem at age 7: how many fruits it makes from {@code stages} successful
     * growth tries, with {@code openSides} sides where a fruit fits, from
     * {@code carry}. Stops at one: an attached stem waits for its fruit to go.
     */
    public static Step stemFruit(double carry, double tries, int openSides) {
        if (openSides <= 0) return new Step(0, carry);
        return step(carry, tries * Math.min(openSides, STEM_SIDES) / STEM_SIDES, 1);
    }

    /**
     * Sugar cane: the stack after {@code randomTicks} whole random ticks on its
     * top block. Grows only with air above ({@code roomAbove} cells of it).
     *
     * @return {height, age of the top block}
     */
    public static int[] cane(int height, int age, int randomTicks, int roomAbove) {
        int top = Math.min(CANE_MAX_HEIGHT, height + Math.max(0, roomAbove));
        int ticks = Math.max(0, randomTicks);
        while (ticks > 0 && height < top) {
            int need = CANE_TICKS_PER_BLOCK - age;
            if (ticks >= need) {
                ticks -= need;
                height++;
                age = 0;
            } else {
                age += ticks;
                ticks = 0;
            }
        }
        // A stack at its top no longer ages (vanilla counts the stack before it ages the top).
        return new int[] { height, age };
    }
}

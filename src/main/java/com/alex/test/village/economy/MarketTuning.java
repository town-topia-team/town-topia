package com.alex.test.village.economy;

/**
 * The five numbers that decide how sales move prices, from the {@code market}
 * block of the price file (see {@link MarketMemory} for how they are used).
 * Whatever a file says is pulled into a sane range, and a missing number
 * falls back to its default.
 *
 * @param dropPerStack    price lost per stack of one good sold lately: the good's
 *                        share is {@code 1 / (1 + dropPerStack * stacks)}
 * @param risePerEmerald  price every other good gains for each emerald the
 *                        merchant spent lately (0.002 is a fifth of a percent)
 * @param maxRise         most that gain can ever add
 * @param lowestShare     a price never falls below this share of its base
 * @param keepEachMorning what is left of the counts after one village morning
 */
public record MarketTuning(double dropPerStack, double risePerEmerald, double maxRise,
                           double lowestShare, double keepEachMorning) {

    public static final double DEFAULT_DROP_PER_STACK = 0.08;
    public static final double DEFAULT_RISE_PER_EMERALD = 0.002;
    public static final double DEFAULT_MAX_RISE = 0.15;
    public static final double DEFAULT_LOWEST_SHARE = 0.25;
    public static final double DEFAULT_KEEP_EACH_MORNING = 2.0 / 3.0;

    public static final MarketTuning DEFAULT = new MarketTuning(DEFAULT_DROP_PER_STACK,
            DEFAULT_RISE_PER_EMERALD, DEFAULT_MAX_RISE, DEFAULT_LOWEST_SHARE, DEFAULT_KEEP_EACH_MORNING);

    /** The numbers pulled into their allowed ranges; anything that is not a number becomes its default. */
    public static MarketTuning clamped(double dropPerStack, double risePerEmerald, double maxRise,
                                       double lowestShare, double keepEachMorning) {
        return new MarketTuning(
                within(dropPerStack, 0.0, 1.0, DEFAULT_DROP_PER_STACK),
                within(risePerEmerald, 0.0, 0.05, DEFAULT_RISE_PER_EMERALD),
                within(maxRise, 0.0, 1.0, DEFAULT_MAX_RISE),
                within(lowestShare, 0.05, 1.0, DEFAULT_LOWEST_SHARE),
                within(keepEachMorning, 0.0, 1.0, DEFAULT_KEEP_EACH_MORNING));
    }

    private static double within(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
}

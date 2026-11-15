package com.alex.test.performance;

/**
 * What one {@link PerformanceOption} cost the server lately: its average time per tick and its worst single tick,
 * in nanoseconds, over the last 5 s. No Minecraft classes.
 */
public record PerformanceCost(long averageNanos, long worstNanos) {
}

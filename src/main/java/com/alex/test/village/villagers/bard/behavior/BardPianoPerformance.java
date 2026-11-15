package com.alex.test.village.villagers.bard.behavior;

/** Server timing for the bundled 132.05-second track, independent of burst settings. */
public final class BardPianoPerformance {
    // Vorbis final granule 5,823,405 at 44,100 Hz, rounded up to game ticks.
    public static final int DURATION_TICKS = 2641;
    private long startedAt = Long.MIN_VALUE;

    public boolean started() { return startedAt != Long.MIN_VALUE; }

    public void start(long gameTime) {
        if (!started()) startedAt = gameTime;
    }

    public boolean finished(long gameTime) {
        return started() && gameTime - startedAt >= DURATION_TICKS;
    }
}

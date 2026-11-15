package com.alex.test.entity;

/**
 * How dirty a villager is, and which of the five skin stages that shows as.
 *
 * <p>Dirt is a number from 0 to {@link #MAX} kept on the server. Only the
 * stage reaches the client, so the texture changes a handful of times a day
 * instead of with every hoe stroke:
 * <table border="1">
 *   <caption>Stages</caption>
 *   <tr><th>stage</th><th>from dirt</th><th>shows</th></tr>
 *   <tr><td>0</td><td>0</td><td>clean</td></tr>
 *   <tr><td>1</td><td>{@value #BOOTS}</td><td>muddy boots</td></tr>
 *   <tr><td>2</td><td>30</td><td>shins and hands</td></tr>
 *   <tr><td>3</td><td>55</td><td>knees, overalls, forearms</td></tr>
 *   <tr><td>4</td><td>80</td><td>the fully dirty skin</td></tr>
 * </table>
 *
 * <p>Kept free of Minecraft types.
 */
public final class DirtLevel {

    public static final float MAX = 100f;
    /** Number of stages, clean included. */
    public static final int STAGES = 5;
    /** Where stage 1 (muddy boots) starts: the rain rinse never goes below it. */
    public static final float BOOTS = 8f;

    /** Lowest dirt of stages 1 to 4. */
    private static final float[] STAGE_STARTS = {BOOTS, 30f, 55f, 80f};

    private DirtLevel() {}

    public static int stageFor(float dirt) {
        int stage = 0;
        while (stage < STAGE_STARTS.length && dirt >= STAGE_STARTS[stage]) stage++;
        return stage;
    }

    public static float clamp(float dirt) {
        return Float.isNaN(dirt) ? 0f : Math.max(0f, Math.min(MAX, dirt));
    }
}

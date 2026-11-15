package com.alex.test.village.villagers.druid.behavior;

import com.alex.test.ModSounds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * What the druid's tree magic sounds like, and its live tuning
 * ({@code /villager druidsound}). The tuning is not saved: a restart brings
 * back the defaults.
 *
 * <p>While a druid dances a sapling into a tree, clients near it hear a soft
 * hum ({@code client/DruidCastSound}, keyed off {@code NpcEntity.getDruidCast};
 * crops and mines have their own).
 * The finish sound starts a little before the tree appears: the file is
 * silent for its first 0.2 s and peaks at 0.85 s, so with the default lead
 * its bright part begins as the tree pops and peaks in the particle burst.
 * It is the only "done" sound; the vanilla chime it replaced is gone. The
 * leaves the druid cuts on its way out go quietly, with one soft rustle,
 * so they do not drown it out.
 *
 * <p>Crops and mines have their own finish sounds, led in the same way: a
 * crop's when it is about to become fully grown (not at every stage), a
 * mine's for every block grown back, in place of vanilla's stone place.
 */
public final class DruidSounds {

    /**
     * Hum volume: full, as the file is already mixed soft (about half as loud
     * as the enchanter's loop). Read by the client ({@code client/DruidCastSound}),
     * so the command changes it in single player only.
     */
    public static final int DEFAULT_LOOP_PERCENT = 100;
    /** Finish volume: at 60% its loudest moment matches the enchanter loop's loudest, about 7 dB over the druid's own loop. */
    public static final int DEFAULT_FINISH_PERCENT = 60;
    /** Ticks before the tree appears that the finish sound starts: 0.5 s. */
    public static final int DEFAULT_LEAD_TICKS = 10;
    /** Longest lead the command allows: 5 s, far below the shortest tree stage (20 s). */
    public static final int MAX_LEAD_TICKS = 100;
    /**
     * The one leaf rustle when the druid cuts its way out from under its new
     * tree, in place of vanilla's full crunch per leaf, which buried the finish
     * sound. At 30% it sits about 6 dB under the finish's bright part.
     */
    public static final int DEFAULT_RUSTLE_PERCENT = 30;
    /**
     * Crop finish volume. It plays only when a crop becomes fully grown, not
     * at every stage (a stage is 4-9 s). At 70% its loudest moment matches
     * the tree finish at 60%.
     */
    public static final int DEFAULT_CROP_FINISH_PERCENT = 70;
    /** No silent start in the crop finish: 0.25 s lead puts its loudest moment 0.35 s after the crop ripens, as with trees. */
    public static final int DEFAULT_CROP_LEAD_TICKS = 5;
    /**
     * Mine finish volume, once per block grown, in place of the stone place
     * sound. The file is mixed far louder than the tree's: at 16% its loudest
     * moment matches the tree finish at 60%.
     */
    public static final int DEFAULT_MINE_FINISH_PERCENT = 16;
    /** The mine finish is silent for its first 0.24 s: 0.3 s lead starts its loud part as the block appears. */
    public static final int DEFAULT_MINE_LEAD_TICKS = 6;
    /** Crop hum: the file is 7 dB louder than the tree's; at 50% it sits level with the tree hum at 100%. Client side. */
    public static final int DEFAULT_CROP_LOOP_PERCENT = 50;
    /** Mine hum: the file is 24 dB louder than the tree's; at 7% it sits level with the tree hum at 100%. Client side. */
    public static final int DEFAULT_MINE_LOOP_PERCENT = 7;

    private static volatile int loopPercent = DEFAULT_LOOP_PERCENT;
    private static volatile int finishPercent = DEFAULT_FINISH_PERCENT;
    private static volatile int leadTicks = DEFAULT_LEAD_TICKS;
    private static volatile int rustlePercent = DEFAULT_RUSTLE_PERCENT;
    private static volatile int cropFinishPercent = DEFAULT_CROP_FINISH_PERCENT;
    private static volatile int cropLeadTicks = DEFAULT_CROP_LEAD_TICKS;
    private static volatile int mineFinishPercent = DEFAULT_MINE_FINISH_PERCENT;
    private static volatile int mineLeadTicks = DEFAULT_MINE_LEAD_TICKS;
    private static volatile int cropLoopPercent = DEFAULT_CROP_LOOP_PERCENT;
    private static volatile int mineLoopPercent = DEFAULT_MINE_LOOP_PERCENT;

    private DruidSounds() {}

    public static int loopPercent() {
        return loopPercent;
    }

    public static void setLoopPercent(int percent) {
        loopPercent = Math.clamp(percent, 0, 100);
    }

    public static int finishPercent() {
        return finishPercent;
    }

    public static void setFinishPercent(int percent) {
        finishPercent = Math.clamp(percent, 0, 100);
    }

    public static int leadTicks() {
        return leadTicks;
    }

    public static void setLeadTicks(int ticks) {
        leadTicks = Math.clamp(ticks, 0, MAX_LEAD_TICKS);
    }

    public static int rustlePercent() {
        return rustlePercent;
    }

    public static void setRustlePercent(int percent) {
        rustlePercent = Math.clamp(percent, 0, 100);
    }

    public static int cropFinishPercent() {
        return cropFinishPercent;
    }

    public static void setCropFinishPercent(int percent) {
        cropFinishPercent = Math.clamp(percent, 0, 100);
    }

    public static int cropLeadTicks() {
        return cropLeadTicks;
    }

    public static void setCropLeadTicks(int ticks) {
        cropLeadTicks = Math.clamp(ticks, 0, MAX_LEAD_TICKS);
    }

    public static int mineFinishPercent() {
        return mineFinishPercent;
    }

    public static void setMineFinishPercent(int percent) {
        mineFinishPercent = Math.clamp(percent, 0, 100);
    }

    public static int mineLeadTicks() {
        return mineLeadTicks;
    }

    public static void setMineLeadTicks(int ticks) {
        mineLeadTicks = Math.clamp(ticks, 0, MAX_LEAD_TICKS);
    }

    public static int cropLoopPercent() {
        return cropLoopPercent;
    }

    public static void setCropLoopPercent(int percent) {
        cropLoopPercent = Math.clamp(percent, 0, 100);
    }

    public static int mineLoopPercent() {
        return mineLoopPercent;
    }

    public static void setMineLoopPercent(int percent) {
        mineLoopPercent = Math.clamp(percent, 0, 100);
    }

    /** The tree is about to stand: the finish sound, from the sapling, for everyone near. */
    static void treeFinishCue(ServerLevel level, Vec3 centre) {
        play(level, centre, ModSounds.DRUID_CASTING_TREE_FINISH.get(), finishPercent);
    }

    /** The crop is about to be fully grown: the crop finish, from the crop. */
    static void cropFinishCue(ServerLevel level, Vec3 crop) {
        play(level, crop, ModSounds.DRUID_CASTING_CROPS_FINISH.get(), cropFinishPercent);
    }

    /**
     * A mine block is about to grow back: the mine finish, from the mouth,
     * where the druid and anyone watching are; the block itself is often
     * deep in the tunnel behind rock.
     */
    static void mineFinishCue(ServerLevel level, Vec3 mouth) {
        play(level, mouth, ModSounds.DRUID_CASTING_SHAFT_FINISH.get(), mineFinishPercent);
    }

    private static void play(ServerLevel level, Vec3 at, SoundEvent sound, int percent) {
        float volume = percent / 100.0f;
        if (volume <= 0.0f) return;
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.NEUTRAL, volume, 1.0f);
    }

    /** Leaves cut quietly round the druid: one soft break sound of that leaf, at its head. */
    static void leafRustle(ServerLevel level, Vec3 at, SoundEvent leafBreak) {
        float volume = rustlePercent / 100.0f;
        if (volume <= 0.0f) return;
        // Pitch 0.8 and the block category, as vanilla plays a block breaking: the same leaf, only softer.
        level.playSound(null, at.x, at.y + 1.0, at.z, leafBreak, SoundSource.BLOCKS, volume, 0.8f);
    }
}

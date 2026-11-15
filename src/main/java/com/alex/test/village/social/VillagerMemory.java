package com.alex.test.village.social;

import net.minecraft.nbt.CompoundTag;

/**
 * What one villager remembers having happened to it lately.
 *
 * <p>Two fixed-length arrays indexed by {@link MemoryTopic#ordinal()}: the game
 * time a topic was last experienced, and how many separate episodes of it there
 * have been. That is deliberately not an event log — a villager only ever needs
 * to answer "how recently, and how often", and a flat array answers both in
 * O(1) with a bounded, fixed NBT footprint no matter how long a world runs.
 *
 * <p>Recency is the whole point: {@link #freshness} decays linearly to zero
 * over {@link #FORGET_TICKS} (three in-game days), so "I was starving on
 * Tuesday" fades out of the conversation rota on its own without any pruning
 * pass. {@link #record} rate-limits repeat episodes with
 * {@link #EPISODE_GAP_TICKS} so that a villager which is hungry for a solid
 * afternoon counts that as one bad afternoon rather than four hundred.
 */
public final class VillagerMemory {

    /** A memory is worthless to conversation once it is this old. */
    private static final long FORGET_TICKS = 72000L; // three in-game days

    /** Re-experiencing a topic inside this window extends it, not a new episode. */
    private static final long EPISODE_GAP_TICKS = 600L;

    /** Episodes past this stop adding interest, so chronic problems can't dominate. */
    private static final int MAX_COUNTED_EPISODES = 5;

    private static final String TAG_SEEN  = "MemorySeen";
    private static final String TAG_COUNT = "MemoryCount";

    /** Game time each topic was last experienced; {@link Long#MIN_VALUE} for never. */
    private final long[] lastSeen = new long[MemoryTopic.count()];
    /** Distinct episodes of each topic, saturating at {@link #MAX_COUNTED_EPISODES}. */
    private final int[]  episodes = new int[MemoryTopic.count()];

    public VillagerMemory() {
        java.util.Arrays.fill(lastSeen, Long.MIN_VALUE);
    }

    /**
     * Note that {@code topic} is happening now. Safe to call every tick: a
     * repeat inside {@link #EPISODE_GAP_TICKS} refreshes the timestamp without
     * counting as a new episode.
     */
    public void record(MemoryTopic topic, long gameTime) {
        int i = topic.ordinal();
        if (lastSeen[i] == Long.MIN_VALUE || gameTime - lastSeen[i] > EPISODE_GAP_TICKS) {
            episodes[i] = Math.min(MAX_COUNTED_EPISODES, episodes[i] + 1);
        }
        lastSeen[i] = gameTime;
    }

    /** 1.0 the moment it happened, falling to 0.0 at {@link #FORGET_TICKS}. */
    public float freshness(MemoryTopic topic, long gameTime) {
        long seen = lastSeen[topic.ordinal()];
        if (seen == Long.MIN_VALUE) return 0f;
        long age = gameTime - seen;
        if (age < 0L) return 1f;                 // clock moved backwards (/time set)
        if (age >= FORGET_TICKS) return 0f;
        return 1f - (float) age / FORGET_TICKS;
    }

    /**
     * How much this villager wants to talk about {@code topic} right now:
     * the topic's base weight, scaled down as the memory ages and up a little
     * for having happened repeatedly. Zero once forgotten.
     */
    public float conversationalWeight(MemoryTopic topic, long gameTime) {
        float fresh = freshness(topic, gameTime);
        if (fresh <= 0f) return 0f;
        float repetition = 1f + 0.15f * (episodes[topic.ordinal()] - 1);
        // Even a day-old memory stays worth a mention, so the decay only takes
        // the weight down to 30 % rather than all the way to nothing.
        return topic.weight() * (0.3f + 0.7f * fresh) * repetition;
    }

    public void save(CompoundTag tag) {
        tag.putLongArray(TAG_SEEN, lastSeen.clone());
        tag.putIntArray(TAG_COUNT, episodes.clone());
    }

    /**
     * Restore from NBT, tolerating a saved array from an older build with fewer
     * topics — extra entries are ignored, missing ones stay "never happened".
     */
    public void load(CompoundTag tag) {
        long[] seen = tag.getLongArray(TAG_SEEN);
        for (int i = 0; i < lastSeen.length; i++) {
            lastSeen[i] = i < seen.length ? seen[i] : Long.MIN_VALUE;
        }
        int[] counts = tag.getIntArray(TAG_COUNT);
        for (int i = 0; i < episodes.length; i++) {
            episodes[i] = i < counts.length ? counts[i] : 0;
        }
    }

    /** Compact one-line summary of live memories, freshest first, for debug dumps. */
    public String describe(long gameTime) {
        StringBuilder sb = new StringBuilder();
        MemoryTopic[] topics = MemoryTopic.all().clone();
        java.util.Arrays.sort(topics, (a, b) ->
                Float.compare(freshness(b, gameTime), freshness(a, gameTime)));
        for (MemoryTopic topic : topics) {
            float fresh = freshness(topic, gameTime);
            if (fresh <= 0f) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(topic.name()).append('(')
              .append(String.format("%.2f", fresh)).append("x")
              .append(episodes[topic.ordinal()]).append(')');
        }
        return sb.length() == 0 ? "(nothing)" : sb.toString();
    }
}

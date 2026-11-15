package com.alex.test.village.social;

/**
 * Something that happened to a villager and that it is still willing to bring
 * up in conversation.
 *
 * <p>A topic is deliberately coarser than a {@code ThoughtState}: the state
 * machine answers "what is wrong right now", a memory answers "what has this
 * villager been through lately", which is what makes a conversation worth
 * having. A farmer who ate well all week and an archer who starved on Tuesday
 * open with different icons even when both are currently fine.
 *
 * <p>{@link #opener()} is the icon the villager leads with when this topic wins
 * the weighting in {@link ConversationScripts}; {@link #weight()} is how much
 * the topic is worth before recency decay is applied. Negative experiences are
 * weighted above pleasant ones on purpose — villagers complain.
 *
 * <p>Every topic has a source, and a new one is only worth adding with a place
 * to record it from. The standing problems come from {@link ThoughtBubbles}'s
 * state pass, which already has the villager's {@code ThoughtState} in hand;
 * the rest are recorded by whatever causes them — the activity that ate the
 * meal, claimed the bed, closed the sale, failed to find the tool, or could not
 * reach where it was going.
 *
 * <p>Order is persisted (see {@link VillagerMemory#save}), so entries may be
 * appended but not reordered.
 */
public enum MemoryTopic {

    STARVATION    (ThoughtIcon.STATUS_STARVING,  1.7f),
    DANGER        (ThoughtIcon.STATUS_AFRAID,    1.6f),
    INJURY        (ThoughtIcon.STATUS_INJURED,   1.4f),
    FOOD_SHORTAGE (ThoughtIcon.STATUS_NO_FOOD,   1.3f),
    HUNGER        (ThoughtIcon.STATUS_HUNGRY,    1.1f),
    NO_BED        (ThoughtIcon.STATUS_NO_BED,    1.1f),
    SLEEPLESS     (ThoughtIcon.STATUS_EXHAUSTED, 0.9f),
    NO_WORK       (ThoughtIcon.STATUS_NO_WORK,   0.9f),
    NO_TOOLS      (ThoughtIcon.STATUS_NO_TOOLS,  0.9f),
    MISERY        (ThoughtIcon.STATUS_SAD,       0.9f),
    LONELY        (ThoughtIcon.STATUS_LONELY,    0.8f),
    STORM         (ThoughtIcon.TOPIC_WEATHER,    0.8f),
    RAIN          (ThoughtIcon.TOPIC_WEATHER,    0.4f),
    FINE_WEATHER  (ThoughtIcon.TOPIC_NATURE,     0.3f),
    WORK_DONE     (ThoughtIcon.TOPIC_WORK,       0.5f),
    SKILL_UP      (ThoughtIcon.EMOTE_PRIDE,      0.8f),
    GOOD_MEAL     (ThoughtIcon.STATUS_SATISFIED, 0.5f),
    GOOD_REST     (ThoughtIcon.TOPIC_BED,        0.4f),
    GOOD_COMPANY  (ThoughtIcon.EMOTE_GOSSIP,     0.35f),
    MAYOR_VISIT   (ThoughtIcon.TOPIC_MAYOR,      0.7f),
    TRADED        (ThoughtIcon.TOPIC_TRADING,    0.6f),
    FAMILY        (ThoughtIcon.TOPIC_FAMILY,     0.6f),
    CELEBRATION   (ThoughtIcon.EMOTE_CELEBRATION, 0.7f),
    CROWDED       (ThoughtIcon.STATUS_OVERCROWDED, 0.8f),
    BORING_FOOD   (ThoughtIcon.EMOTE_BOREDOM,    0.6f),
    FAVOURITE_MEAL(ThoughtIcon.EMOTE_LOVE,       0.6f),
    SPOILED_FOOD  (ThoughtIcon.EMOTE_DISGUST,    0.7f),
    /** A cleric healed it, brought it food or cheered it up. */
    CARED_FOR     (ThoughtIcon.EMOTE_GRATITUDE,  0.6f),
    /** A day at school: in class, or teaching it. */
    SCHOOL_DAY    (ThoughtIcon.TOPIC_LEARNING,   0.4f),
    /** A child of the village grew up and finished school. */
    GRADUATION    (ThoughtIcon.EMOTE_CELEBRATION, 0.7f);

    private static final MemoryTopic[] VALUES = values();

    private final ThoughtIcon opener;
    private final float       weight;

    MemoryTopic(ThoughtIcon opener, float weight) {
        this.opener = opener;
        this.weight = weight;
    }

    /** The icon this villager leads with when it raises the topic. */
    public ThoughtIcon opener() { return opener; }

    /** Base conversational interest, before recency decay. */
    public float weight() { return weight; }

    public boolean isNegative() { return opener.isNegative(); }

    public static int count() { return VALUES.length; }

    public static MemoryTopic byOrdinal(int ordinal) { return VALUES[ordinal]; }

    /** Shared, allocation-free view of the constants. */
    public static MemoryTopic[] all() { return VALUES; }
}

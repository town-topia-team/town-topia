package com.alex.test.village.social;

import com.alex.test.village.villagers.core.Profession;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.alex.test.village.social.ThoughtIcon.*;

/**
 * Turns two villagers' memories and jobs into an exchange of icons.
 *
 * <p>The shape a conversation aims for is the one a person would recognise:
 * somebody raises what has been on their mind, the other reacts to it, and —
 * if their job gives them anything to offer — answers it.
 *
 * <pre>
 *   archer (starved on Tuesday) ─ starving
 *   farmer                      ─ sad
 *   archer                      ─ anger
 *   farmer (can grow food)      ─ farming
 *   archer                      ─ relief
 * </pre>
 *
 * <p>Three mechanisms produce that without any hand-written dialogue trees:
 * <ul>
 *   <li><b>Opening</b> — weighted by {@link VillagerMemory#conversationalWeight},
 *       so the topic is whatever that villager has actually been through
 *       recently, falling back to shop talk when nothing has.</li>
 *   <li><b>Replies</b> — {@link #REPLIES} maps each icon to the icons that
 *       plausibly follow it. Unlisted icons fall back to a table chosen by
 *       {@link ThoughtIcon.Valence}, so every icon in the set can appear in a
 *       conversation the day it is added.</li>
 *   <li><b>Remedies and mirrors</b> — {@link ProfessionTopics#remedyFor} lets
 *       the partner answer the problem from its own trade, and a partner who
 *       shares the memory can say "me too" instead.</li>
 * </ul>
 *
 * <p>Everything is decided at pairing time; see {@link ConversationScript}.
 */
public final class ConversationScripts {

    /** Ticks each icon stays up before the partner answers. Two seconds reads well. */
    private static final int LINE_TICKS = 40;

    /** Turns after the opening line, so a conversation is 4–7 icons long. */
    private static final int MIN_REPLIES = 3;
    private static final int MAX_REPLIES = 4;

    private static final float GREETING_CHANCE       = 0.35f;
    private static final float MIRROR_CHANCE         = 0.6f;
    private static final float REMEDY_CHANCE         = 0.8f;
    private static final float CHANGE_SUBJECT_CHANCE = 0.35f;

    /** A partner needs a memory at least this fresh to answer "me too". */
    private static final float SHARED_MEMORY_THRESHOLD = 0.25f;

    private ConversationScripts() {}

    // =========================================================================
    // Entry points
    // =========================================================================

    /**
     * Script an exchange between two villagers. {@code aMemory} may be null for
     * a speaker that keeps no memory (the Mayor), in which case the opening is
     * village affairs and the other villager gets the floor to answer with
     * whatever is on its own mind.
     */
    public static ConversationScript between(@Nullable VillagerMemory aMemory, Profession aJob,
                                             @Nullable VillagerMemory bMemory, Profession bJob,
                                             long gameTime, RandomSource rnd) {
        return between(aMemory, aJob, bMemory, bJob, gameTime, rnd, true);
    }

    /**
     * As {@link #between}, but {@code allowHello} false leaves out the opening
     * hello: housemates, or two villagers who already talked tonight, just
     * carry on talking.
     */
    public static ConversationScript between(@Nullable VillagerMemory aMemory, Profession aJob,
                                             @Nullable VillagerMemory bMemory, Profession bJob,
                                             long gameTime, RandomSource rnd, boolean allowHello) {
        return build(pickOpening(aMemory, aJob, gameTime, rnd), bMemory, bJob, gameTime, rnd, allowHello);
    }

    /**
     * An exchange the initiator opens on a fixed subject rather than on
     * whatever is on its mind — the Mayor doing the rounds on village affairs,
     * for instance. The partner still answers from its own memory and trade.
     */
    public static ConversationScript about(MemoryTopic topic, @Nullable VillagerMemory bMemory,
                                           Profession bJob, long gameTime, RandomSource rnd) {
        return build(new Opening(topic, topic.opener()), bMemory, bJob, gameTime, rnd, true);
    }

    /**
     * A guest and its host: a longer chat about two or three topics (see
     * {@link VisitTalk}), opened with both saying hello when {@code greet}
     * (nobody waved at the door). The guest, {@code a}, speaks first.
     */
    public static ConversationScript visit(@Nullable VillagerMemory aMemory, Profession aJob,
                                           @Nullable VillagerMemory bMemory, Profession bJob,
                                           long gameTime, RandomSource rnd, boolean greet) {
        // Two or three subjects: what is on the guest's mind if it is a
        // subject at all, each one's trade, and the everyday ones.
        List<ThoughtIcon> pool = new ArrayList<>();
        ThoughtIcon onMind = pickOpening(aMemory, aJob, gameTime, rnd).icon();
        if (onMind.name().startsWith("TOPIC_")) pool.add(onMind);
        for (ThoughtIcon t : new ThoughtIcon[]{ProfessionTopics.shopTalk(aJob), ProfessionTopics.shopTalk(bJob)}) {
            if (!pool.contains(t)) pool.add(t);
        }
        List<ThoughtIcon> common = new ArrayList<>(List.of(VisitTalk.COMMON_TOPICS));
        for (int i = common.size() - 1; i > 0; i--) java.util.Collections.swap(common, i, rnd.nextInt(i + 1));
        List<ThoughtIcon> topics = new ArrayList<>();
        if (!pool.isEmpty()) topics.add(pool.get(0));
        int count = 2 + rnd.nextInt(2);
        // Mostly everyday subjects, with one trade subject now and then.
        if (pool.size() > 1 && rnd.nextFloat() < 0.5f) topics.add(pool.get(1 + rnd.nextInt(pool.size() - 1)));
        for (ThoughtIcon t : common) {
            if (topics.size() >= count) break;
            if (!topics.contains(t)) topics.add(t);
        }
        return VisitTalk.script(topics, new java.util.Random(rnd.nextLong()), greet);
    }

    private static ConversationScript build(Opening opening, @Nullable VillagerMemory bMemory,
                                            Profession bJob, long gameTime, RandomSource rnd,
                                            boolean allowHello) {
        List<ConversationScript.Line> lines = new ArrayList<>();

        if (allowHello && rnd.nextFloat() < GREETING_CHANCE) {
            lines.add(new ConversationScript.Line(true, EMOTE_GREETING));
        }
        lines.add(new ConversationScript.Line(true, opening.icon()));

        int replies = MIN_REPLIES + rnd.nextInt(MAX_REPLIES - MIN_REPLIES + 1);
        // The partner's second turn is where an offer of help lands naturally:
        // sympathy first, then the answer. Short exchanges get it immediately.
        int remedyTurn = replies >= 3 ? 2 : 0;
        ThoughtIcon remedy = opening.memory() == null
                ? null
                : ProfessionTopics.remedyFor(opening.memory(), bJob);

        ThoughtIcon previous = opening.icon();
        ThoughtIcon beforeThat = null;
        boolean initiatorTurn = false;

        for (int turn = 0; turn < replies; turn++) {
            boolean partnerTurn = !initiatorTurn;
            ThoughtIcon next = null;

            if (partnerTurn && turn == 0) {
                next = partnerFirstReply(opening, bMemory, bJob, gameTime, rnd);
            }
            if (next == null && partnerTurn && turn == remedyTurn
                    && remedy != null && rnd.nextFloat() < REMEDY_CHANCE) {
                next = remedy;
            }
            if (next == null || next == previous) {
                next = reply(previous, previous, beforeThat, rnd);
            }

            lines.add(new ConversationScript.Line(initiatorTurn, next));
            beforeThat = previous;
            previous = next;
            initiatorTurn = !initiatorTurn;
        }

        lines.add(new ConversationScript.Line(initiatorTurn, closer(previous, rnd)));
        return new ConversationScript(List.copyOf(lines), LINE_TICKS);
    }

    /**
     * A script trimmed so the whole exchange fits in {@code maxTicks}. Used by
     * the Mayor, whose chats are on a shorter leash than the coordinator's.
     */
    public static ConversationScript within(ConversationScript full, int maxTicks) {
        int fits = Math.max(2, maxTicks / full.lineTicks());
        if (fits >= full.size()) return full;
        return new ConversationScript(List.copyOf(full.lines().subList(0, fits)), full.lineTicks());
    }

    // =========================================================================
    // Opening
    // =========================================================================

    /** The subject raised, and the icon that raises it. */
    private record Opening(@Nullable MemoryTopic memory, ThoughtIcon icon) {}

    /**
     * Weighted pick across everything the villager remembers, with a floor of
     * small talk so a villager who has had an uneventful week still has
     * something to say — its trade, its house, or the weather.
     */
    private static Opening pickOpening(@Nullable VillagerMemory memory, Profession job,
                                       long gameTime, RandomSource rnd) {
        float smallTalk = 0.7f;
        float total = smallTalk;
        if (memory != null) {
            for (MemoryTopic topic : MemoryTopic.all()) {
                total += memory.conversationalWeight(topic, gameTime);
            }
        }

        float roll = rnd.nextFloat() * total;
        if (memory != null) {
            for (MemoryTopic topic : MemoryTopic.all()) {
                roll -= memory.conversationalWeight(topic, gameTime);
                if (roll <= 0f) return new Opening(topic, openerFor(topic, job));
            }
        }
        return new Opening(null, smallTalk(job, rnd));
    }

    private static ThoughtIcon smallTalk(Profession job, RandomSource rnd) {
        return switch (rnd.nextInt(4)) {
            case 0  -> TOPIC_WEATHER;
            case 1  -> TOPIC_HOME;
            case 2  -> EMOTE_GOSSIP;
            default -> ProfessionTopics.shopTalk(job);
        };
    }

    private static ThoughtIcon openerFor(MemoryTopic topic, Profession job) {
        return topic == MemoryTopic.NO_WORK && job == Profession.LUMBERJACK
                ? STATUS_NO_TREES : topic.opener();
    }

    // =========================================================================
    // The partner's opening reaction
    // =========================================================================

    /**
     * How the partner takes the opening line: shares the experience, changes
     * the subject to a problem of its own, or just reacts.
     */
    @Nullable
    private static ThoughtIcon partnerFirstReply(Opening opening, @Nullable VillagerMemory memory,
                                                 Profession job, long gameTime, RandomSource rnd) {
        if (memory == null) return null;

        if (opening.memory() != null
                && memory.freshness(opening.memory(), gameTime) >= SHARED_MEMORY_THRESHOLD
                && rnd.nextFloat() < MIRROR_CHANCE) {
            return openerFor(opening.memory(), job);
        }

        if (rnd.nextFloat() < CHANGE_SUBJECT_CHANCE) {
            MemoryTopic own = strongestGrievance(memory, opening.memory(), gameTime);
            if (own != null) return openerFor(own, job);
        }
        return null;
    }

    /** The partner's own worst recent experience, excluding the one just raised. */
    @Nullable
    private static MemoryTopic strongestGrievance(VillagerMemory memory,
                                                  @Nullable MemoryTopic exclude, long gameTime) {
        MemoryTopic best = null;
        float bestWeight = 0f;
        for (MemoryTopic topic : MemoryTopic.all()) {
            if (topic == exclude || !topic.isNegative()) continue;
            float weight = memory.conversationalWeight(topic, gameTime);
            if (weight > bestWeight) { bestWeight = weight; best = topic; }
        }
        return best;
    }

    // =========================================================================
    // Reply tables
    // =========================================================================

    private static ThoughtIcon reply(ThoughtIcon to, ThoughtIcon avoidA, @Nullable ThoughtIcon avoidB,
                                     RandomSource rnd) {
        ThoughtIcon[] options = REPLIES.getOrDefault(to, fallback(to));
        ThoughtIcon pick = options[rnd.nextInt(options.length)];
        // Two chances to avoid echoing the last couple of icons; if the table is
        // narrow enough that both retries collide, a repeat is still a fine reply.
        for (int i = 0; i < 2 && (pick == avoidA || pick == avoidB); i++) {
            pick = options[rnd.nextInt(options.length)];
        }
        return pick;
    }

    private static ThoughtIcon closer(ThoughtIcon to, RandomSource rnd) {
        ThoughtIcon[] options = switch (to.valence()) {
            case POSITIVE -> CLOSE_WARM;
            case NEGATIVE -> CLOSE_GLUM;
            case NEUTRAL  -> CLOSE_FLAT;
        };
        int index = rnd.nextInt(options.length);
        if (options[index] == to) index = (index + 1) % options.length;
        return options[index];
    }

    /** Table for an icon with no entry of its own, chosen by how it reads. */
    private static ThoughtIcon[] fallback(ThoughtIcon to) {
        if (to.name().startsWith("TOPIC_")) return ANY_TOPIC;
        return switch (to.valence()) {
            case POSITIVE -> ANY_POSITIVE;
            case NEGATIVE -> ANY_NEGATIVE;
            case NEUTRAL  -> ANY_NEUTRAL;
        };
    }

    private static final ThoughtIcon[] ANY_NEGATIVE = {
        STATUS_SAD, EMOTE_APOLOGY, EMOTE_ANGER, EMOTE_WARNING, EMOTE_DOUBT,
        EMOTE_QUESTION, EMOTE_IDEA, EMOTE_SURPRISED, EMOTE_AGREE, EMOTE_CONFUSION };

    private static final ThoughtIcon[] ANY_POSITIVE = {
        EMOTE_AGREE, EMOTE_LAUGHTER, EMOTE_CELEBRATION, EMOTE_ADMIRATION,
        EMOTE_GRATITUDE, EMOTE_PRIDE, EMOTE_ENVY, STATUS_HAPPY };

    private static final ThoughtIcon[] ANY_NEUTRAL = {
        EMOTE_AGREE, EMOTE_QUESTION, EMOTE_IDEA, EMOTE_GOSSIP, EMOTE_SURPRISED,
        EMOTE_DOUBT, EMOTE_TALKING, EMOTE_LAUGHTER };

    private static final ThoughtIcon[] ANY_TOPIC = {
        EMOTE_AGREE, EMOTE_QUESTION, EMOTE_IDEA, EMOTE_ADMIRATION, EMOTE_GOSSIP,
        EMOTE_DOUBT, EMOTE_BOREDOM, EMOTE_SURPRISED };

    private static final ThoughtIcon[] CLOSE_WARM = {
        EMOTE_AGREE, EMOTE_LAUGHTER, EMOTE_GRATITUDE, EMOTE_CELEBRATION, STATUS_HAPPY };

    private static final ThoughtIcon[] CLOSE_GLUM = {
        EMOTE_APOLOGY, STATUS_SAD, EMOTE_AGREE, EMOTE_WARNING, EMOTE_RELIEF };

    private static final ThoughtIcon[] CLOSE_FLAT = {
        EMOTE_AGREE, EMOTE_GRATITUDE, EMOTE_LAUGHTER, EMOTE_TALKING, EMOTE_GREETING };

    /** What plausibly follows each icon. Unlisted icons use {@link #fallback}. */
    private static final Map<ThoughtIcon, ThoughtIcon[]> REPLIES = new EnumMap<>(ThoughtIcon.class);

    static {
        // -- somebody's problem -----------------------------------------------
        REPLIES.put(STATUS_STARVING,  new ThoughtIcon[]{ STATUS_SAD, EMOTE_SURPRISED, EMOTE_APOLOGY, TOPIC_FOOD, EMOTE_WARNING, STATUS_HUNGRY });
        REPLIES.put(STATUS_HUNGRY,    new ThoughtIcon[]{ STATUS_SAD, TOPIC_FOOD, EMOTE_AGREE, EMOTE_APOLOGY, EMOTE_QUESTION });
        REPLIES.put(STATUS_NO_FOOD,   new ThoughtIcon[]{ EMOTE_WARNING, EMOTE_ANGER, TOPIC_STORAGE, TOPIC_FARMING, EMOTE_DOUBT });
        REPLIES.put(STATUS_NO_BED,    new ThoughtIcon[]{ EMOTE_SURPRISED, TOPIC_BED, TOPIC_BUILDING, EMOTE_APOLOGY, STATUS_SAD });
        REPLIES.put(STATUS_SLEEPY,    new ThoughtIcon[]{ EMOTE_AGREE, TOPIC_BED, EMOTE_LAUGHTER, EMOTE_BOREDOM });
        REPLIES.put(STATUS_EXHAUSTED, new ThoughtIcon[]{ EMOTE_AGREE, TOPIC_BED, STATUS_SAD, EMOTE_APOLOGY, TOPIC_WORK });
        REPLIES.put(STATUS_SAD,       new ThoughtIcon[]{ EMOTE_ANGER, EMOTE_APOLOGY, EMOTE_QUESTION, EMOTE_IDEA, EMOTE_LOVE, STATUS_SAD });
        REPLIES.put(STATUS_LONELY,    new ThoughtIcon[]{ EMOTE_GREETING, EMOTE_LOVE, EMOTE_APOLOGY, TOPIC_FAMILY, EMOTE_LAUGHTER });
        REPLIES.put(STATUS_NO_TOOLS,  new ThoughtIcon[]{ TOPIC_TOOLS, TOPIC_SMITHING, EMOTE_APOLOGY, EMOTE_DOUBT, EMOTE_ANGER });
        REPLIES.put(STATUS_NO_WORK,   new ThoughtIcon[]{ EMOTE_BOREDOM, TOPIC_WORK, EMOTE_IDEA, EMOTE_APOLOGY, EMOTE_DOUBT });
        REPLIES.put(STATUS_NO_TREES,  new ThoughtIcon[]{ EMOTE_BOREDOM, TOPIC_WOOD, EMOTE_IDEA, EMOTE_APOLOGY, EMOTE_DOUBT });
        REPLIES.put(STATUS_AFRAID,    new ThoughtIcon[]{ EMOTE_WARNING, TOPIC_GUARDING, EMOTE_SURPRISED, EMOTE_AGREE, STATUS_AFRAID });
        REPLIES.put(STATUS_FLEEING,   new ThoughtIcon[]{ EMOTE_SURPRISED, EMOTE_WARNING, TOPIC_GUARDING, EMOTE_RELIEF });
        REPLIES.put(STATUS_INJURED,   new ThoughtIcon[]{ EMOTE_SURPRISED, TOPIC_HEALING, EMOTE_APOLOGY, STATUS_SAD, EMOTE_WARNING });
        REPLIES.put(STATUS_STUCK,     new ThoughtIcon[]{ EMOTE_CONFUSION, EMOTE_LAUGHTER, TOPIC_BUILDING, EMOTE_IDEA, EMOTE_APOLOGY });
        REPLIES.put(STATUS_OVERCROWDED, new ThoughtIcon[]{ EMOTE_AGREE, TOPIC_BUILDING, TOPIC_HOME, EMOTE_ANGER, EMOTE_IDEA, STATUS_OVERCROWDED });
        REPLIES.put(STATUS_HAPPY,     new ThoughtIcon[]{ EMOTE_LAUGHTER, EMOTE_AGREE, EMOTE_CELEBRATION, EMOTE_ADMIRATION, EMOTE_ENVY });
        REPLIES.put(STATUS_SATISFIED, new ThoughtIcon[]{ EMOTE_ENVY, EMOTE_AGREE, EMOTE_LAUGHTER, TOPIC_FOOD, EMOTE_GRATITUDE });

        // -- reactions ---------------------------------------------------------
        REPLIES.put(EMOTE_GREETING,    new ThoughtIcon[]{ EMOTE_GREETING, EMOTE_TALKING, EMOTE_QUESTION, EMOTE_LAUGHTER });
        REPLIES.put(EMOTE_TALKING,     new ThoughtIcon[]{ EMOTE_AGREE, EMOTE_QUESTION, EMOTE_GOSSIP, EMOTE_LAUGHTER, EMOTE_BOREDOM });
        REPLIES.put(EMOTE_ANGER,       new ThoughtIcon[]{ EMOTE_APOLOGY, EMOTE_DISAGREE, TOPIC_FOOD, EMOTE_IDEA, EMOTE_WARNING, EMOTE_SURPRISED });
        REPLIES.put(EMOTE_APOLOGY,     new ThoughtIcon[]{ EMOTE_AGREE, EMOTE_RELIEF, EMOTE_GRATITUDE, EMOTE_DOUBT, EMOTE_ANGER });
        REPLIES.put(EMOTE_QUESTION,    new ThoughtIcon[]{ EMOTE_IDEA, EMOTE_CONFUSION, EMOTE_TALKING, EMOTE_AGREE, EMOTE_GOSSIP, EMOTE_DOUBT });
        REPLIES.put(EMOTE_IDEA,        new ThoughtIcon[]{ EMOTE_AGREE, EMOTE_SURPRISED, EMOTE_ADMIRATION, EMOTE_DOUBT, EMOTE_RELIEF });
        REPLIES.put(EMOTE_AGREE,       new ThoughtIcon[]{ EMOTE_GRATITUDE, EMOTE_RELIEF, EMOTE_LAUGHTER, EMOTE_PRIDE, EMOTE_CELEBRATION });
        REPLIES.put(EMOTE_DISAGREE,    new ThoughtIcon[]{ EMOTE_ANGER, EMOTE_DOUBT, EMOTE_APOLOGY, EMOTE_CONFUSION, EMOTE_WARNING });
        REPLIES.put(EMOTE_SURPRISED,   new ThoughtIcon[]{ EMOTE_AGREE, EMOTE_QUESTION, EMOTE_GOSSIP, EMOTE_LAUGHTER, EMOTE_DOUBT });
        REPLIES.put(EMOTE_LAUGHTER,    new ThoughtIcon[]{ EMOTE_LAUGHTER, EMOTE_AGREE, EMOTE_RELIEF, EMOTE_GOSSIP, EMOTE_BOREDOM });
        REPLIES.put(EMOTE_GOSSIP,      new ThoughtIcon[]{ EMOTE_SURPRISED, EMOTE_LAUGHTER, EMOTE_DOUBT, EMOTE_DISGUST, EMOTE_QUESTION });
        REPLIES.put(EMOTE_DOUBT,       new ThoughtIcon[]{ EMOTE_DISAGREE, EMOTE_QUESTION, EMOTE_IDEA, EMOTE_ANGER, EMOTE_CONFUSION });
        REPLIES.put(EMOTE_WARNING,     new ThoughtIcon[]{ EMOTE_SURPRISED, EMOTE_AGREE, STATUS_AFRAID, TOPIC_GUARDING, EMOTE_DOUBT });
        REPLIES.put(EMOTE_GRATITUDE,   new ThoughtIcon[]{ EMOTE_AGREE, EMOTE_LAUGHTER, EMOTE_PRIDE, EMOTE_LOVE });
        REPLIES.put(EMOTE_LOVE,        new ThoughtIcon[]{ EMOTE_LOVE, EMOTE_LAUGHTER, EMOTE_HEARTBREAK, EMOTE_ADMIRATION, EMOTE_ENVY });
        REPLIES.put(EMOTE_HEARTBREAK,  new ThoughtIcon[]{ STATUS_SAD, EMOTE_APOLOGY, EMOTE_LOVE, EMOTE_GRATITUDE });
        REPLIES.put(EMOTE_PRIDE,       new ThoughtIcon[]{ EMOTE_ADMIRATION, EMOTE_ENVY, EMOTE_DOUBT, EMOTE_LAUGHTER, EMOTE_AGREE });
        REPLIES.put(EMOTE_ADMIRATION,  new ThoughtIcon[]{ EMOTE_PRIDE, EMOTE_GRATITUDE, EMOTE_LAUGHTER, EMOTE_AGREE });
        REPLIES.put(EMOTE_BOREDOM,     new ThoughtIcon[]{ EMOTE_IDEA, EMOTE_LAUGHTER, EMOTE_AGREE, TOPIC_MUSIC, EMOTE_GOSSIP, STATUS_SLEEPY });
        REPLIES.put(EMOTE_RELIEF,      new ThoughtIcon[]{ EMOTE_AGREE, EMOTE_LAUGHTER, EMOTE_GRATITUDE, STATUS_HAPPY });
        REPLIES.put(EMOTE_CONFUSION,   new ThoughtIcon[]{ EMOTE_QUESTION, EMOTE_IDEA, EMOTE_LAUGHTER, EMOTE_TALKING });
        REPLIES.put(EMOTE_CELEBRATION, new ThoughtIcon[]{ EMOTE_CELEBRATION, EMOTE_LAUGHTER, EMOTE_AGREE, EMOTE_PRIDE, EMOTE_GRATITUDE });
        REPLIES.put(EMOTE_ENVY,        new ThoughtIcon[]{ EMOTE_APOLOGY, EMOTE_LAUGHTER, EMOTE_PRIDE, EMOTE_DISAGREE });
        REPLIES.put(EMOTE_DISGUST,     new ThoughtIcon[]{ EMOTE_AGREE, EMOTE_LAUGHTER, EMOTE_APOLOGY, EMOTE_DISAGREE });

        // -- subjects ----------------------------------------------------------
        REPLIES.put(TOPIC_FOOD,     new ThoughtIcon[]{ EMOTE_RELIEF, EMOTE_GRATITUDE, STATUS_HAPPY, EMOTE_QUESTION, STATUS_SATISFIED, EMOTE_AGREE });
        REPLIES.put(TOPIC_WEATHER,  new ThoughtIcon[]{ EMOTE_AGREE, EMOTE_BOREDOM, EMOTE_DISGUST, TOPIC_NATURE, EMOTE_WARNING });
        REPLIES.put(TOPIC_WORK,     new ThoughtIcon[]{ EMOTE_AGREE, EMOTE_BOREDOM, EMOTE_PRIDE, EMOTE_QUESTION, EMOTE_DOUBT, STATUS_STUCK });
        REPLIES.put(TOPIC_BED,      new ThoughtIcon[]{ EMOTE_AGREE, EMOTE_ENVY, EMOTE_ADMIRATION, EMOTE_QUESTION, TOPIC_BUILDING, STATUS_SLEEPY });
        REPLIES.put(TOPIC_HOME,     new ThoughtIcon[]{ EMOTE_AGREE, EMOTE_ENVY, EMOTE_ADMIRATION, TOPIC_BUILDING, EMOTE_GRATITUDE });
        REPLIES.put(TOPIC_TOOLS,    new ThoughtIcon[]{ EMOTE_AGREE, TOPIC_SMITHING, EMOTE_GRATITUDE, EMOTE_QUESTION });
        REPLIES.put(TOPIC_GUARDING, new ThoughtIcon[]{ EMOTE_AGREE, EMOTE_ADMIRATION, EMOTE_RELIEF, EMOTE_WARNING, EMOTE_DOUBT });
        REPLIES.put(TOPIC_ARCHERY,  new ThoughtIcon[]{ EMOTE_ADMIRATION, EMOTE_AGREE, EMOTE_DOUBT, EMOTE_PRIDE, EMOTE_RELIEF });
        REPLIES.put(TOPIC_HEALING,  new ThoughtIcon[]{ EMOTE_GRATITUDE, EMOTE_RELIEF, EMOTE_AGREE, EMOTE_ADMIRATION });
        REPLIES.put(TOPIC_TRADING,  new ThoughtIcon[]{ EMOTE_IDEA, EMOTE_DOUBT, EMOTE_AGREE, EMOTE_ENVY, EMOTE_GRATITUDE });
        REPLIES.put(TOPIC_MAYOR,    new ThoughtIcon[]{ EMOTE_AGREE, EMOTE_DOUBT, EMOTE_GOSSIP, EMOTE_ADMIRATION, EMOTE_ANGER });
        REPLIES.put(TOPIC_FAMILY,   new ThoughtIcon[]{ EMOTE_LOVE, EMOTE_LAUGHTER, EMOTE_ADMIRATION, EMOTE_AGREE, EMOTE_GRATITUDE });
        REPLIES.put(TOPIC_MUSIC,    new ThoughtIcon[]{ EMOTE_ADMIRATION, EMOTE_LAUGHTER, EMOTE_CELEBRATION, EMOTE_BOREDOM, EMOTE_AGREE });
        REPLIES.put(TOPIC_NATURE,   new ThoughtIcon[]{ EMOTE_ADMIRATION, EMOTE_AGREE, EMOTE_RELIEF, EMOTE_BOREDOM });
        REPLIES.put(TOPIC_LEARNING, new ThoughtIcon[]{ EMOTE_IDEA, EMOTE_QUESTION, EMOTE_ADMIRATION, EMOTE_BOREDOM, EMOTE_AGREE });
        REPLIES.put(TOPIC_FARMING,  new ThoughtIcon[]{ EMOTE_GRATITUDE, EMOTE_RELIEF, EMOTE_AGREE, TOPIC_FOOD, EMOTE_ADMIRATION });
        REPLIES.put(TOPIC_COOKING,  new ThoughtIcon[]{ EMOTE_GRATITUDE, STATUS_SATISFIED, EMOTE_RELIEF, TOPIC_FOOD, EMOTE_LOVE });
        REPLIES.put(TOPIC_SMITHING, new ThoughtIcon[]{ EMOTE_GRATITUDE, EMOTE_ADMIRATION, TOPIC_TOOLS, EMOTE_AGREE, EMOTE_RELIEF });
        REPLIES.put(TOPIC_BUILDING, new ThoughtIcon[]{ EMOTE_GRATITUDE, EMOTE_ADMIRATION, EMOTE_AGREE, EMOTE_RELIEF, EMOTE_DOUBT, STATUS_STUCK });
        REPLIES.put(TOPIC_STORAGE,  new ThoughtIcon[]{ EMOTE_QUESTION, EMOTE_DOUBT, EMOTE_AGREE, EMOTE_WARNING, TOPIC_FOOD });
    }
}

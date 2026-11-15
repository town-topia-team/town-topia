package com.alex.test.village.social;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.alex.test.village.social.ThoughtIcon.*;

/**
 * A guest and its host talking about something: 14-18 icons in two or three
 * short topic threads, strictly taking turns, with a few pauses.
 *
 * <p>Each thread opens with the topic, the other one reacts (a question,
 * surprise or agreement), the opener adds a detail about it, the other
 * answers from its own side, and longer threads go round once more. Topics
 * change with an idea or a bit of gossip, and the chat ends with thanks or a
 * laugh. The guest speaks first.
 *
 * <p>Pure: which topics come from the caller, the rest from {@link Random}.
 */
public final class VisitTalk {

    public static final int MIN_LINES = 14;
    public static final int MAX_LINES = 18;
    public static final int LINE_TICKS = 40;
    /** A turn now and then comes after a short pause. */
    private static final float PAUSE_CHANCE = 0.25f;
    private static final int PAUSE_MIN = 10, PAUSE_SPREAD = 11;

    private static final ThoughtIcon[] REACTIONS = {EMOTE_QUESTION, EMOTE_SURPRISED, EMOTE_AGREE};
    private static final ThoughtIcon[] CHANGES = {EMOTE_IDEA, EMOTE_GOSSIP};
    private static final ThoughtIcon[] CLOSERS = {EMOTE_GRATITUDE, EMOTE_LAUGHTER};

    private VisitTalk() {}

    /** Topics every villager can talk about on a visit. */
    public static final ThoughtIcon[] COMMON_TOPICS = {TOPIC_FOOD, TOPIC_HOME, TOPIC_FAMILY, TOPIC_MAYOR, TOPIC_WEATHER};

    /**
     * The script. {@code topics} holds the two or three subjects in order
     * (extra ones are ignored, a missing second one is filled with food);
     * {@code greet} opens with both saying hello, counted in the length.
     */
    public static ConversationScript script(List<ThoughtIcon> topics, Random rnd, boolean greet) {
        List<ThoughtIcon> chosen = new ArrayList<>(topics.subList(0, Math.min(3, topics.size())));
        while (chosen.size() < 2) chosen.add(chosen.contains(TOPIC_FOOD) ? TOPIC_HOME : TOPIC_FOOD);

        int total = MIN_LINES + rnd.nextInt(MAX_LINES - MIN_LINES + 1);
        List<ThoughtIcon> icons = new ArrayList<>();
        if (greet) {
            icons.add(EMOTE_GREETING);
            icons.add(EMOTE_GREETING);
        }
        int n = chosen.size();
        int budget = total - icons.size() - (n - 1) - 1;
        if (n == 3 && budget < 12) {
            n = 2;
            chosen = chosen.subList(0, 2);
            budget = total - icons.size() - 1 - 1;
        }
        for (int t = 0; t < n; t++) {
            int len = budget / n + (t < budget % n ? 1 : 0);
            icons.addAll(thread(chosen.get(t), Math.max(4, len), rnd));
            if (t < n - 1) icons.add(pick(CHANGES, rnd));
        }
        icons.add(pick(CLOSERS, rnd));

        List<ConversationScript.Line> lines = new ArrayList<>();
        List<Integer> pauses = new ArrayList<>();
        for (int i = 0; i < icons.size(); i++) {
            lines.add(new ConversationScript.Line(i % 2 == 0, icons.get(i)));
            pauses.add(i > 0 && rnd.nextFloat() < PAUSE_CHANCE ? PAUSE_MIN + rnd.nextInt(PAUSE_SPREAD) : 0);
        }
        List<String> names = new ArrayList<>();
        for (ThoughtIcon topic : chosen) names.add(topicName(topic));
        return new ConversationScript(List.copyOf(lines), LINE_TICKS, List.copyOf(pauses), List.copyOf(names));
    }

    /** One topic: opener, reaction, detail, answer, then round again for longer threads. */
    static List<ThoughtIcon> thread(ThoughtIcon topic, int length, Random rnd) {
        ThoughtIcon[] details = details(topic);
        ThoughtIcon[] answers = answers(topic);
        List<ThoughtIcon> out = new ArrayList<>();
        out.add(topic);
        while (out.size() < length) {
            switch (out.size() % 3) {
                case 1 -> out.add(pick(REACTIONS, rnd));
                case 2 -> out.add(pick(details, rnd));
                default -> out.add(pick(answers, rnd));
            }
        }
        return out;
    }

    private static ThoughtIcon[] details(ThoughtIcon topic) {
        return switch (topic) {
            case TOPIC_FOOD    -> new ThoughtIcon[]{TOPIC_COOKING, TOPIC_FARMING, STATUS_HUNGRY};
            case TOPIC_HOME    -> new ThoughtIcon[]{TOPIC_BUILDING, TOPIC_FAMILY, EMOTE_ADMIRATION};
            case TOPIC_FAMILY  -> new ThoughtIcon[]{EMOTE_LOVE, TOPIC_LEARNING, TOPIC_HOME};
            case TOPIC_MAYOR   -> new ThoughtIcon[]{EMOTE_GOSSIP, TOPIC_BUILDING, TOPIC_TRADING};
            case TOPIC_WEATHER -> new ThoughtIcon[]{TOPIC_FARMING, TOPIC_NATURE, EMOTE_SURPRISED};
            default            -> new ThoughtIcon[]{TOPIC_TOOLS, STATUS_EXHAUSTED, EMOTE_PRIDE, TOPIC_WORK};
        };
    }

    private static ThoughtIcon[] answers(ThoughtIcon topic) {
        return switch (topic) {
            case TOPIC_FOOD    -> new ThoughtIcon[]{EMOTE_LAUGHTER, EMOTE_LOVE, EMOTE_AGREE};
            case TOPIC_HOME    -> new ThoughtIcon[]{EMOTE_ADMIRATION, EMOTE_GRATITUDE, EMOTE_PRIDE};
            case TOPIC_FAMILY  -> new ThoughtIcon[]{EMOTE_LOVE, EMOTE_LAUGHTER, EMOTE_AGREE};
            case TOPIC_MAYOR   -> new ThoughtIcon[]{EMOTE_DOUBT, EMOTE_AGREE, EMOTE_DISAGREE};
            case TOPIC_WEATHER -> new ThoughtIcon[]{EMOTE_AGREE, EMOTE_BOREDOM, EMOTE_RELIEF};
            default            -> new ThoughtIcon[]{EMOTE_ADMIRATION, EMOTE_AGREE, EMOTE_LAUGHTER, EMOTE_PRIDE};
        };
    }

    /** FOOD, MAYOR, SMITHING ... for the diary. */
    public static String topicName(ThoughtIcon topic) {
        String name = topic.name();
        return name.startsWith("TOPIC_") ? name.substring(6) : name;
    }

    private static ThoughtIcon pick(ThoughtIcon[] options, Random rnd) {
        return options[rnd.nextInt(options.length)];
    }
}

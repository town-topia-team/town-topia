package com.alex.test.village.social;

import com.alex.test.village.villagers.core.Profession;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;

/**
 * What each profession talks about, and what it can offer when somebody else
 * has a problem.
 *
 * <p>{@link #shopTalk} is the villager's own subject — the thing it falls back
 * to when nothing memorable has happened to it. {@link #remedyFor} is the far
 * more interesting one: it turns a conversation from two villagers trading
 * moods into one of them actually answering the other. A starving archer gets a
 * loaf from the farmer and a shrug from the enchanter, which is the difference
 * between a scripted emote loop and a village that appears to have opinions.
 */
public final class ProfessionTopics {

    private static final Map<Profession, ThoughtIcon> SHOP_TALK =
            new EnumMap<>(Profession.class);

    static {
        SHOP_TALK.put(Profession.LUMBERJACK, ThoughtIcon.TOPIC_WOOD);
        SHOP_TALK.put(Profession.GUARD,      ThoughtIcon.TOPIC_GUARDING);
        SHOP_TALK.put(Profession.CAPTAIN,    ThoughtIcon.TOPIC_GUARDING);
        SHOP_TALK.put(Profession.ARCHER,     ThoughtIcon.TOPIC_ARCHERY);
        SHOP_TALK.put(Profession.FARMER,     ThoughtIcon.TOPIC_FARMING);
        SHOP_TALK.put(Profession.RANCHER,    ThoughtIcon.TOPIC_LIVESTOCK);
        SHOP_TALK.put(Profession.COOK,       ThoughtIcon.TOPIC_COOKING);
        SHOP_TALK.put(Profession.BUTCHER,    ThoughtIcon.TOPIC_LIVESTOCK);
        SHOP_TALK.put(Profession.BLACKSMITH, ThoughtIcon.TOPIC_SMITHING);
        SHOP_TALK.put(Profession.MINER,      ThoughtIcon.TOPIC_MINING);
        SHOP_TALK.put(Profession.BUILDER,  ThoughtIcon.TOPIC_BUILDING);
        SHOP_TALK.put(Profession.BARD,       ThoughtIcon.TOPIC_MUSIC);
        SHOP_TALK.put(Profession.CLERIC,     ThoughtIcon.TOPIC_HEALING);
        SHOP_TALK.put(Profession.DRUID,      ThoughtIcon.TOPIC_NATURE);
        SHOP_TALK.put(Profession.ENCHANTER,  ThoughtIcon.TOPIC_ENCHANTING);
        SHOP_TALK.put(Profession.TEACHER,    ThoughtIcon.TOPIC_LEARNING);
        SHOP_TALK.put(Profession.TRADESMAN,  ThoughtIcon.TOPIC_TRADING);
        SHOP_TALK.put(Profession.CHILD,      ThoughtIcon.TOPIC_LEARNING);
    }

    private ProfessionTopics() {}

    /** The subject this profession brings up on its own. Never {@code null}. */
    public static ThoughtIcon shopTalk(Profession profession) {
        return SHOP_TALK.getOrDefault(profession, ThoughtIcon.TOPIC_WORK);
    }

    /**
     * What {@code helper} can offer somebody who just complained about
     * {@code topic}, or {@code null} when this profession has nothing to say
     * about that particular problem.
     */
    @Nullable
    public static ThoughtIcon remedyFor(MemoryTopic topic, Profession helper) {
        return switch (topic) {
            case STARVATION, HUNGER, FOOD_SHORTAGE, GOOD_MEAL, BORING_FOOD, FAVOURITE_MEAL, SPOILED_FOOD -> switch (helper) {
                case FARMER            -> ThoughtIcon.TOPIC_FARMING;
                case COOK              -> ThoughtIcon.TOPIC_COOKING;
                case RANCHER, BUTCHER  -> ThoughtIcon.TOPIC_LIVESTOCK;
                case TRADESMAN         -> ThoughtIcon.TOPIC_TRADING;
                case DRUID             -> ThoughtIcon.TOPIC_NATURE;
                default                -> null;
            };
            case NO_BED, GOOD_REST -> switch (helper) {
                case BUILDER  -> ThoughtIcon.TOPIC_BUILDING;
                case LUMBERJACK -> ThoughtIcon.TOPIC_WOOD;
                case TRADESMAN  -> ThoughtIcon.TOPIC_TRADING;
                default         -> null;
            };
            case NO_TOOLS -> switch (helper) {
                case BLACKSMITH -> ThoughtIcon.TOPIC_SMITHING;
                case MINER      -> ThoughtIcon.TOPIC_MINING;
                case TRADESMAN  -> ThoughtIcon.TOPIC_TRADING;
                default         -> null;
            };
            case DANGER, STORM -> switch (helper) {
                case GUARD, CAPTAIN -> ThoughtIcon.TOPIC_GUARDING;
                case ARCHER         -> ThoughtIcon.TOPIC_ARCHERY;
                case MAGE           -> ThoughtIcon.TOPIC_GUARDING;
                case BLACKSMITH     -> ThoughtIcon.TOPIC_SMITHING;
                case BUILDER      -> ThoughtIcon.TOPIC_BUILDING;
                default             -> null;
            };
            case INJURY, CARED_FOR -> switch (helper) {
                case CLERIC, DRUID -> ThoughtIcon.TOPIC_HEALING;
                case ENCHANTER     -> ThoughtIcon.TOPIC_ENCHANTING;
                default            -> null;
            };
            case NO_WORK, WORK_DONE, SKILL_UP -> switch (helper) {
                case TEACHER   -> ThoughtIcon.TOPIC_LEARNING;
                case TRADESMAN -> ThoughtIcon.TOPIC_TRADING;
                case BUILDER -> ThoughtIcon.TOPIC_BUILDING;
                default        -> null;
            };
            case LONELY, MISERY -> switch (helper) {
                case BARD    -> ThoughtIcon.TOPIC_MUSIC;
                case CLERIC  -> ThoughtIcon.TOPIC_HEALING;
                case TEACHER -> ThoughtIcon.TOPIC_LEARNING;
                case CHILD   -> ThoughtIcon.TOPIC_FAMILY;
                default      -> null;
            };
            case SLEEPLESS -> helper == Profession.BARD ? ThoughtIcon.TOPIC_MUSIC : null;
            default -> null;
        };
    }
}

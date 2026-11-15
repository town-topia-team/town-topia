package com.alex.test.village.economy.away.jobs;

import com.alex.test.village.economy.away.AwayContext;
import com.alex.test.village.economy.away.AwayJob;
import com.alex.test.village.villagers.core.Profession;

/**
 * A villager with no away work of its own: it only eats and sleeps, and the
 * meals are the whole village's turn, not a job's (round 2 of the plan). Each
 * job here says why it makes nothing away (round 11), for the preview and the
 * log; none of these is a snag the player could fix.
 */
public final class EatOnlyAway implements AwayJob {

    public static final EatOnlyAway INSTANCE = new EatOnlyAway();

    /** Every reason starts with this, so the preview's list tells them from snags. */
    public static final String NO_AWAY_WORK = "no away work: ";

    private EatOnlyAway() {}

    @Override
    public String cannotWork(AwayContext ctx) {
        return NO_AWAY_WORK + why(ctx.npc().getProfession());
    }

    @Override
    public void workDay(AwayContext ctx) {
        // Nothing: never reached, cannotWork always says no.
    }

    /** Why {@code profession} makes nothing while the village is away. Every profession listed, no default. */
    static String why(Profession profession) {
        return switch (profession) {
            case GUARD, CAPTAIN -> "guard (no fights while nobody is near)";
            case ARCHER -> "archer (no arrows made away)";
            case MAGE -> "mage (its mana fills by time already)";
            case BARD -> "bard (plays for listeners)";
            case CLERIC -> "cleric (its heals are in the day's health)";
            case DRUID -> "druid (its crop help is in the fields' growth)";
            case TRADESMAN -> "tradesman (no merchant visits a sleeping village)";
            case BUILDER -> "builder (builds wait for you)";
            case CHILD -> "child (its school days count when you are back)";
            case NONE -> "jobless";
            // Jobs with an away file of their own never get here.
            case FARMER, COOK, RANCHER, BUTCHER, LUMBERJACK, MINER, BLACKSMITH, ENCHANTER, TEACHER ->
                    profession.displayName().toLowerCase(java.util.Locale.ROOT);
        };
    }
}

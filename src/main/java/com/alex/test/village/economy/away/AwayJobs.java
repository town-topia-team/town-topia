package com.alex.test.village.economy.away;

import com.alex.test.village.villagers.core.Profession;

/**
 * Which away job each profession does, and in what order the village works
 * each day: farmer, lumberjack and miner first (others use their goods), then
 * rancher, butcher, cook, smith, enchanter, teacher, then everyone else. Cook
 * before smith: food first.
 *
 * <p>The switches list every profession with no default, so a new profession
 * does not compile until it is given a job here. Pure logic without Minecraft
 * types, so unit tests can use it; the job objects are in {@link AwayJob#of}.
 */
public final class AwayJobs {

    /** The away job files; one per job that works while away (round 3 onward). */
    public enum Kind {
        /** No away work, each for its reason ({@code jobs/EatOnlyAway}, round 11): eats and sleeps only. */
        EAT_ONLY,
        /** Round 3: the shared field ({@code jobs/FarmerAway}). */
        FARMER,
        /** Round 4: the shared kitchen ({@code jobs/CookAway}). */
        COOK,
        /** Round 5: the shared pens ({@code jobs/RancherAway}). */
        RANCHER,
        /** Round 6: culls from the shared pens ({@code jobs/ButcherAway}). */
        BUTCHER,
        /** Round 7: fells the shared trees ({@code jobs/LumberjackAway}). */
        LUMBERJACK,
        /** Round 8: digs the shared mines ({@code jobs/MinerAway}). */
        MINER,
        /** Round 9: forges what the village is short of ({@code jobs/BlacksmithAway}). */
        BLACKSMITH,
        /** Round 10: writes the books the village's tools want ({@code jobs/EnchanterAway}). */
        ENCHANTER,
        /** Round 11: the school days away, to its own class ({@code jobs/TeacherAway}). */
        TEACHER
    }

    private AwayJobs() {}

    public static Kind kind(Profession profession) {
        return switch (profession) {
            case FARMER -> Kind.FARMER;
            case COOK -> Kind.COOK;
            case RANCHER -> Kind.RANCHER;
            case BUTCHER -> Kind.BUTCHER;
            case LUMBERJACK -> Kind.LUMBERJACK;
            case MINER -> Kind.MINER;
            case BLACKSMITH -> Kind.BLACKSMITH;
            case ENCHANTER -> Kind.ENCHANTER;
            case TEACHER -> Kind.TEACHER;
            case NONE, GUARD, BUILDER, BARD,
                 CLERIC, DRUID, TRADESMAN, ARCHER, CHILD, CAPTAIN, MAGE -> Kind.EAT_ONLY;
        };
    }

    /** Place in each day's work order, lowest first. */
    public static int order(Profession profession) {
        return switch (profession) {
            case FARMER, LUMBERJACK, MINER -> 0;
            case RANCHER -> 1;
            case BUTCHER -> 2;
            case COOK -> 3;
            case BLACKSMITH -> 4;
            case ENCHANTER -> 5;
            case TEACHER -> 6;
            case NONE, GUARD, BUILDER, BARD, CLERIC, DRUID, TRADESMAN, ARCHER, CHILD, CAPTAIN, MAGE -> 7;
        };
    }
}

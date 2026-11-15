package com.alex.test.village.villagers.builder.copy;

import com.alex.test.village.villagers.builder.copy.LandView.Group;

/**
 * Whether a land block (stone, dirt, sand, snow...) above a building's lowest floor was placed by
 * a player or made by the world. Today's rule calls every land block the world's, so stone in a
 * wall or chimney cuts the copy there; this judge asks the world generator what the bare land is
 * at that spot instead: solid there means the world made it, air or water means somebody put it there.
 * A block remembered as placed ({@code PlacedLand}) is placed whatever the bare land says.
 *
 * <p>It also judges the lookalikes: terracotta, infested stone, packed and blue ice count as building
 * today, but the world makes mesas and icebergs of them. Those stay out where the bare land shows the
 * world made them, at any height; the test can only ever leave out blocks today's copy takes.
 *
 * <p>No Minecraft in here: it reads through {@link LandView}, so it runs in plain unit tests.
 * Design: docs/plans/2026-10-23-builder-copy-placed-blocks-design.md.
 *
 * <p>{@link Verdict#TODAY} means "no opinion, use today's rule": the block is not land, it is at or
 * below the lowest floor (the generator raises the ground under village houses; players build up
 * platforms), the land around does not match the generator ({@link LandTrust}), or the generator
 * gave no answer.
 */
public final class PlacedJudge {

    /** The lowest floor when the building has no detected floor: every block keeps today's rule. */
    public static final int NO_FLOOR = Integer.MIN_VALUE;
    /** How far a soil stack is followed down before it counts as standing on land. */
    static final int SOIL_DEPTH = 64;

    private PlacedJudge() {}

    public enum Verdict { PLACED, WORLD, TODAY }

    public enum Reason {
        NOT_LAND("not a land block, so today's rule"),
        UNLOADED("its chunk is not loaded, so today's rule"),
        RULE_OFF("this test is switched off, so today's rule"),
        NO_FLOOR("the building has no detected floor, so today's rule"),
        AT_OR_BELOW_FLOOR("at or below the lowest floor, so today's rule"),
        NOT_TRUSTED("the land around doesn't match the world generator, so today's rule"),
        BARE_UNKNOWN("the world generator gave no answer, so today's rule"),
        REMEMBERED("a player or a Builder placed it here: placed"),
        BARE_SOLID("the bare land is solid here: the world made it"),
        FROZEN_OVER_FLUID("ice or snow where the bare land is water: the world made it"),
        BARE_AIR("the bare land is air here: placed"),
        BARE_FLUID("the bare land is water here: placed"),
        STAYS_BUILT("the world doesn't make it here, so it stays building as today"),
        SOIL_OFF_LAND("soil that doesn't stand on the land: placed"),
        SOIL_INSIDE_WALLS("soil inside the walls: placed"),
        SOIL_UNDER_BUILDING("soil with building above it: placed"),
        SOIL_ON_LAND("soil heaped on the land outside the walls: landscaping, left out");

        private final String words;

        Reason(String words) {
            this.words = words;
        }

        /** Plain words for {@code /villager copycheck}. */
        public String words() {
            return words;
        }
    }

    /**
     * Which rules are on. Each one is a {@code /villager walkfeel} switch.
     *
     * @param land       the bare-land test for land blocks ({@code copyland}); off: today's rule for them
     * @param soil       soil the test calls placed must also look like part of the building ({@code copysoil})
     * @param trust      a copy whose surroundings don't match the generator keeps today's rule ({@code copytrust})
     * @param memory     blocks remembered as placed are placed ({@code copymemory})
     * @param lookalikes terracotta, infested stone, packed and blue ice the world made stay out ({@code copylookalikes})
     */
    public record Rules(boolean land, boolean soil, boolean trust, boolean memory, boolean lookalikes) {
        public static Rules all() {
            return new Rules(true, true, true, true, true);
        }

        /** Exactly today's copy. */
        public static Rules old() {
            return new Rules(false, false, false, false, false);
        }

        public boolean any() {
            return land || lookalikes;
        }
    }

    public record Judgement(Verdict verdict, Reason reason) {
        public boolean placed() {
            return verdict == Verdict.PLACED;
        }
    }

    /**
     * @param lowestFloor one below the building's lowest room tile, or {@link #NO_FLOOR}
     * @param trusted     the vote of {@link LandTrust} for this copy
     */
    public static Judgement judge(LandView view, int x, int y, int z, int lowestFloor, boolean trusted, Rules rules) {
        Group group = view.group(x, y, z);
        if (group == Group.UNLOADED) return today(Reason.UNLOADED);
        if (group.isLookalike()) return judgeLookalike(view, group, x, y, z, lowestFloor, trusted, rules);
        if (!group.isLand()) return today(Reason.NOT_LAND);
        if (!rules.land()) return today(Reason.RULE_OFF);
        if (lowestFloor == NO_FLOOR) return today(Reason.NO_FLOOR);
        if (y <= lowestFloor) return today(Reason.AT_OR_BELOW_FLOOR);
        Reason placedBecause;
        if (rules.memory() && view.remembered(x, y, z)) {
            // Seen placed: no need to trust or ask the generator.
            placedBecause = Reason.REMEMBERED;
        } else {
            if (rules.trust() && !trusted) return today(Reason.NOT_TRUSTED);
            switch (view.bare(x, y, z)) {
                case UNKNOWN -> {
                    return today(Reason.BARE_UNKNOWN);
                }
                case SOLID -> {
                    return new Judgement(Verdict.WORLD, Reason.BARE_SOLID);
                }
                case FLUID -> {
                    // Frozen lakes and icebergs: the world freezes its own water.
                    if (group == Group.FROZEN) return new Judgement(Verdict.WORLD, Reason.FROZEN_OVER_FLUID);
                    placedBecause = Reason.BARE_FLUID;
                }
                default -> placedBecause = Reason.BARE_AIR;
            }
        }
        if (group != Group.SOIL || !rules.soil()) return new Judgement(Verdict.PLACED, placedBecause);
        return judgeSoil(view, x, y, z, lowestFloor, trusted, rules);
    }

    /**
     * Terracotta, infested stone, packed and blue ice: building today. Left out only where the bare
     * land shows the world made them: solid there (a mesa, a mountain), or water for the ice (an
     * iceberg). Any height, since this only ever takes away from today's copy.
     */
    private static Judgement judgeLookalike(LandView view, Group group, int x, int y, int z, int lowestFloor,
                                            boolean trusted, Rules rules) {
        if (!rules.lookalikes()) return today(Reason.RULE_OFF);
        if (lowestFloor == NO_FLOOR) return today(Reason.NO_FLOOR);
        if (rules.memory() && view.remembered(x, y, z)) return new Judgement(Verdict.PLACED, Reason.REMEMBERED);
        if (rules.trust() && !trusted) return today(Reason.NOT_TRUSTED);
        return switch (view.bare(x, y, z)) {
            case UNKNOWN -> today(Reason.BARE_UNKNOWN);
            case SOLID -> new Judgement(Verdict.WORLD, Reason.BARE_SOLID);
            case FLUID -> group == Group.HARD_ICE
                    ? new Judgement(Verdict.WORLD, Reason.FROZEN_OVER_FLUID) : today(Reason.STAYS_BUILT);
            case AIR -> today(Reason.STAYS_BUILT);
        };
    }

    /**
     * Placed soil is part of the building when it doesn't stand on the land (a turf roof, a planter on
     * a plank floor), is inside the walls (a dirt hut, a planter on a dirt floor) or has building above
     * it (the outer layer of a dirt wall under the eaves). The rest is landscaping: a mound by the house.
     */
    private static Judgement judgeSoil(LandView view, int x, int y, int z, int lowestFloor, boolean trusted,
                                       Rules rules) {
        if (!standsOnLand(view, x, y, z, lowestFloor, trusted, rules)) {
            return new Judgement(Verdict.PLACED, Reason.SOIL_OFF_LAND);
        }
        if (view.insideFootprint(x, z)) return new Judgement(Verdict.PLACED, Reason.SOIL_INSIDE_WALLS);
        for (int up = y + 1; up <= view.topY(); up++) {
            if (view.builtToday(x, up, z)) return new Judgement(Verdict.PLACED, Reason.SOIL_UNDER_BUILDING);
        }
        return new Judgement(Verdict.WORLD, Reason.SOIL_ON_LAND);
    }

    /** Follows the soil straight down (soil on soil) to the first other block: is it the world's ground? */
    static boolean standsOnLand(LandView view, int x, int y, int z, int lowestFloor, boolean trusted, Rules rules) {
        for (int down = y - 1; down >= y - SOIL_DEPTH; down--) {
            Group below = view.group(x, down, z);
            switch (below) {
                case SOIL -> {
                    continue;
                }
                case BUILT -> {
                    return false;
                }
                // Can't see it: the world's, the careful answer.
                case UNLOADED -> {
                    return true;
                }
                // Bedrock or a mushroom block is ground; air, water or a plant is not.
                case NEVER -> {
                    return view.solid(x, down, z);
                }
                case LOG -> {
                    return !view.builtToday(x, down, z);
                }
                default -> {
                    // Rock, frozen, sandstone or a lookalike: placed carries the soil, the world's is ground.
                    Verdict verdict = judge(view, x, down, z, lowestFloor, trusted, rules).verdict();
                    if (verdict == Verdict.TODAY) return !view.builtToday(x, down, z);
                    return verdict == Verdict.WORLD;
                }
            }
        }
        return true;
    }

    private static Judgement today(Reason reason) {
        return new Judgement(Verdict.TODAY, reason);
    }
}

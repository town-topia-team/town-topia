package com.alex.test.village.economy.away;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * One meal time of the whole village, served in turns: the hungriest first,
 * one serving each, round after round, until everyone has eaten up to its
 * target or the food runs out. So when food is short, everyone goes a bit
 * hungry instead of the last few in line getting nothing.
 *
 * <p>Each villager eats from its own pocket first, then from storage. Its
 * best food comes first ({@link Taste}, the live rules of {@code EatActivity});
 * a serving that would waste more than half of itself past full is skipped
 * for something smaller, unless nothing fits and the plate is still empty.
 * Fancy food ({@code FoodCatalog.isRationed}) is held to
 * {@link #RATIONED_PER_DAY} a villager a day, so one villager cannot take it all.
 *
 * <p>Pure logic without Minecraft types, so unit tests can use it;
 * {@code AwayMeals} builds the food list and applies what was served.
 */
public final class MealShare {

    /** {@link Food#owner} of food in storage, anyone's to take. */
    public static final int SHARED = -1;

    /** Fancy servings a villager may have a day (my call, 10-22). */
    public static final int RATIONED_PER_DAY = 1;

    /** Servings of one kind from one place: a villager's pocket or a storage slot. */
    public static final class Food {
        /** The eater whose pocket it is in, or {@link #SHARED}. */
        public final int owner;
        /** Hunger one serving restores. */
        public final float fill;
        public final boolean rationed;
        public final boolean spoiled;
        /** Servings left; lowered as they are eaten. */
        public int count;
        /** What the caller needs to take one (never read here). */
        public final Object source;

        public Food(int owner, float fill, boolean rationed, boolean spoiled, int count, Object source) {
            this.owner = owner;
            this.fill = fill;
            this.rationed = rationed;
            this.spoiled = spoiled;
            this.count = count;
            this.source = source;
        }
    }

    /** How much {@code eater}, at {@code hunger}, wants {@code food} next: higher first. */
    public interface Taste {
        float score(int eater, Food food, float hunger);
    }

    /** One serving eaten, in the order eaten. */
    public record Serving(int eater, Food food) {}

    private MealShare() {}

    /**
     * Serves one meal time.
     *
     * @param hunger       each eater's hunger, raised as it eats (at most 100)
     * @param target       the hunger each eats up to; at or above it an eater does not eat
     * @param rationedLeft fancy servings each may still have today, lowered as eaten
     */
    public static List<Serving> serve(float[] hunger, float[] target, int[] rationedLeft, List<Food> foods,
                                      Taste taste) {
        return serve(hunger, target, rationedLeft, new int[hunger.length], foods, taste);
    }

    /**
     * The same, as a further helping of a meal already begun.
     *
     * @param plate servings each has had this meal so far, raised as it eats
     */
    public static List<Serving> serve(float[] hunger, float[] target, int[] rationedLeft, int[] plate,
                                      List<Food> foods, Taste taste) {
        List<Serving> served = new ArrayList<>();
        Integer[] order = new Integer[hunger.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        boolean progress = true;
        while (progress) {
            progress = false;
            // The hungriest first, again every round.
            Arrays.sort(order, Comparator.comparingDouble(i -> hunger[i]));
            for (int eater : order) {
                if (hunger[eater] >= target[eater]) continue;
                Food food = pick(eater, hunger[eater], plate[eater] == 0, rationedLeft[eater] > 0, foods, taste);
                if (food == null) continue;
                food.count--;
                hunger[eater] = Math.min(100f, hunger[eater] + food.fill);
                plate[eater]++;
                if (food.rationed) rationedLeft[eater]--;
                served.add(new Serving(eater, food));
                progress = true;
            }
        }
        return served;
    }

    /** The serving {@code eater} takes next, or null when there is nothing it may or will eat. */
    static Food pick(int eater, float hunger, boolean plateEmpty, boolean mayHaveRationed, List<Food> foods,
                     Taste taste) {
        // Its own pocket first: storage only once the pocket has nothing it may eat.
        boolean ownFood = false;
        for (Food food : foods) {
            if (food.owner == eater && allowed(food, mayHaveRationed)) {
                ownFood = true;
                break;
            }
        }
        int from = ownFood ? eater : SHARED;
        boolean anyFits = false;
        for (Food food : foods) {
            if (food.owner == from && allowed(food, mayHaveRationed) && !overfills(hunger, food.fill)) {
                anyFits = true;
                break;
            }
        }
        Food best = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (Food food : foods) {
            if (food.owner != from || !allowed(food, mayHaveRationed)) continue;
            // A villager must eat something: an empty plate takes the best even if it overfills.
            if (overfills(hunger, food.fill) && (anyFits || !plateEmpty)) continue;
            float score = taste.score(eater, food, hunger);
            if (best == null || score > bestScore) {
                best = food;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean allowed(Food food, boolean mayHaveRationed) {
        return food.count > 0 && (!food.rationed || mayHaveRationed);
    }

    /** Eating this would waste more than half of it past a full stomach (as {@code EatActivity}). */
    public static boolean overfills(float hunger, float fill) {
        return hunger + fill - 100f > fill * 0.5f;
    }
}

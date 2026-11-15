package com.alex.test.village.villagers.builder.craft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * How the Builder would make blocks it is short of, from a ledger of what it may use: which
 * recipes in which order, what it takes, the fuel, and how many it really gets, or why it
 * can't. No Minecraft here (items are ids like "minecraft:stone"), so it is unit tested;
 * {@link RecipeBook} turns the game's recipes and the village's storage into its inputs.
 *
 * <p>Rules (docs/plans/2026-10-23-builder-makes-missing-blocks.md): stonecutter, crafting
 * table and furnace recipes only, chains of at most {@link #MAX_DEPTH}, no loops; the best
 * way first (cut, then craft, then smelt; fewest items in), the way that makes the most when
 * none makes all; as many runs as the ledger pays for, never more than fit in the pack.
 * Planning is run by run on copies, so a run that can't finish takes nothing.
 */
public final class MaterialPlanner {
    private MaterialPlanner() {}

    /** Steps in one chain: stone bricks from stone from cobblestone is two. */
    public static final int MAX_DEPTH = 3;
    public static final int STACK = 64;

    /** The three kinds of recipes, in the order they are preferred. */
    public enum Kind {
        CUT("Cut", "stonecutter"), CRAFT("Craft", "crafting table"), SMELT("Smelt", "furnace");

        public final String verb, station;

        Kind(String verb, String station) {
            this.verb = verb;
            this.station = station;
        }
    }

    /** One recipe: each slot lists the items that fit it; {@code count} of {@code result} per run. */
    public record Recipe(Kind kind, List<List<String>> slots, String result, int count) {
        /** The slots grouped: each distinct list of items and how many of it one run takes. */
        Map<List<String>, Integer> perRun() {
            Map<List<String>, Integer> groups = new LinkedHashMap<>();
            for (List<String> slot : slots) groups.merge(slot, 1, Integer::sum);
            return groups;
        }

        /** Items in per item out, to rank ways of making the same thing. */
        double cost() {
            return slots.size() / (double) Math.max(1, count);
        }
    }

    /** Every recipe by its result, what each fuel smelts, and stack sizes other than 64. */
    public record Book(Map<String, List<Recipe>> recipes, Map<String, Double> fuel, Map<String, Integer> stackSizes) {
        public List<Recipe> recipesFor(String item) {
            return recipes.getOrDefault(item, List.of());
        }

        public int stackSize(String item) {
            return Math.max(1, stackSizes.getOrDefault(item, STACK));
        }

        /** The same book with every recipe of {@code kind} left out. */
        public Book without(Kind kind) {
            Map<String, List<Recipe>> kept = new HashMap<>();
            recipes.forEach((item, list) -> {
                List<Recipe> left = list.stream().filter(recipe -> recipe.kind() != kind).toList();
                if (!left.isEmpty()) kept.put(item, left);
            });
            return new Book(Map.copyOf(kept), fuel, stackSizes);
        }
    }

    /** The work stations of the storage rooms; the crafting table is always there. */
    public record Stations(boolean table, boolean stonecutter, boolean furnace) {
        public boolean has(Kind kind) {
            return switch (kind) {
                case CUT -> stonecutter;
                case CRAFT -> table;
                case SMELT -> furnace;
            };
        }
    }

    /** Why the planner makes nothing (or fewer than wanted). */
    public enum Why {
        NONE(""),
        NO_RECIPE("no recipe makes it"),
        NO_INPUTS("nothing spare to make it from"),
        NO_STONECUTTER("no stonecutter in the storage room"),
        NO_FURNACE("no furnace in the storage room"),
        NO_TABLE("no crafting table in the storage room"),
        NO_FUEL("no spare fuel to smelt with"),
        PACK_FULL("no more room in the Builder's pack");

        public final String words;

        Why(String words) {
            this.words = words;
        }

        static Why station(Kind kind) {
            return switch (kind) {
                case CUT -> NO_STONECUTTER;
                case CRAFT -> NO_TABLE;
                case SMELT -> NO_FURNACE;
            };
        }

        /** Which of two failures to tell the player: a missing station or fuel says more than "nothing to use". */
        int weight() {
            return switch (this) {
                case NONE -> 0;
                case NO_RECIPE -> 1;
                case NO_INPUTS -> 2;
                case PACK_FULL -> 3;
                case NO_TABLE, NO_STONECUTTER, NO_FURNACE -> 4;
                case NO_FUEL -> 5;
            };
        }
    }

    /** One step: {@code runs} of {@code recipe}, and the items it used (from storage or from earlier steps). */
    public record Step(Recipe recipe, int runs, Map<String, Integer> used) {
        public int gives() {
            return runs * recipe.count();
        }
    }

    /**
     * The answer. {@code makes} may be under {@code wanted} (a part is better than nothing; {@code why}
     * says what stopped it) or a little over (a run gives 4). {@code takes} and {@code fuel} come out of
     * the ledger; {@code leftover} is what the steps make beyond what they use, the result not included.
     */
    public record Plan(String item, int wanted, int makes, List<Step> steps, Map<String, Integer> takes,
                       Map<String, Integer> fuel, Map<String, Integer> leftover, Why why) {
        public boolean any() {
            return makes > 0;
        }
    }

    /**
     * Plans making {@code wanted} of {@code item} from {@code ledger} (what may be used, see
     * {@link #spare}), at {@code stations}, with at most {@code packSlots} pack slots for what it
     * takes and what it makes.
     */
    public static Plan plan(Book book, Map<String, Integer> ledger, Stations stations, String item, int wanted,
                            int packSlots) {
        List<Recipe> ways = ways(book, item);
        if (wanted <= 0) return new Plan(item, wanted, 0, List.of(), Map.of(), Map.of(), Map.of(), Why.NONE);
        if (ways.isEmpty()) return new Plan(item, wanted, 0, List.of(), Map.of(), Map.of(), Map.of(), Why.NO_RECIPE);
        Sim best = null;
        // What stopped the way picked, and the most telling failure of all the ways (for "makes none").
        Why bestWhy = Why.NONE, worstWhy = Why.NONE;
        for (Recipe way : ways) {
            Sim sim = new Sim(book, ledger, stations);
            Set<String> path = Set.of(item);
            Why stopped = Why.NONE;
            while (sim.made(item) < wanted) {
                Sim before = sim.copy();
                if (!sim.run(way, 1, path)) {
                    stopped = sim.why;
                    sim = before;
                    break;
                }
                if (sim.slots(item) > packSlots) {
                    stopped = Why.PACK_FULL;
                    sim = before;
                    break;
                }
            }
            if (stopped.weight() > worstWhy.weight()) worstWhy = stopped;
            if (best == null || sim.made(item) > best.made(item)) {
                best = sim;
                bestWhy = stopped;
            }
            if (best.made(item) >= wanted) break;
        }
        int makes = best.made(item);
        Why why = makes >= wanted ? Why.NONE : makes == 0 ? worstWhy : bestWhy;
        if (makes < wanted && why == Why.NONE) why = Why.NO_INPUTS;
        Map<String, Integer> leftover = new LinkedHashMap<>(best.pool);
        leftover.remove(item);
        leftover.values().removeIf(n -> n <= 0);
        List<Step> steps = new ArrayList<>();
        for (Map.Entry<Recipe, Integer> run : best.runs.entrySet()) {
            steps.add(new Step(run.getKey(), run.getValue(), Collections.unmodifiableMap(best.used.get(run.getKey()))));
        }
        return new Plan(item, wanted, makes, List.copyOf(steps), Collections.unmodifiableMap(best.taken),
                Collections.unmodifiableMap(best.fuelTaken), Collections.unmodifiableMap(leftover), why);
    }

    /** The ways to make {@code item}, best first: cut, craft, smelt, then fewest items in per item out. */
    static List<Recipe> ways(Book book, String item) {
        List<Recipe> ways = new ArrayList<>(book.recipesFor(item));
        ways.sort(Comparator.comparing((Recipe r) -> r.kind().ordinal()).thenComparingDouble(Recipe::cost));
        return ways;
    }

    /** A planning run on its own copy of the ledger. */
    private static final class Sim {
        final Book book;
        final Stations stations;
        /** What is still there to take. */
        final Map<String, Integer> ledger;
        final Map<String, Integer> taken, fuelTaken;
        /** Made and not used yet: the result, and spare from steps that give more than one. */
        final Map<String, Integer> pool;
        /** Runs per recipe, in the order each first finished (so inputs come first). */
        final LinkedHashMap<Recipe, Integer> runs;
        final Map<Recipe, Map<String, Integer>> used;
        /** Smelts left from fuel already burned. */
        double fuelLeft;
        Why why = Why.NONE;

        Sim(Book book, Map<String, Integer> ledger, Stations stations) {
            this.book = book;
            this.stations = stations;
            this.ledger = new HashMap<>(ledger);
            this.taken = new LinkedHashMap<>();
            this.fuelTaken = new LinkedHashMap<>();
            this.pool = new LinkedHashMap<>();
            this.runs = new LinkedHashMap<>();
            this.used = new HashMap<>();
        }

        Sim copy() {
            Sim copy = new Sim(book, ledger, stations);
            copy.taken.putAll(taken);
            copy.fuelTaken.putAll(fuelTaken);
            copy.pool.putAll(pool);
            copy.runs.putAll(runs);
            for (Map.Entry<Recipe, Map<String, Integer>> each : used.entrySet()) {
                copy.used.put(each.getKey(), new LinkedHashMap<>(each.getValue()));
            }
            copy.fuelLeft = fuelLeft;
            copy.why = why;
            return copy;
        }

        /** Restores this run's state from {@code other} (a copy made before a try that failed). */
        void restore(Sim other) {
            ledger.clear();
            ledger.putAll(other.ledger);
            taken.clear();
            taken.putAll(other.taken);
            fuelTaken.clear();
            fuelTaken.putAll(other.fuelTaken);
            pool.clear();
            pool.putAll(other.pool);
            runs.clear();
            runs.putAll(other.runs);
            used.clear();
            used.putAll(other.used);
            fuelLeft = other.fuelLeft;
        }

        int made(String item) {
            return pool.getOrDefault(item, 0);
        }

        /** Pack slots for what is taken (the fetch) plus what is made: never less than the real need. */
        int slots(String result) {
            int slots = 0;
            for (Map.Entry<String, Integer> each : taken.entrySet()) slots += slotsFor(each.getKey(), each.getValue());
            for (Map.Entry<String, Integer> each : fuelTaken.entrySet()) slots += slotsFor(each.getKey(), each.getValue());
            for (Map.Entry<String, Integer> each : pool.entrySet()) slots += slotsFor(each.getKey(), each.getValue());
            return slots;
        }

        private int slotsFor(String item, int count) {
            int stack = book.stackSize(item);
            return count <= 0 ? 0 : (count + stack - 1) / stack;
        }

        /**
         * One run of {@code recipe}: gathers its inputs (made ones first, then the ledger, then
         * made on the spot), then the station and fuel. False, with {@link #why}, when it can't;
         * the caller throws this copy away then.
         */
        boolean run(Recipe recipe, int depth, Set<String> path) {
            Map<String, Integer> usedNow = new LinkedHashMap<>();
            for (Map.Entry<List<String>, Integer> group : recipe.perRun().entrySet()) {
                if (!gather(group.getKey(), group.getValue(), depth, path, usedNow)) return false;
            }
            if (!stations.has(recipe.kind())) {
                why = Why.station(recipe.kind());
                return false;
            }
            if (recipe.kind() == Kind.SMELT && !burn()) {
                why = Why.NO_FUEL;
                return false;
            }
            pool.merge(recipe.result(), recipe.count(), Integer::sum);
            runs.merge(recipe, 1, Integer::sum);
            Map<String, Integer> usedBy = used.computeIfAbsent(recipe, key -> new LinkedHashMap<>());
            usedNow.forEach((item, n) -> usedBy.merge(item, n, Integer::sum));
            return true;
        }

        /** {@code need} items of any of {@code items}, into {@code usedNow}. */
        private boolean gather(List<String> items, int need, int depth, Set<String> path, Map<String, Integer> usedNow) {
            // Made earlier and not used yet (the planks left from the last log).
            for (String item : items) {
                if (need == 0) return true;
                int have = pool.getOrDefault(item, 0);
                if (have <= 0 || path.contains(item)) continue;
                int take = Math.min(have, need);
                pool.put(item, have - take);
                usedNow.merge(item, take, Integer::sum);
                need -= take;
            }
            // From the ledger: the biggest pile first ("any planks").
            while (need > 0) {
                String biggest = null;
                for (String item : items) {
                    if (path.contains(item) || ledger.getOrDefault(item, 0) <= 0) continue;
                    if (biggest == null || ledger.get(item) > ledger.get(biggest)) biggest = item;
                }
                if (biggest == null) break;
                int take = Math.min(ledger.get(biggest), need);
                ledger.merge(biggest, -take, Integer::sum);
                taken.merge(biggest, take, Integer::sum);
                usedNow.merge(biggest, take, Integer::sum);
                need -= take;
            }
            if (need == 0) return true;
            // Made on the spot, one run at a time, from the item with the most to make it from.
            if (depth >= MAX_DEPTH) {
                why = Why.NO_INPUTS;
                return false;
            }
            List<String> order = new ArrayList<>(items);
            order.removeIf(path::contains);
            order.sort(Comparator.comparingInt(this::potential).reversed());
            Why failed = Why.NO_INPUTS;
            for (String item : order) {
                Set<String> deeper = new HashSet<>(path);
                deeper.add(item);
                while (need > 0) {
                    boolean made = false;
                    for (Recipe way : ways(book, item)) {
                        Sim before = copy();
                        if (run(way, depth + 1, deeper)) {
                            made = true;
                            break;
                        }
                        if (why.weight() > failed.weight()) failed = why;
                        restore(before);
                    }
                    if (!made) break;
                    int take = Math.min(pool.getOrDefault(item, 0), need);
                    pool.merge(item, -take, Integer::sum);
                    usedNow.merge(item, take, Integer::sum);
                    need -= take;
                }
                if (need == 0) return true;
            }
            why = failed;
            return false;
        }

        /** Roughly how much of {@code item} the ledger could make: its best recipe's scarcest input pile. */
        private int potential(String item) {
            int best = 0;
            for (Recipe way : book.recipesFor(item)) {
                int scarce = Integer.MAX_VALUE;
                for (List<String> slot : way.perRun().keySet()) {
                    int pile = 0;
                    for (String input : slot) pile = Math.max(pile, ledger.getOrDefault(input, 0));
                    scarce = Math.min(scarce, pile);
                }
                if (scarce != Integer.MAX_VALUE) best = Math.max(best, scarce);
            }
            return best;
        }

        /** One smelt's worth of fuel: what is left burning, else the best fuel in the ledger. */
        private boolean burn() {
            while (fuelLeft < 1) {
                String best = null;
                for (Map.Entry<String, Double> fuel : book.fuel().entrySet()) {
                    if (fuel.getValue() <= 0 || ledger.getOrDefault(fuel.getKey(), 0) <= 0) continue;
                    if (best == null || fuel.getValue() > book.fuel().get(best)
                            || fuel.getValue().equals(book.fuel().get(best)) && ledger.get(fuel.getKey()) > ledger.get(best)) {
                        best = fuel.getKey();
                    }
                }
                if (best == null) return false;
                ledger.merge(best, -1, Integer::sum);
                fuelTaken.merge(best, 1, Integer::sum);
                fuelLeft += book.fuel().get(best);
            }
            fuelLeft -= 1;
            return true;
        }
    }

    // =========================================================================
    // What may be used
    // =========================================================================

    /** A floor: these items together always keep at least {@code keep} back. */
    public record Floor(Set<String> items, int keep) {}

    /**
     * The ledger: what is stored (village goods the Builder may take) plus what the Builder
     * carries, less what the village's orders still need as that very item, less the floors;
     * never anything {@code never} names, valuables only with {@code useValuables}.
     */
    public static Map<String, Integer> spare(Map<String, Integer> stored, Map<String, Integer> carried,
                                             Map<String, Integer> ordersNeed, List<Floor> floors,
                                             Predicate<String> never, Predicate<String> valuable,
                                             boolean useValuables) {
        Map<String, Integer> spare = new HashMap<>(stored);
        carried.forEach((item, n) -> spare.merge(item, n, Integer::sum));
        spare.keySet().removeIf(item -> never.test(item) || !useValuables && valuable.test(item));
        ordersNeed.forEach((item, n) -> spare.computeIfPresent(item, (key, have) -> have - n));
        for (Floor floor : floors) {
            List<String> piles = new ArrayList<>();
            for (String item : floor.items()) {
                if (spare.getOrDefault(item, 0) > 0) piles.add(item);
            }
            // The smallest piles are kept first, so the biggest is what is used.
            piles.sort(Comparator.comparingInt(spare::get));
            int keep = floor.keep();
            for (String item : piles) {
                int kept = Math.min(keep, spare.get(item));
                spare.put(item, spare.get(item) - kept);
                keep -= kept;
            }
        }
        spare.values().removeIf(n -> n <= 0);
        return spare;
    }
}

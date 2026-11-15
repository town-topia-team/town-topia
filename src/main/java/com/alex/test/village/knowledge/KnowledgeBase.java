package com.alex.test.village.knowledge;

import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import net.minecraft.core.BlockPos;

import java.util.*;

/**
 * The village's persistent world model — everything it "knows" about its surroundings.
 *
 * <h3>Design intent</h3>
 * The KnowledgeBase is populated <em>once</em> at NavGraph build time via
 * {@link KnowledgeScanner}, then kept up-to-date purely through Minecraft block/entity
 * events fired into {@link VillageManager}.  No polling, no transient-node hacks.
 *
 * <h3>Extending with new categories</h3>
 * <ol>
 *   <li>Add a constant to {@link Category}.</li>
 *   <li>Add initial population logic in {@link KnowledgeScanner}.</li>
 *   <li>Add event-driven updates in {@link VillageManager} (and
 *       {@link VillageManagerRegistry.Events} for the routing layer).</li>
 *   <li>Add a work branch in {@link com.alex.test.village.brain.WorkActivity}
 *       if villagers should act on the new category.</li>
 * </ol>
 *
 * <h3>Thread safety</h3>
 * All reads and writes must happen on the server tick thread.
 */
public final class KnowledgeBase {

    // =========================================================================
    // Category
    // =========================================================================

    /**
     * A type of world object the village tracks.  Each category has its own
     * independent slot map — categories never interfere with each other.
     */
    public enum Category {

        /**
         * The base-log block position of a standing, harvestable tree.
         * Populated by {@link TreeScanner} at init; updated when a sapling
         * grows ({@code BlockGrowFeatureEvent}) or a log is broken
         * ({@code BlockEvent.BreakEvent}).
         */
        TREE,

        /**
         * The block position of a fully-grown harvestable crop (e.g. wheat,
         * carrots, potatoes).  Populated at init from nav-graph surface nodes;
         * updated on {@code BlockEvent.CropGrowEvent.Post} and break events.
         */
        CROP,

        /**
         * The base-block position of a multi-block sugarcane plant.
         * Populated at init by column scan; updated on grow/break events.
         */
        SUGARCANE,

        /**
         * Position of an item frame entity.  Item frames are used as markers
         * that signal the presence of a room.  Populated at init by entity
         * scan; updated on {@code EntityJoinLevelEvent} and leave events.
         */
        ROOM
    }

    // =========================================================================
    // Storage — one LinkedHashMap per category (insertion-ordered, fast lookup)
    // =========================================================================

    private final Map<Category, LinkedHashMap<Long, BlockPos>> store = new EnumMap<>(Category.class);

    public KnowledgeBase() {
        for (Category c : Category.values()) store.put(c, new LinkedHashMap<>());
    }

    // =========================================================================
    // Mutation
    // =========================================================================

    /**
     * Add (or silently update) an entry.
     *
     * @return {@code true} if the entry was not already present (i.e., it is new
     *         knowledge — callers may want to trigger task planning on {@code true}).
     */
    public boolean add(Category category, BlockPos pos) {
        long key = pos.asLong();
        boolean isNew = !store.get(category).containsKey(key);
        store.get(category).put(key, pos);
        return isNew;
    }

    /**
     * Remove an entry.
     *
     * @return {@code true} if the entry existed (i.e., we actually lost knowledge —
     *         callers may want to cancel related tasks on {@code true}).
     */
    public boolean remove(Category category, BlockPos pos) {
        return store.get(category).remove(pos.asLong()) != null;
    }

    /** True if an entry exists at exactly {@code pos} in this category. */
    public boolean contains(Category category, BlockPos pos) {
        return store.get(category).containsKey(pos.asLong());
    }

    // =========================================================================
    // Query
    // =========================================================================

    /**
     * All known positions for a category, in insertion order.
     * The collection is a live, unmodifiable view — do not hold across ticks.
     */
    public Collection<BlockPos> get(Category category) {
        return Collections.unmodifiableCollection(store.get(category).values());
    }

    public int count(Category category) {
        return store.get(category).size();
    }

    /** Remove all entries for one category (e.g. before a full re-scan). */
    public void clear(Category category) {
        store.get(category).clear();
    }

    /** Remove all entries in every category (used at VillageManager teardown / re-init). */
    public void clearAll() {
        for (Category c : Category.values()) store.get(c).clear();
    }

    // =========================================================================
    // Diagnostics
    // =========================================================================

    public String dumpStats() {
        StringBuilder sb = new StringBuilder("KnowledgeBase[");
        for (Category c : Category.values())
            sb.append(c.name()).append('=').append(count(c)).append(' ');
        sb.setCharAt(sb.length() - 1, ']');
        return sb.toString();
    }
}

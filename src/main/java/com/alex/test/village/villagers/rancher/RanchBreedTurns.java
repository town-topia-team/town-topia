package com.alex.test.village.villagers.rancher;

import com.alex.test.village.building.RanchBuilding;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Whose turn it is to be bred in each pen: the game time each kind last had a
 * pair picked for feeding, shared by every rancher. In memory only, so a
 * restart starts every kind level. Without it the kind that came first in an
 * unordered map was bred whenever it could be, and with carrots in the lunch
 * that was always the pigs.
 */
public final class RanchBreedTurns {

    private RanchBreedTurns() {}

    /** Ties nothing else settles go in this order. */
    private static final List<EntityType<?>> KINDS =
            List.of(EntityType.COW, EntityType.SHEEP, EntityType.PIG, EntityType.CHICKEN);

    /** Server thread only; a pen that is gone takes its turns with it. */
    private static final Map<RanchBuilding, Map<EntityType<?>, Long>> LAST_PICKED = new WeakHashMap<>();

    /** A pair of {@code kind} in {@code ranch} was picked for feeding at {@code gameTime}. */
    public static void picked(RanchBuilding ranch, EntityType<?> kind, long gameTime) {
        LAST_PICKED.computeIfAbsent(ranch, r -> new HashMap<>()).put(kind, gameTime);
    }

    /** Game time {@code kind} last had a pair picked in the pen; {@code Long.MIN_VALUE} for not since the start. */
    public static long lastPicked(RanchBuilding ranch, EntityType<?> kind) {
        Map<EntityType<?>, Long> kinds = LAST_PICKED.get(ranch);
        Long at = kinds == null ? null : kinds.get(kind);
        return at == null ? Long.MIN_VALUE : at;
    }

    /**
     * {@code kinds} in turn order: the longest since last picked first, then
     * the one with fewer heads ({@code halfHeads}, as the room check counts
     * them), then {@link #KINDS} order.
     */
    public static List<EntityType<?>> inTurn(RanchBuilding ranch, Collection<EntityType<?>> kinds,
                                             Map<EntityType<?>, Integer> halfHeads) {
        List<EntityType<?>> order = new ArrayList<>(kinds);
        order.sort(Comparator.<EntityType<?>>comparingLong(kind -> lastPicked(ranch, kind))
                .thenComparingInt(kind -> halfHeads.getOrDefault(kind, 0))
                .thenComparingInt(RanchBreedTurns::fixedIndex));
        return order;
    }

    private static int fixedIndex(EntityType<?> kind) {
        int index = KINDS.indexOf(kind);
        return index < 0 ? KINDS.size() : index;
    }
}

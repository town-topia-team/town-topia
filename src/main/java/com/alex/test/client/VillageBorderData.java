package com.alex.test.client;

import net.minecraft.world.phys.AABB;

import java.util.Collections;
import java.util.List;

/**
 * Client-side store for the current village boundary boxes.
 * Written by the packet handler; read by {@link VillageBorderRenderer} each frame.
 *
 * <p>Volatile list reference ensures the renderer always sees the latest update
 * without needing synchronisation overhead — we only ever replace the reference,
 * never mutate the list in place.</p>
 */
public final class VillageBorderData {

    private static volatile List<AABB> bounds = Collections.emptyList();

    /** Replace the current set of village bounds (called from packet handler on render thread). */
    public static void update(List<AABB> newBounds) {
        bounds = List.copyOf(newBounds);
    }

    /** Current snapshot of all village boundary boxes. Never null; may be empty. */
    public static List<AABB> get() {
        return bounds;
    }

    private VillageBorderData() {}
}

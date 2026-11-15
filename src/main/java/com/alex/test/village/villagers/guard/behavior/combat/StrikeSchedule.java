package com.alex.test.village.villagers.guard.behavior.combat;

import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * When the blows of the guards fighting one mob will land, so they take turns.
 *
 * <p>Vanilla shrugs off a hit that lands within {@value #IMMUNITY_TICKS} ticks
 * of the last one unless it is harder: two guards swinging every 16 ticks at
 * the same zombie kept landing inside each other's window, and one of the two
 * blows did nothing. Each guard books the tick its blow will land; another
 * guard only starts a swing whose blow lands at least that far from every
 * booked one. At most {@link #maxGuardsOn} guards (two for an ordinary mob) swing at one mob -- that
 * is already a blow every ten ticks, all it can take -- and the rest hold,
 * shield up if they need to, or go for another.
 */
public final class StrikeSchedule {

    private static final int IMMUNITY_TICKS = 10;
    /** Guards an ordinary mob can take: its blows already land every ten ticks with two. */
    public static final int REGULAR_MAX_GUARDS = 2;
    /** Guards a big mob (a ravager, a boss) can take. */
    public static final int BIG_MAX_GUARDS = 4;
    /** From this much health a mob counts as big. */
    private static final float BIG_MOB_HEALTH = 60.0f;
    /** A guard that booked a blow this recently still counts as one of the mob's swingers. */
    private static final int SWINGER_MEMORY_TICKS = 40;

    private record Booking(UUID guardId, long landAt) {}

    private static final Map<UUID, List<Booking>> BOOKINGS = new HashMap<>();
    private static long lastPruneAt;

    private StrikeSchedule() {}

    /**
     * Guards a mob can take before more only queue behind them: two for an
     * ordinary mob, more for a big one. A soft limit -- it steers where guards
     * go first, and it is how many swing at once; it never turns guards away.
     */
    public static int maxGuardsOn(LivingEntity foe) {
        return foe.getMaxHealth() >= BIG_MOB_HEALTH ? BIG_MAX_GUARDS : REGULAR_MAX_GUARDS;
    }

    /**
     * Whether {@code guardId} may start a swing at {@code foe} whose blow lands at
     * {@code landAt}. A guard answering a mob that is hitting it is never held
     * back by the two-swinger limit, only by the spacing between blows.
     */
    public static boolean canLandAt(LivingEntity foe, UUID guardId, long landAt, long now, boolean selfDefense) {
        List<Booking> bookings = BOOKINGS.get(foe.getUUID());
        if (bookings == null) return true;
        int swingers = 0;
        boolean amSwinger = false;
        for (Booking booking : bookings) {
            if (booking.landAt() < now - SWINGER_MEMORY_TICKS) continue;
            if (booking.guardId().equals(guardId)) {
                amSwinger = true;
                continue;
            }
            swingers++;
            if (Math.abs(booking.landAt() - landAt) < IMMUNITY_TICKS) return false;
        }
        return selfDefense || amSwinger || swingers < maxGuardsOn(foe);
    }

    /** Whether there is room for another guard to swing at {@code foe}. */
    public static boolean hasRoomFor(LivingEntity foe, UUID guardId, long now) {
        List<Booking> bookings = BOOKINGS.get(foe.getUUID());
        if (bookings == null) return true;
        int others = 0;
        for (Booking booking : bookings) {
            if (booking.landAt() < now - SWINGER_MEMORY_TICKS) continue;
            if (booking.guardId().equals(guardId)) return true;
            others++;
        }
        return others < maxGuardsOn(foe);
    }

    public static void book(LivingEntity foe, UUID guardId, long landAt, long now) {
        List<Booking> bookings = BOOKINGS.computeIfAbsent(foe.getUUID(), id -> new ArrayList<>());
        bookings.removeIf(b -> b.guardId().equals(guardId) || b.landAt() < now - SWINGER_MEMORY_TICKS);
        bookings.add(new Booking(guardId, landAt));
        if (now - lastPruneAt > 200) {
            lastPruneAt = now;
            BOOKINGS.values().forEach(list -> list.removeIf(b -> b.landAt() < now - SWINGER_MEMORY_TICKS));
            BOOKINGS.values().removeIf(List::isEmpty);
        }
    }
}

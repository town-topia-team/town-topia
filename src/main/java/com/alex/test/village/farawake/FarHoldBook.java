package com.alex.test.village.farawake;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongConsumer;

/**
 * Which chunks far villagers need held, and when each hold is asked for, renewed or let go.
 * No Minecraft classes (unit tested): {@link Tickets} is the one way out, and it adds or renews
 * a level 33 ticket that runs out by itself unless renewed ({@code FarAwake.HOLD_LIFESPAN}).
 *
 * <p>Each tick villages say what they need ({@link #need}); then {@link #tick} once:
 * <ol>
 *   <li>a village's need older than {@code releaseAfter} is dropped; a chunk nobody needs is
 *       forgotten (its ticket runs out by itself, nothing is removed by hand);</li>
 *   <li>held chunks are renewed every {@code renewEvery} ticks, spread over the ticks by chunk;</li>
 *   <li>at most {@code newPerTick} new chunks are held, best {@link FarRank} first, then the most
 *       recently needed, then by chunk key. No chunk is ever refused: the rest wait a tick.</li>
 * </ol>
 * The release delay keeps a body walking along a chunk line from asking for the same chunk
 * again and again. Server thread only.
 */
public final class FarHoldBook {

    public record Settings(int newPerTick, int renewEvery, int releaseAfter) {
        public Settings {
            if (newPerTick < 1 || renewEvery < 1 || releaseAfter < 1) {
                throw new IllegalArgumentException("FarHoldBook settings must be positive: " + newPerTick
                        + ", " + renewEvery + ", " + releaseAfter);
            }
        }
    }

    /** Adds a hold on a chunk, or renews it (same call: Minecraft resets the ticket's age). */
    public interface Tickets {
        void add(long chunk);
    }

    private static final class Need {
        FarRank rank;
        long last;
    }

    private static final class Entry {
        final long chunk;
        final Map<UUID, Need> needs = new HashMap<>(4);
        boolean held;
        long lastRenew;
        /** Filled by {@link #tick} for the order of new holds. */
        FarRank bestRank;
        long newestNeed;

        Entry(long chunk) {
            this.chunk = chunk;
        }
    }

    private static final Comparator<Entry> NEW_HOLD_ORDER = (a, b) -> {
        int byRank = Integer.compare(a.bestRank.ordinal(), b.bestRank.ordinal());
        if (byRank != 0) return byRank;
        int byNewest = Long.compare(b.newestNeed, a.newestNeed);
        if (byNewest != 0) return byNewest;
        return Long.compare(a.chunk, b.chunk);
    };

    private final Settings settings;
    private final Map<Long, Entry> entries = new HashMap<>();
    private final List<Entry> waiting = new ArrayList<>();
    private int heldCount;
    private int waitingCount;
    private int addedLastTick;
    private int renewedLastTick;
    private int forgottenLastTick;

    public FarHoldBook(Settings settings) {
        this.settings = settings;
    }

    public Settings settings() {
        return settings;
    }

    /**
     * {@code village} needs {@code chunk} held this tick for {@code rank}. Several calls in one
     * tick keep the best rank; the first call of a later tick sets it afresh.
     */
    public void need(long chunk, UUID village, FarRank rank, long now) {
        Entry entry = entries.get(chunk);
        if (entry == null) {
            entry = new Entry(chunk);
            entries.put(chunk, entry);
        }
        Need need = entry.needs.get(village);
        if (need == null) {
            need = new Need();
            need.rank = rank;
            entry.needs.put(village, need);
        } else if (need.last != now || rank.ordinal() < need.rank.ordinal()) {
            need.rank = rank;
        }
        need.last = now;
    }

    /** A village stopped ticking or was removed: its needs go; chunks others need stay held. */
    public void forgetVillage(UUID village) {
        for (Entry entry : entries.values()) entry.needs.remove(village);
    }

    /** The system was turned off or the level is going: nothing is renewed again. */
    public void forgetAll() {
        entries.clear();
        waiting.clear();
        heldCount = 0;
        waitingCount = 0;
    }

    /** Once a tick, after every {@link #need} of the tick. */
    public void tick(long now, Tickets tickets) {
        addedLastTick = 0;
        renewedLastTick = 0;
        forgottenLastTick = 0;
        heldCount = 0;
        waiting.clear();

        Iterator<Entry> entryIt = entries.values().iterator();
        while (entryIt.hasNext()) {
            Entry entry = entryIt.next();
            FarRank best = null;
            long newest = Long.MIN_VALUE;
            Iterator<Need> needIt = entry.needs.values().iterator();
            while (needIt.hasNext()) {
                Need need = needIt.next();
                if (now - need.last >= settings.releaseAfter()) {
                    needIt.remove();
                    continue;
                }
                if (best == null || need.rank.ordinal() < best.ordinal()) best = need.rank;
                if (need.last > newest) newest = need.last;
            }
            if (best == null) {
                if (entry.held) forgottenLastTick++;
                entryIt.remove();
                continue;
            }
            entry.bestRank = best;
            entry.newestNeed = newest;
            if (entry.held) {
                heldCount++;
                if (now - entry.lastRenew >= settings.renewEvery()) {
                    tickets.add(entry.chunk);
                    entry.lastRenew = now;
                    renewedLastTick++;
                }
            } else {
                waiting.add(entry);
            }
        }

        if (waiting.isEmpty()) {
            waitingCount = 0;
            return;
        }
        if (waiting.size() > 1) waiting.sort(NEW_HOLD_ORDER);
        int take = Math.min(settings.newPerTick(), waiting.size());
        for (int i = 0; i < take; i++) {
            Entry entry = waiting.get(i);
            entry.held = true;
            // Spread later renewals over the ticks, so a village woken at once does not
            // renew every chunk in the same tick forever after.
            entry.lastRenew = now - Math.floorMod(Long.hashCode(entry.chunk), settings.renewEvery());
            tickets.add(entry.chunk);
            addedLastTick++;
            heldCount++;
        }
        waitingCount = waiting.size() - take;
        waiting.clear();
    }

    public boolean held(long chunk) {
        Entry entry = entries.get(chunk);
        return entry != null && entry.held;
    }

    /** Chunks held after the last {@link #tick}. */
    public int heldCount() {
        return heldCount;
    }

    /** Held chunks this village needs (one chunk two villages need counts for both). */
    public int heldCount(UUID village) {
        int count = 0;
        for (Entry entry : entries.values()) {
            if (entry.held && entry.needs.containsKey(village)) count++;
        }
        return count;
    }

    /** Needed chunks not held yet after the last {@link #tick}. */
    public int waitingCount() {
        return waitingCount;
    }

    public int addedLastTick() {
        return addedLastTick;
    }

    public int renewedLastTick() {
        return renewedLastTick;
    }

    public int forgottenLastTick() {
        return forgottenLastTick;
    }

    public void forEachHeld(LongConsumer action) {
        for (Entry entry : entries.values()) {
            if (entry.held) action.accept(entry.chunk);
        }
    }
}

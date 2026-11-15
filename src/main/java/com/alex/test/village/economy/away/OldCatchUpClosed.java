package com.alex.test.village.economy.away;

import com.alex.test.village.VillageManager;
import com.alex.test.village.economy.CatchUp;
import com.alex.test.village.economy.ReconciliationSummary;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A catch-up the old calculator (deleted in round 12) saved part done. It paid
 * each villager as it went, so running the days again would pay twice: it is
 * closed as done, with a log line. Only the end is still done: the skip of the
 * uncounted days ({@link AwaySkip}) and the welcome-back lines from what it had
 * made. Saved as it came, so a restart before its one step closes it the same way.
 */
public final class OldCatchUpClosed implements CatchUp {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final UUID villageId;
    private final CompoundTag saved;
    private final String phase;
    private final long awayTicks;
    private final long countedTicks;
    private final long villageElapsedTicks;
    private final Map<Item, Integer> produced = new LinkedHashMap<>();
    private final Map<Item, Integer> consumed = new LinkedHashMap<>();
    private final Set<UUID> notLoaded = new LinkedHashSet<>();
    private boolean uncountedSkipped;

    private OldCatchUpClosed(UUID villageId, CompoundTag saved) {
        this.villageId = villageId;
        this.saved = saved.copy();
        // The old calculator's tag names (economy/AwayCatchUp, copy in backups/away-calculator-2026-10-22/round-12).
        this.phase = saved.getString("phase");
        this.awayTicks = Math.max(0L, saved.getLong("away"));
        this.countedTicks = Math.max(0L, saved.getLong("counted"));
        this.villageElapsedTicks = Math.max(0L, saved.getLong("villageElapsed"));
        readCounts(saved.getCompound("produced"), produced);
        readCounts(saved.getCompound("consumed"), consumed);
    }

    /**
     * The old run in a save, closed; null when it had not begun (still at its
     * start, nothing paid yet): the village then catches up on the new calculator.
     */
    @Nullable
    public static OldCatchUpClosed load(UUID villageId, CompoundTag tag) {
        String phase = tag.getString("phase");
        if (phase.isEmpty() || phase.equals("START")) {
            LOGGER.info("[AwayRun] village {}: the old calculator's saved catch-up had not begun; the new one runs",
                    AwayRun.shortId(villageId));
            return null;
        }
        return new OldCatchUpClosed(villageId, tag);
    }

    @Override
    public boolean step(ServerLevel level, VillageManager mgr, long deadlineNanos) {
        LOGGER.info("[AwayRun] village {}: a catch-up the old calculator saved part done (at {}) is closed as done;"
                        + " it paid as it went, so its days are not worked again ({} of {} tick(s) counted)",
                AwayRun.shortId(villageId), phase, countedTicks, awayTicks);
        AwaySkip.skipUncounted(level, mgr, this);
        return true;
    }

    @Override public CompoundTag save() { return saved.copy(); }
    @Override public long awayTicks() { return awayTicks; }
    @Override public long countedTicks() { return countedTicks; }
    @Override public long uncountedTicks() { return Math.max(0L, villageElapsedTicks - countedTicks); }
    @Override public Set<UUID> notLoaded() { return notLoaded; }
    @Override public Map<Item, Integer> made() { return AwayRun.net(produced, consumed); }
    /** Out of food at the old meals step, if it got that far. */
    @Override public int starvingNow() { return saved.getInt("hungry"); }

    @Override
    public boolean claimUncountedSkip() {
        if (uncountedSkipped) return false;
        uncountedSkipped = true;
        return true;
    }

    @Override
    public ReconciliationSummary summary() {
        List<String> notes = new ArrayList<>();
        for (Tag note : saved.getList("notes", Tag.TAG_STRING)) notes.add(note.getAsString());
        notes.add("Old calculator's catch-up closed as done at " + phase + " (round 12).");
        return new ReconciliationSummary(countedTicks, saved.getInt("processed"), saved.getFloat("hunger"),
                saved.getInt("meals"), saved.getInt("starvation"), byRegistryKey(produced), byRegistryKey(consumed),
                0, List.copyOf(notes), awayTicks, saved.getInt("hungry"));
    }

    private static void readCounts(CompoundTag tag, Map<Item, Integer> into) {
        for (String key : tag.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(key);
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                into.merge(BuiltInRegistries.ITEM.get(id), tag.getInt(key), Integer::sum);
            }
        }
    }

    private static Map<String, Integer> byRegistryKey(Map<Item, Integer> byItem) {
        Map<String, Integer> result = new TreeMap<>();
        byItem.forEach((item, count) -> result.put(BuiltInRegistries.ITEM.getKey(item).toString(), count));
        return result;
    }
}

package com.alex.test.village.takeover;

import com.alex.test.village.villagers.core.Profession;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The generated villages of one dimension that have been dealt with.
 *
 * <p>The fresh list holds the villages that were brand new when their chunks
 * first loaded; no other generated village is ever changed. The old list holds
 * villages found to have been loaded before (their middle was a full chunk on
 * disk already), which stay vanilla for good. The handled list holds every
 * generated village that was taken, or left alone for good; the left-alone
 * list says which of those stayed vanilla, so their waiting villagers are
 * turned back into vanilla ones whenever they load. A village whose setup
 * failed is kept off for the rest of the session and tried again after the
 * next world load, until it has failed {@link VanillaVillageTakeover}'s limit
 * of times; then it is left alone. The area list holds the ground of each
 * village that was taken, so a waiting villager whose chunk loads later joins
 * it. And it keeps each village's rolled size and counts the waiting settlers
 * made for it, so none gets more villagers than its size.
 */
final class TakenVillages extends SavedData {

    /**
     * Not the first name: the first build saved failed setups as handled for
     * good, so the villages it tried would never have been tried again.
     */
    private static final String DATA_NAME = "dynamicvillages_generated_villages";
    private static final SavedData.Factory<TakenVillages> FACTORY =
            new SavedData.Factory<>(TakenVillages::new, TakenVillages::load, null);

    /** The ground of one taken village and the village that now owns it. */
    /**
     * The ground of one taken village, the village that now owns it, and the
     * jobs it was set up with (farmer first), so a settler arriving late takes
     * the first of them still missing.
     */
    record Area(BoundingBox box, UUID villageId, List<Profession> jobs) {}

    /**
     * Start chunks of brand-new generated villages: their middle chunk loaded
     * for the very first time, or never had been when one of their villagers
     * appeared. Only these ever wake up.
     */
    private final LongOpenHashSet fresh = new LongOpenHashSet();
    /** Start chunks of villages that were loaded before: vanilla for good. */
    private final LongOpenHashSet old = new LongOpenHashSet();
    /** Start chunks of generated villages taken or left alone for good. */
    private final LongOpenHashSet handled = new LongOpenHashSet();
    /** Of those, the ones left alone: vanilla, their waiting villagers turned back. */
    private final LongOpenHashSet leftAlone = new LongOpenHashSet();
    /** Start chunks whose setup failed this session; never saved. */
    private final LongOpenHashSet failedThisSession = new LongOpenHashSet();
    /** Failed setups per village, over every session. */
    private final Long2IntOpenHashMap failures = new Long2IntOpenHashMap();
    /** Waiting settlers made so far per generated village, by start chunk; dead ones taken off. */
    private final Long2IntOpenHashMap settlers = new Long2IntOpenHashMap();
    /** How many villagers each generated village gets, rolled once per village. */
    private final Long2IntOpenHashMap sizes = new Long2IntOpenHashMap();
    /** How well off each generated village is, rolled once per village. */
    private final Long2IntOpenHashMap wealth = new Long2IntOpenHashMap();
    private final List<Area> areas = new ArrayList<>();

    static TakenVillages get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    boolean isFresh(ChunkPos start) {
        return fresh.contains(start.toLong());
    }

    void markFresh(ChunkPos start) {
        if (fresh.add(start.toLong())) setDirty();
    }

    boolean isOld(ChunkPos start) {
        return old.contains(start.toLong());
    }

    void markOld(ChunkPos start) {
        if (old.add(start.toLong())) setDirty();
    }

    /** Whether the village should not be tried now. */
    boolean isHandled(ChunkPos start) {
        return handled.contains(start.toLong()) || failedThisSession.contains(start.toLong());
    }

    /** Taken, or left alone for good; a failed try this session does not count. */
    boolean isDone(ChunkPos start) {
        return handled.contains(start.toLong());
    }

    /** Left alone for good: stays vanilla. */
    boolean isLeftAlone(ChunkPos start) {
        return leftAlone.contains(start.toLong());
    }

    int settlers(ChunkPos start) {
        return settlers.get(start.toLong());
    }

    void addSettler(ChunkPos start) {
        settlers.addTo(start.toLong(), 1);
        setDirty();
    }

    /** A waiting settler died before its village woke: it will not be coming. */
    void removeSettler(ChunkPos start) {
        long key = start.toLong();
        if (settlers.get(key) <= 0) return;
        settlers.addTo(key, -1);
        setDirty();
    }

    /** No more settlers for this village: it has all the villagers it gets. */
    void markFull(ChunkPos start, int size) {
        if (settlers.get(start.toLong()) >= size) return;
        settlers.put(start.toLong(), size);
        setDirty();
    }

    /** The village's size, rolled the first time it is asked for and kept from then on. */
    int villageSize(ChunkPos start, RandomSource random) {
        long key = start.toLong();
        if (!sizes.containsKey(key)) {
            sizes.put(key, VanillaVillagerSwap.rollVillageSize(random));
            setDirty();
        }
        // Rolled before the limits changed (up to 8 once): kept within them.
        return Math.max(VanillaVillagerSwap.MIN_VILLAGERS,
                Math.min(VanillaVillagerSwap.MAX_VILLAGERS, sizes.get(key)));
    }

    /** The village's wealth, rolled the first time it is asked for and kept from then on. */
    VillageCharacter.Wealth wealth(ChunkPos start, RandomSource random) {
        long key = start.toLong();
        if (!wealth.containsKey(key)) {
            wealth.put(key, VillageCharacter.rollWealth(random).ordinal());
            setDirty();
        }
        return VillageCharacter.Wealth.byId(wealth.get(key));
    }

    void markHandled(ChunkPos start) {
        if (handled.add(start.toLong())) setDirty();
    }

    void markLeftAlone(ChunkPos start) {
        markHandled(start);
        if (leftAlone.add(start.toLong())) setDirty();
    }

    void markFailed(ChunkPos start) {
        failedThisSession.add(start.toLong());
    }

    /** Counts one more failed setup; returns how many there have been. */
    int addFailure(ChunkPos start) {
        int count = failures.addTo(start.toLong(), 1) + 1;
        setDirty();
        return count;
    }

    void addArea(BoundingBox box, UUID villageId, List<Profession> jobs) {
        for (Area area : areas) {
            if (area.box().equals(box) && area.villageId().equals(villageId)) return;
        }
        areas.add(new Area(box, villageId, List.copyOf(jobs)));
        setDirty();
    }

    /** The taken village whose ground holds {@code pos}, if any. */
    @Nullable
    Area areaAt(BlockPos pos) {
        for (Area area : areas) {
            if (area.box().isInside(pos)) return area;
        }
        return null;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray("Fresh", fresh.toLongArray());
        tag.putLongArray("Old", old.toLongArray());
        tag.putLongArray("Handled", handled.toLongArray());
        tag.putLongArray("LeftAlone", leftAlone.toLongArray());
        saveCounts(tag, failures, "FailureVillages", "Failures");
        saveCounts(tag, settlers, "SettlerVillages", "SettlerCounts");
        saveCounts(tag, sizes, "SizeVillages", "Sizes");
        saveCounts(tag, wealth, "WealthVillages", "Wealth");
        ListTag list = new ListTag();
        for (Area area : areas) {
            CompoundTag entry = new CompoundTag();
            BoundingBox box = area.box();
            entry.putIntArray("Box", new int[] {
                    box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()});
            entry.putUUID("Village", area.villageId());
            StringBuilder jobs = new StringBuilder();
            for (Profession job : area.jobs()) {
                if (!jobs.isEmpty()) jobs.append(',');
                jobs.append(job.name());
            }
            entry.putString("Jobs", jobs.toString());
            list.add(entry);
        }
        tag.put("Areas", list);
        return tag;
    }

    private static void saveCounts(CompoundTag tag, Long2IntOpenHashMap counts, String keysName, String valuesName) {
        long[] keys = counts.keySet().toLongArray();
        int[] values = new int[keys.length];
        for (int i = 0; i < keys.length; i++) values[i] = counts.get(keys[i]);
        tag.putLongArray(keysName, keys);
        tag.putIntArray(valuesName, values);
    }

    private static void loadCounts(CompoundTag tag, Long2IntOpenHashMap counts, String keysName, String valuesName) {
        long[] keys = tag.getLongArray(keysName);
        int[] values = tag.getIntArray(valuesName);
        for (int i = 0; i < Math.min(keys.length, values.length); i++) counts.put(keys[i], values[i]);
    }

    private static TakenVillages load(CompoundTag tag, HolderLookup.Provider registries) {
        TakenVillages data = new TakenVillages();
        for (long packed : tag.getLongArray("Fresh")) data.fresh.add(packed);
        for (long packed : tag.getLongArray("Old")) data.old.add(packed);
        for (long packed : tag.getLongArray("Handled")) data.handled.add(packed);
        for (long packed : tag.getLongArray("LeftAlone")) data.leftAlone.add(packed);
        loadCounts(tag, data.failures, "FailureVillages", "Failures");
        loadCounts(tag, data.settlers, "SettlerVillages", "SettlerCounts");
        loadCounts(tag, data.sizes, "SizeVillages", "Sizes");
        loadCounts(tag, data.wealth, "WealthVillages", "Wealth");
        for (Tag element : tag.getList("Areas", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) element;
            int[] box = entry.getIntArray("Box");
            if (box.length != 6 || !entry.hasUUID("Village")) continue;
            // Saved before jobs were kept: the starter jobs of the time.
            List<Profession> jobs = new ArrayList<>();
            if (entry.contains("Jobs")) {
                for (String name : entry.getString("Jobs").split(",")) {
                    if (name.isEmpty()) continue;
                    Profession job = Profession.byName(name);
                    if (job != Profession.NONE) jobs.add(job);
                }
            } else {
                jobs.add(Profession.FARMER);
                jobs.add(Profession.LUMBERJACK);
            }
            data.areas.add(new Area(new BoundingBox(box[0], box[1], box[2], box[3], box[4], box[5]),
                    entry.getUUID("Village"), List.copyOf(jobs)));
        }
        return data;
    }
}

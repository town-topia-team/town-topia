package com.alex.test.village.villagers.miner;

import com.alex.test.village.villagers.miner.settings.MinerSettingsSchema;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One mine's own tunnel lengths and pause switch, as the player sets them in
 * its Mine screen.
 *
 * <p>Side tunnels either follow the village's number (the Mayor's "Longest
 * side tunnel") or are this mine's own, never longer than that number, left and right apart: left and right
 * as seen standing at the token looking into the rock. The main tunnel length
 * is per mine only; the village has no number for it. A new mine follows the
 * village and runs the full {@link MineShaftPlan#MAX_MAIN_LENGTH}.
 *
 * @param sidesFollowVillage the side tunnels take the village's number; then
 *                           {@code left} and {@code right} are not used
 * @param left               side tunnel length on the left, 0 to 12
 * @param right              side tunnel length on the right, 0 to 12
 * @param mainLength         main tunnel length, 6 to 96 in steps of 6
 * @param paused             miners and druids leave this mine alone
 */
public record MineSettings(boolean sidesFollowVillage, int left, int right, int mainLength, boolean paused) {

    /** The main tunnel length moves in steps of this many blocks. */
    public static final int MAIN_STEP = 6;

    public static final MineSettings DEFAULT = new MineSettings(true,
            MinerSettingsSchema.DEFAULT_SIDE_BRANCH_DISTANCE, MinerSettingsSchema.DEFAULT_SIDE_BRANCH_DISTANCE,
            MineShaftPlan.MAX_MAIN_LENGTH, false);

    /** The shape a mine is dug to: its own settings with the village's number filled in. */
    public record Shape(int left, int right, int mainLength, boolean paused) {}

    /**
     * Within the limits, the main length on a step. While the sides follow the
     * village their own numbers are reset, so two settings that dig the same
     * mine are equal (and a default one is not stored).
     */
    public MineSettings clamped() {
        int main = Math.round(mainLength / (float) MAIN_STEP) * MAIN_STEP;
        main = Math.max(MAIN_STEP, Math.min(MineShaftPlan.MAX_MAIN_LENGTH, main));
        if (sidesFollowVillage) {
            return new MineSettings(true, DEFAULT.left, DEFAULT.right, main, paused);
        }
        return new MineSettings(false, side(left), side(right), main, paused);
    }

    /**
     * The shape to dig, with {@code villageSides} for sides that follow the village. The village's
     * number is also the most a mine's own sides get (user 10-18: the Mayor's number wins). The own
     * numbers stay stored, so raising the Mayor's number again gives a mine back its own lengths.
     */
    public Shape resolve(int villageSides) {
        MineSettings settings = clamped();
        int village = side(villageSides);
        return settings.sidesFollowVillage
                ? new Shape(village, village, settings.mainLength, settings.paused)
                : new Shape(Math.min(settings.left, village), Math.min(settings.right, village),
                        settings.mainLength, settings.paused);
    }

    private static int side(int length) {
        return Math.max(0, Math.min(MinerSettingsSchema.MAX_SIDE_BRANCH_DISTANCE, length));
    }

    /**
     * Every mine's settings in one village, by the mine's token frame id.
     * Only mines changed from {@link #DEFAULT} have an entry.
     */
    public static final class Book {
        private static final String TAG_MINES = "mines";
        private static final String TAG_ID = "id";
        private static final String TAG_FOLLOW = "follow";
        private static final String TAG_LEFT = "left";
        private static final String TAG_RIGHT = "right";
        private static final String TAG_MAIN = "main";
        private static final String TAG_PAUSED = "paused";

        private final Map<UUID, MineSettings> byMine = new LinkedHashMap<>();

        /** This mine's settings, {@link #DEFAULT} when it was never changed. */
        public MineSettings get(UUID mineId) {
            return byMine.getOrDefault(mineId, DEFAULT);
        }

        /** Stores this mine's settings (clamped); true when anything changed. */
        public boolean set(UUID mineId, MineSettings settings) {
            MineSettings next = settings.clamped();
            if (next.equals(get(mineId))) return false;
            if (next.equals(DEFAULT)) byMine.remove(mineId);
            else byMine.put(mineId, next);
            return true;
        }

        /**
         * The settings of the mines in {@code keep}; the rest belong to mines
         * that are gone and are left out. {@code keep} comes from the saved
         * mine markers, never the live building list: that one is briefly
         * empty on joining, which would wipe every mine's settings.
         */
        public CompoundTag save(Set<UUID> keep) {
            ListTag mines = new ListTag();
            for (Map.Entry<UUID, MineSettings> entry : byMine.entrySet()) {
                if (!keep.contains(entry.getKey())) continue;
                MineSettings settings = entry.getValue();
                CompoundTag tag = new CompoundTag();
                tag.putUUID(TAG_ID, entry.getKey());
                tag.putBoolean(TAG_FOLLOW, settings.sidesFollowVillage());
                tag.putInt(TAG_LEFT, settings.left());
                tag.putInt(TAG_RIGHT, settings.right());
                tag.putInt(TAG_MAIN, settings.mainLength());
                tag.putBoolean(TAG_PAUSED, settings.paused());
                mines.add(tag);
            }
            CompoundTag tag = new CompoundTag();
            tag.put(TAG_MINES, mines);
            return tag;
        }

        /** Replaces everything with what {@link #save} wrote; an empty tag leaves every mine on default. */
        public void load(CompoundTag tag) {
            byMine.clear();
            ListTag mines = tag.getList(TAG_MINES, Tag.TAG_COMPOUND);
            for (int i = 0; i < mines.size(); i++) {
                CompoundTag entry = mines.getCompound(i);
                if (!entry.hasUUID(TAG_ID)) continue;
                set(entry.getUUID(TAG_ID), new MineSettings(
                        entry.getBoolean(TAG_FOLLOW), entry.getInt(TAG_LEFT), entry.getInt(TAG_RIGHT),
                        entry.contains(TAG_MAIN) ? entry.getInt(TAG_MAIN) : MineShaftPlan.MAX_MAIN_LENGTH,
                        entry.getBoolean(TAG_PAUSED)));
            }
        }
    }
}

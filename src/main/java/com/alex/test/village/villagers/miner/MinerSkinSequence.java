package com.alex.test.village.villagers.miner;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Hands out miner skins in turn, so a village's miners cycle through every
 * variant before any repeats. The position survives world reloads. Same
 * scheme as {@code FarmerSkinSequence}.
 *
 * <p>A skin brings its own hat and extras with it ({@code VillagerGeoModel.MINER_OUTFITS}),
 * so two miners never share a hat until all three are in use.
 */
public final class MinerSkinSequence extends SavedData {

    /** Number of miner skins; index order is fixed by {@code VillagerGeoModel}. */
    public static final int SKIN_COUNT = 3;

    private static final String DATA_NAME = "dynamicvillages_miner_skins";
    private static final SavedData.Factory<MinerSkinSequence> FACTORY =
            new SavedData.Factory<>(MinerSkinSequence::new, MinerSkinSequence::load, null);

    private int lastSkin = -1;

    private static MinerSkinSequence get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /**
     * The next skin: 0 coal miner, 1 goggles and lantern, 2 prospector.
     * (The modern hard-hat miner was removed 10-17.) A world's first miner starts at a random place.
     */
    public static int nextSkin(ServerLevel level) {
        MinerSkinSequence sequence = get(level);
        int skin = sequence.lastSkin < 0
                ? level.getRandom().nextInt(SKIN_COUNT)
                : (sequence.lastSkin + 1) % SKIN_COUNT;
        sequence.lastSkin = skin;
        sequence.setDirty();
        return skin;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("LastSkin", lastSkin);
        return tag;
    }

    private static MinerSkinSequence load(CompoundTag tag, HolderLookup.Provider registries) {
        MinerSkinSequence sequence = new MinerSkinSequence();
        int skin = tag.getInt("LastSkin");
        sequence.lastSkin = skin >= 0 && skin < SKIN_COUNT ? skin : -1;
        return sequence;
    }
}

package com.alex.test.village.villagers.farmer;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Hands out farmer skins in turn, so a village's farmers cycle through every
 * variant before any repeats. The position survives world reloads.
 *
 * <p>Farmers from before skins were stored never come through here: they keep
 * the face they were already wearing (see {@code NpcEntity#legacyFarmerSkin}).
 */
public final class FarmerSkinSequence extends SavedData {

    /** Number of farmer skins; index order is fixed by {@code VillagerGeoModel}. */
    public static final int SKIN_COUNT = 4;

    private static final String DATA_NAME = "dynamicvillages_farmer_skins";
    private static final SavedData.Factory<FarmerSkinSequence> FACTORY =
            new SavedData.Factory<>(FarmerSkinSequence::new, FarmerSkinSequence::load, null);

    private int lastSkin = -1;

    private static FarmerSkinSequence get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /**
     * The next skin: 0 female 1, 1 female 2, 2 gay 1, 3 male 2. A world's
     * first farmer starts at a random place in the cycle.
     */
    public static int nextSkin(ServerLevel level) {
        FarmerSkinSequence sequence = get(level);
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

    private static FarmerSkinSequence load(CompoundTag tag, HolderLookup.Provider registries) {
        FarmerSkinSequence sequence = new FarmerSkinSequence();
        int skin = tag.getInt("LastSkin");
        sequence.lastSkin = skin >= 0 && skin < SKIN_COUNT ? skin : -1;
        return sequence;
    }
}

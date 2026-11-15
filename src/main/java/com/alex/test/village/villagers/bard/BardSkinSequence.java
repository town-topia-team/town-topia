package com.alex.test.village.villagers.bard;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Hands out bard skins in turn, so a village's bards cycle through every
 * variant before any repeats. The position survives world reloads. Same
 * scheme as {@code FarmerSkinSequence}.
 *
 * <p>A skin brings its own hat and extras with it ({@code VillagerGeoModel.BARD_OUTFITS}),
 * so two bards never share a hat until all four are in use.
 */
public final class BardSkinSequence extends SavedData {

    /** Number of bard skins; index order is fixed by {@code VillagerGeoModel}. */
    public static final int SKIN_COUNT = 4;

    private static final String DATA_NAME = "dynamicvillages_bard_skins";
    private static final SavedData.Factory<BardSkinSequence> FACTORY =
            new SavedData.Factory<>(BardSkinSequence::new, BardSkinSequence::load, null);

    private int lastSkin = -1;

    private static BardSkinSequence get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /**
     * The next skin: 0 crimson beret, 1 blue cavalier, 2 emerald cap and lute,
     * 3 violet wreath. A world's first bard starts at a random place in the cycle.
     */
    public static int nextSkin(ServerLevel level) {
        BardSkinSequence sequence = get(level);
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

    private static BardSkinSequence load(CompoundTag tag, HolderLookup.Provider registries) {
        BardSkinSequence sequence = new BardSkinSequence();
        int skin = tag.getInt("LastSkin");
        sequence.lastSkin = skin >= 0 && skin < SKIN_COUNT ? skin : -1;
        return sequence;
    }
}

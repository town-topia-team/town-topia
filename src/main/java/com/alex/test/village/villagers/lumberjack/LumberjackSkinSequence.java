package com.alex.test.village.villagers.lumberjack;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Remembers the last lumberjack skin choices across world reloads. */
public final class LumberjackSkinSequence extends SavedData {

    private static final String DATA_NAME = "dynamicvillages_lumberjack_skins";
    private static final SavedData.Factory<LumberjackSkinSequence> FACTORY =
            new SavedData.Factory<>(LumberjackSkinSequence::new, LumberjackSkinSequence::load, null);

    private int lastSkin = -1;
    private int streak;

    private static LumberjackSkinSequence get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** Returns 0 for male 1, 1 for female 1, and 2 for female 2. */
    public static int nextSkin(ServerLevel level) {
        LumberjackSkinSequence sequence = get(level);
        int roll = level.getRandom().nextInt(40);
        int skin = roll < 18 ? 0 : roll < 29 ? 1 : 2;

        if (sequence.streak >= 2 && skin == sequence.lastSkin) {
            // Keep the relative 45:27.5:27.5 weights among the two allowed skins.
            skin = switch (sequence.lastSkin) {
                case 0 -> level.getRandom().nextBoolean() ? 1 : 2;
                case 1 -> level.getRandom().nextInt(29) < 18 ? 0 : 2;
                default -> level.getRandom().nextInt(29) < 18 ? 0 : 1;
            };
        }

        sequence.streak = skin == sequence.lastSkin ? sequence.streak + 1 : 1;
        sequence.lastSkin = skin;
        sequence.setDirty();
        return skin;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("LastSkin", lastSkin);
        tag.putInt("Streak", streak);
        return tag;
    }

    private static LumberjackSkinSequence load(CompoundTag tag, HolderLookup.Provider registries) {
        LumberjackSkinSequence sequence = new LumberjackSkinSequence();
        int skin = tag.getInt("LastSkin");
        sequence.lastSkin = skin >= 0 && skin < 3 ? skin : -1;
        sequence.streak = sequence.lastSkin < 0 ? 0 : Math.clamp(tag.getInt("Streak"), 0, 2);
        return sequence;
    }
}

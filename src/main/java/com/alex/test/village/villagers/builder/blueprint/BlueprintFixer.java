package com.alex.test.village.villagers.builder.blueprint;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Updates an older file's block ids and block entity data to this Minecraft
 * version with vanilla's own DataFixer, so e.g. a 1.16 {@code grass_path}
 * becomes {@code dirt_path} instead of an unknown block.
 *
 * <p>Instead of a fixer per piece, the palette and the block entities are put
 * into one small structure-shaped tag (the STRUCTURE type's schema knows that
 * palette entries are block states and that {@code blocks[].nbt} is a block
 * entity) and run through once.
 *
 * <p>The first call builds vanilla's fixer rules, which takes a few seconds:
 * callers read blueprints off the render and server threads.
 */
public final class BlueprintFixer {
    private BlueprintFixer() {
    }

    /** Data version of Minecraft 1.13, the first one with today's block ids. */
    public static final int FIRST_SUPPORTED_VERSION = 1519;

    public static int currentVersion() {
        return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
    }

    /** Refuses files from before 1.13; their numeric block ids are a different world. */
    public static void checkSupported(int dataVersion) throws BlueprintException {
        if (dataVersion < FIRST_SUPPORTED_VERSION) {
            throw new BlueprintException("This file is from before Minecraft 1.13, so it can't be read.");
        }
    }

    /**
     * Updates {@code raw.palette} and {@code raw.blockEntities} in place. Files
     * from this version (or a newer one, which the fixer cannot go back from)
     * are left as they are.
     */
    static void fix(BlueprintReader.Raw raw) throws BlueprintException {
        checkSupported(raw.dataVersion);
        if (raw.dataVersion >= currentVersion()) {
            return;
        }

        CompoundTag structure = new CompoundTag();
        ListTag palette = new ListTag();
        for (CompoundTag entry : raw.palette) {
            palette.add(entry);
        }
        structure.put("palette", palette);
        // The cell index rides along in pos[0] so each block entity finds its way back.
        ListTag blocks = new ListTag();
        for (Map.Entry<Integer, CompoundTag> e : raw.blockEntities.entrySet()) {
            int cell = e.getKey();
            CompoundTag block = new CompoundTag();
            ListTag pos = new ListTag();
            pos.add(IntTag.valueOf(cell));
            pos.add(IntTag.valueOf(0));
            pos.add(IntTag.valueOf(0));
            block.put("pos", pos);
            block.putInt("state", Math.max(0, raw.cells[cell]));
            block.put("nbt", e.getValue());
            blocks.add(block);
        }
        structure.put("blocks", blocks);
        structure.put("entities", new ListTag());

        CompoundTag fixed;
        try {
            fixed = DataFixTypes.STRUCTURE.updateToCurrentVersion(DataFixers.getDataFixer(), structure, raw.dataVersion);
        } catch (RuntimeException e) {
            throw new BlueprintException("This blueprint could not be updated to this Minecraft version.", e);
        }

        ListTag fixedPalette = fixed.getList("palette", Tag.TAG_COMPOUND);
        if (fixedPalette.size() != raw.palette.size()) {
            throw new BlueprintException("This blueprint could not be updated to this Minecraft version.");
        }
        List<CompoundTag> newPalette = new ArrayList<>(fixedPalette.size());
        for (int i = 0; i < fixedPalette.size(); i++) {
            newPalette.add(fixedPalette.getCompound(i));
        }
        Map<Integer, CompoundTag> newBlockEntities = new HashMap<>();
        ListTag fixedBlocks = fixed.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < fixedBlocks.size(); i++) {
            CompoundTag block = fixedBlocks.getCompound(i);
            ListTag pos = block.getList("pos", Tag.TAG_INT);
            if (pos.size() == 3 && block.contains("nbt", Tag.TAG_COMPOUND)) {
                newBlockEntities.put(pos.getInt(0), block.getCompound("nbt"));
            }
        }
        raw.palette = newPalette;
        raw.blockEntities = newBlockEntities;
    }
}

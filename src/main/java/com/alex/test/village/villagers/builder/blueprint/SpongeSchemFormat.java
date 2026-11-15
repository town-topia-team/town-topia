package com.alex.test.village.villagers.builder.blueprint;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * WorldEdit's Sponge schematics ({@code .schem}, and {@code .schematic} from
 * newer tools). v1 and v2 keep {@code Palette} and {@code BlockData} at the
 * top; v3 nests them as {@code Blocks.Palette} and {@code Blocks.Data}.
 *
 * <p>The palette maps state text ({@code minecraft:oak_stairs[facing=north]})
 * to a number; the block data is one varint per cell in X, then Z, then Y
 * order, which is {@link Blueprint}'s own order. Every cell is listed, so air
 * here clears the block. Sizes are unsigned shorts.
 */
final class SpongeSchemFormat {
    private SpongeSchemFormat() {
    }

    /** @param schem the schematic compound (for v3, the {@code Schematic} child of the root) */
    static BlueprintReader.Raw read(CompoundTag schem) throws BlueprintException {
        // v1 files have no DataVersion; they were written by WorldEdit for 1.13.
        int dataVersion = schem.contains("DataVersion", Tag.TAG_ANY_NUMERIC)
                ? schem.getInt("DataVersion") : BlueprintFixer.FIRST_SUPPORTED_VERSION;
        BlueprintFixer.checkSupported(dataVersion);

        int sx = schem.getShort("Width") & 0xFFFF;
        int sy = schem.getShort("Height") & 0xFFFF;
        int sz = schem.getShort("Length") & 0xFFFF;
        BlueprintLimits.checkSize(sx, sy, sz);

        boolean v3 = schem.contains("Blocks", Tag.TAG_COMPOUND);
        CompoundTag paletteTag;
        byte[] data;
        ListTag blockEntities;
        if (v3) {
            CompoundTag blocks = schem.getCompound("Blocks");
            paletteTag = blocks.getCompound("Palette");
            data = blocks.getByteArray("Data");
            blockEntities = blocks.getList("BlockEntities", Tag.TAG_COMPOUND);
        } else {
            paletteTag = schem.getCompound("Palette");
            data = schem.getByteArray("BlockData");
            // v1 called them TileEntities.
            blockEntities = schem.contains("BlockEntities", Tag.TAG_LIST)
                    ? schem.getList("BlockEntities", Tag.TAG_COMPOUND)
                    : schem.getList("TileEntities", Tag.TAG_COMPOUND);
        }

        // The palette is written as text -> number; turn it into a list by number.
        int paletteSize = 0;
        for (String key : paletteTag.getAllKeys()) {
            int id = paletteTag.getInt(key);
            if (id < 0 || id >= BlueprintLimits.MAX_PALETTE) {
                BlueprintLimits.checkPalette(id + 1);
                throw BlueprintReader.damaged();
            }
            paletteSize = Math.max(paletteSize, id + 1);
        }
        List<CompoundTag> palette = new ArrayList<>(paletteSize);
        for (int i = 0; i < paletteSize; i++) {
            palette.add(new CompoundTag()); // A number nobody uses stays nameless, i.e. unknown.
        }
        for (String key : paletteTag.getAllKeys()) {
            palette.set(paletteTag.getInt(key), stateTag(key));
        }

        BlueprintReader.Raw raw = new BlueprintReader.Raw(Blueprint.SourceFormat.SPONGE, sx, sy, sz, palette, dataVersion);
        int[] values;
        try {
            values = VarInts.decode(data, raw.cells.length);
        } catch (IllegalArgumentException e) {
            throw new BlueprintException("This file is damaged, so it can't be read.", e);
        }
        for (int i = 0; i < values.length; i++) {
            if (values[i] >= paletteSize) {
                throw BlueprintReader.damaged();
            }
            raw.cells[i] = values[i];
        }

        for (int i = 0; i < blockEntities.size(); i++) {
            CompoundTag entry = blockEntities.getCompound(i);
            int[] pos = entry.getIntArray("Pos");
            if (pos.length != 3 || !raw.contains(pos[0], pos[1], pos[2])) {
                continue;
            }
            String id = entry.contains("Id", Tag.TAG_STRING) ? entry.getString("Id") : entry.getString("id");
            if (id.isEmpty()) {
                continue; // Without an id the game cannot tell what the data is for.
            }
            CompoundTag nbt;
            if (v3) {
                nbt = entry.getCompound("Data").copy();
            } else {
                nbt = entry.copy();
                nbt.remove("Pos");
                nbt.remove("Id");
            }
            nbt.remove("x");
            nbt.remove("y");
            nbt.remove("z");
            nbt.putString("id", id);
            raw.blockEntities.put(raw.index(pos[0], pos[1], pos[2]), nbt);
        }
        return raw;
    }

    /** "minecraft:oak_stairs[facing=north]" -> {Name, Properties} as vanilla writes it. */
    private static CompoundTag stateTag(String text) {
        CompoundTag tag = new CompoundTag();
        BlockStateStrings.Parsed parsed = BlockStateStrings.parse(text);
        if (parsed == null) {
            return tag;
        }
        tag.putString("Name", parsed.name());
        if (!parsed.properties().isEmpty()) {
            CompoundTag props = new CompoundTag();
            for (Map.Entry<String, String> e : parsed.properties().entrySet()) {
                props.put(e.getKey(), StringTag.valueOf(e.getValue()));
            }
            tag.put("Properties", props);
        }
        return tag;
    }
}

package com.alex.test.village.villagers.builder.blueprint;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * Vanilla structure files ({@code .nbt} from a structure block, and Create's
 * schematics, which are the same format): {@code size}, a {@code palette} (or
 * {@code palettes}, of which the first is used), and a {@code blocks} list of
 * {@code {pos, state, nbt}}.
 *
 * <p>Cells the file does not list stay {@link Blueprint#VOID}: a structure
 * block leaves out structure void, so "missing" means "leave the world block".
 * Air that is listed is real and clears the block.
 */
final class VanillaStructureFormat {
    private VanillaStructureFormat() {
    }

    /** What vanilla's StructureTemplateManager assumes for a file with no version: very old. */
    private static final int NO_VERSION = 500;

    static BlueprintReader.Raw read(CompoundTag root) throws BlueprintException {
        int dataVersion = NbtUtils.getDataVersion(root, NO_VERSION);
        BlueprintFixer.checkSupported(dataVersion);

        ListTag size = root.getList("size", Tag.TAG_INT);
        if (size.size() != 3) {
            throw BlueprintReader.damaged();
        }
        int sx = size.getInt(0), sy = size.getInt(1), sz = size.getInt(2);
        BlueprintLimits.checkSize(sx, sy, sz);

        ListTag paletteTag;
        if (root.contains("palettes", Tag.TAG_LIST)) {
            // Several palettes = random variants (shipwrecks); the first is as good as any.
            ListTag palettes = root.getList("palettes", Tag.TAG_LIST);
            paletteTag = palettes.isEmpty() ? new ListTag() : palettes.getList(0);
        } else {
            paletteTag = root.getList("palette", Tag.TAG_COMPOUND);
        }
        BlueprintLimits.checkPalette(paletteTag.size());
        List<CompoundTag> palette = new ArrayList<>(paletteTag.size());
        for (int i = 0; i < paletteTag.size(); i++) {
            palette.add(paletteTag.getCompound(i).copy());
        }

        BlueprintReader.Raw raw = new BlueprintReader.Raw(Blueprint.SourceFormat.STRUCTURE, sx, sy, sz, palette, dataVersion);
        ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            ListTag pos = block.getList("pos", Tag.TAG_INT);
            int state = block.getInt("state");
            if (pos.size() != 3 || state < 0 || state >= palette.size()) {
                continue; // One broken entry is skipped; the rest of the building still counts.
            }
            int x = pos.getInt(0), y = pos.getInt(1), z = pos.getInt(2);
            if (!raw.contains(x, y, z)) {
                continue;
            }
            int cell = raw.index(x, y, z);
            raw.cells[cell] = state;
            if (block.contains("nbt", Tag.TAG_COMPOUND)) {
                CompoundTag nbt = block.getCompound("nbt").copy();
                nbt.remove("x");
                nbt.remove("y");
                nbt.remove("z");
                raw.blockEntities.put(cell, nbt);
            } else {
                raw.blockEntities.remove(cell);
            }
        }
        return raw;
    }
}

package com.alex.test.village.villagers.builder.blueprint;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a schematic file into a {@link Blueprint}: vanilla structure files
 * (also Create's), WorldEdit/Sponge schematics v1 to v3, and Litematica files.
 * The file's content decides the format, not its extension, because people
 * rename files and WorldEdit uses {@code .schematic} for two different formats.
 *
 * <p>Every failure is a {@link BlueprintException} with a sentence the player
 * can read. Slow on the first old file (vanilla's DataFixer warms up), so call
 * it off the render and server threads.
 */
public final class BlueprintReader {
    private BlueprintReader() {
    }

    /** Block entity keys that hold contents or loot; a blueprint copies the block, never what is inside. */
    private static final String[] CONTENT_KEYS = {"Items", "LootTable", "LootTableSeed", "RecordItem", "Book", "item"};
    /** A beehive's bees and a furnace's stored experience: handed out again by every build (full review round 2). */
    private static final String[] MORE_CONTENT_KEYS = {"bees", "RecipesUsed"};

    private static final String DAMAGED = "This file is damaged, so it can't be read.";
    /** This mod's own tag in a copied building's file; other tools ignore it. */
    public static final String COPY_TAG = "dynamicvillages";
    /** In {@link #COPY_TAG}: the row of the floor under the copied building's door. */
    public static final String FLOOR_KEY = "floor";

    // =========================================================================
    // Entry points
    // =========================================================================

    /**
     * Reads a whole file as it was on disk (gzip or plain NBT).
     *
     * @param nameHint the file name; becomes the blueprint's name without its extension
     */
    public static Blueprint read(byte[] bytes, String nameHint) throws BlueprintException {
        return read(readNbt(bytes), nameHint);
    }

    /** Reads an already unpacked file, e.g. one this mod saved itself. No file size check. */
    public static Blueprint read(CompoundTag root, String nameHint) throws BlueprintException {
        Raw raw;
        try {
            raw = parse(root);
        } catch (BlueprintException e) {
            throw e;
        } catch (RuntimeException e) {
            // A hand-made or cut-off file can break any getter in surprising ways.
            throw new BlueprintException(DAMAGED, e);
        }
        return assemble(raw, cleanName(nameHint), floorRow(root, raw.sizeY));
    }

    /** The floor row a copy of this mod wrote ({@link #FLOOR_KEY}); -1 when the file has none that fits. */
    private static int floorRow(CompoundTag root, int sizeY) {
        CompoundTag info = root.getCompound(COPY_TAG);
        if (!info.contains(FLOOR_KEY, Tag.TAG_INT)) return -1;
        int row = info.getInt(FLOOR_KEY);
        return row >= 0 && row < sizeY ? row : -1;
    }

    /** Unpacks the NBT with the size caps from {@link BlueprintLimits}. */
    public static CompoundTag readNbt(byte[] bytes) throws BlueprintException {
        BlueprintLimits.checkFileSize(bytes.length);
        if (bytes.length < 3) {
            throw new BlueprintException("This file is empty.");
        }
        NbtAccounter accounter = NbtAccounter.create(BlueprintLimits.MAX_NBT_BYTES);
        boolean gzip = (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
        try {
            if (gzip) {
                return NbtIo.readCompressed(new ByteArrayInputStream(bytes), accounter);
            }
            return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)), accounter);
        } catch (IOException e) {
            throw new BlueprintException("This file is not a blueprint Minecraft can read, or it is damaged.", e);
        } catch (RuntimeException e) {
            // NbtAccounterException: the file unpacks to more than MAX_NBT_BYTES or nests too deep.
            throw new BlueprintException("This file holds too much data to read safely.", e);
        }
    }

    /** Removes contents and loot from block entity data, in place. Also used when copying buildings. */
    public static void stripContents(CompoundTag blockEntity) {
        for (String key : CONTENT_KEYS) {
            blockEntity.remove(key);
        }
        if (com.alex.test.village.WalkFeel.buildStripMore) {
            for (String key : MORE_CONTENT_KEYS) {
                blockEntity.remove(key);
            }
        }
    }

    // =========================================================================
    // Format sniffing
    // =========================================================================

    private static Raw parse(CompoundTag root) throws BlueprintException {
        if (root.contains("Regions", Tag.TAG_COMPOUND)) {
            return LitematicaFormat.read(root);
        }
        if (root.contains("Schematic", Tag.TAG_COMPOUND)) {
            return SpongeSchemFormat.read(root.getCompound("Schematic"));
        }
        if (root.contains("Palette", Tag.TAG_COMPOUND) && root.contains("BlockData", Tag.TAG_BYTE_ARRAY)) {
            return SpongeSchemFormat.read(root);
        }
        if (root.contains("Blocks", Tag.TAG_BYTE_ARRAY)
                && (root.contains("Materials", Tag.TAG_STRING) || root.contains("Data", Tag.TAG_BYTE_ARRAY))) {
            // MCEdit / old WorldEdit: numeric block ids from before the 1.13 flattening.
            throw new BlueprintException("This file is from before Minecraft 1.13, so it can't be read.");
        }
        if (root.contains("size", Tag.TAG_LIST) && root.contains("blocks", Tag.TAG_LIST)) {
            return VanillaStructureFormat.read(root);
        }
        throw new BlueprintException("This file is not a blueprint this mod can read.");
    }

    // =========================================================================
    // Shared last steps
    // =========================================================================

    /**
     * What a format hands back before ids are updated and looked up: the box,
     * palette entries as {@code {Name, Properties}} tags, a raw palette index
     * per cell ({@link Blueprint#VOID} where the file has nothing), and block
     * entity data by cell index with {@code id} set and no position keys.
     */
    static final class Raw {
        final Blueprint.SourceFormat format;
        final int sizeX, sizeY, sizeZ;
        final int[] cells;
        List<CompoundTag> palette;
        Map<Integer, CompoundTag> blockEntities = new HashMap<>();
        int dataVersion;

        Raw(Blueprint.SourceFormat format, int sizeX, int sizeY, int sizeZ, List<CompoundTag> palette, int dataVersion) {
            this.format = format;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.palette = palette;
            this.dataVersion = dataVersion;
            this.cells = new int[sizeX * sizeY * sizeZ];
            Arrays.fill(cells, Blueprint.VOID);
        }

        int index(int x, int y, int z) {
            return (y * sizeZ + z) * sizeX + x;
        }

        boolean contains(int x, int y, int z) {
            return x >= 0 && y >= 0 && z >= 0 && x < sizeX && y < sizeY && z < sizeZ;
        }
    }

    /** Throws {@link #DAMAGED}; formats use it for impossible values. */
    static BlueprintException damaged() {
        return new BlueprintException(DAMAGED);
    }

    private static Blueprint assemble(Raw raw, String name, int floorRow) throws BlueprintException {
        BlueprintLimits.checkSize(raw.sizeX, raw.sizeY, raw.sizeZ);
        BlueprintLimits.checkPalette(raw.palette.size());

        // Contents go before the fixer (less to update) and again after (in case a fix moved them).
        raw.blockEntities.entrySet().removeIf(e -> raw.cells[e.getKey()] == Blueprint.VOID);
        raw.blockEntities.values().forEach(BlueprintReader::stripContents);
        BlueprintFixer.fix(raw);
        raw.blockEntities.values().forEach(BlueprintReader::stripContents);

        BlueprintPalette.Resolved resolved = BlueprintPalette.resolve(raw.palette);
        int rawSize = raw.palette.size();
        int[] remap = new int[rawSize];
        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> seen = new HashMap<>();
        for (int i = 0; i < rawSize; i++) {
            BlockState state = resolved.states()[i];
            if (state == null) {
                remap[i] = Blueprint.VOID;
                continue;
            }
            Integer at = seen.get(state);
            if (at == null) {
                at = palette.size();
                palette.add(state);
                seen.put(state, at);
            }
            remap[i] = at;
        }

        int[] usedBy = new int[rawSize];
        int[] cells = new int[raw.cells.length];
        int nonVoid = 0;
        for (int i = 0; i < cells.length; i++) {
            int p = raw.cells[i];
            if (p == Blueprint.VOID) {
                cells[i] = Blueprint.VOID;
                continue;
            }
            usedBy[p]++;
            cells[i] = remap[p];
            if (cells[i] != Blueprint.VOID) {
                nonVoid++;
            }
        }
        Map<String, Integer> unknown = new LinkedHashMap<>();
        for (int i = 0; i < rawSize; i++) {
            String id = resolved.unknownIds()[i];
            if (id != null && usedBy[i] > 0) {
                unknown.merge(id, usedBy[i], Integer::sum);
            }
        }
        if (nonVoid == 0) {
            throw new BlueprintException(unknown.isEmpty()
                    ? "This blueprint has no blocks in it."
                    : "None of the blocks in this blueprint exist in this game.");
        }
        BlueprintLimits.checkNonVoid(nonVoid);

        // Only keep data a block can actually hold: stale data on a plain block would do nothing.
        Map<Integer, CompoundTag> blockEntities = new HashMap<>();
        for (Map.Entry<Integer, CompoundTag> e : raw.blockEntities.entrySet()) {
            int p = cells[e.getKey()];
            if (p != Blueprint.VOID && palette.get(p).hasBlockEntity()) {
                blockEntities.put(e.getKey(), e.getValue());
            }
        }
        return new Blueprint(name, raw.sizeX, raw.sizeY, raw.sizeZ, palette, cells, blockEntities, unknown,
                raw.format, raw.dataVersion, floorRow);
    }

    /** "C:/x/My House.schem" -> "My House". */
    static String cleanName(String nameHint) {
        if (nameHint == null) {
            return "Blueprint";
        }
        String s = nameHint.replace('\\', '/');
        s = s.substring(s.lastIndexOf('/') + 1);
        int dot = s.lastIndexOf('.');
        if (dot > 0) {
            s = s.substring(0, dot);
        }
        s = s.trim();
        return s.isEmpty() ? "Blueprint" : s;
    }
}

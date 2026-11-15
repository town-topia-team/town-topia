package com.alex.test.village.villagers.builder.blueprint;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A building read from a schematic file, ready for the Builder: a box of cells,
 * each either a block state to place (air means "clear this block") or
 * {@link #VOID}, "leave whatever the world has here".
 *
 * <p>Immutable: the constructor copies what it is given and the getters hand
 * out copies of block entity data, so one blueprint can be shared between the
 * desk screen, the store and a build project without anyone changing it.
 *
 * <p>Cells are stored flat, X fastest, then Z, then Y ({@link #index}), the
 * order Sponge and Litematica files use, so reading them needs no reshuffle.
 * No client classes here: the dedicated server uses this too.
 */
public final class Blueprint {
    /** Cell value meaning "leave the world block": missing, structure void, jigsaw or unknown block. */
    public static final int VOID = -1;

    /** The kind of file a blueprint came from, with a name the player knows. */
    public enum SourceFormat {
        STRUCTURE("Structure file"),
        SPONGE("WorldEdit schematic"),
        LITEMATICA("Litematica schematic");

        private final String displayName;

        SourceFormat(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    private final String name;
    private final int sizeX, sizeY, sizeZ;
    private final List<BlockState> palette;
    /** Palette index per cell, or {@link #VOID}. */
    private final int[] cells;
    /** Block entity data by cell index; contents (items, loot) already removed. */
    private final Map<Integer, CompoundTag> blockEntities;
    /** Block ids this game does not have, with how many cells used them (now VOID). */
    private final Map<String, Integer> unknownBlocks;
    private final SourceFormat sourceFormat;
    /** The Minecraft data version the file was saved in, before any update. */
    private final int dataVersion;
    private final int nonVoidCount;
    /** The copied building's floor row under its door, or -1 (see {@link #floorRow}). */
    private final int floorRow;

    /**
     * @param cells palette index per cell in {@link #index} order, or {@link #VOID}
     * @param floorRow the row of a copied building's floor under its door, -1 when not known
     * @throws IllegalArgumentException when the parts do not fit together
     */
    public Blueprint(String name, int sizeX, int sizeY, int sizeZ, List<BlockState> palette, int[] cells,
                     Map<Integer, CompoundTag> blockEntities, Map<String, Integer> unknownBlocks,
                     SourceFormat sourceFormat, int dataVersion, int floorRow) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            throw new IllegalArgumentException("empty size " + sizeX + "x" + sizeY + "x" + sizeZ);
        }
        long volume = (long) sizeX * sizeY * sizeZ;
        if (cells.length != volume) {
            throw new IllegalArgumentException("cells " + cells.length + " != volume " + volume);
        }
        this.name = name;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.palette = List.copyOf(palette);
        this.cells = cells.clone();
        int count = 0;
        for (int c : this.cells) {
            if (c == VOID) {
                continue;
            }
            if (c < 0 || c >= this.palette.size()) {
                throw new IllegalArgumentException("cell points past the palette: " + c);
            }
            count++;
        }
        this.nonVoidCount = count;
        Map<Integer, CompoundTag> bes = new HashMap<>();
        for (Map.Entry<Integer, CompoundTag> e : blockEntities.entrySet()) {
            int i = e.getKey();
            // Data for a cell that places nothing would never be used.
            if (i >= 0 && i < this.cells.length && this.cells[i] != VOID) {
                bes.put(i, e.getValue().copy());
            }
        }
        this.blockEntities = Collections.unmodifiableMap(bes);
        this.unknownBlocks = Collections.unmodifiableMap(new LinkedHashMap<>(unknownBlocks));
        this.sourceFormat = sourceFormat;
        this.dataVersion = dataVersion;
        this.floorRow = floorRow >= 0 && floorRow < sizeY ? floorRow : -1;
    }

    // =========================================================================
    // Reading
    // =========================================================================

    /** Shown in lists: usually the file name without its extension. */
    public String name() {
        return name;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public int volume() {
        return cells.length;
    }

    /** Flat cell index of x,y,z; no bounds check. */
    public int index(int x, int y, int z) {
        return (y * sizeZ + z) * sizeX + x;
    }

    public boolean contains(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < sizeX && y < sizeY && z < sizeZ;
    }

    public int xOf(int index) {
        return index % sizeX;
    }

    public int yOf(int index) {
        return index / (sizeX * sizeZ);
    }

    public int zOf(int index) {
        return (index / sizeX) % sizeZ;
    }

    /** The different block states used, without duplicates. */
    public List<BlockState> palette() {
        return palette;
    }

    /** Palette index at a flat cell index, or {@link #VOID}. */
    public int paletteIndex(int index) {
        return cells[index];
    }

    /** Palette index at x,y,z, or {@link #VOID} (also outside the box). */
    public int paletteIndexAt(int x, int y, int z) {
        return contains(x, y, z) ? cells[index(x, y, z)] : VOID;
    }

    /** The state to place at x,y,z, or null where the world block is left alone. */
    @Nullable
    public BlockState stateAt(int x, int y, int z) {
        int p = paletteIndexAt(x, y, z);
        return p == VOID ? null : palette.get(p);
    }

    /** Cells the Builder would place or clear (everything but {@link #VOID}). */
    public int nonVoidCount() {
        return nonVoidCount;
    }

    /** A copy of the block entity data at x,y,z (no position, no contents), or null. */
    @Nullable
    public CompoundTag blockEntityAt(int x, int y, int z) {
        if (!contains(x, y, z)) {
            return null;
        }
        CompoundTag tag = blockEntities.get(index(x, y, z));
        return tag == null ? null : tag.copy();
    }

    /** Flat indexes of the cells that carry block entity data. */
    public Set<Integer> blockEntityIndexes() {
        return blockEntities.keySet();
    }

    /** Block ids this game does not have and how many cells used each; those cells are VOID. */
    public Map<String, Integer> unknownBlocks() {
        return unknownBlocks;
    }

    /** Total cells left empty because their block id was unknown. */
    public int unknownBlockCount() {
        int total = 0;
        for (int n : unknownBlocks.values()) {
            total += n;
        }
        return total;
    }

    public SourceFormat sourceFormat() {
        return sourceFormat;
    }

    /** The data version the file was saved in (before this mod updated it). */
    public int dataVersion() {
        return dataVersion;
    }

    /**
     * For a building copied by this mod: the row (y) of the floor under its door, so a placed
     * copy can stand that floor on the ground. -1 for every other file.
     */
    public int floorRow() {
        return floorRow;
    }

    // =========================================================================
    // Turning and mirroring
    // =========================================================================

    /**
     * A new blueprint mirrored, then turned, like a structure block does it:
     * positions move with {@link BlueprintTransform} and every state is passed
     * through {@code mirror} then {@code rotate}, so stairs and doors face the
     * new way. The result's corner is again at 0,0,0.
     */
    @SuppressWarnings("deprecation") // The level-free rotate is what StructureTemplate uses for its palette too.
    public Blueprint transformed(Rotation rotation, Mirror mirror) {
        if (rotation == Rotation.NONE && mirror == Mirror.NONE) {
            return this;
        }
        BlueprintTransform t = new BlueprintTransform(sizeX, sizeZ, mirrorCode(mirror), quarterTurns(rotation));
        int newX = t.newSizeX();
        int newZ = t.newSizeZ();

        // States first: two old states can become the same new one, so merge them.
        List<BlockState> newPalette = new ArrayList<>(palette.size());
        Map<BlockState, Integer> seen = new HashMap<>();
        int[] remap = new int[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            BlockState turned = palette.get(i).mirror(mirror).rotate(rotation);
            Integer at = seen.get(turned);
            if (at == null) {
                at = newPalette.size();
                newPalette.add(turned);
                seen.put(turned, at);
            }
            remap[i] = at;
        }

        int[] newCells = new int[cells.length];
        java.util.Arrays.fill(newCells, VOID);
        Map<Integer, CompoundTag> newBlockEntities = new HashMap<>();
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    int from = index(x, y, z);
                    int to = (y * newZ + t.mapZ(x, z)) * newX + t.mapX(x, z);
                    int p = cells[from];
                    newCells[to] = p == VOID ? VOID : remap[p];
                    CompoundTag be = blockEntities.get(from);
                    if (be != null) {
                        newBlockEntities.put(to, be);
                    }
                }
            }
        }
        // Turning and mirroring never move a row, so the floor row stays.
        return new Blueprint(name, newX, sizeY, newZ, newPalette, newCells, newBlockEntities,
                unknownBlocks, sourceFormat, dataVersion, floorRow);
    }

    private static int mirrorCode(Mirror mirror) {
        return switch (mirror) {
            case LEFT_RIGHT -> BlueprintTransform.MIRROR_LEFT_RIGHT;
            case FRONT_BACK -> BlueprintTransform.MIRROR_FRONT_BACK;
            default -> BlueprintTransform.MIRROR_NONE;
        };
    }

    private static int quarterTurns(Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> 1;
            case CLOCKWISE_180 -> 2;
            case COUNTERCLOCKWISE_90 -> 3;
            default -> 0;
        };
    }
}

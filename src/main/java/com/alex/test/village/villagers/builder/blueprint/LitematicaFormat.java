package com.alex.test.village.villagers.builder.blueprint;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Litematica's {@code .litematic}: one or more regions, each a box with its own
 * palette and a bit-packed block array ({@link PackedLongs}). All regions are
 * merged into one box; gaps between them stay {@link Blueprint#VOID}.
 *
 * <p>A region's {@code Size} may be negative on any axis (the player dragged
 * the selection backwards); its corner nearest 0 is then
 * {@code Position + Size + 1}. Block entity positions are relative to that
 * corner. Every cell of a region is listed, so air clears the block; where two
 * regions overlap, a real block wins over air.
 */
final class LitematicaFormat {
    private LitematicaFormat() {
    }

    private static final class Region {
        CompoundTag tag;
        int minX, minY, minZ;
        int sizeX, sizeY, sizeZ;
    }

    static BlueprintReader.Raw read(CompoundTag root) throws BlueprintException {
        // Files before Litematica's schematic version 5 have no data version; they are 1.13 or later.
        int dataVersion = root.contains("MinecraftDataVersion", Tag.TAG_ANY_NUMERIC)
                ? root.getInt("MinecraftDataVersion") : BlueprintFixer.FIRST_SUPPORTED_VERSION;
        BlueprintFixer.checkSupported(dataVersion);

        CompoundTag regionsTag = root.getCompound("Regions");
        List<Region> regions = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (String key : regionsTag.getAllKeys()) {
            CompoundTag tag = regionsTag.getCompound(key);
            CompoundTag position = tag.getCompound("Position");
            CompoundTag size = tag.getCompound("Size");
            int px = position.getInt("x"), py = position.getInt("y"), pz = position.getInt("z");
            int sx = size.getInt("x"), sy = size.getInt("y"), sz = size.getInt("z");
            if (sx == 0 || sy == 0 || sz == 0) {
                continue;
            }
            Region r = new Region();
            r.tag = tag;
            r.sizeX = Math.abs(sx);
            r.sizeY = Math.abs(sy);
            r.sizeZ = Math.abs(sz);
            // Check each region before it is unpacked, so a huge one fails early.
            BlueprintLimits.checkSize(r.sizeX, r.sizeY, r.sizeZ);
            r.minX = px + (sx < 0 ? sx + 1 : 0);
            r.minY = py + (sy < 0 ? sy + 1 : 0);
            r.minZ = pz + (sz < 0 ? sz + 1 : 0);
            regions.add(r);
            minX = Math.min(minX, r.minX);
            minY = Math.min(minY, r.minY);
            minZ = Math.min(minZ, r.minZ);
            maxX = Math.max(maxX, r.minX + r.sizeX - 1);
            maxY = Math.max(maxY, r.minY + r.sizeY - 1);
            maxZ = Math.max(maxZ, r.minZ + r.sizeZ - 1);
        }
        if (regions.isEmpty()) {
            throw new BlueprintException("This blueprint has no blocks in it.");
        }
        long boxX = (long) maxX - minX + 1, boxY = (long) maxY - minY + 1, boxZ = (long) maxZ - minZ + 1;
        BlueprintLimits.checkSize((int) Math.min(Integer.MAX_VALUE, boxX),
                (int) Math.min(Integer.MAX_VALUE, boxY), (int) Math.min(Integer.MAX_VALUE, boxZ));

        // One palette for all regions; identical entries are shared.
        List<CompoundTag> palette = new ArrayList<>();
        List<Boolean> isAir = new ArrayList<>();
        Map<CompoundTag, Integer> seen = new HashMap<>();
        BlueprintReader.Raw raw = new BlueprintReader.Raw(Blueprint.SourceFormat.LITEMATICA,
                (int) boxX, (int) boxY, (int) boxZ, palette, dataVersion);

        for (Region r : regions) {
            ListTag paletteTag = r.tag.getList("BlockStatePalette", Tag.TAG_COMPOUND);
            if (paletteTag.isEmpty()) {
                throw BlueprintReader.damaged();
            }
            BlueprintLimits.checkPalette(paletteTag.size());
            int[] map = new int[paletteTag.size()];
            for (int i = 0; i < paletteTag.size(); i++) {
                CompoundTag entry = paletteTag.getCompound(i).copy();
                Integer at = seen.get(entry);
                if (at == null) {
                    at = palette.size();
                    palette.add(entry);
                    isAir.add(isAirName(entry.getString("Name")));
                    seen.put(entry, at);
                    BlueprintLimits.checkPalette(palette.size());
                }
                map[i] = at;
            }

            int count = r.sizeX * r.sizeY * r.sizeZ;
            int[] values;
            try {
                values = PackedLongs.unpack(r.tag.getLongArray("BlockStates"),
                        PackedLongs.bitsFor(paletteTag.size()), count);
            } catch (IllegalArgumentException e) {
                throw new BlueprintException("This file is damaged, so it can't be read.", e);
            }
            int ox = r.minX - minX, oy = r.minY - minY, oz = r.minZ - minZ;
            int i = 0;
            for (int y = 0; y < r.sizeY; y++) {
                for (int z = 0; z < r.sizeZ; z++) {
                    for (int x = 0; x < r.sizeX; x++, i++) {
                        int v = values[i];
                        if (v >= map.length) {
                            throw BlueprintReader.damaged();
                        }
                        int p = map[v];
                        int cell = raw.index(ox + x, oy + y, oz + z);
                        if (raw.cells[cell] == Blueprint.VOID || !isAir.get(p)) {
                            raw.cells[cell] = p;
                        }
                    }
                }
            }

            ListTag tiles = r.tag.getList("TileEntities", Tag.TAG_COMPOUND);
            for (int t = 0; t < tiles.size(); t++) {
                CompoundTag nbt = tiles.getCompound(t).copy();
                int x = nbt.getInt("x"), y = nbt.getInt("y"), z = nbt.getInt("z");
                if (x < 0 || y < 0 || z < 0 || x >= r.sizeX || y >= r.sizeY || z >= r.sizeZ
                        || !nbt.contains("id", Tag.TAG_STRING)) {
                    continue;
                }
                nbt.remove("x");
                nbt.remove("y");
                nbt.remove("z");
                raw.blockEntities.put(raw.index(ox + x, oy + y, oz + z), nbt);
            }
        }
        return raw;
    }

    private static boolean isAirName(String name) {
        return name.equals("minecraft:air") || name.equals("minecraft:cave_air") || name.equals("minecraft:void_air");
    }
}

package com.alex.test.village.villagers.builder.blueprint;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.state.BlockState;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Writes a {@link Blueprint} as a vanilla structure file, the one form this mod
 * stores blueprints in (whatever format they came from). A structure block can
 * load the result too.
 *
 * <p>VOID cells are simply not listed, which is how the structure format says
 * "leave the world block"; air is listed and clears. Unknown block ids are
 * already VOID, so they do not survive a save.
 */
public final class BlueprintWriter {
    private BlueprintWriter() {
    }

    public static CompoundTag toStructureNbt(Blueprint blueprint) {
        CompoundTag root = new CompoundTag();
        ListTag palette = new ListTag();
        for (BlockState state : blueprint.palette()) {
            palette.add(NbtUtils.writeBlockState(state));
        }
        ListTag blocks = new ListTag();
        for (int y = 0; y < blueprint.sizeY(); y++) {
            for (int z = 0; z < blueprint.sizeZ(); z++) {
                for (int x = 0; x < blueprint.sizeX(); x++) {
                    int p = blueprint.paletteIndexAt(x, y, z);
                    if (p == Blueprint.VOID) {
                        continue;
                    }
                    CompoundTag block = new CompoundTag();
                    block.put("pos", intList(x, y, z));
                    block.putInt("state", p);
                    CompoundTag nbt = blueprint.blockEntityAt(x, y, z);
                    if (nbt != null) {
                        block.put("nbt", nbt);
                    }
                    blocks.add(block);
                }
            }
        }
        root.put("blocks", blocks);
        root.put("palette", palette);
        root.put("entities", new ListTag());
        root.put("size", intList(blueprint.sizeX(), blueprint.sizeY(), blueprint.sizeZ()));
        return NbtUtils.addCurrentDataVersion(root);
    }

    /** The structure NBT gzipped, as it would be on disk. */
    public static byte[] toBytes(Blueprint blueprint) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(toStructureNbt(blueprint), out);
        return out.toByteArray();
    }

    private static ListTag intList(int a, int b, int c) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(a));
        list.add(IntTag.valueOf(b));
        list.add(IntTag.valueOf(c));
        return list;
    }
}

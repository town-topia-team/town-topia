package com.alex.test.command;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * The blocks the debug wool commands replaced, saved with the world.
 *
 * <p>They used to live in static maps, which a restart of the game emptied —
 * after that, {@code /villager building clear} had nothing to put back and the
 * wool stayed in the floor for good. One store per dimension, since the
 * positions are only meaningful in the level they were taken from.
 */
final class DebugWoolStore extends SavedData {

    private static final String DATA_NAME = "dynamicvillages_debug_wool";
    private static final String TAG_NODES = "Nodes";
    private static final String TAG_BUILDINGS = "Buildings";

    /** Replaced by {@code /villager debug wool}: one per nav node surface. */
    final Map<BlockPos, BlockState> nodes = new HashMap<>();
    /** Replaced by {@code /villager building wool}: room floors, doors, frames. */
    final Map<BlockPos, BlockState> buildings = new HashMap<>();

    static DebugWoolStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(DebugWoolStore::new,
                        (tag, registries) -> load(tag, registries), null),
                DATA_NAME);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put(TAG_NODES, write(nodes));
        tag.put(TAG_BUILDINGS, write(buildings));
        return tag;
    }

    private static DebugWoolStore load(CompoundTag tag, HolderLookup.Provider registries) {
        DebugWoolStore store = new DebugWoolStore();
        HolderLookup.RegistryLookup<net.minecraft.world.level.block.Block> blocks =
                registries.lookupOrThrow(Registries.BLOCK);
        read(tag.getList(TAG_NODES, Tag.TAG_COMPOUND), blocks, store.nodes);
        read(tag.getList(TAG_BUILDINGS, Tag.TAG_COMPOUND), blocks, store.buildings);
        return store;
    }

    private static ListTag write(Map<BlockPos, BlockState> map) {
        ListTag list = new ListTag();
        for (Map.Entry<BlockPos, BlockState> entry : map.entrySet()) {
            CompoundTag item = new CompoundTag();
            item.putLong("Pos", entry.getKey().asLong());
            item.put("State", NbtUtils.writeBlockState(entry.getValue()));
            list.add(item);
        }
        return list;
    }

    private static void read(ListTag list,
                             HolderLookup<net.minecraft.world.level.block.Block> blocks,
                             Map<BlockPos, BlockState> into) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag item = list.getCompound(i);
            into.put(BlockPos.of(item.getLong("Pos")),
                    NbtUtils.readBlockState(blocks, item.getCompound("State")));
        }
    }
}

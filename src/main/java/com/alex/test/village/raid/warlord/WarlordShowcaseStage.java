package com.alex.test.village.raid.warlord;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

/**
 * The showcase's stage blocks, written into the world's saved data while a
 * showcase runs. A clean end takes them down and clears this; after a crash
 * (or a kill) that skipped that, the next server start puts back what stood
 * there, wherever the stage block is still in place.
 */
final class WarlordShowcaseStage extends SavedData {

    private static final String DATA_NAME = "dynamicvillages_warlord_showcase_stage";
    private static final SavedData.Factory<WarlordShowcaseStage> FACTORY =
            new SavedData.Factory<>(WarlordShowcaseStage::new, WarlordShowcaseStage::load, null);
    /** Stage blocks go in and come out with no neighbour updates and no drops. */
    static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** One stage block: what stood there before, and what the stage put there. */
    record Placed(ResourceKey<Level> dimension, BlockPos pos, BlockState before, BlockState placed) {}

    private final List<Placed> blocks = new ArrayList<>();

    static WarlordShowcaseStage get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    void remember(List<Placed> placed) {
        blocks.addAll(placed);
        setDirty();
    }

    void forget(ResourceKey<Level> dimension) {
        if (blocks.removeIf(b -> b.dimension().equals(dimension))) setDirty();
    }

    /** Puts back every stage block a showcase left behind; never one somebody has changed since. */
    void restoreLeftovers(MinecraftServer server) {
        if (blocks.isEmpty()) return;
        for (Placed block : blocks) {
            ServerLevel level = server.getLevel(block.dimension());
            if (level != null) putBack(level, block);
        }
        blocks.clear();
        setDirty();
    }

    /** What stood there before, if the stage block is still the one placed. */
    static void putBack(ServerLevel level, Placed block) {
        if (level.getBlockState(block.pos()) == block.placed()) level.setBlock(block.pos(), block.before(), QUIET);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Placed block : blocks) {
            CompoundTag entry = new CompoundTag();
            entry.putString("dimension", block.dimension().location().toString());
            entry.put("pos", NbtUtils.writeBlockPos(block.pos()));
            entry.put("before", NbtUtils.writeBlockState(block.before()));
            entry.put("placed", NbtUtils.writeBlockState(block.placed()));
            list.add(entry);
        }
        tag.put("blocks", list);
        return tag;
    }

    private static WarlordShowcaseStage load(CompoundTag tag, HolderLookup.Provider registries) {
        WarlordShowcaseStage stage = new WarlordShowcaseStage();
        HolderGetter<Block> blockLookup = registries.lookupOrThrow(Registries.BLOCK);
        for (Tag raw : tag.getList("blocks", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) raw;
            ResourceLocation dimension = ResourceLocation.tryParse(entry.getString("dimension"));
            if (dimension == null) continue;
            NbtUtils.readBlockPos(entry, "pos").ifPresent(pos -> stage.blocks.add(new Placed(
                    ResourceKey.create(Registries.DIMENSION, dimension), pos,
                    NbtUtils.readBlockState(blockLookup, entry.getCompound("before")),
                    NbtUtils.readBlockState(blockLookup, entry.getCompound("placed")))));
        }
        return stage;
    }
}

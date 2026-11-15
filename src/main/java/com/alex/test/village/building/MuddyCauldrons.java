package com.alex.test.village.building;

import com.alex.test.network.MuddyCauldronPayload;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The cauldrons a farmer has washed in, per dimension: their water is muddy
 * and the client draws it brown (see {@code ClientMuddyCauldrons}).
 *
 * <p>A wash marks the cauldron before it lowers the level, so the client
 * already knows when the block update arrives. The mark goes when a villager
 * tips the water out or pours a refill, and whenever the cauldron is found full
 * (level 3) or not holding water any more: on any read, and in a sweep every
 * {@link #SWEEP_TICKS} ticks, which is what catches a player refilling or
 * emptying one. The client only tints below level 3, so a full cauldron looks
 * clean even while a stale mark waits for the sweep.
 */
public final class MuddyCauldrons extends SavedData {

    public static final int SWEEP_TICKS = 200;

    private static final String DATA_NAME = "dynamicvillages_muddy_cauldrons";
    private static final SavedData.Factory<MuddyCauldrons> FACTORY =
            new SavedData.Factory<>(MuddyCauldrons::new, MuddyCauldrons::load, null);

    private final LongOpenHashSet marked = new LongOpenHashSet();

    private static MuddyCauldrons get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** Mark the cauldron's water muddy and tell the players who can see it. */
    public static void mark(ServerLevel level, BlockPos pos) {
        MuddyCauldrons data = get(level);
        if (!data.marked.add(pos.asLong())) return;
        data.setDirty();
        PacketDistributor.sendToPlayersInDimension(level,
                new MuddyCauldronPayload(MuddyCauldronPayload.ADD, new long[] {pos.asLong()}));
    }

    /** Clean water again (tipped out, refilled, or gone). */
    public static void clear(ServerLevel level, BlockPos pos) {
        MuddyCauldrons data = get(level);
        if (!data.marked.remove(pos.asLong())) return;
        data.setDirty();
        PacketDistributor.sendToPlayersInDimension(level,
                new MuddyCauldronPayload(MuddyCauldronPayload.REMOVE, new long[] {pos.asLong()}));
    }

    /**
     * Whether this cauldron holds muddy water: marked and below full. A mark
     * found on a full or dry cauldron is cleared on the way.
     */
    public static boolean isDirtyWater(ServerLevel level, BlockPos pos) {
        MuddyCauldrons data = get(level);
        if (!data.marked.contains(pos.asLong())) return false;
        if (isStale(level.getBlockState(pos))) {
            clear(level, pos);
            return false;
        }
        return true;
    }

    /** Whether the cauldron was washed in, whatever its level now. */
    public static boolean isMarked(ServerLevel level, BlockPos pos) {
        return get(level).marked.contains(pos.asLong());
    }

    /** Clears marks on cauldrons that were refilled, emptied or removed. Loaded chunks only. */
    public static void sweep(ServerLevel level) {
        MuddyCauldrons data = get(level);
        if (data.marked.isEmpty()) return;
        for (long packed : data.marked.toLongArray()) {
            BlockPos pos = BlockPos.of(packed);
            if (!level.isLoaded(pos)) continue;
            if (isStale(level.getBlockState(pos))) clear(level, pos);
        }
    }

    /** The whole set for the player's current dimension. */
    public static void sendAll(ServerPlayer player) {
        MuddyCauldrons data = get(player.serverLevel());
        PacketDistributor.sendToPlayer(player,
                new MuddyCauldronPayload(MuddyCauldronPayload.REPLACE, data.marked.toLongArray()));
    }

    private static boolean isStale(BlockState state) {
        return !state.is(Blocks.WATER_CAULDRON) || state.getValue(LayeredCauldronBlock.LEVEL) >= 3;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray("Marked", marked.toLongArray());
        return tag;
    }

    private static MuddyCauldrons load(CompoundTag tag, HolderLookup.Provider registries) {
        MuddyCauldrons data = new MuddyCauldrons();
        for (long packed : tag.getLongArray("Marked")) data.marked.add(packed);
        return data;
    }
}

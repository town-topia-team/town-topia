package com.alex.test.village.villagers.builder.copy;

import com.alex.test.LegacyModId;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Builder copy round 5 (10-23): where players and Builders placed land blocks (stone, dirt, sand,
 * snow, ice, sandstone) and the lookalikes (terracotta, infested stone, packed and blue ice), one
 * saved set per dimension. {@link PlacedJudge} calls a remembered block placed whatever the bare
 * land says, so a wall built where a hill was dug out still copies whole.
 *
 * <p>Only blocks placed after this shipped are known. A player breaking one forgets it; blocks
 * gone another way (explosions, villagers, pistons) leave a stale spot behind, harmless while it
 * holds no land block. Registered in {@code DynamicVillages}; the Builder calls {@link #notePlaced}.
 */
public final class PlacedLand extends SavedData {

    private static final String DATA_NAME = LegacyModId.SAVE_PREFIX + "_placed_land";
    private static final String TAG_POSITIONS = "positions";

    private static final SavedData.Factory<PlacedLand> FACTORY =
            new SavedData.Factory<>(PlacedLand::new, PlacedLand::load, null);

    private final LongOpenHashSet positions = new LongOpenHashSet();

    public static PlacedLand get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    private static PlacedLand load(CompoundTag tag, HolderLookup.Provider registries) {
        PlacedLand memory = new PlacedLand();
        for (long pos : tag.getLongArray(TAG_POSITIONS)) memory.positions.add(pos);
        return memory;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray(TAG_POSITIONS, positions.toLongArray());
        return tag;
    }

    boolean contains(long pos) {
        return positions.contains(pos);
    }

    int size() {
        return positions.size();
    }

    /** Something placed {@code state} here: remembered when a copy would otherwise have to guess. */
    public static void notePlaced(ServerLevel level, BlockPos pos, BlockState state) {
        LandView.Group group = LevelLandView.group(state);
        if (!group.isLand() && !group.isLookalike()) return;
        PlacedLand memory = get(level);
        if (memory.positions.add(pos.asLong())) memory.setDirty();
    }

    /** The block here was taken away. */
    public static void noteRemoved(ServerLevel level, BlockPos pos) {
        PlacedLand memory = get(level);
        if (memory.positions.remove(pos.asLong())) memory.setDirty();
    }

    /** Last, so a placement another mod cancels is never remembered. Players only (endermen place dirt too). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Player)) return;
        notePlaced(level, event.getPos(), event.getPlacedBlock());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) noteRemoved(level, event.getPos());
    }
}

package com.alex.test.village.villagers.mage.behavior;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Night lights: on night duty a mage leaves floating wisps of light in the
 * village's darkest spots, so fewer monsters spawn inside it. Each wisp is a
 * vanilla light block (invisible, no collision, walked through by everyone)
 * with a soft glow of particles, and every one fades at dawn.
 *
 * <p>The only blocks a mage ever places, so it is careful with them: only in
 * plain air, only where it is dark, never next to another wisp, at most
 * {@link #MAX_PER_LEVEL} at once, and every one it places is written down here
 * (saved with the world) so it is taken away at dawn even across a restart or
 * an unloaded chunk. One is only removed if it is still a light block: a block
 * a player has put there since is left alone. "Night Lights" off in the mage's
 * settings, it places none.
 */
public final class MageLights extends SavedData {

    private static final String DATA_NAME = "dynamicvillages_mage_lights";
    private static final SavedData.Factory<MageLights> FACTORY =
            new SavedData.Factory<>(MageLights::new, MageLights::load, null);

    /** A wisp's brightness: lights a circle of about 6 blocks enough to stop spawns. */
    public static final int LIGHT_LEVEL = 12;
    /** A spot counts as dark below this block light. */
    public static final int DARK_BELOW = 8;
    /** Wisps kept at once in one world, across all its villages. */
    public static final int MAX_PER_LEVEL = 64;
    /** No two wisps closer than this. */
    private static final int SPACING = 6;
    /** How often the wisps glow and the dawn check runs. */
    private static final int TICK_EVERY = 40;

    private final List<BlockPos> lights = new ArrayList<>();

    private MageLights() {}

    public static MageLights get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /**
     * Leaves a wisp at {@code pos} if it is a good spot: plain air, dark, no
     * wisp near, room in the budget. Returns whether one was placed.
     */
    public boolean tryPlace(ServerLevel level, BlockPos pos) {
        if (lights.size() >= MAX_PER_LEVEL || !level.isLoaded(pos)) return false;
        // Only where dawn comes to take it away again.
        if (level.dimensionType().hasFixedTime()) return false;
        if (!level.getBlockState(pos).isAir() || !level.getFluidState(pos).isEmpty()) return false;
        if (level.getBrightness(LightLayer.BLOCK, pos) >= DARK_BELOW) return false;
        for (BlockPos other : lights) {
            if (other.distManhattan(pos) < SPACING) return false;
        }
        BlockState wisp = Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, LIGHT_LEVEL);
        if (!level.setBlock(pos, wisp, 3)) return false;
        lights.add(pos.immutable());
        setDirty();
        glow(level, pos, 12);
        return true;
    }

    /** Takes every wisp away (dawn), or only those in loaded chunks; the rest wait for their chunk. */
    private void fadeAll(ServerLevel level) {
        boolean changed = false;
        for (Iterator<BlockPos> it = lights.iterator(); it.hasNext(); ) {
            BlockPos pos = it.next();
            if (!level.isLoaded(pos)) continue;
            if (level.getBlockState(pos).is(Blocks.LIGHT)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                level.sendParticles(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                        4, 0.2, 0.2, 0.2, 0.01);
            }
            it.remove();
            changed = true;
        }
        if (changed) setDirty();
    }

    /** The soft glow that shows where a wisp floats. */
    private static void glow(ServerLevel level, BlockPos pos, int count) {
        level.sendParticles(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                count, 0.15, 0.15, 0.15, 0.005);
    }

    public int count() {
        return lights.size();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % TICK_EVERY != 0) return;
        for (ServerLevel level : server.getAllLevels()) {
            // Only worlds that already hold wisps: never creates the data file.
            MageLights data = level.getDataStorage().get(FACTORY, DATA_NAME);
            if (data == null || data.lights.isEmpty()) continue;
            if (level.isDay()) {
                data.fadeAll(level);
                continue;
            }
            for (BlockPos pos : data.lights) {
                if (level.isLoaded(pos) && level.getBlockState(pos).is(Blocks.LIGHT)) glow(level, pos, 1);
            }
        }
    }

    // =========================================================================
    // Saving
    // =========================================================================

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (BlockPos pos : lights) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("X", pos.getX());
            entry.putInt("Y", pos.getY());
            entry.putInt("Z", pos.getZ());
            list.add(entry);
        }
        tag.put("Lights", list);
        return tag;
    }

    private static MageLights load(CompoundTag tag, HolderLookup.Provider registries) {
        MageLights data = new MageLights();
        ListTag list = tag.getList("Lights", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            data.lights.add(new BlockPos(entry.getInt("X"), entry.getInt("Y"), entry.getInt("Z")));
        }
        return data;
    }
}

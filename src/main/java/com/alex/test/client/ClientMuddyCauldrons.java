package com.alex.test.client;

import com.alex.test.network.MuddyCauldronPayload;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * Client copy of the cauldrons a farmer has washed in, and the water tint that
 * shows it: light brown at level 2, dark brown at level 1. A full cauldron is
 * always drawn with the ordinary water colour.
 *
 * <p>Registering a colour for the water cauldron replaces vanilla's, so
 * {@link #waterColor} starts from vanilla's own rule (the biome's water colour,
 * or no tint without a world position).
 */
public final class ClientMuddyCauldrons {

    /** Muddy wash water after one wash. */
    public static final int LIGHT_BROWN = 0x8B6B3E;
    /** Muddy wash water after two washes. */
    public static final int DARK_BROWN = 0x4E3A24;

    private static final LongOpenHashSet MARKED = new LongOpenHashSet();

    private ClientMuddyCauldrons() {}

    /** Applies a server update, then re-meshes every cauldron whose colour may have changed. */
    public static void apply(MuddyCauldronPayload payload) {
        LongOpenHashSet changed = new LongOpenHashSet(payload.positions());
        switch (payload.mode()) {
            case MuddyCauldronPayload.REPLACE -> {
                changed.addAll(MARKED);
                MARKED.clear();
                for (long packed : payload.positions()) MARKED.add(packed);
            }
            case MuddyCauldronPayload.ADD -> {
                for (long packed : payload.positions()) MARKED.add(packed);
            }
            case MuddyCauldronPayload.REMOVE -> {
                for (long packed : payload.positions()) MARKED.remove(packed);
            }
            default -> { return; }
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.levelRenderer == null || minecraft.level == null) return;
        for (long packed : changed) {
            BlockPos pos = BlockPos.of(packed);
            minecraft.levelRenderer.setBlocksDirty(pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX(), pos.getY(), pos.getZ());
        }
    }

    /** Tint for a water cauldron's water (tint index 0). */
    public static int waterColor(BlockState state, @Nullable BlockAndTintGetter getter,
                                 @Nullable BlockPos pos, int tintIndex) {
        if (getter == null || pos == null) return -1;
        if (MARKED.contains(pos.asLong()) && state.hasProperty(LayeredCauldronBlock.LEVEL)) {
            int level = state.getValue(LayeredCauldronBlock.LEVEL);
            if (level == 2) return LIGHT_BROWN;
            if (level == 1) return DARK_BROWN;
        }
        return BiomeColors.getAverageWaterColor(getter, pos);
    }
}

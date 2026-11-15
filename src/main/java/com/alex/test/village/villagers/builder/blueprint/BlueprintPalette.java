package com.alex.test.village.villagers.builder.blueprint;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Turns palette entries ({@code {Name, Properties}}, already updated by
 * {@link BlueprintFixer}) into this game's block states.
 *
 * <p>An id this game does not have (a block from a mod that is not installed,
 * or a typo) never fails the file: it is reported back so the reader can count
 * it, and its cells become {@link Blueprint#VOID}. Vanilla's
 * {@code NbtUtils.readBlockState} would quietly turn it into air, which would
 * make the Builder dig a hole where the block should be, so the id is checked
 * against the registry first. A property the block does not know is skipped
 * by vanilla with a log line, which is fine.
 */
public final class BlueprintPalette {
    private BlueprintPalette() {
    }

    /**
     * What each raw palette entry became.
     *
     * @param states the state per entry, or null where the cell becomes VOID
     * @param unknownIds the id as written per entry when this game lacks it, else null
     */
    public record Resolved(BlockState[] states, String[] unknownIds) {
    }

    public static Resolved resolve(List<CompoundTag> entries) {
        BlockState[] states = new BlockState[entries.size()];
        String[] unknown = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag tag = entries.get(i);
            BlockState state = stateOf(tag);
            if (state == null) {
                String written = tag.getString("Name");
                unknown[i] = written.isBlank() ? "(no block id)" : written;
            } else if (!isVoidMarker(state)) {
                states[i] = state;
            }
        }
        return new Resolved(states, unknown);
    }

    /** The state for one entry, or null when the id is missing or not in this game. */
    @Nullable
    public static BlockState stateOf(CompoundTag tag) {
        if (!tag.contains("Name", Tag.TAG_STRING)) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(tag.getString("Name").trim());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return null;
        }
        if (!id.toString().equals(tag.getString("Name"))) {
            // readBlockState parses the name again; hand it the clean one.
            tag = tag.copy();
            tag.putString("Name", id.toString());
        }
        return NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag);
    }

    /**
     * Blocks that mean "nothing here" in a structure: structure void marks cells
     * to leave alone, and a jigsaw is a world-generation joint, not part of a house.
     */
    public static boolean isVoidMarker(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.STRUCTURE_VOID || block == Blocks.JIGSAW;
    }
}

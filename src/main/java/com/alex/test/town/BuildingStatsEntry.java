package com.alex.test.town;

import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.List;

/** One grouped building card in the Mayor's village overview. */
public record BuildingStatsEntry(
        String name,
        int count,
        int operationalCount,
        int roomTiles,
        int functionalBlocks,
        String iconItemId,
        List<String> acceptedFeatures) {

    public BuildingStatsEntry {
        acceptedFeatures = List.copyOf(acceptedFeatures);
    }

    public BuildingStatsEntry(String name, int count, int operationalCount,
                              int roomTiles, int functionalBlocks, String iconItemId) {
        this(name, count, operationalCount, roomTiles, functionalBlocks, iconItemId, List.of());
    }

    /** Kept for callers that only have the old grouped summary information. */
    public BuildingStatsEntry(String name, int count, String iconItemId) {
        this(name, count, count, 0, 0, iconItemId, List.of());
    }

    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(name, 128);
        buffer.writeVarInt(count);
        buffer.writeVarInt(operationalCount);
        buffer.writeVarInt(roomTiles);
        buffer.writeVarInt(functionalBlocks);
        buffer.writeUtf(iconItemId, 128);
        buffer.writeCollection(acceptedFeatures, (buf, feature) -> buf.writeUtf(feature, 256));
    }

    public static BuildingStatsEntry read(RegistryFriendlyByteBuf buffer) {
        return new BuildingStatsEntry(
                buffer.readUtf(128),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readUtf(128),
                buffer.readList(buf -> buf.readUtf(256)));
    }
}

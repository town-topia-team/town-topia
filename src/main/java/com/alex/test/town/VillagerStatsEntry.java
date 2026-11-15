package com.alex.test.town;

import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.UUID;

/** Immutable server snapshot for one row and its expandable detail panel. */
public record VillagerStatsEntry(
        UUID id,
        String name,
        String profession,
        String activity,
        String status,
        String home,
        String bed,
        String workplace,
        int health,
        int hunger,
        int wellbeing,
        int intelligence,
        String iconItemId,
        /* Favourite, disliked and recently over-eaten food, one line. */
        String diet
) {

    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(id);
        buffer.writeUtf(name, 128);
        buffer.writeUtf(profession, 128);
        buffer.writeUtf(activity, 128);
        buffer.writeUtf(status, 128);
        buffer.writeUtf(home, 128);
        buffer.writeUtf(bed, 128);
        buffer.writeUtf(workplace, 128);
        buffer.writeVarInt(health);
        buffer.writeVarInt(hunger);
        buffer.writeVarInt(wellbeing);
        buffer.writeVarInt(intelligence);
        buffer.writeUtf(iconItemId, 128);
        buffer.writeUtf(diet, 256);
    }

    public static VillagerStatsEntry read(RegistryFriendlyByteBuf buffer) {
        return new VillagerStatsEntry(
                buffer.readUUID(),
                buffer.readUtf(128),
                buffer.readUtf(128),
                buffer.readUtf(128),
                buffer.readUtf(128),
                buffer.readUtf(128),
                buffer.readUtf(128),
                buffer.readUtf(128),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readUtf(128),
                buffer.readUtf(256));
    }
}

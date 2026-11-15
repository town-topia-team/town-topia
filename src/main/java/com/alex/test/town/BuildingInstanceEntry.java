package com.alex.test.town;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.List;
import java.util.UUID;

/**
 * One single building in the Mayor's village overview, next to the grouped
 * {@link BuildingStatsEntry} of its type.
 *
 * @param id        the building's token frame id; {@link net.minecraft.Util#NIL_UUID} for a guard post
 * @param type      the name of its group in the overview
 * @param lowCeilingTiles floor tiles under a low ceiling, which count half toward its space
 * @param crowdedCount    villagers living in it, or inside it at once lately, when more than it holds; 0 when not crowded
 * @param crowdedLiving   true when too many live here; false when too many were inside at once
 * @param capacity  resident limit for a house or barracks; 0 for other buildings
 * @param bedsFound bed heads the room scan found, past the capacity too
 * @param residents villagers holding a bed claim in this building
 */
public record BuildingInstanceEntry(
        UUID id,
        String type,
        String iconItemId,
        Category category,
        BlockPos door,
        Status status,
        int roomTiles,
        int functionalBlocks,
        int lowCeilingTiles,
        int crowdedCount,
        boolean crowdedLiving,
        int capacity,
        int bedsFound,
        List<Resident> residents,
        /** Village goods waiting in a merchant stall's sell box; -1 for any other building. */
        int sellBoxGoods,
        /** A Barracks, which has a captain line. */
        boolean barracks,
        /** That Barracks' captain, "Name (Lv 34)"; empty when it has none. */
        String captain,
        /** Extra "label	value" rows for the card (the School's classes); empty for most buildings. */
        List<String> info) {

    /** The Mayor's type buttons: where villagers sleep, work, store things, and the rest. */
    public enum Category { HOUSE, WORK, STORAGE, OTHER }

    public enum Status {
        OPERATIONAL,
        /** The scan found no room behind the door. */
        NO_ROOM,
        /** Villagers cannot walk to the door. */
        UNREACHABLE
    }

    /** A villager whose claimed bed is in the house; {@code missing} when that bed is gone. */
    public record Resident(UUID id, String name, BlockPos bed, boolean missing) {}

    public BuildingInstanceEntry {
        residents = List.copyOf(residents);
        info = List.copyOf(info);
    }

    public boolean isHouse() {
        return category == Category.HOUSE;
    }

    /** A house, a Barracks, or a work room with beds of its own (the Mage Tower). */
    public boolean hasBeds() {
        return isHouse() || barracks || capacity > 0;
    }

    /** A work room whose beds are a bonus (the Mage Tower): none at all is not a problem. */
    public boolean bedsOptional() {
        return !isHouse() && !barracks && capacity > 0;
    }

    public boolean operational() {
        return status == Status.OPERATIONAL;
    }

    public boolean crowded() {
        return crowdedCount > 0;
    }

    public int missingBeds() {
        int count = 0;
        for (Resident resident : residents) if (resident.missing()) count++;
        return count;
    }

    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(id);
        buffer.writeUtf(type, 128);
        buffer.writeUtf(iconItemId, 128);
        buffer.writeVarInt(category.ordinal());
        buffer.writeBlockPos(door);
        buffer.writeVarInt(status.ordinal());
        buffer.writeVarInt(roomTiles);
        buffer.writeVarInt(functionalBlocks);
        buffer.writeVarInt(lowCeilingTiles);
        buffer.writeVarInt(crowdedCount);
        buffer.writeBoolean(crowdedLiving);
        buffer.writeVarInt(capacity);
        buffer.writeVarInt(bedsFound);
        buffer.writeCollection(residents, (buf, resident) -> {
            buf.writeUUID(resident.id());
            buf.writeUtf(resident.name(), 64);
            buf.writeBlockPos(resident.bed());
            buf.writeBoolean(resident.missing());
        });
        buffer.writeVarInt(sellBoxGoods);
        buffer.writeBoolean(barracks);
        buffer.writeUtf(captain, 96);
        buffer.writeCollection(info, (buf, row) -> buf.writeUtf(row, 256));
    }

    public static BuildingInstanceEntry read(RegistryFriendlyByteBuf buffer) {
        UUID id = buffer.readUUID();
        String type = buffer.readUtf(128);
        String icon = buffer.readUtf(128);
        int categoryOrdinal = buffer.readVarInt();
        Category category = categoryOrdinal >= 0 && categoryOrdinal < Category.values().length
                ? Category.values()[categoryOrdinal] : Category.OTHER;
        BlockPos door = buffer.readBlockPos();
        int ordinal = buffer.readVarInt();
        Status status = ordinal >= 0 && ordinal < Status.values().length
                ? Status.values()[ordinal] : Status.OPERATIONAL;
        return new BuildingInstanceEntry(id, type, icon, category, door, status,
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readBoolean(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readList(buf -> new Resident(buf.readUUID(), buf.readUtf(64),
                        buf.readBlockPos(), buf.readBoolean())),
                buffer.readVarInt(),
                buffer.readBoolean(),
                buffer.readUtf(96),
                buffer.readList(buf -> buf.readUtf(256)));
    }
}

package com.alex.test.entity.mayor;

import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/** Persistent identity link between a Mayor, its village manager, and its Town Hall. */
public record MayorTownHallLink(UUID villageId, UUID townHallId) {

    private static final String TAG_VILLAGE = "MayorVillageId";
    private static final String TAG_TOWN_HALL = "MayorTownHallId";

    public void save(CompoundTag tag) {
        tag.putUUID(TAG_VILLAGE, villageId);
        tag.putUUID(TAG_TOWN_HALL, townHallId);
    }

    @Nullable
    public static MayorTownHallLink load(CompoundTag tag) {
        if (!tag.hasUUID(TAG_VILLAGE) || !tag.hasUUID(TAG_TOWN_HALL)) return null;
        return new MayorTownHallLink(tag.getUUID(TAG_VILLAGE), tag.getUUID(TAG_TOWN_HALL));
    }
}

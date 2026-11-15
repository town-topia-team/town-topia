package com.alex.test.village;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Persistent identity link from an NPC entity to its owning village.
 *
 * <p>The village UUID is authoritative. The dimension and Town Hall/anchor
 * position are deliberately duplicated as repair data for migrated worlds or
 * a registry that has not finished loading when the entity joins the level.
 */
public record VillagerVillageLink(
        UUID villageId,
        ResourceKey<Level> dimension,
        BlockPos townHallPos) {

    private static final String TAG_VILLAGE = "VillageId";
    private static final String TAG_DIMENSION = "VillageDimension";
    private static final String TAG_TOWN_HALL_X = "VillageTownHallX";
    private static final String TAG_TOWN_HALL_Y = "VillageTownHallY";
    private static final String TAG_TOWN_HALL_Z = "VillageTownHallZ";

    public void save(CompoundTag tag) {
        tag.putUUID(TAG_VILLAGE, villageId);
        tag.putString(TAG_DIMENSION, dimension.location().toString());
        tag.putInt(TAG_TOWN_HALL_X, townHallPos.getX());
        tag.putInt(TAG_TOWN_HALL_Y, townHallPos.getY());
        tag.putInt(TAG_TOWN_HALL_Z, townHallPos.getZ());
    }

    @Nullable
    public static VillagerVillageLink load(CompoundTag tag) {
        if (!tag.hasUUID(TAG_VILLAGE) || !tag.contains(TAG_DIMENSION)) return null;
        ResourceLocation dimensionId = ResourceLocation.tryParse(tag.getString(TAG_DIMENSION));
        if (dimensionId == null) return null;

        BlockPos townHallPos = new BlockPos(
                tag.getInt(TAG_TOWN_HALL_X),
                tag.getInt(TAG_TOWN_HALL_Y),
                tag.getInt(TAG_TOWN_HALL_Z));
        return new VillagerVillageLink(
                tag.getUUID(TAG_VILLAGE),
                ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimensionId),
                townHallPos);
    }
}

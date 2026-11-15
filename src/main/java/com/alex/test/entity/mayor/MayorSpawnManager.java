package com.alex.test.entity.mayor;

import com.alex.test.DynamicVillages;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.TownHallBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the one-Mayor-per-Town-Hall invariant for a village manager.
 *
 * <p>Checks run every five seconds. A Mayor is spawned only when all chunks
 * containing the Town Hall room are loaded, preventing a hidden unloaded Mayor
 * from being mistaken for a missing one.
 */
public final class MayorSpawnManager {

    private static final int CHECK_INTERVAL = 100;

    private final VillageManager village;
    private final Map<UUID, UUID> mayorByTownHall = new HashMap<>();
    private int timer;

    public MayorSpawnManager(VillageManager village) {
        this.village = village;
    }

    public void tick(ServerLevel level) {
        if (DynamicVillages.isServerStopping()) return;
        if (++timer < CHECK_INTERVAL) return;
        timer = 0;

        List<TownHallBuilding> validTownHalls = new ArrayList<>();
        for (Building building : village.getBuildings()) {
            if (building instanceof TownHallBuilding hall && hall.isValid()) {
                validTownHalls.add(hall);
                ensureMayor(level, hall);
            }
        }

        mayorByTownHall.keySet().removeIf(id -> validTownHalls.stream()
                .noneMatch(hall -> hall.getItemFrameId().equals(id)));
    }

    /** Spread loaded Mayors' wake-up work across the same morning window. */
    public void onPlayerSleepFinished(ServerLevel level) {
        for (UUID mayorId : mayorByTownHall.values()) {
            if (level.getEntity(mayorId) instanceof MayorEntity mayor) {
                mayor.staggerWakeAfterPlayerSleep(level);
            }
        }
    }

    public void onTownHallRegistered(ServerLevel level, TownHallBuilding hall) {
        if (DynamicVillages.isServerStopping()) return;
        if (hall.isValid()) ensureMayor(level, hall);
    }

    public void onTownHallRemoved(ServerLevel level, TownHallBuilding hall) {
        // Keep the Mayor entity and its persistent village link while the village
        // is orphaned. A replacement Town Hall will relink this same Mayor.
        UUID mayorId = mayorByTownHall.remove(hall.getItemFrameId());
        if (mayorId != null) {
            VillageManagerRegistry.get(level.getServer())
                    .rememberMayor(village.getVillageId(), mayorId);
        }
    }

    public void teardown() {
        ServerLevel level = village.getLevel();
        if (level == null) return;
        if (DynamicVillages.isServerStopping()) {
            mayorByTownHall.clear();
            return;
        }
        for (UUID mayorId : mayorByTownHall.values()) {
            Entity entity = level.getEntity(mayorId);
            if (entity instanceof MayorEntity mayor) mayor.discard();
        }
        mayorByTownHall.clear();
    }

    private void ensureMayor(ServerLevel level, TownHallBuilding hall) {
        if (DynamicVillages.isServerStopping()) return;
        if (!allTownHallChunksLoaded(level, hall)) return;

        List<MayorEntity> linked = findLinkedMayors(level, hall);
        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        UUID rememberedId = registry.getRememberedMayor(village.getVillageId());
        UUID preferredId = rememberedId != null
                ? rememberedId
                : mayorByTownHall.get(hall.getItemFrameId());
        MayorEntity keeper = null;

        if (preferredId != null) {
            Entity preferred = level.getEntity(preferredId);
            if (preferred instanceof MayorEntity mayor && mayor.isAlive()) keeper = mayor;
        }

        // SavedData is restored before chunk entities finish joining the level.
        // An unresolved persisted UUID therefore means "not loaded yet", not
        // "dead".  Waiting here prevents startup from briefly creating a second
        // Mayor; confirmed deaths/permanent removals clear the remembered UUID
        // through VillageManagerRegistry's entity lifecycle handlers.
        if (rememberedId != null && keeper == null) return;

        for (MayorEntity candidate : linked) {
            if (keeper == null || candidate.getUUID().equals(preferredId)) {
                if (keeper != null && keeper != candidate) keeper.discard();
                keeper = candidate;
            } else if (candidate != keeper) {
                candidate.discard();
            }
        }

        if (keeper == null) {
            BlockPos spawnPos = village.getTownHallSpawnPos(hall.getItemFrameId());
            if (spawnPos == null) return;

            keeper = DynamicVillages.MAYOR.get().create(level);
            if (keeper == null) return;
            keeper.linkToTownHall(new MayorTownHallLink(
                    village.getVillageId(), hall.getItemFrameId()));
            keeper.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(),
                    spawnPos.getZ() + 0.5, 0.0F, 0.0F);
            level.addFreshEntity(keeper);
            DynamicVillages.LOGGER.info("[VillageManager {}] Spawned Mayor {} for Town Hall {}.",
                    shortId(village.getVillageId()), shortId(keeper.getUUID()),
                    shortId(hall.getItemFrameId()));
        }

        keeper.linkToTownHall(new MayorTownHallLink(
                village.getVillageId(), hall.getItemFrameId()));
        mayorByTownHall.put(hall.getItemFrameId(), keeper.getUUID());
        registry.rememberMayor(village.getVillageId(), keeper.getUUID());
    }

    private List<MayorEntity> findLinkedMayors(ServerLevel level, TownHallBuilding hall) {
        AABB search = roomBounds(hall).inflate(32.0, 16.0, 32.0);
        UUID villageId = village.getVillageId();
        return level.getEntitiesOfClass(MayorEntity.class, search, mayor -> {
            MayorTownHallLink link = mayor.getTownHallLink();
            return link != null && link.villageId().equals(villageId);
        });
    }

    private static boolean allTownHallChunksLoaded(ServerLevel level, TownHallBuilding hall) {
        if (hall.getNavMeshBlocks().isEmpty()) return false;
        for (BlockPos pos : hall.getNavMeshBlocks()) {
            if (!level.hasChunkAt(pos)) return false;
        }
        return true;
    }

    private static AABB roomBounds(TownHallBuilding hall) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : hall.getNavMeshBlocks()) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        return new AABB(minX, minY, minZ, maxX + 1, maxY + 3, maxZ + 1);
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}

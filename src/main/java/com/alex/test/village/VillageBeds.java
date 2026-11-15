package com.alex.test.village;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager.SavedBedAssignment;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.BarracksBuilding;
import com.alex.test.village.building.HouseBuilding;
import com.alex.test.village.building.MageTowerBuilding;
import com.alex.test.village.building.TownHallBuilding;
import com.alex.test.village.brain.GoHomeActivity;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent bed assignments: one bed per villager, one villager per bed.
 *
 * <p>Owned by {@link VillageManager}, which exposes the public bed API and
 * persists {@link SavedBedAssignment}s through the registry.
 *
 * <p>A house or barracks bed, once claimed, remains the villager's home. A night it cannot
 * be slept in (unreachable, someone else lying in it, broken, the room
 * mid-rescan) only sends the villager elsewhere for that night. The claim ends
 * when the villager leaves the village, the house token is removed, or a
 * player moves it in the house screen, or a defender transfers to a barracks.
 * A house holds at most its token's
 * capacity in claims; its other beds stay free.
 *
 * <p>Town Hall beds are the exception: the Mayor takes one each night and
 * gives it back in the morning, and they are dropped as soon as they cannot
 * be used, as before.
 */
final class VillageBeds {

    private final VillageManager village;
    private final VillageBuildings buildings;

    private final Map<UUID, BlockPos> assignedBeds = new HashMap<>();
    private final Map<Long, UUID> bedOwners = new HashMap<>();
    /** Building that owns each villager's assigned bed. Prevents cross-room drift after rescans. */
    private final Map<UUID, UUID> assignedBedBuildings = new HashMap<>();
    /** Loaded from SavedData and hydrated once the owning bedroom marker is available. */
    private final Map<UUID, SavedBedAssignment> savedBedAssignments = new LinkedHashMap<>();
    /** Persisted with the claim so an unloaded captain still holds its barracks place. */
    private final Set<UUID> barracksCaptains = new HashSet<>();
    private record ResidentState(Profession profession, boolean resting) {}
    private Map<UUID, ResidentState> barracksRoster = Map.of();
    private boolean barracksAssignmentsQueued = true;
    private long nextRosterCheck;
    private long nextBarracksRetry;

    VillageBeds(VillageManager village, VillageBuildings buildings) {
        this.village = village;
        this.buildings = buildings;
    }

    void clear() {
        savedBedAssignments.clear();
        assignedBeds.clear();
        assignedBedBuildings.clear();
        bedOwners.clear();
        barracksCaptains.clear();
        barracksRoster = Map.of();
        barracksAssignmentsQueued = true;
        nextRosterCheck = nextBarracksRetry = 0;
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    List<SavedBedAssignment> getSavedBedAssignments() {
        LinkedHashMap<UUID, SavedBedAssignment> result = new LinkedHashMap<>(savedBedAssignments);
        for (Map.Entry<UUID, BlockPos> entry : assignedBeds.entrySet()) {
            UUID buildingId = assignedBedBuildings.get(entry.getKey());
            if (buildingId == null) continue;
            result.put(entry.getKey(), new SavedBedAssignment(entry.getKey(), buildingId, entry.getValue(),
                    barracksCaptains.contains(entry.getKey())));
        }
        return List.copyOf(result.values());
    }

    void rememberBedAssignments(Collection<SavedBedAssignment> assignments) {
        barracksCaptains.clear();
        assignedBeds.clear();
        assignedBedBuildings.clear();
        bedOwners.clear();
        savedBedAssignments.clear();
        for (SavedBedAssignment assignment : assignments) {
            if (assignment == null || assignment.villagerId() == null
                    || assignment.buildingId() == null || assignment.bedPos() == null) {
                continue;
            }
            // Pending claims reserve their beds even before the owning room is restored.
            if (savedBedAssignments.containsKey(assignment.villagerId())
                    || bedOwners.putIfAbsent(assignment.bedPos().asLong(), assignment.villagerId()) != null) continue;
            savedBedAssignments.put(assignment.villagerId(), assignment);
            if (assignment.barracksCaptain()) barracksCaptains.add(assignment.villagerId());
        }
        hydrateSavedBedAssignments();
        queueBarracksAssignments();
    }

    // =========================================================================
    // Reconciliation against the registered buildings
    // =========================================================================

    void reconcileBedAssignments() {
        boolean changed = false;
        Iterator<Map.Entry<UUID, BlockPos>> it = assignedBeds.entrySet().iterator();
        bedOwners.clear();
        while (it.hasNext()) {
            Map.Entry<UUID, BlockPos> entry = it.next();
            BlockPos bed = entry.getValue();
            UUID villagerId = entry.getKey();
            UUID buildingId = assignedBedBuildings.get(villagerId);
            Building building = buildingId != null ? buildings.get(buildingId) : null;
            boolean keep;
            if (keepsClaims(building)) {
                // Permanent homes (and the Mayor's hall bed) outlive a broken bed or a failed rescan.
                keep = true;
            } else if (building == null && buildingId != null && buildings.hasSavedMarker(buildingId)) {
                // The house is waiting to be restored: park the claim until it is.
                savedBedAssignments.put(villagerId, new SavedBedAssignment(villagerId, buildingId, bed,
                        barracksCaptains.contains(villagerId)));
                keep = false;
            } else {
                keep = isAssignableBedForBuilding(bed, buildingId);
            }
            if (!keep || bedOwners.containsKey(bed.asLong())) {
                if (building instanceof TownHallBuilding) logHallDrop(villagerId, bed, "another claim holds that bed");
                it.remove();
                assignedBedBuildings.remove(villagerId);
                changed = true;
            } else {
                bedOwners.put(bed.asLong(), villagerId);
            }
        }
        assignedBedBuildings.keySet().removeIf(villagerId -> {
            boolean stale = !assignedBeds.containsKey(villagerId);
            if (stale) village.markPersistentDataDirty();
            return stale;
        });
        Iterator<SavedBedAssignment> savedIt = savedBedAssignments.values().iterator();
        while (savedIt.hasNext()) {
            SavedBedAssignment assignment = savedIt.next();
            UUID buildingId = assignment.buildingId();
            Building building = buildings.get(buildingId);
            if (building == null && !buildings.hasSavedMarker(buildingId)) {
                savedIt.remove();
                changed = true;
            } else if (building != null && !keepsClaims(building)
                    && !isAssignableBedForBuilding(assignment.bedPos(), buildingId)) {
                savedIt.remove();
                changed = true;
            }
        }
        // Hydration must not treat a parked saved claim as a vacant bed.
        for (SavedBedAssignment assignment : savedBedAssignments.values()) {
            bedOwners.putIfAbsent(assignment.bedPos().asLong(), assignment.villagerId());
        }
        barracksCaptains.removeIf(id -> !assignedBeds.containsKey(id) && !savedBedAssignments.containsKey(id));
        if (changed) village.markPersistentDataDirty();
        hydrateSavedBedAssignments();
        queueBarracksAssignments();
    }

    void hydrateSavedBedAssignments() {
        enforceBarracksCapacity();
        if (savedBedAssignments.isEmpty()) return;

        boolean changed = false;
        Iterator<Map.Entry<UUID, SavedBedAssignment>> iterator = savedBedAssignments.entrySet().iterator();
        while (iterator.hasNext()) {
            SavedBedAssignment assignment = iterator.next().getValue();
            UUID buildingId = assignment.buildingId();
            Building building = buildings.get(buildingId);

            if (building == null) {
                if (!buildings.hasSavedMarker(buildingId)) {
                    iterator.remove();
                    bedOwners.remove(assignment.bedPos().asLong(), assignment.villagerId());
                    barracksCaptains.remove(assignment.villagerId());
                    changed = true;
                }
                continue;
            }

            boolean usable = keepsClaims(building)
                    || isAssignableBedForBuilding(assignment.bedPos(), buildingId);
            UUID owner = bedOwners.get(assignment.bedPos().asLong());
            if (!usable || owner != null && !owner.equals(assignment.villagerId())
                    || assignedBeds.containsKey(assignment.villagerId())) {
                if (building instanceof TownHallBuilding && !assignedBeds.containsKey(assignment.villagerId())) {
                    logHallDrop(assignment.villagerId(), assignment.bedPos(), "another claim holds that bed");
                }
                iterator.remove();
                if (!assignment.bedPos().equals(assignedBeds.get(assignment.villagerId()))) {
                    bedOwners.remove(assignment.bedPos().asLong(), assignment.villagerId());
                }
                if (!assignedBeds.containsKey(assignment.villagerId())) barracksCaptains.remove(assignment.villagerId());
                changed = true;
                continue;
            }

            assignedBeds.put(assignment.villagerId(), assignment.bedPos());
            assignedBedBuildings.put(assignment.villagerId(), buildingId);
            bedOwners.put(assignment.bedPos().asLong(), assignment.villagerId());
            if (assignment.barracksCaptain()) barracksCaptains.add(assignment.villagerId());
            iterator.remove();
        }

        if (changed) village.markPersistentDataDirty();
    }

    private boolean isAssignableBedForBuilding(BlockPos bed, UUID buildingId) {
        ServerLevel level = village.getLevel();
        if (buildingId == null || bed == null || level == null || !level.isLoaded(bed)) return false;
        BlockState state = level.getBlockState(bed);
        if (!(state.getBlock() instanceof BedBlock)
                || state.getValue(BlockStateProperties.BED_PART) != BedPart.HEAD) return false;
        Building building = buildings.get(buildingId);
        if (!isBedroomBuilding(building)) return false;
        return buildingContainsBed(building, bed);
    }

    private static boolean isBedroomBuilding(Building building) {
        return (isPermanentBedroom(building) || building instanceof TownHallBuilding) && building.isValid();
    }

    private static boolean isPermanentBedroom(@Nullable Building building) {
        return building instanceof HouseBuilding || building instanceof BarracksBuilding
                || building instanceof MageTowerBuilding;
    }

    /**
     * Rooms whose claims a load or a room re-check never drops: permanent homes, and since 10-24
     * the Town Hall (the Mayor keeps its bed for good). Only for keeping claims: villagers' bed
     * search still goes by {@link #isPermanentBedroom}, so it never hands out hall beds.
     */
    private static boolean keepsClaims(@Nullable Building building) {
        return isPermanentBedroom(building) || building instanceof TownHallBuilding;
    }

    /** The claimed bed when it is a Town Hall bed (the Mayor's, kept for good); else null. */
    @Nullable
    private BlockPos keptHallBed(UUID villagerId) {
        SavedBedAssignment claim = claimOf(villagerId);
        return claim != null && buildings.get(claim.buildingId()) instanceof TownHallBuilding ? claim.bedPos() : null;
    }

    /** A Town Hall claim going away is rare and was silent; say why. */
    private void logHallDrop(UUID villagerId, BlockPos bed, String why) {
        VillageManager.LOGGER.info("[VillageManager {}] Town Hall bed {} no longer claimed by {}: {}.",
                village.shortId(), bed, villagerId.toString().substring(0, 8), why);
    }

    static boolean buildingContainsBed(Building building, BlockPos bed) {
        if (building instanceof HouseBuilding house) return house.getAllBeds().contains(bed);
        if (building instanceof TownHallBuilding townHall) return townHall.getBeds().contains(bed);
        if (building instanceof BarracksBuilding barracks) return barracks.getBeds().contains(bed);
        if (building instanceof MageTowerBuilding tower) return tower.getBeds().contains(bed);
        return false;
    }

    private boolean assignBed(UUID villagerId, UUID buildingId, BlockPos bed) {
        if (buildings.get(buildingId) instanceof BarracksBuilding barracks
                && !canClaimBarracks(barracks, villagerId)) return false;
        if (buildings.get(buildingId) instanceof MageTowerBuilding tower
                && !canClaimTower(tower, villagerId)) return false;
        BlockPos oldBed = assignedBeds.put(villagerId, bed);
        UUID oldBuilding = assignedBedBuildings.put(villagerId, buildingId);
        SavedBedAssignment pending = savedBedAssignments.remove(villagerId);
        if (pending != null && !pending.bedPos().equals(bed)) {
            bedOwners.remove(pending.bedPos().asLong(), villagerId);
        }
        if (oldBed != null && !oldBed.equals(bed)) bedOwners.remove(oldBed.asLong(), villagerId);
        bedOwners.put(bed.asLong(), villagerId);
        ResidentState resident = barracksRoster.get(villagerId);
        if (buildings.get(buildingId) instanceof BarracksBuilding
                && resident != null && resident.profession() == Profession.CAPTAIN) barracksCaptains.add(villagerId);
        else barracksCaptains.remove(villagerId);
        if (!bed.equals(oldBed) || !buildingId.equals(oldBuilding)) {
            village.markPersistentDataDirty();
            queueBarracksAssignments();
        }
        return true;
    }

    /** Counts saved and hydrated claims together, including missing beds and unloaded residents. */
    private boolean canClaimBarracks(BarracksBuilding barracks, UUID villagerId) {
        VillagerModel model = village.getModel(villagerId);
        NpcEntity npc = model == null ? null : model.puppet();
        if (npc == null || !isDefender(npc.getProfession())) return false;
        SavedBedAssignment own = claimOf(villagerId);
        int claims = claimsIn(barracks.getItemFrameId());
        if (own != null && own.buildingId().equals(barracks.getItemFrameId())) {
            return claims <= BarracksBuilding.CAPACITY;
        }
        int limit = npc.getProfession() == Profession.CAPTAIN || hasCaptain(barracks, null)
                ? BarracksBuilding.CAPACITY : BarracksBuilding.CAPACITY - 1;
        return claims < limit && (npc.getProfession() != Profession.CAPTAIN || !hasCaptain(barracks, null));
    }

    /** Mages only, and at most {@link MageTowerBuilding#CAPACITY} of them; a mage already here always fits. */
    private boolean canClaimTower(MageTowerBuilding tower, UUID villagerId) {
        VillagerModel model = village.getModel(villagerId);
        NpcEntity npc = model == null ? null : model.puppet();
        if (npc == null || npc.getProfession() != Profession.MAGE) return false;
        SavedBedAssignment own = claimOf(villagerId);
        if (own != null && own.buildingId().equals(tower.getItemFrameId())) return true;
        return claimsIn(tower.getItemFrameId()) < MageTowerBuilding.CAPACITY;
    }

    /** A free, reachable Mage Tower bed for this mage, in its own tower first; null when there is none. */
    @Nullable
    private BlockPos claimTowerBed(VillagerModel model, List<MageTowerBuilding> towers) {
        SavedBedAssignment current = claimOf(model.npcId);
        List<MageTowerBuilding> order = new ArrayList<>(towers);
        if (current != null && buildings.get(current.buildingId()) instanceof MageTowerBuilding own
                && order.remove(own)) order.addFirst(own);
        for (MageTowerBuilding tower : order) {
            if (!canClaimTower(tower, model.npcId)) continue;
            for (BlockPos bed : tower.getBeds()) {
                UUID owner = bedOwners.get(bed.asLong());
                if (owner != null && !owner.equals(model.npcId)
                        || !isAssignableBedForBuilding(bed, tower.getItemFrameId())
                        || village.getLevel().getBlockState(bed).getValue(BlockStateProperties.OCCUPIED)
                        || isSleptInByOther(model, bed)
                        || reachableBedWalkTargetInBuilding(model, bed, model.puppet().blockPosition(), tower) == null) continue;
                if (assignBed(model.npcId, tower.getItemFrameId(), bed)) return bed;
            }
        }
        return null;
    }

    /** Repairs old saves before new claims or resumed sleep can exceed the limit. */
    private void enforceBarracksCapacity() {
        Map<UUID, List<SavedBedAssignment>> byBarracks = new HashMap<>();
        for (SavedBedAssignment claim : getSavedBedAssignments()) {
            if (buildings.isBarracks(claim.buildingId())) {
                byBarracks.computeIfAbsent(claim.buildingId(), ignored -> new ArrayList<>()).add(claim);
            }
        }
        for (List<SavedBedAssignment> claims : byBarracks.values()) {
            claims.sort(Comparator.comparingInt((SavedBedAssignment claim) -> claim.barracksCaptain() ? 0 : 1)
                    .thenComparing(SavedBedAssignment::villagerId));
            int limit = claims.stream().anyMatch(SavedBedAssignment::barracksCaptain)
                    ? BarracksBuilding.CAPACITY : BarracksBuilding.CAPACITY - 1;
            for (int index = limit; index < claims.size(); index++) {
                UUID id = claims.get(index).villagerId();
                releaseBedFor(id);
                VillagerModel model = village.getModel(id);
                NpcEntity npc = model == null ? null : model.puppet();
                if (npc == null || !npc.isAlive() || npc.isRemoved()) continue;
                if (model.brain().currentActivity() instanceof GoHomeActivity home) {
                    home.onBedClaimRevoked(new com.alex.test.village.brain.Activity.Context(
                            model.brain(), model, village, village.getLevel(), npc));
                } else if (npc.isSleeping()) {
                    npc.stopSleeping();
                    npc.setPose(net.minecraft.world.entity.Pose.STANDING);
                    npc.setAnimation(com.alex.test.animation.VillagerAnimationState.IDLE);
                }
            }
        }
    }

    // =========================================================================
    // Barracks allocation: event-driven, with bounded retries for sleep/loading/routes.
    // =========================================================================

    void queueBarracksAssignments() {
        barracksAssignmentsQueued = true;
    }

    private static boolean isDefender(Profession profession) {
        return profession.isDefender();
    }

    private static boolean isResting(VillagerModel model) {
        return model.puppet().isSleeping()
                || model.brain().currentActivity() instanceof GoHomeActivity home && !home.canTransferHome();
    }

    /** Cheap roster checks once per second; route retries at most once per five seconds. */
    void tickBarracksAssignments() {
        ServerLevel level = village.getLevel();
        if (level == null || village.getGraph() == null || level.getGameTime() < nextRosterCheck) return;
        nextRosterCheck = level.getGameTime() + 20;
        Map<UUID, ResidentState> roster = new HashMap<>();
        List<VillagerModel> residents = new ArrayList<>();
        for (VillagerModel model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.isRemoved()) continue;
            residents.add(model);
            roster.put(model.npcId, new ResidentState(npc.getProfession(), isResting(model)));
            SavedBedAssignment claim = claimOf(model.npcId);
            boolean wasCaptain = barracksCaptains.contains(model.npcId);
            Building home = claim != null ? buildings.get(claim.buildingId()) : null;
            boolean captain = npc.getProfession() == Profession.CAPTAIN && claim != null
                    && (home instanceof BarracksBuilding || home == null && claim.barracksCaptain())
                    && (wasCaptain || !(home instanceof BarracksBuilding barracks) || !hasCaptain(barracks, model.npcId));
            if (captain) barracksCaptains.add(model.npcId);
            else barracksCaptains.remove(model.npcId);
            if (captain != wasCaptain) {
                if (savedBedAssignments.containsKey(model.npcId)) {
                    savedBedAssignments.put(model.npcId, new SavedBedAssignment(model.npcId,
                            claim.buildingId(), claim.bedPos(), captain));
                }
                queueBarracksAssignments();
                village.markPersistentDataDirty();
            }
        }
        if (!roster.equals(barracksRoster)) queueBarracksAssignments();
        barracksRoster = roster;
        if (!barracksAssignmentsQueued && level.getGameTime() < nextBarracksRetry) return;
        if (village.hasPendingNavRebuild()) return;
        hydrateSavedBedAssignments();
        barracksAssignmentsQueued = false;
        nextBarracksRetry = Long.MAX_VALUE;
        residents.sort(Comparator.comparingInt((VillagerModel model) ->
                        model.puppet().getProfession() == Profession.CAPTAIN ? 0 : 1)
                .thenComparing(model -> model.npcId));
        List<BarracksBuilding> barracks = buildings.all().stream()
                .filter(b -> b instanceof BarracksBuilding && b.isValid())
                .map(b -> (BarracksBuilding) b)
                .sorted(Comparator.comparing(Building::getItemFrameId)).toList();
        List<MageTowerBuilding> towers = buildings.all().stream()
                .filter(b -> b instanceof MageTowerBuilding && b.isValid())
                .map(b -> (MageTowerBuilding) b)
                .sorted(Comparator.comparing(Building::getItemFrameId)).toList();
        boolean retry = false;
        for (VillagerModel model : residents) {
            Profession profession = model.puppet().getProfession();
            SavedBedAssignment claim = claimOf(model.npcId);
            Building home = claim != null ? buildings.get(claim.buildingId()) : null;
            // Never move a loaded villager ahead of its own saved home's restoration.
            if (claim != null && home == null && buildings.hasSavedMarker(claim.buildingId())) {
                retry |= isDefender(profession);
                continue;
            }
            if (isResting(model)) {
                retry |= isDefender(profession) || home instanceof BarracksBuilding
                        || home instanceof MageTowerBuilding;
                continue;
            }
            // A Mage Tower bed is a mage's: one that changed job gives it up.
            if (home instanceof MageTowerBuilding && profession != Profession.MAGE) {
                releaseBedFor(model.npcId);
                home = null;
                if (!isDefender(profession)) {
                    claimFreeBed(model, null);
                    continue;
                }
            }
            if (!isDefender(profession)) {
                if (home instanceof BarracksBuilding) {
                    releaseBedFor(model.npcId);
                    claimFreeBed(model, null);
                }
                continue;
            }
            // A mage sleeps in a Mage Tower when one has room, and moves out of a
            // Barracks or house bed into it; a full tower leaves it to the Barracks rules.
            if (profession == Profession.MAGE) {
                if (home instanceof MageTowerBuilding && !isClaimedBedGone(model.npcId)) continue;
                if (claimTowerBed(model, towers) != null) continue;
                retry |= towers.stream().anyMatch(tower -> canClaimTower(tower, model.npcId));
            }
            if (home instanceof BarracksBuilding && !isClaimedBedGone(model.npcId)
                    && (profession != Profession.CAPTAIN || barracksCaptains.contains(model.npcId))) continue;
            BlockPos replacement = claimBarracksBed(model, barracks, profession == Profession.CAPTAIN);
            if (replacement == null) {
                retry |= !barracks.isEmpty();
                if (home == null || (home instanceof BarracksBuilding || home instanceof MageTowerBuilding)
                        && isClaimedBedGone(model.npcId)) {
                    claimFreeBed(model, null);
                }
            }
        }
        // assignBed/releaseBedFor queue changes, but this pass already allocated sequentially.
        barracksAssignmentsQueued = false;
        if (retry) nextBarracksRetry = level.getGameTime() + 100;
    }

    private boolean hasCaptain(BarracksBuilding barracks, @Nullable UUID except) {
        for (UUID id : barracksCaptains) {
            if (id.equals(except)) continue;
            SavedBedAssignment claim = claimOf(id);
            if (claim != null && claim.buildingId().equals(barracks.getItemFrameId())) return true;
        }
        return false;
    }

    boolean barracksNeedsCaptain(BarracksBuilding barracks) {
        return !hasCaptain(barracks, null);
    }

    /** The first usable unclaimed bed is reserved while the barracks lacks a captain. */
    @Nullable
    BlockPos reservedCaptainBed(BarracksBuilding barracks) {
        if (claimsIn(barracks.getItemFrameId()) >= BarracksBuilding.CAPACITY) return null;
        for (UUID id : barracksCaptains) {
            SavedBedAssignment claim = claimOf(id);
            if (claim != null && claim.buildingId().equals(barracks.getItemFrameId())
                    && (!village.getLevel().isLoaded(claim.bedPos())
                    || isAssignableBedForBuilding(claim.bedPos(), claim.buildingId()))) return null;
        }
        for (BlockPos bed : barracks.getBeds()) {
            if (isAssignableBedForBuilding(bed, barracks.getItemFrameId())
                    && !bedOwners.containsKey(bed.asLong()) && !isSleptInByOther(null, bed)
                    && !village.getLevel().getBlockState(bed).getValue(BlockStateProperties.OCCUPIED)) return bed;
        }
        return null;
    }

    @Nullable
    private BlockPos claimBarracksBed(VillagerModel model, List<BarracksBuilding> barracks, boolean captain) {
        SavedBedAssignment current = claimOf(model.npcId);
        List<BarracksBuilding> order = new ArrayList<>(barracks);
        // A replacement for a destroyed bed stays in the same barracks when possible.
        if (current != null && buildings.get(current.buildingId()) instanceof BarracksBuilding own
                && order.remove(own)) order.addFirst(own);
        for (BarracksBuilding building : order) {
            if (!canClaimBarracks(building, model.npcId)) continue;
            if (captain && hasCaptain(building, model.npcId)) continue;
            BlockPos reserved = captain ? null : reservedCaptainBed(building);
            for (BlockPos bed : building.getBeds()) {
                UUID owner = bedOwners.get(bed.asLong());
                if (bed.equals(reserved) || owner != null && !owner.equals(model.npcId)
                        || !isAssignableBedForBuilding(bed, building.getItemFrameId())
                        || village.getLevel().getBlockState(bed).getValue(BlockStateProperties.OCCUPIED)
                        || isSleptInByOther(model, bed)
                        || reachableBedWalkTargetInBuilding(model, bed, model.puppet().blockPosition(), building) == null) continue;
                // assignBed replaces the old claim without an intermediate homeless state.
                if (assignBed(model.npcId, building.getItemFrameId(), bed)) return bed;
            }
        }
        return null;
    }

    // =========================================================================
    // House claims
    // =========================================================================

    /** The house this villager's claim is in, registered and whatever its state. */
    @Nullable
    private HouseBuilding claimHouse(UUID villagerId) {
        UUID buildingId = assignedBedBuildings.get(villagerId);
        return buildingId != null && buildings.get(buildingId) instanceof HouseBuilding house ? house : null;
    }

    /** House and barracks claims remain homes even during rescans and partial loading. */
    @Nullable
    private BlockPos keptHomeBed(UUID villagerId) {
        SavedBedAssignment claim = claimOf(villagerId);
        if (claim == null) return null;
        Building building = buildings.get(claim.buildingId());
        return isPermanentBedroom(building) || building == null && buildings.hasSavedMarker(claim.buildingId())
                ? claim.bedPos() : null;
    }

    /** Claims held in this house, broken beds included. */
    int claimsIn(UUID houseId) {
        return houseClaims(houseId).size();
    }

    /** Whether one more villager may claim a bed in this house. */
    private boolean hasRoom(HouseBuilding house, UUID villagerId) {
        return house.getItemFrameId().equals(assignedBedBuildings.get(villagerId))
                || claimsIn(house.getItemFrameId()) < house.getCapacity();
    }

    /** Villager id to claimed bed for every claim in this house, broken beds included. */
    Map<UUID, BlockPos> houseClaims(UUID houseId) {
        Map<UUID, BlockPos> claims = new LinkedHashMap<>();
        for (Map.Entry<UUID, UUID> entry : assignedBedBuildings.entrySet()) {
            if (!houseId.equals(entry.getValue())) continue;
            BlockPos bed = assignedBeds.get(entry.getKey());
            if (bed != null) claims.put(entry.getKey(), bed);
        }
        for (SavedBedAssignment claim : savedBedAssignments.values()) {
            if (houseId.equals(claim.buildingId())) claims.putIfAbsent(claim.villagerId(), claim.bedPos());
        }
        return claims;
    }

    /** @see VillageManager#releaseHallClaimsExcept */
    int releaseHallClaimsExcept(UUID hallId, UUID keeperId) {
        int freed = 0;
        // Only Mayors claim Town Hall beds, so any other holder is one that died or left.
        for (Map.Entry<UUID, BlockPos> claim : houseClaims(hallId).entrySet()) {
            if (claim.getKey().equals(keeperId)) continue;
            releaseBedFor(claim.getKey());
            freed++;
            VillageManager.LOGGER.info("[VillageManager {}] Freed Town Hall bed {} held by {}, not the Mayor.",
                    village.shortId(), claim.getValue(), claim.getKey().toString().substring(0, 8));
        }
        return freed;
    }

    /** The villager's claimed bed and its building, broken bed or not. */
    @Nullable
    SavedBedAssignment claimOf(UUID villagerId) {
        BlockPos bed = assignedBeds.get(villagerId);
        UUID buildingId = assignedBedBuildings.get(villagerId);
        if (bed != null && buildingId != null) {
            return new SavedBedAssignment(villagerId, buildingId, bed, barracksCaptains.contains(villagerId));
        }
        return savedBedAssignments.get(villagerId);
    }

    /**
     * A player gives {@code bed} in {@code house} to this villager. Whoever
     * held the bed swaps into the villager's old bed in the same house, or
     * loses their claim and picks a free bed at bedtime. False when the house
     * is full and neither the villager nor the bed already belongs to it.
     */
    boolean assignByPlayer(UUID villagerId, HouseBuilding house, BlockPos bed) {
        if (!house.getAllBeds().contains(bed)) return false;
        UUID houseId = house.getItemFrameId();
        UUID previousOwner = bedOwners.get(bed.asLong());
        if (villagerId.equals(previousOwner)) return true;
        if (previousOwner == null && !hasRoom(house, villagerId)) return false;

        BlockPos oldBed = assignedBeds.get(villagerId);
        boolean oldBedHere = oldBed != null && houseId.equals(assignedBedBuildings.get(villagerId));
        releaseBedFor(villagerId);
        if (previousOwner != null) {
            releaseBedFor(previousOwner);
            if (oldBedHere) assignBed(previousOwner, houseId, oldBed);
        }
        assignBed(villagerId, houseId, bed);
        VillageManager.LOGGER.info("[VillageManager {}] Player gave bed {} to villager {}.",
                village.shortId(), bed, villagerId.toString().substring(0, 8));
        return true;
    }

    /**
     * A player gives a Mage Tower bed to a mage, as {@link #assignByPlayer}
     * does in a house: whoever held it swaps into the mage's old bed in this
     * tower, or loses the claim and finds another bed. Returns the refusal,
     * empty when it went through.
     */
    String assignTowerBedByPlayer(UUID villagerId, MageTowerBuilding tower, BlockPos bed) {
        if (!tower.getBeds().contains(bed)) return "That bed is no longer part of the Mage Tower.";
        VillagerModel model = village.getModel(villagerId);
        NpcEntity npc = model == null ? null : model.puppet();
        if (npc == null || npc.getProfession() != Profession.MAGE) return "Only mages sleep in the Mage Tower.";
        UUID towerId = tower.getItemFrameId();
        UUID previousOwner = bedOwners.get(bed.asLong());
        if (villagerId.equals(previousOwner)) return "";
        if (previousOwner == null && !canClaimTower(tower, villagerId)) {
            return "The tower is full: " + MageTowerBuilding.CAPACITY + " of " + MageTowerBuilding.CAPACITY
                    + " mages sleep here. Move one of them instead.";
        }
        BlockPos oldBed = assignedBeds.get(villagerId);
        boolean oldBedHere = oldBed != null && towerId.equals(assignedBedBuildings.get(villagerId));
        releaseBedFor(villagerId);
        if (previousOwner != null) {
            releaseBedFor(previousOwner);
            if (oldBedHere) assignBed(previousOwner, towerId, oldBed);
        }
        assignBed(villagerId, towerId, bed);
        VillageManager.LOGGER.info("[VillageManager {}] Player gave Mage Tower bed {} to villager {}.",
                village.shortId(), bed, villagerId.toString().substring(0, 8));
        return "";
    }

    /**
     * A player gives {@code bed} in the Town Hall to the Mayor ({@code mayorId} = its npc id). Its old
     * hall bed comes free; anyone else holding this one (only a Mayor that is gone can) loses it.
     * Empty when it went through, else why not.
     */
    String assignHallBedByPlayer(UUID mayorId, TownHallBuilding hall, BlockPos bed) {
        if (!hall.getBeds().contains(bed)) return "That bed is no longer part of the Town Hall.";
        if (!isAssignableBedForBuilding(bed, hall.getItemFrameId())) return "That bed cannot be used right now.";
        UUID previousOwner = bedOwners.get(bed.asLong());
        if (mayorId.equals(previousOwner)) return "";
        if (previousOwner != null) {
            logHallDrop(previousOwner, bed, "a player gave the bed to the Mayor");
            releaseBedFor(previousOwner);
        }
        releaseBedFor(mayorId);
        assignBed(mayorId, hall.getItemFrameId(), bed);
        VillageManager.LOGGER.info("[VillageManager {}] Player gave Town Hall bed {} to the Mayor {}.",
                village.shortId(), bed, mayorId.toString().substring(0, 8));
        return "";
    }

    // =========================================================================
    // Queries and requests (exposed through VillageManager)
    // =========================================================================

    /** @see VillageManager#hasFreeBed */
    boolean hasFreeBed() {
        for (Building building : buildings.all()) {
            if (!(building instanceof HouseBuilding house) || !house.isValid()) continue;
            if (claimsIn(house.getItemFrameId()) >= house.getCapacity()) continue;
            for (BlockPos bed : house.getAllBeds()) {
                if (!bedOwners.containsKey(bed.asLong())) return true;
            }
        }
        return false;
    }

    /** @see VillageManager#isVillagerBed */
    boolean isVillagerBed(BlockPos bed) {
        if (bedOwners.containsKey(bed.asLong())) return true;
        for (SavedBedAssignment claim : savedBedAssignments.values()) {
            if (claim.bedPos().equals(bed)) return true;
        }
        for (Building building : buildings.all()) {
            if (isPermanentBedroom(building) && buildingContainsBed(building, bed)) return true;
        }
        return false;
    }

    /**
     * @see VillageManager#getAssignedBed
     * Null while the bed cannot be used; a permanent home claim (or the Mayor's hall bed) is kept all the same.
     */
    @Nullable
    BlockPos getAssignedBed(UUID villagerId) {
        BlockPos bed = assignedBeds.get(villagerId);
        if (bed == null) return null;
        UUID buildingId = assignedBedBuildings.get(villagerId);
        if (isAssignableBedForBuilding(bed, buildingId)) return bed;
        if (keptHomeBed(villagerId) == null && keptHallBed(villagerId) == null) releaseBedFor(villagerId);
        return null;
    }

    /** The villager's permanent home even with a broken bed, else its usable bed's room. */
    @Nullable
    Building getAssignedBedBuilding(UUID villagerId) {
        Building home = buildings.get(assignedBedBuildings.get(villagerId));
        if (isPermanentBedroom(home)) return home.isValid() ? home : null;
        if (getAssignedBed(villagerId) == null) return null;
        return buildings.get(assignedBedBuildings.get(villagerId));
    }

    /** @see VillageManager#requestBedFor */
    @Nullable
    BlockPos requestBedFor(VillagerModel model) {
        NpcEntity npc = model.puppet();
        // A permanently lost bed must try barracks before falling back to a house,
        // even if the normal roster check ran before navigation finished rebuilding.
        if (npc != null && isDefender(npc.getProfession()) && !isResting(model)
                && isClaimedBedGone(model.npcId)) {
            queueBarracksAssignments();
            nextRosterCheck = 0;
        }
        tickBarracksAssignments();
        UUID villagerId = model.npcId;
        if (keptHomeBed(villagerId) != null) {
            if (isClaimedBedGone(villagerId)) {
                // Barracks allocation ran first. Now try a house, their own house first. With
                // none free they keep the claim and sleep elsewhere until one turns up.
                return claimFreeBed(model, claimHouse(villagerId));
            }
            // Their own bed or nothing: a bad night never hands them another.
            BlockPos own = getAssignedBed(villagerId);
            if (own == null || isSleptInByOther(model, own) || reachableBedWalkTarget(model, own) == null) {
                return null;
            }
            return own;
        }
        BlockPos existing = getAssignedBed(villagerId);
        if (existing != null && !isSleptInByOther(model, existing)
                && reachableBedWalkTarget(model, existing) != null) return existing;
        if (existing != null) releaseBedFor(villagerId);
        return claimFreeBed(model, null);
    }

    // =========================================================================
    // Diagnostics: why a villager has no bed tonight
    // =========================================================================

    /**
     * Read-only account of why {@link #requestBedFor} found no bed for this
     * villager: every house, and every bed in it, with the first check it
     * fails. Follows requestBedFor's own order (a permanent home first) so the
     * reasons match the decision. For the log and {@code /villager beds why};
     * claims nothing.
     */
    List<String> explainNoBed(VillagerModel model) {
        List<String> lines = new ArrayList<>();
        ServerLevel level = village.getLevel();
        NavGraph graph = village.getGraph();
        NpcEntity npc = model.puppet();
        if (level == null || graph == null) {
            lines.add("village has no level or nav graph loaded");
            return lines;
        }
        if (village.hasPendingNavRebuild()) lines.add("nav graph rebuild pending (beds may read as unreachable)");
        if (npc == null) {
            lines.add("villager not loaded (no puppet)");
        } else if (graph.nearestOnFloor(NavGraph.feetCell(npc), 3, 3) == null) {
            lines.add("villager at " + fmt(npc.blockPosition()) + " is not on the nav graph: nothing reads as reachable");
        }

        UUID villagerId = model.npcId;
        SavedBedAssignment claim = claimOf(villagerId);
        if (claim != null) {
            Building home = buildings.get(claim.buildingId());
            String homeName = home != null ? home.getDisplayName() : "unrestored building";
            lines.add("claims bed " + fmt(claim.bedPos()) + " in " + homeName
                    + (savedBedAssignments.containsKey(villagerId) ? " (parked, waiting for the room to load)" : ""));
            if (keptHomeBed(villagerId) != null && !isClaimedBedGone(villagerId)) {
                // requestBedFor: "their own bed or nothing".
                lines.add("permanent home: only its own bed is tried");
                String problem = home != null ? bedProblem(model, home, claim.bedPos(), true) : "room not restored";
                lines.add("  own bed " + fmt(claim.bedPos()) + ": " + problem);
                return lines;
            }
            if (keptHomeBed(villagerId) != null) lines.add("claimed bed is gone: free beds are tried, own house first");
        } else {
            lines.add("no bed claim");
        }

        boolean anyHouse = false;
        for (Building b : buildings.all()) {
            if (!(b instanceof HouseBuilding house)) continue;
            anyHouse = true;
            StringBuilder head = new StringBuilder(house.getDisplayName()).append(" at ").append(fmt(house.getDoorPos()));
            if (!house.isValid()) {
                head.append(": INVALID (").append(!house.isShapeValid() ? "room scan failed" : "not reachable from the village")
                        .append(")");
                lines.add(head.toString());
                continue;
            }
            Map<UUID, BlockPos> claims = houseClaims(house.getItemFrameId());
            head.append(": claims ").append(claims.size()).append('/').append(house.getCapacity());
            if (!hasRoom(house, villagerId)) {
                head.append(" FULL, held by ");
                List<String> holders = new ArrayList<>();
                for (UUID holder : claims.keySet()) {
                    holders.add(shortId(holder) + (village.getModel(holder) == null ? " (not a villager here!)" : ""));
                }
                head.append(String.join(", ", holders));
                lines.add(head.toString());
                continue;
            }
            lines.add(head.toString());
            if (house.getAllBeds().isEmpty()) lines.add("  no beds found in the room scan");
            for (BlockPos bed : house.getAllBeds()) {
                lines.add("  bed " + fmt(bed) + ": " + bedProblem(model, house, bed, false));
            }
        }
        if (!anyHouse) lines.add("no houses registered");
        return lines;
    }

    /** The first reason {@code bed} cannot be this villager's tonight, or "OK" and the tile it would walk to. */
    private String bedProblem(VillagerModel model, Building building, BlockPos bed, boolean ownBed) {
        ServerLevel level = village.getLevel();
        if (!level.isLoaded(bed)) return "chunk not loaded";
        BlockState state = level.getBlockState(bed);
        if (!(state.getBlock() instanceof BedBlock)
                || state.getValue(BlockStateProperties.BED_PART) != BedPart.HEAD) return "no bed head here any more";
        if (!building.isValid()) return building.getDisplayName() + " is invalid";
        if (!buildingContainsBed(building, bed)) return "not in the room's last scan";
        UUID owner = bedOwners.get(bed.asLong());
        if (!ownBed && owner != null && !owner.equals(model.npcId)) return "claimed by " + shortId(owner);
        if (!ownBed && isBarracksBed(bed)) return "barracks or Mage Tower bed";
        if (isSleptInByOther(model, bed)) return "someone else is lying in it";
        BlockPos target = reachableBedWalkTarget(model, bed);
        if (target != null) return "OK, would walk to " + fmt(target);
        return "no reachable tile beside it: " + describeBedSideTiles(model, building, bed);
    }

    /** Each tile beside the bed's two halves and what rules it out. */
    private String describeBedSideTiles(VillagerModel model, Building building, BlockPos head) {
        ServerLevel level = village.getLevel();
        NavGraph graph = village.getGraph();
        List<BlockPos> halves = new ArrayList<>(List.of(head));
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockState other = level.getBlockState(head.relative(dir));
            if (other.getBlock() instanceof BedBlock
                    && other.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT) {
                halves.add(head.relative(dir));
                break;
            }
        }
        List<String> tiles = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos half : halves) {
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos tile = half.relative(dir);
                // A wall, or outside the room.
                if (halves.contains(tile) || !seen.add(tile) || !building.getNavMeshBlocks().contains(tile)) continue;
                String why;
                if (graph.nodeAt(tile.getX(), tile.getY(), tile.getZ()) == null) why = "room tile but no nav node";
                else if (model.isGoalBlacklisted(tile)) why = "blacklisted after a failed walk";
                else if (!model.canReach(graph, tile)) why = "unreachable from the villager";
                else why = "reachable, yet not chosen";
                tiles.add(fmt(tile) + " " + why);
            }
        }
        return tiles.isEmpty() ? "no room tile touches the bed" : String.join("; ", tiles);
    }

    private static String fmt(BlockPos pos) {
        return pos == null ? "?" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    /**
     * The claimed bed's block is no longer a bed, or the home's last good scan no longer
     * finds it inside. A home mid-rescan or not loaded does not count: that passes.
     */
    private boolean isClaimedBedGone(UUID villagerId) {
        Building home = buildings.get(assignedBedBuildings.get(villagerId));
        BlockPos bed = assignedBeds.get(villagerId);
        ServerLevel level = village.getLevel();
        if (!keepsClaims(home) || bed == null || level == null || !level.isLoaded(bed)
                || village.hasPendingNavRebuild()) return false;
        BlockState state = level.getBlockState(bed);
        if (!(state.getBlock() instanceof BedBlock)
                || state.getValue(BlockStateProperties.BED_PART) != BedPart.HEAD) return true;
        return home.isValid() && !buildingContainsBed(home, bed);
    }

    /** Claims the first free, reachable house bed, in {@code preferred} first; null when there is none. */
    @Nullable
    private BlockPos claimFreeBed(VillagerModel model, @Nullable HouseBuilding preferred) {
        UUID villagerId = model.npcId;
        List<Building> order = new java.util.ArrayList<>(buildings.all());
        if (preferred != null && order.remove(preferred)) order.addFirst(preferred);
        for (Building b : order) {
            if (!(b instanceof HouseBuilding house) || !house.isValid()) continue;
            if (!hasRoom(house, villagerId)) continue;
            for (BlockPos bed : house.getAllBeds()) {
                if (!isAssignableBedForBuilding(bed, house.getItemFrameId())
                        || bedOwners.containsKey(bed.asLong())
                        || isBarracksBed(bed)
                        || isSleptInByOther(model, bed)) continue;
                if (reachableBedWalkTarget(model, bed) == null) continue;
                assignBed(villagerId, house.getItemFrameId(), bed);
                VillageManager.LOGGER.info("[VillageManager {}] Assigned house bed {} to villager {}.",
                        village.shortId(), bed, villagerId.toString().substring(0, 8));
                return bed;
            }
        }
        return null;
    }

    /** A Barracks or Mage Tower bed: never a house bed for whoever comes along. */
    private boolean isBarracksBed(BlockPos bed) {
        for (Building building : buildings.all()) {
            if ((building instanceof BarracksBuilding || building instanceof MageTowerBuilding)
                    && buildingContainsBed(building, bed)) return true;
        }
        return false;
    }

    /** @see VillageManager#requestBedInTownHall */
    @Nullable
    BlockPos requestBedInTownHall(VillagerModel model, TownHallBuilding townHall) {
        UUID occupantId = model.npcId;
        SavedBedAssignment claim = claimOf(occupantId);
        if (claim != null && townHall.getItemFrameId().equals(claim.buildingId())
                && !isClaimedBedGone(occupantId)) {
            // Its own bed for good (10-24), like a house bed: a night it cannot reach it, or
            // someone else lies in it, it sleeps on the hall floor and keeps the bed.
            BlockPos own = getAssignedBed(occupantId);
            return own != null && !isSleptInByOther(model, own)
                    && reachableBedWalkTarget(model, own) != null ? own : null;
        }
        if (claim != null && keptHallBed(occupantId) != null) {
            logHallDrop(occupantId, claim.bedPos(), townHall.getItemFrameId().equals(claim.buildingId())
                    ? "its bed is gone" : "it is in another Town Hall");
        }
        releaseBedFor(occupantId);
        ServerLevel level = village.getLevel();
        if (level == null) return null;

        for (BlockPos bed : townHall.getBeds()) {
            BlockState state = level.getBlockState(bed);
            if (!(state.getBlock() instanceof BedBlock)) continue;
            if (state.getValue(BlockStateProperties.OCCUPIED)) continue;
            if (bedOwners.containsKey(bed.asLong())) continue;
            if (isSleptInByOther(model, bed)) continue;
            if (reachableBedWalkTarget(model, bed) == null) continue;
            assignBed(occupantId, townHall.getItemFrameId(), bed);
            return bed;
        }
        return null;
    }

    /** @see VillageManager#claimBedForSleep */
    boolean claimBedForSleep(VillagerModel model, BlockPos bed, boolean alreadyLyingInIt) {
        if (model == null || bed == null) return false;
        if (isSleptInByOther(model, bed)) return false;
        UUID owner = bedOwners.get(bed.asLong());
        if (owner != null) return owner.equals(model.npcId);

        // A villager with a permanent home bed sleeps there or not at all.
        // Its claim may still be waiting for the house to be restored after a
        // load, in which case lying in that very bed is fine.
        BlockPos own = keptHomeBed(model.npcId);
        if (own != null) return own.equals(bed);
        // Same for the Mayor's hall bed: lying in its old one after a player gave it another,
        // it gets up and walks to the new one instead of taking the old one back.
        BlockPos hallBed = keptHallBed(model.npcId);
        if (hallBed != null) return hallBed.equals(bed);

        // Even the resume/unregistered-bed path must respect soldier-only beds and reservations.
        for (Building b : buildings.all()) {
            if (!(b instanceof BarracksBuilding barracks) || !buildingContainsBed(b, bed)) continue;
            if (!canClaimBarracks(barracks, model.npcId) || !isAssignableBedForBuilding(bed, b.getItemFrameId())) return false;
            NpcEntity npc = model.puppet();
            if (npc == null || !isDefender(npc.getProfession())) return false;
            if (npc.getProfession() == Profession.CAPTAIN) {
                if (hasCaptain(barracks, model.npcId)) return false;
            } else if (bed.equals(reservedCaptainBed(barracks))) return false;
        }
        // A Mage Tower bed only for a mage, while the tower has room for it.
        for (Building b : buildings.all()) {
            if (!(b instanceof MageTowerBuilding tower) || !buildingContainsBed(b, bed)) continue;
            if (!canClaimTower(tower, model.npcId) || !isAssignableBedForBuilding(bed, b.getItemFrameId())) return false;
        }

        // Nobody holds it. A bed that is not assignable right now is still
        // nobody else's, so the villager may use it; it just cannot be recorded.
        boolean listed = false;
        for (Building b : buildings.all()) {
            if (!buildingContainsBed(b, bed)) continue;
            listed = true;
            if (!isBedroomBuilding(b) || !isAssignableBedForBuilding(bed, b.getItemFrameId())) continue;
            if (b instanceof HouseBuilding house && !hasRoom(house, model.npcId)) return false;
            if (!assignBed(model.npcId, b.getItemFrameId(), bed)) return false;
            break;
        }
        // A bed no building lists: its building was removed, or one near it
        // cannot say yet. Used to be a yes, which kept villagers asleep in a
        // removed house until morning.
        return listed || mayBeListedSoon(bed);
    }

    /** How near a broken or not-yet-restored room must be to a bed to maybe hold it. */
    private static final double UNKNOWN_ROOM_REACH_SQ = 32 * 32;

    /**
     * No building lists {@code bed}, but one may soon: a bedroom near it is
     * broken right now (a broken room lists no beds until it is whole), or a
     * saved building near it is not back yet after a load.
     */
    private boolean mayBeListedSoon(BlockPos bed) {
        for (Building b : buildings.all()) {
            if (b.isValid() || !(isPermanentBedroom(b) || b instanceof TownHallBuilding)) continue;
            if (b.getDoorPos().distSqr(bed) <= UNKNOWN_ROOM_REACH_SQ) return true;
        }
        return buildings.hasUnrestoredBuildingNear(bed, UNKNOWN_ROOM_REACH_SQ);
    }

    /**
     * Someone other than this villager is lying in {@code bed} right now.
     * The claim table alone missed this whenever a claim was dropped while
     * its owner slept, and the next villager to ask was handed the same bed.
     */
    private boolean isSleptInByOther(VillagerModel model, BlockPos bed) {
        ServerLevel level = village.getLevel();
        if (level == null || bed == null) return false;
        NpcEntity self = model != null ? model.puppet() : null;
        return !level.getEntitiesOfClass(LivingEntity.class, new AABB(bed).inflate(2.0),
                e -> e != self && e.isAlive() && e.isSleeping()
                        && e.getSleepingPos().filter(bed::equals).isPresent()).isEmpty();
    }

    /** @see VillageManager#reachableBedWalkTarget */
    @Nullable
    BlockPos reachableBedWalkTarget(VillagerModel model, BlockPos bed) {
        NavGraph navGraph = village.getGraph();
        if (navGraph == null || model == null || bed == null || village.getLevel() == null) return null;
        BlockPos from = model.puppet() != null ? model.puppet().blockPosition() : bed;

        UUID assignedBuildingId = assignedBedBuildings.get(model.npcId);
        Building assignedBuilding = assignedBuildingId != null ? buildings.get(assignedBuildingId) : null;
        BlockPos assignedTarget = reachableBedWalkTargetInBuilding(model, bed, from, assignedBuilding);
        if (assignedTarget != null) return assignedTarget;

        // Prefer the registered building that owns this bed. This prevents a bed
        // on the bedroom floor from snapping to an equally close storage-room node
        // at the same X/Z on another floor.
        for (Building b : buildings.all()) {
            BlockPos target = reachableBedWalkTargetInBuilding(model, bed, from, b);
            if (target != null) return model.canReach(navGraph, target) ? target : null;
        }

        return null;
    }

    @Nullable
    private BlockPos reachableBedWalkTargetInBuilding(
            VillagerModel model, BlockPos bed, BlockPos from, @Nullable Building building) {
        if (!isBedroomBuilding(building) || !buildingContainsBed(building, bed)) return null;
        ServerLevel level = village.getLevel();
        NavGraph navGraph = village.getGraph();
        // The nearest tile beside the bed that this villager can walk to, not
        // just the nearest: one boxed in at the head must not hide the open
        // one beside the foot.
        java.util.function.Predicate<BlockPos> reachable = tile -> model.canReach(navGraph, tile);
        if (building instanceof HouseBuilding house) {
            return house.getWalkTargetBesideBed(level, bed, navGraph, from, reachable);
        } else if (building instanceof TownHallBuilding townHall) {
            return townHall.getWalkTargetBesideBed(level, bed, navGraph, from, reachable);
        } else if (building instanceof BarracksBuilding barracks) {
            return barracks.getWalkTargetBesideBed(level, bed, navGraph, from, reachable);
        } else if (building instanceof MageTowerBuilding tower) {
            return tower.getWalkTargetBesideBed(level, bed, navGraph, from, reachable);
        }
        return null;
    }

    /**
     * Drops only temporary claims. House and barracks claims survive a night they
     * cannot be used; so does the Mayor's Town Hall bed (10-24) unless the bed is gone.
     */
    void releaseTemporaryBedFor(UUID villagerId) {
        if (keptHomeBed(villagerId) != null) return;
        BlockPos hallBed = keptHallBed(villagerId);
        if (hallBed != null) {
            if (!isClaimedBedGone(villagerId)) return;
            logHallDrop(villagerId, hallBed, "its bed is gone");
        }
        releaseBedFor(villagerId);
    }

    /** @see VillageManager#releaseBedFor */
    void releaseBedFor(UUID villagerId) {
        BlockPos bed = assignedBeds.remove(villagerId);
        assignedBedBuildings.remove(villagerId);
        SavedBedAssignment saved = savedBedAssignments.remove(villagerId);
        barracksCaptains.remove(villagerId);
        if (bed != null) bedOwners.remove(bed.asLong(), villagerId);
        if (saved != null) bedOwners.remove(saved.bedPos().asLong(), villagerId);
        if (bed != null || saved != null) {
            village.markPersistentDataDirty();
            queueBarracksAssignments();
        }
    }
}

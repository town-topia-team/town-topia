package com.alex.test.ui;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.mayor.MayorEntity;
import com.alex.test.town.TownStatsService;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.building.HouseBuilding;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.BarracksBuilding;
import com.alex.test.village.building.MageTowerBuilding;
import com.alex.test.village.building.TownHallBuilding;
import com.alex.test.village.claims.ClaimLock;
import com.alex.test.network.HouseRoomPayloads;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared bed-room data: houses allow moving claims; barracks and the Town Hall expose an inspection view.
 *
 * <p>The bed and resident lists are sent when the screen opens and again after every change
 * the player makes and when the server observes a change while the screen is open.
 */
public final class HouseRoomMenu extends AbstractContainerMenu implements NpcUiMenu {

    /** What a bed looks like in the screen. */
    public enum BedStatus {
        /** A villager's bed for good. */
        CLAIMED,
        /** Free, and the house has room: the next villager without a bed may take it. */
        FREE,
        /** Free, but the house already holds as many villagers as its token allows. */
        FULL,
        /** A villager's claim whose bed is gone, or no longer inside the house. */
        MISSING,
        /** A usable bed kept for the captain. */
        RESERVED,
        /** An unclaimed bed currently occupied by a sleeper. */
        OCCUPIED,
        /** The barracks captain's own bed. */
        CAPTAIN
    }

    public record BedMarker(BlockPos head, @Nullable BlockPos foot, BedStatus status,
                            @Nullable UUID owner, String ownerName) {}

    public enum HomeKind { NONE, HERE, ELSEWHERE }

    /** A villager of the village and where it sleeps. */
    public record Resident(UUID id, String name, String profession, Item icon, HomeKind home,
                           @Nullable BlockPos bed) {}

    /** The kinds of bed room this screen shows: who may sleep there and what else is in it. */
    public enum RoomKind { HOUSE, BARRACKS, MAGE_TOWER, TOWN_HALL }

    /** Only the Mayor sleeps in the Town Hall: once it holds a bed for the night, the others are spare. */
    private static final int TOWN_HALL_CAPACITY = 1;

    /** A Mage Tower study station: an enchanting table or lectern, and the mage studying there now. */
    public record StudySpot(BlockPos pos, boolean lectern, String user) {}

    /** What a Mage Tower has besides its beds; {@link #NONE} for any other room. */
    public record TowerInfo(List<StudySpot> spots, List<BlockPos> crystals, int bookshelves, int studyPercent) {
        public static final TowerInfo NONE = new TowerInfo(List.of(), List.of(), 0, 0);
    }

    /** Where the Mayor spends tonight, for the Town Hall. */
    public enum MayorNight {
        /** Its body is not loaded, or the village has none: only its bed claim is known. */
        UNKNOWN,
        /** Daytime. Its bed, if it has one, stays its own (10-24). */
        AWAKE,
        /** In a Town Hall bed, or on its way to one. */
        BED,
        /** It cannot get to its bed tonight, or no bed it can walk to is free: it sleeps on a hall floor tile. */
        FLOOR,
        /** No free bed and no free floor tile: it waits standing in the hall. */
        WAITING
    }

    /**
     * What a Town Hall shows besides its beds; {@link #NONE} for any other room. {@code mayorId} is the
     * Mayor's npc id (its bed claim's id) while its body is loaded, else null: its bed can be changed then only.
     */
    public record HallInfo(String mayorName, MayorNight night, @Nullable UUID mayorId) {
        public static final HallInfo NONE = new HallInfo("", MayorNight.UNKNOWN, null);
    }

    /** Everything that changes when beds move. */
    public record Snapshot(boolean houseValid, List<BedMarker> beds, List<Resident> residents,
                           List<BlockPos> floor, float space, int lowCeilingTiles,
                           int recognisedBeds, boolean captainSlotReserved, TowerInfo tower, HallInfo hall) {}

    @Nullable private final VillageManager village;
    @Nullable private final Building building;
    private final RoomKind kind;
    private final boolean barracks;
    private final Player viewer;
    private long nextRefresh;
    /** The Mayor that opened this screen from its building overview; null when opened from the token. */
    @Nullable private final MayorEntity mayor;
    /** Entity id of that Mayor on both sides, or -1. */
    private final int mayorId;
    private final UUID frameId;
    private final BlockPos doorPos;
    private final int capacity;
    private Snapshot snapshot;
    /** Last refusal from the server, shown until the next change. */
    private String message = "";
    /** Bumped whenever a new snapshot arrives, so the screen knows to redraw its model. */
    private int version;
    /**
     * Village claims round 7a: this player may change who sleeps where (Owner or Officer, or claims do not apply).
     * Otherwise the window is look only and names the owner ({@link #lockOwner}). The server checks again.
     */
    private final boolean mayChange;
    private final String lockOwner;

    public HouseRoomMenu(int id, Inventory inventory, VillageManager village, Building house,
                         ServerLevel level) {
        this(id, inventory, village, house, level, null);
    }

    public HouseRoomMenu(int id, Inventory inventory, VillageManager village, Building house,
                         ServerLevel level, @Nullable MayorEntity mayor) {
        super(DynamicVillages.HOUSE_ROOM_MENU.get(), id);
        this.village = village;
        if (!isBedRoom(house)) {
            throw new IllegalArgumentException("Bed room must be a house, barracks, Mage Tower or Town Hall");
        }
        this.building = house;
        this.kind = kindOf(house);
        this.barracks = kind == RoomKind.BARRACKS;
        this.viewer = inventory.player;
        this.mayor = mayor;
        this.mayorId = mayor == null ? -1 : mayor.getId();
        this.frameId = house.getItemFrameId();
        this.doorPos = house.getDoorPos();
        this.capacity = capacityOf(house);
        this.snapshot = snapshot(level, village, house);
        this.mayChange = !(viewer instanceof ServerPlayer player)
                || ClaimLock.mayIn(player, village, ClaimLock.Act.MANAGE);
        this.lockOwner = mayChange ? "" : village.claim().ownerName();
    }

    /** A room this screen can show: a house, a Barracks, a Mage Tower or the Town Hall. */
    public static boolean isBedRoom(@Nullable Building building) {
        return building instanceof HouseBuilding || building instanceof BarracksBuilding
                || building instanceof MageTowerBuilding || building instanceof TownHallBuilding;
    }

    private static RoomKind kindOf(Building building) {
        if (building instanceof BarracksBuilding) return RoomKind.BARRACKS;
        if (building instanceof MageTowerBuilding) return RoomKind.MAGE_TOWER;
        if (building instanceof TownHallBuilding) return RoomKind.TOWN_HALL;
        return RoomKind.HOUSE;
    }

    private static int capacityOf(Building building) {
        if (building instanceof HouseBuilding home) return home.getCapacity();
        if (building instanceof MageTowerBuilding) return MageTowerBuilding.CAPACITY;
        if (building instanceof TownHallBuilding) return TOWN_HALL_CAPACITY;
        return BarracksBuilding.CAPACITY;
    }

    private static List<BlockPos> bedsOf(Building building) {
        if (building instanceof HouseBuilding home) return home.getAllBeds();
        if (building instanceof MageTowerBuilding tower) return tower.getBeds();
        if (building instanceof TownHallBuilding hall) return hall.getBeds();
        return ((BarracksBuilding) building).getBeds();
    }

    /** The screen's title: a Barracks or the Town Hall is inspected, a house or a Mage Tower managed. */
    public static String titleFor(Building building) {
        return (building instanceof BarracksBuilding || building instanceof TownHallBuilding ? "Inspect " : "Manage ")
                + building.getDisplayName();
    }

    public HouseRoomMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        super(DynamicVillages.HOUSE_ROOM_MENU.get(), id);
        this.village = null;
        this.building = null;
        this.viewer = inventory.player;
        this.mayor = null;
        this.frameId = buffer.readUUID();
        this.doorPos = buffer.readBlockPos();
        this.capacity = buffer.readVarInt();
        this.kind = enumAt(RoomKind.values(), buffer.readVarInt(), RoomKind.HOUSE);
        this.barracks = kind == RoomKind.BARRACKS;
        this.snapshot = readSnapshot(buffer);
        this.mayorId = buffer.readVarInt();
        this.mayChange = buffer.readBoolean();
        this.lockOwner = buffer.readUtf(64);
    }

    public void writeOpeningData(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(frameId);
        buffer.writeBlockPos(doorPos);
        buffer.writeVarInt(capacity);
        buffer.writeVarInt(kind.ordinal());
        writeSnapshot(buffer, snapshot);
        buffer.writeVarInt(mayorId);
        buffer.writeBoolean(mayChange);
        buffer.writeUtf(lockOwner, 64);
    }

    // =========================================================================
    // Snapshot
    // =========================================================================

    /** The house's beds and the village's villagers as the server sees them now. */
    public static Snapshot snapshot(ServerLevel level, VillageManager village, Building house) {
        UUID houseId = house.getItemFrameId();
        Map<UUID, BlockPos> claims = village.buildingBedClaims(houseId);
        Map<BlockPos, UUID> owners = new HashMap<>();
        claims.forEach((villager, bed) -> owners.put(bed, villager));
        Map<UUID, String> names = new HashMap<>();
        List<Resident> residents = new ArrayList<>();
        boolean tower = house instanceof MageTowerBuilding;
        boolean hall = house instanceof TownHallBuilding;
        // The Mayor is not one of the village's villagers, so a Town Hall claim nobody here holds is its.
        String unknownOwner = hall ? "Mayor" : "Villager";
        MayorEntity hallMayor = hall ? mayorOf(level, village) : null;
        // Its claim is held under its own id (getNpcId), not the entity's UUID.
        if (hallMayor != null) names.put(hallMayor.getNpcId(), hallMayor.titledName());
        for (VillagerModel model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc != null && (!npc.isAlive() || npc.isRemoved())) continue;
            String name = villagerName(model);
            names.put(model.npcId, name);
            // Nobody is given a Town Hall bed, so nobody is offered.
            if (hall) continue;
            Profession profession = npc != null ? npc.getProfession() : null;
            // Only mages may be given a Mage Tower bed, so only mages are offered.
            if (tower && profession != Profession.MAGE) continue;
            Item icon = BuiltInRegistries.ITEM.get(ResourceLocation.parse(TownStatsService.professionIcon(profession)));
            var claim = village.bedClaimOf(model.npcId);
            HomeKind home = claim == null ? HomeKind.NONE
                    : houseId.equals(claim.buildingId()) ? HomeKind.HERE : HomeKind.ELSEWHERE;
            residents.add(new Resident(model.npcId, name, profession != null ? profession.displayName() : "Villager",
                    icon, home, home == HomeKind.HERE ? claim.bedPos() : null));
        }

        boolean captainReserved = house instanceof BarracksBuilding barracks && village.barracksNeedsCaptain(barracks);
        int capacity = capacityOf(house);
        boolean full = claims.size() >= capacity - (captainReserved ? 1 : 0);
        BlockPos reservedBed = house instanceof BarracksBuilding barracks ? village.reservedCaptainBed(barracks) : null;
        List<BlockPos> recognised = bedsOf(house);
        List<BedMarker> beds = new ArrayList<>();
        for (BlockPos head : recognised) {
            boolean loaded = level.isLoaded(head);
            BlockState state = loaded ? level.getBlockState(head) : null;
            boolean present = !loaded || state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD;
            UUID owner = owners.remove(head);
            var ownerClaim = owner != null && house instanceof BarracksBuilding ? village.bedClaimOf(owner) : null;
            boolean captain = ownerClaim != null && ownerClaim.barracksCaptain();
            BedStatus status = owner != null ? (!present ? BedStatus.MISSING : captain ? BedStatus.CAPTAIN : BedStatus.CLAIMED)
                    : !present ? null : head.equals(reservedBed) ? BedStatus.RESERVED
                    : full ? BedStatus.FULL : loaded && state.getValue(BedBlock.OCCUPIED) ? BedStatus.OCCUPIED : BedStatus.FREE;
            if (status == null) continue;
            BlockPos foot = present && loaded ? head.relative(BedBlock.getConnectedDirection(state)) : null;
            beds.add(new BedMarker(head, foot, status, owner, owner != null ? names.getOrDefault(owner, unknownOwner) : ""));
        }
        // Claims whose bed the last scan no longer found.
        owners.forEach((bed, owner) -> beds.add(new BedMarker(bed, null, BedStatus.MISSING, owner,
                names.getOrDefault(owner, unknownOwner))));

        // This house's villagers first, in bed order; then the ones without a bed; then the rest.
        List<BlockPos> order = beds.stream().map(BedMarker::head).toList();
        residents.sort(Comparator.comparing(Resident::home)
                .thenComparingInt(resident -> resident.bed() == null ? 0 : order.indexOf(resident.bed()))
                .thenComparing(Resident::name, String.CASE_INSENSITIVE_ORDER));
        List<BlockPos> floor = house.getNavMeshBlocks().stream()
                .filter(tile -> !level.isLoaded(tile.below())
                        || !(level.getBlockState(tile.below()).getBlock() instanceof BedBlock))
                .sorted().toList();
        TowerInfo towerInfo = house instanceof MageTowerBuilding mageTower
                ? towerInfo(level, village, mageTower, names) : TowerInfo.NONE;
        HallInfo hallInfo = hall ? hallInfo(hallMayor, claims) : HallInfo.NONE;
        return new Snapshot(house.isValid(), List.copyOf(beds), List.copyOf(residents), floor,
                house.getSpace().space(), house.getSpace().lowTiles(), recognised.size(), captainReserved,
                towerInfo, hallInfo);
    }

    /** The village's Mayor, while its body is loaded here; null otherwise. */
    @Nullable public static MayorEntity mayorOf(ServerLevel level, VillageManager village) {
        UUID id = VillageManagerRegistry.get(level.getServer()).getRememberedMayor(village.getVillageId());
        return id != null && level.getEntity(id) instanceof MayorEntity mayor && mayor.isAlive() ? mayor : null;
    }

    /** The Mayor's name and where it spends tonight; {@code claims} are this hall's bed claims. */
    private static HallInfo hallInfo(@Nullable MayorEntity mayor, Map<UUID, BlockPos> claims) {
        if (mayor == null) return new HallInfo("Mayor", MayorNight.UNKNOWN, null);
        // A hall floor spot first: since 10-24 it keeps its bed claim on a night it cannot reach the bed.
        // Lying in a bed counts as a bed even with no claim.
        MayorNight night = !mayor.isInNight() ? MayorNight.AWAKE
                : mayor.hallSleepSpot() != null && !mayor.isSleeping() ? MayorNight.FLOOR
                : claims.containsKey(mayor.getNpcId()) || mayor.isSleeping() ? MayorNight.BED
                : MayorNight.WAITING;
        return new HallInfo(mayor.titledName(), night, mayor.getNpcId());
    }

    /** The tower's study stations and who studies at each, its crystals, and its study speed. */
    private static TowerInfo towerInfo(ServerLevel level, VillageManager village, MageTowerBuilding tower,
                                       Map<UUID, String> names) {
        List<StudySpot> spots = new ArrayList<>();
        for (BlockPos station : tower.getStudyStations()) {
            UUID holder = village.getClaims().holder(station);
            boolean lectern = level.isLoaded(station)
                    && level.getBlockState(station).is(net.minecraft.world.level.block.Blocks.LECTERN);
            spots.add(new StudySpot(station, lectern, holder == null ? "" : names.getOrDefault(holder, "a mage")));
        }
        return new TowerInfo(List.copyOf(spots), tower.getCrystals(), tower.getBookshelves().size(),
                Math.round(tower.studyPay() * 100));
    }

    private static String villagerName(VillagerModel model) {
        // The remembered name, so a villager far away keeps its name too.
        return VillageManager.displayName(model);
    }

    public static void writeSnapshot(FriendlyByteBuf buffer, Snapshot snapshot) {
        buffer.writeBoolean(snapshot.houseValid());
        buffer.writeCollection(snapshot.floor(), (buf, pos) -> buf.writeBlockPos(pos));
        buffer.writeFloat(snapshot.space());
        buffer.writeVarInt(snapshot.lowCeilingTiles());
        buffer.writeVarInt(snapshot.recognisedBeds());
        buffer.writeBoolean(snapshot.captainSlotReserved());
        buffer.writeCollection(snapshot.beds(), (buf, bed) -> {
            buf.writeBlockPos(bed.head());
            buf.writeBoolean(bed.foot() != null);
            if (bed.foot() != null) buf.writeBlockPos(bed.foot());
            buf.writeVarInt(bed.status().ordinal());
            buf.writeBoolean(bed.owner() != null);
            if (bed.owner() != null) buf.writeUUID(bed.owner());
            buf.writeUtf(bed.ownerName(), 64);
        });
        buffer.writeCollection(snapshot.residents(), (buf, resident) -> {
            buf.writeUUID(resident.id());
            buf.writeUtf(resident.name(), 64);
            buf.writeUtf(resident.profession(), 64);
            buf.writeResourceLocation(BuiltInRegistries.ITEM.getKey(resident.icon()));
            buf.writeVarInt(resident.home().ordinal());
            buf.writeBoolean(resident.bed() != null);
            if (resident.bed() != null) buf.writeBlockPos(resident.bed());
        });
        TowerInfo tower = snapshot.tower();
        buffer.writeCollection(tower.spots(), (buf, spot) -> {
            buf.writeBlockPos(spot.pos());
            buf.writeBoolean(spot.lectern());
            buf.writeUtf(spot.user(), 64);
        });
        buffer.writeCollection(tower.crystals(), (buf, pos) -> buf.writeBlockPos(pos));
        buffer.writeVarInt(tower.bookshelves());
        buffer.writeVarInt(tower.studyPercent());
        buffer.writeUtf(snapshot.hall().mayorName(), 64);
        buffer.writeVarInt(snapshot.hall().night().ordinal());
        buffer.writeBoolean(snapshot.hall().mayorId() != null);
        if (snapshot.hall().mayorId() != null) buffer.writeUUID(snapshot.hall().mayorId());
    }

    public static Snapshot readSnapshot(FriendlyByteBuf buffer) {
        boolean valid = buffer.readBoolean();
        List<BlockPos> floor = buffer.readList(buf -> buf.readBlockPos());
        float space = buffer.readFloat();
        int lowCeilingTiles = buffer.readVarInt();
        int recognisedBeds = buffer.readVarInt();
        boolean captainReserved = buffer.readBoolean();
        List<BedMarker> beds = buffer.readList(buf -> {
            BlockPos head = buf.readBlockPos();
            BlockPos foot = buf.readBoolean() ? buf.readBlockPos() : null;
            BedStatus status = enumAt(BedStatus.values(), buf.readVarInt(), BedStatus.FREE);
            UUID owner = buf.readBoolean() ? buf.readUUID() : null;
            return new BedMarker(head, foot, status, owner, buf.readUtf(64));
        });
        List<Resident> residents = buffer.readList(buf -> {
            UUID id = buf.readUUID();
            String name = buf.readUtf(64);
            String profession = buf.readUtf(64);
            Item icon = BuiltInRegistries.ITEM.get(buf.readResourceLocation());
            HomeKind home = enumAt(HomeKind.values(), buf.readVarInt(), HomeKind.NONE);
            BlockPos bed = buf.readBoolean() ? buf.readBlockPos() : null;
            return new Resident(id, name, profession, icon, home, bed);
        });
        List<StudySpot> spots = buffer.readList(buf -> new StudySpot(buf.readBlockPos(), buf.readBoolean(), buf.readUtf(64)));
        List<BlockPos> crystals = buffer.readList(buf -> buf.readBlockPos());
        int bookshelves = buffer.readVarInt();
        int studyPercent = buffer.readVarInt();
        TowerInfo tower = spots.isEmpty() && crystals.isEmpty() && bookshelves == 0 && studyPercent == 0
                ? TowerInfo.NONE : new TowerInfo(spots, crystals, bookshelves, studyPercent);
        String mayorName = buffer.readUtf(64);
        MayorNight night = enumAt(MayorNight.values(), buffer.readVarInt(), MayorNight.UNKNOWN);
        UUID mayorId = buffer.readBoolean() ? buffer.readUUID() : null;
        HallInfo hall = mayorName.isEmpty() && night == MayorNight.UNKNOWN ? HallInfo.NONE
                : new HallInfo(mayorName, night, mayorId);
        return new Snapshot(valid, beds, residents, floor, space, lowCeilingTiles, recognisedBeds, captainReserved,
                tower, hall);
    }

    private static <E> E enumAt(E[] values, int ordinal, E fallback) {
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : fallback;
    }

    // =========================================================================
    // Access
    // =========================================================================

    /** Client: a new snapshot from the server, with an optional refusal to show. */
    public void applySnapshot(Snapshot next, String refusal) {
        snapshot = next;
        message = refusal;
        version++;
    }

    public UUID frameId() { return frameId; }
    public BlockPos doorPos() { return doorPos; }
    public int capacity() { return capacity; }
    public List<BlockPos> floor() { return snapshot.floor(); }
    public float space() { return snapshot.space(); }
    public int lowCeilingTiles() { return snapshot.lowCeilingTiles(); }
    public int recognisedBeds() { return snapshot.recognisedBeds(); }
    public boolean isBarracks() { return barracks; }
    public boolean isMageTower() { return kind == RoomKind.MAGE_TOWER; }
    public boolean isTownHall() { return kind == RoomKind.TOWN_HALL; }
    /**
     * No villager list, search or Free: a Barracks hands out its beds by its own rules, the Town Hall's are
     * the Mayor's (a player may only give it one: "Give to the Mayor", 10-24).
     */
    public boolean inspectOnly() { return barracks || isTownHall(); }
    public TowerInfo tower() { return snapshot.tower(); }
    public HallInfo hall() { return snapshot.hall(); }
    public boolean captainSlotReserved() { return snapshot.captainSlotReserved(); }
    public boolean houseValid() { return snapshot.houseValid(); }
    public List<BedMarker> beds() { return snapshot.beds(); }
    public List<Resident> residents() { return snapshot.residents(); }
    public String message() { return message; }
    public int version() { return version; }
    /** Village claims round 7a: false = look only, beds are changed by the owner and their Officers. */
    public boolean mayChange() { return mayChange; }
    /** The owner's name when this player may not change beds, else empty. */
    public String lockOwner() { return lockOwner; }
    /** The Mayor to go back to, or -1 when the house token opened this screen. */
    public int mayorId() { return mayorId; }
    @Nullable public VillageManager serverVillage() { return village; }
    @Nullable public HouseBuilding serverHouse() { return building instanceof HouseBuilding house ? house : null; }
    @Nullable public Building serverBuilding() { return building; }

    @Override public void broadcastChanges() {
        super.broadcastChanges();
        if (!(viewer instanceof ServerPlayer player) || village == null || building == null
                || !stillValid(player) || player.serverLevel().getGameTime() < nextRefresh) return;
        nextRefresh = player.serverLevel().getGameTime() + 20;
        Snapshot next = snapshot(player.serverLevel(), village, building);
        if (next.equals(snapshot)) return;
        snapshot = next;
        PacketDistributor.sendToPlayer(player, new HouseRoomPayloads.Sync(frameId, next, message));
    }

    @Override public boolean stillValid(Player player) {
        if (building == null) return true;
        if (village == null || village.getLevel() != player.level() || !village.getBuildings().contains(building)) return false;
        // Opened from the Mayor: the player stands at the Mayor, not at the house.
        if (mayor != null) return mayor.isAlive() && player.distanceToSqr(mayor) < 64.0;
        return player.distanceToSqr(doorPos.getX() + 0.5, doorPos.getY() + 0.5,
                        doorPos.getZ() + 0.5) < 1024.0;
    }

    /** Keeps the Mayor attentive while the player manages beds through it. */
    @Override public boolean isForNpc(NpcEntity npc) {
        return mayor != null && mayor == npc;
    }

    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
}

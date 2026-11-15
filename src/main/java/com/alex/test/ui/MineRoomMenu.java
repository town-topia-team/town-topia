package com.alex.test.ui;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.mayor.MayorEntity;
import com.alex.test.network.MineRoomPayloads;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.MineBuilding;
import com.alex.test.village.claims.ClaimLock;
import com.alex.test.village.claims.RoomRights;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.druid.behavior.MineRegrowth;
import com.alex.test.village.villagers.miner.MineReport;
import com.alex.test.village.villagers.miner.MineReservations;
import com.alex.test.village.villagers.miner.MineSettings;
import com.alex.test.village.villagers.miner.MineShaftPlan;
import com.alex.test.village.villagers.miner.OreHeights;
import com.alex.test.village.villagers.miner.behavior.MinerActivity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Mine screen's data: one mine's plan cells, dug or not, the ore its
 * tunnels have opened, where it stops early and why, sealed water and lava,
 * who is down it, and the mine's own settings.
 *
 * <p>Sent when the screen opens, after each settings change, and once a second
 * while it is open when anything changed. Nothing here loads a chunk: with the
 * mine's chunks unloaded only its status and settings are sent.
 */
public final class MineRoomMenu extends AbstractContainerMenu implements NpcUiMenu {

    /** Cobblestone the miner set against water or lava. */
    public record Seal(BlockPos pos, boolean lava) {}

    /** A village miner or druid standing in the mine. */
    public record Worker(BlockPos pos, String name, boolean druid) {}

    /**
     * Everything the screen shows.
     *
     * @param valid    the mine is valid as the last scan saw it
     * @param loaded   its chunks are loaded on the server; when not, every list is empty
     * @param dug      plan cells (floor level) with nothing left to dig
     * @param undug    plan cells with rock still in them
     * @param ores     ore blocks touching an open cell, so only ore the tunnels show
     * @param tunnel   the Mayor card's tunnel lines ({@link MineReport#tunnelRows}), "label\tvalue"
     * @param holder   who holds the mine's claim, or ""
     */
    public record Snapshot(boolean valid, boolean loaded, BlockPos mouth, Direction direction,
                           MineSettings settings, int villageSides,
                           List<BlockPos> dug, List<BlockPos> undug, List<BlockPos> ores,
                           List<MineShaftPlan.End> ends, List<Seal> seals, List<Worker> workers,
                           List<String> tunnel, int shaftY, String oresHere,
                           String holder, boolean holderDruid) {
        /** Nothing left to dig, as far as the server could see. */
        public boolean workedOut() { return loaded && undug.isEmpty(); }

        public int planCells() { return dug.size() + undug.size(); }

        public Snapshot withSettings(MineSettings next) {
            return new Snapshot(valid, loaded, mouth, direction, next, villageSides, dug, undug, ores,
                    ends, seals, workers, tunnel, shaftY, oresHere, holder, holderDruid);
        }
    }

    @Nullable private final VillageManager village;
    @Nullable private final MineBuilding mine;
    private final Player viewer;
    /** The Mayor that opened this screen from its building overview; null when opened from the token. */
    @Nullable private final MayorEntity mayor;
    /** Entity id of that Mayor on both sides, or -1. */
    private final int mayorId;
    private final UUID frameId;
    private final BlockPos framePos;
    private Snapshot snapshot;
    /** Last refusal from the server, shown until the next change. */
    private String message = "";
    /** Village claims round 7b: look only below Officer. */
    private final RoomRights rights;
    /** Bumped whenever a new snapshot arrives, so the screen knows to redraw its model. */
    private int version;
    private long nextRefresh;

    public MineRoomMenu(int id, Inventory inventory, VillageManager village, MineBuilding mine,
                        ServerLevel level, @Nullable MayorEntity mayor) {
        super(DynamicVillages.MINE_ROOM_MENU.get(), id);
        this.village = village;
        this.mine = mine;
        this.viewer = inventory.player;
        this.mayor = mayor;
        this.mayorId = mayor == null ? -1 : mayor.getId();
        this.frameId = mine.getItemFrameId();
        this.framePos = mine.getItemFramePos();
        this.snapshot = snapshot(level, village, mine);
        this.rights = RoomRights.of(viewer, village, ClaimLock.Act.MANAGE);
    }

    public MineRoomMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        super(DynamicVillages.MINE_ROOM_MENU.get(), id);
        this.village = null;
        this.mine = null;
        this.viewer = inventory.player;
        this.mayor = null;
        this.frameId = buffer.readUUID();
        this.framePos = buffer.readBlockPos();
        this.snapshot = readSnapshot(buffer);
        this.mayorId = buffer.readVarInt();
        this.rights = RoomRights.read(buffer);
    }

    public void writeOpeningData(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(frameId);
        buffer.writeBlockPos(framePos);
        writeSnapshot(buffer, snapshot);
        buffer.writeVarInt(mayorId);
        rights.write(buffer);
    }

    public static String titleFor(MineBuilding mine) {
        return "Manage " + mine.getDisplayName();
    }

    // =========================================================================
    // Snapshot
    // =========================================================================

    /** The mine as the server sees it now. */
    public static Snapshot snapshot(ServerLevel level, VillageManager village, MineBuilding mine) {
        UUID id = mine.getItemFrameId();
        MineSettings settings = village.mineSettings().get(id);
        int villageSides = village.minerSideBranchDistance();
        int shaftY = mine.mouth().getY();
        List<String> oreNames = new ArrayList<>();
        for (OreHeights.Ore ore : OreHeights.mainOres(shaftY)) oreNames.add(ore.label());
        String oresHere = String.join(", ", oreNames);

        String holder = "";
        boolean holderDruid = false;
        UUID holderId = village.getClaims().holder(mine.mouth());
        VillagerModel holderModel = holderId == null ? null : village.getModel(holderId);
        if (holderModel != null) {
            holder = VillageManager.displayName(holderModel);
            NpcEntity npc = holderModel.puppet();
            holderDruid = npc != null && npc.getProfession() == Profession.DRUID;
        }

        Direction direction = mine.shaftDirection();
        MineReservations.Snapshot reservations = MineReservations.snapshot(level);
        if (direction == null || !reservations.knows(id)) {
            return new Snapshot(mine.isValid(), false, mine.mouth(), direction == null ? Direction.NORTH : direction,
                    settings, villageSides, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), shaftY, oresHere, holder, holderDruid);
        }
        MineShaftPlan plan = MineReservations.planFor(mine, reservations, village);
        List<String> tunnel = MineReport.tunnelRows(plan);
        BlockPos far = mine.mouth().relative(direction, Math.max(0, plan.corridorLength()));
        if (!level.isLoaded(mine.mouth()) || !level.isLoaded(far)) {
            return new Snapshot(mine.isValid(), false, mine.mouth(), direction, settings, villageSides,
                    List.of(), List.of(), List.of(), List.copyOf(plan.ends()), List.of(), List.of(),
                    tunnel, shaftY, oresHere, holder, holderDruid);
        }

        // Every read below asks isLoaded first: getBlockState on an unloaded
        // chunk loads it, and a side tunnel can reach into one.
        List<BlockPos> dug = new ArrayList<>();
        List<BlockPos> undug = new ArrayList<>();
        Set<BlockPos> open = new HashSet<>();
        for (int i = 0; i < plan.cellCount(); i++) {
            MineShaftPlan.Cell cell = plan.cell(i);
            if (cell == null) break;
            BlockPos floor = cell.floor();
            boolean rock = false;
            for (int h = 0; h < MineShaftPlan.TUNNEL_HEIGHT && !rock; h++) {
                BlockPos pos = floor.above(h);
                rock = !level.isLoaded(pos) || MinerActivity.isDiggable(level.getBlockState(pos));
            }
            if (rock) {
                undug.add(floor);
            } else {
                dug.add(floor);
                for (int h = 0; h < MineShaftPlan.TUNNEL_HEIGHT; h++) open.add(floor.above(h));
            }
        }

        Set<BlockPos> ores = new LinkedHashSet<>();
        Map<BlockPos, Boolean> seals = new LinkedHashMap<>();
        for (BlockPos air : open) {
            for (Direction side : Direction.values()) {
                BlockPos next = air.relative(side);
                if (open.contains(next) || ores.contains(next) || seals.containsKey(next)
                        || !level.isLoaded(next)) continue;
                BlockState state = level.getBlockState(next);
                if (MinerActivity.isOre(state)) {
                    ores.add(next);
                } else if (state.is(Blocks.COBBLESTONE)) {
                    Boolean lava = fluidBehind(level, next);
                    if (lava != null) seals.put(next, lava);
                }
            }
        }
        List<Seal> sealList = new ArrayList<>();
        seals.forEach((pos, lava) -> sealList.add(new Seal(pos, lava)));

        List<Worker> workers = new ArrayList<>();
        AABB box = MineRegrowth.shaftBox(plan);
        for (VillagerModel model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.isRemoved()) continue;
            Profession profession = npc.getProfession();
            if (profession != Profession.MINER && profession != Profession.DRUID) continue;
            if (!box.contains(npc.getX(), npc.getY(), npc.getZ())) continue;
            workers.add(new Worker(npc.blockPosition(), VillageManager.displayName(model),
                    profession == Profession.DRUID));
        }

        return new Snapshot(mine.isValid(), true, mine.mouth(), direction, settings, villageSides,
                List.copyOf(dug), List.copyOf(undug), List.copyOf(ores), List.copyOf(plan.ends()),
                List.copyOf(sealList), List.copyOf(workers), tunnel, shaftY, oresHere, holder, holderDruid);
    }

    /** True for lava, false for water, null when neither touches {@code pos}. */
    @Nullable
    private static Boolean fluidBehind(ServerLevel level, BlockPos pos) {
        boolean water = false;
        for (Direction side : Direction.values()) {
            BlockPos next = pos.relative(side);
            if (!level.isLoaded(next)) continue;
            FluidState fluid = level.getFluidState(next);
            if (fluid.is(FluidTags.LAVA)) return true;
            if (fluid.is(FluidTags.WATER)) water = true;
        }
        return water ? Boolean.FALSE : null;
    }

    public static void writeSettings(FriendlyByteBuf buffer, MineSettings settings) {
        buffer.writeBoolean(settings.sidesFollowVillage());
        buffer.writeVarInt(settings.left());
        buffer.writeVarInt(settings.right());
        buffer.writeVarInt(settings.mainLength());
        buffer.writeBoolean(settings.paused());
    }

    public static MineSettings readSettings(FriendlyByteBuf buffer) {
        return new MineSettings(buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readBoolean());
    }

    public static void writeSnapshot(FriendlyByteBuf buffer, Snapshot snapshot) {
        buffer.writeBoolean(snapshot.valid());
        buffer.writeBoolean(snapshot.loaded());
        buffer.writeBlockPos(snapshot.mouth());
        buffer.writeVarInt(snapshot.direction().get3DDataValue());
        writeSettings(buffer, snapshot.settings());
        buffer.writeVarInt(snapshot.villageSides());
        buffer.writeCollection(snapshot.dug(), (buf, pos) -> buf.writeBlockPos(pos));
        buffer.writeCollection(snapshot.undug(), (buf, pos) -> buf.writeBlockPos(pos));
        buffer.writeCollection(snapshot.ores(), (buf, pos) -> buf.writeBlockPos(pos));
        buffer.writeCollection(snapshot.ends(), (buf, end) -> {
            buf.writeBlockPos(end.at());
            buf.writeVarInt(end.why().ordinal());
            buf.writeBoolean(end.branch());
        });
        buffer.writeCollection(snapshot.seals(), (buf, seal) -> {
            buf.writeBlockPos(seal.pos());
            buf.writeBoolean(seal.lava());
        });
        buffer.writeCollection(snapshot.workers(), (buf, worker) -> {
            buf.writeBlockPos(worker.pos());
            buf.writeUtf(worker.name(), 64);
            buf.writeBoolean(worker.druid());
        });
        buffer.writeCollection(snapshot.tunnel(), (buf, line) -> buf.writeUtf(line, 256));
        buffer.writeVarInt(snapshot.shaftY());
        buffer.writeUtf(snapshot.oresHere(), 256);
        buffer.writeUtf(snapshot.holder(), 64);
        buffer.writeBoolean(snapshot.holderDruid());
    }

    public static Snapshot readSnapshot(FriendlyByteBuf buffer) {
        boolean valid = buffer.readBoolean();
        boolean loaded = buffer.readBoolean();
        BlockPos mouth = buffer.readBlockPos();
        Direction direction = Direction.from3DDataValue(buffer.readVarInt());
        if (direction.getAxis().isVertical()) direction = Direction.NORTH;
        MineSettings settings = readSettings(buffer);
        int villageSides = buffer.readVarInt();
        List<BlockPos> dug = buffer.readList(buf -> buf.readBlockPos());
        List<BlockPos> undug = buffer.readList(buf -> buf.readBlockPos());
        List<BlockPos> ores = buffer.readList(buf -> buf.readBlockPos());
        MineShaftPlan.Stop[] stops = MineShaftPlan.Stop.values();
        List<MineShaftPlan.End> ends = buffer.readList(buf -> {
            BlockPos at = buf.readBlockPos();
            int why = buf.readVarInt();
            return new MineShaftPlan.End(at, why >= 0 && why < stops.length ? stops[why] : MineShaftPlan.Stop.BORDER,
                    buf.readBoolean());
        });
        List<Seal> seals = buffer.readList(buf -> new Seal(buf.readBlockPos(), buf.readBoolean()));
        List<Worker> workers = buffer.readList(buf -> new Worker(buf.readBlockPos(), buf.readUtf(64), buf.readBoolean()));
        List<String> tunnel = buffer.readList(buf -> buf.readUtf(256));
        int shaftY = buffer.readVarInt();
        String oresHere = buffer.readUtf(256);
        String holder = buffer.readUtf(64);
        boolean holderDruid = buffer.readBoolean();
        return new Snapshot(valid, loaded, mouth, direction, settings, villageSides, dug, undug, ores, ends,
                seals, workers, tunnel, shaftY, oresHere, holder, holderDruid);
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

    /** Client: the settings just clicked, shown at once; the server's snapshot follows. */
    public void showSettings(MineSettings settings) {
        snapshot = snapshot.withSettings(settings.clamped());
        message = "";
    }

    public Snapshot snapshot() { return snapshot; }
    public UUID frameId() { return frameId; }
    public BlockPos framePos() { return framePos; }
    public String message() { return message; }
    public RoomRights rights() { return rights; }
    public int version() { return version; }
    /** The Mayor to go back to, or -1 when the mine's token opened this screen. */
    public int mayorId() { return mayorId; }
    @Nullable public VillageManager serverVillage() { return village; }
    @Nullable public MineBuilding serverMine() { return mine; }

    @Override public void broadcastChanges() {
        super.broadcastChanges();
        if (!(viewer instanceof ServerPlayer player) || village == null || mine == null
                || !stillValid(player) || player.serverLevel().getGameTime() < nextRefresh) return;
        nextRefresh = player.serverLevel().getGameTime() + 20;
        Snapshot next = snapshot(player.serverLevel(), village, mine);
        if (next.equals(snapshot)) return;
        snapshot = next;
        PacketDistributor.sendToPlayer(player, new MineRoomPayloads.Sync(frameId, next, message));
    }

    @Override public boolean stillValid(Player player) {
        if (mine == null) return true;
        if (village == null || village.getLevel() != player.level() || !village.getBuildings().contains(mine)) return false;
        // Opened from the Mayor: the player stands at the Mayor, not at the mine.
        if (mayor != null) return mayor.isAlive() && player.distanceToSqr(mayor) < 64.0;
        return player.distanceToSqr(framePos.getX() + 0.5, framePos.getY() + 0.5,
                framePos.getZ() + 0.5) < 1024.0;
    }

    /** Keeps the Mayor attentive while the player manages the mine through it. */
    @Override public boolean isForNpc(NpcEntity npc) {
        return mayor != null && mayor == npc;
    }

    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
}

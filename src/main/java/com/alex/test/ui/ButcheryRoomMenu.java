package com.alex.test.ui;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.mayor.MayorEntity;
import com.alex.test.network.ButcheryRoomPayloads;
import com.alex.test.village.VillageDays;
import com.alex.test.village.VillageManager;
import com.alex.test.village.building.ButcheryBuilding;
import com.alex.test.village.building.ButcheryTrouble;
import com.alex.test.village.claims.ClaimLock;
import com.alex.test.village.claims.RoomRights;
import com.alex.test.village.villagers.butcher.ButcheryChecks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * One Butchery, looked at from its token: its room, its chopping blocks and grindstone, and the
 * tiles the butcher works on (round 1 of docs/plans/2026-10-24-butchery-window.md). Nothing here
 * changes the room; the actions are "Try again now" on a paused Butchery (round 3) and picking where
 * the butcher stands (round 8).
 *
 * <p>The snapshot is sent when the screen opens and again when the server sees it change while
 * the screen is open (a room scan moved the work spots, a chopping block was broken).
 */
public final class ButcheryRoomMenu extends AbstractContainerMenu implements NpcUiMenu {

    /**
     * Everything the screen shows. {@code killSpot} is where the butcher stands, {@code animalSpot}
     * where the led animal is stopped, {@code entry} the first tile inside the door (null when it
     * is not a room tile). {@code workSpots} counts every usable pair, the first one in use.
     * {@code troubles}: the last give-ups here, newest first (round 2). {@code paused}: too many
     * give-ups lately, no culls here until the next morning (round 3). {@code checks}: the server's
     * half of the check list (round 4). Round 8: {@code standSpots} every tile the butcher can stand on
     * (the pairs' kill spots), {@code chosen} the one the player picked (null = automatic) and
     * {@code choiceTrouble} why it is not in use now.
     */
    public record Snapshot(boolean valid, List<BlockPos> floor, List<BlockPos> choppingBlocks,
                           @Nullable BlockPos grindstone, @Nullable BlockPos killSpot,
                           @Nullable BlockPos animalSpot, @Nullable BlockPos entry, int workSpots,
                           List<ButcheryTrouble> troubles, boolean paused, ButcheryChecks.Checks checks,
                           List<BlockPos> standSpots, @Nullable BlockPos chosen,
                           ButcheryBuilding.ChoiceTrouble choiceTrouble) {
        /** Whether a butcher can work here now. */
        public boolean ready() {
            return valid && killSpot != null;
        }
    }

    @Nullable private final VillageManager village;
    @Nullable private final ButcheryBuilding butchery;
    private final Player viewer;
    private final UUID frameId;
    private final BlockPos doorPos;
    /** The Mayor that opened this screen from its building overview (round 7); null when opened from the token. */
    @Nullable private final MayorEntity mayor;
    /** Entity id of that Mayor on both sides, or -1. */
    private final int mayorId;
    private Snapshot snapshot;
    /** Bumped whenever a new snapshot arrives, so the screen knows to redraw its model. */
    private int version;
    private long nextRefresh;
    /** Village claims round 7b: look only below Officer (Try again and the spot greyed). */
    private final RoomRights rights;

    public ButcheryRoomMenu(int id, Inventory inventory, VillageManager village, ButcheryBuilding butchery,
                            @Nullable MayorEntity mayor) {
        super(DynamicVillages.BUTCHERY_ROOM_MENU.get(), id);
        this.village = village;
        this.butchery = butchery;
        this.viewer = inventory.player;
        this.frameId = butchery.getItemFrameId();
        this.doorPos = butchery.getDoorPos();
        this.mayor = mayor;
        this.mayorId = mayor == null ? -1 : mayor.getId();
        this.snapshot = snapshot(village, butchery);
        this.rights = RoomRights.of(viewer, village, ClaimLock.Act.MANAGE);
    }

    public ButcheryRoomMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        super(DynamicVillages.BUTCHERY_ROOM_MENU.get(), id);
        this.village = null;
        this.butchery = null;
        this.mayor = null;
        this.viewer = inventory.player;
        this.frameId = buffer.readUUID();
        this.doorPos = buffer.readBlockPos();
        this.snapshot = readSnapshot(buffer);
        this.mayorId = buffer.readVarInt();
        this.rights = RoomRights.read(buffer);
    }

    public void writeOpeningData(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(frameId);
        buffer.writeBlockPos(doorPos);
        writeSnapshot(buffer, snapshot);
        buffer.writeVarInt(mayorId);
        rights.write(buffer);
    }

    public static String titleFor(ButcheryBuilding butchery) {
        return butchery.getDisplayName();
    }

    // =========================================================================
    // Snapshot
    // =========================================================================

    /** The room and its work spots as the last scan left them; no block is read here. */
    public static Snapshot snapshot(VillageManager village, ButcheryBuilding butchery) {
        List<BlockPos> floor = butchery.getNavMeshBlocks().stream().sorted().toList();
        List<BlockPos> blocks = butchery.getChoppingBlocks().stream().sorted().toList();
        BlockPos entry = butchery.entryTile();
        boolean paused = butchery.isPaused(VillageDays.dayIndex(village.getLevel().getDayTime()));
        return new Snapshot(butchery.isValid(), floor, blocks, butchery.getGrindstone(), butchery.killSpot(),
                butchery.animalSpot(), butchery.getNavMeshBlocks().contains(entry) ? entry : null,
                butchery.getWorkSpots().size(), butchery.recentTroubles(), paused,
                ButcheryChecks.compute(village, butchery),
                butchery.getWorkSpots().stream().map(ButcheryBuilding.WorkSpots::killSpot).distinct().toList(),
                butchery.chosenKillSpot(), butchery.choiceTrouble());
    }

    public static void writeSnapshot(FriendlyByteBuf buffer, Snapshot snapshot) {
        buffer.writeBoolean(snapshot.valid());
        buffer.writeCollection(snapshot.floor(), (buf, pos) -> buf.writeBlockPos(pos));
        buffer.writeCollection(snapshot.choppingBlocks(), (buf, pos) -> buf.writeBlockPos(pos));
        writeMaybe(buffer, snapshot.grindstone());
        writeMaybe(buffer, snapshot.killSpot());
        writeMaybe(buffer, snapshot.animalSpot());
        writeMaybe(buffer, snapshot.entry());
        buffer.writeVarInt(snapshot.workSpots());
        buffer.writeCollection(snapshot.troubles(), (buf, trouble) -> {
            buf.writeEnum(trouble.kind());
            buf.writeUtf(trouble.animal());
            buf.writeBlockPos(trouble.where());
            buf.writeEnum(trouble.place());
            buf.writeLong(trouble.gameTime());
        });
        buffer.writeBoolean(snapshot.paused());
        ButcheryChecks.Checks checks = snapshot.checks();
        buffer.writeCollection(checks.usableBlocks(), (buf, pos) -> buf.writeBlockPos(pos));
        buffer.writeCollection(checks.outside(), (buf, pos) -> buf.writeBlockPos(pos));
        buffer.writeEnum(checks.ranch());
        buffer.writeVarInt(checks.distance());
        buffer.writeVarInt(checks.ranchesUsing());
        buffer.writeEnum(checks.path());
        buffer.writeCollection(checks.butchers(), (buf, butcher) -> {
            buf.writeUtf(butcher.name());
            buf.writeUtf(butcher.status());
            buf.writeBoolean(butcher.workOn());
            buf.writeVarInt(butcher.keep());
            buf.writeCollection(butcher.allowed(), FriendlyByteBuf::writeUtf);
            buf.writeVarInt(butcher.kills());
            buf.writeVarInt(butcher.strays());
            buf.writeVarInt(butcher.sword());
            buf.writeVarInt(butcher.sharpened());
        });
        buffer.writeCollection(checks.pens(), (buf, pen) -> {
            buf.writeVarInt(pen.distance());
            buf.writeCollection(pen.herds(), (b, herd) -> {
                b.writeUtf(herd.type());
                b.writeVarInt(herd.adults());
                b.writeVarInt(herd.busy());
                b.writeVarInt(herd.waiting());
            });
            buf.writeVarInt(pen.neverCulled());
        });
        buffer.writeVarInt(checks.defaultKeep());
        buffer.writeCollection(snapshot.standSpots(), (buf, pos) -> buf.writeBlockPos(pos));
        writeMaybe(buffer, snapshot.chosen());
        buffer.writeEnum(snapshot.choiceTrouble());
    }

    public static Snapshot readSnapshot(FriendlyByteBuf buffer) {
        boolean valid = buffer.readBoolean();
        List<BlockPos> floor = buffer.readList(buf -> buf.readBlockPos());
        List<BlockPos> blocks = buffer.readList(buf -> buf.readBlockPos());
        BlockPos grindstone = readMaybe(buffer);
        BlockPos killSpot = readMaybe(buffer);
        BlockPos animalSpot = readMaybe(buffer);
        BlockPos entry = readMaybe(buffer);
        int workSpots = buffer.readVarInt();
        List<ButcheryTrouble> troubles = buffer.readList(buf -> new ButcheryTrouble(
                buf.readEnum(ButcheryTrouble.Kind.class), buf.readUtf(), buf.readBlockPos(),
                buf.readEnum(ButcheryTrouble.Place.class), buf.readLong()));
        boolean paused = buffer.readBoolean();
        ButcheryChecks.Checks checks = new ButcheryChecks.Checks(
                buffer.readList(buf -> buf.readBlockPos()), buffer.readList(buf -> buf.readBlockPos()),
                buffer.readEnum(ButcheryChecks.RanchLink.class), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readEnum(ButcheryChecks.Path.class),
                buffer.readList(buf -> new ButcheryChecks.Butcher(buf.readUtf(), buf.readUtf(), buf.readBoolean(),
                        buf.readVarInt(), buf.readList(FriendlyByteBuf::readUtf), buf.readVarInt(), buf.readVarInt(),
                        buf.readVarInt(), buf.readVarInt())),
                buffer.readList(buf -> new ButcheryChecks.Pen(buf.readVarInt(),
                        buf.readList(b -> new ButcheryChecks.Herd(b.readUtf(), b.readVarInt(), b.readVarInt(), b.readVarInt())),
                        buf.readVarInt())),
                buffer.readVarInt());
        List<BlockPos> standSpots = buffer.readList(buf -> buf.readBlockPos());
        BlockPos chosen = readMaybe(buffer);
        ButcheryBuilding.ChoiceTrouble choiceTrouble = buffer.readEnum(ButcheryBuilding.ChoiceTrouble.class);
        return new Snapshot(valid, floor, blocks, grindstone, killSpot, animalSpot, entry, workSpots, troubles,
                paused, checks, standSpots, chosen, choiceTrouble);
    }

    private static void writeMaybe(FriendlyByteBuf buffer, @Nullable BlockPos pos) {
        buffer.writeBoolean(pos != null);
        if (pos != null) buffer.writeBlockPos(pos);
    }

    @Nullable
    private static BlockPos readMaybe(FriendlyByteBuf buffer) {
        return buffer.readBoolean() ? buffer.readBlockPos() : null;
    }

    // =========================================================================
    // Access
    // =========================================================================

    /** Client: a new snapshot from the server. */
    public void applySnapshot(Snapshot next) {
        snapshot = next;
        version++;
    }

    public UUID frameId() { return frameId; }
    public BlockPos doorPos() { return doorPos; }
    public Snapshot snapshot() { return snapshot; }
    public int version() { return version; }
    /** The Mayor to go back to, or -1 when the Butchery's token opened this screen. */
    public int mayorId() { return mayorId; }
    /** Village claims round 7b: false = look only (the owner's name is in it). */
    public RoomRights rights() { return rights; }

    /** Server: may this player change the Butchery (Try again, the spot)? Tells them why not. */
    public boolean serverMayChange(ServerPlayer player) {
        if (village == null || ClaimLock.mayIn(player, village, ClaimLock.Act.MANAGE)) return true;
        ClaimLock.tellManageRefused(player, village);
        return false;
    }

    @Override public void broadcastChanges() {
        super.broadcastChanges();
        if (!(viewer instanceof ServerPlayer player) || village == null || butchery == null
                || !stillValid(player) || player.serverLevel().getGameTime() < nextRefresh) return;
        nextRefresh = player.serverLevel().getGameTime() + 20;
        Snapshot next = snapshot(village, butchery);
        if (next.equals(snapshot)) return;
        snapshot = next;
        PacketDistributor.sendToPlayer(player, new ButcheryRoomPayloads.Sync(frameId, next));
    }

    /** Server: the Butchery this window shows (null on the client). */
    @Nullable
    public ButcheryBuilding serverButchery() {
        return butchery;
    }

    /**
     * Server: the player picked where the butcher stands ({@code tile}, one of the stand spots), or
     * automatic (null). Saved with the village; the next animal is struck from there (round 8).
     */
    public void chooseSpot(ServerPlayer player, @Nullable BlockPos tile) {
        if (village == null || butchery == null) return;
        if (tile != null && butchery.getWorkSpots().stream().noneMatch(pair -> pair.killSpot().equals(tile))) return;
        butchery.chooseKillSpot(tile);
        village.markPersistentDataDirty();
        DynamicVillages.LOGGER.info("[Butchery] {} at {}: butcher's spot {}", player.getName().getString(),
                doorPos.toShortString(), tile == null ? "automatic" : "picked at " + tile.toShortString());
        syncNow(player);
    }

    /** Server: send the snapshot now, after a change from the window, not at the next second. */
    public void syncNow(ServerPlayer player) {
        if (village == null || butchery == null) return;
        snapshot = snapshot(village, butchery);
        PacketDistributor.sendToPlayer(player, new ButcheryRoomPayloads.Sync(frameId, snapshot));
    }

    @Override public boolean stillValid(Player player) {
        if (butchery == null) return true;
        if (village == null || village.getLevel() != player.level() || !village.getBuildings().contains(butchery)) {
            return false;
        }
        // Opened from the Mayor: the player stands at the Mayor, not at the Butchery.
        if (mayor != null) return mayor.isAlive() && player.distanceToSqr(mayor) < 64.0;
        return player.distanceToSqr(doorPos.getX() + 0.5, doorPos.getY() + 0.5, doorPos.getZ() + 0.5) < 1024.0;
    }

    /** Keeps the Mayor attentive while the player looks at the Butchery through it. */
    @Override public boolean isForNpc(NpcEntity npc) {
        return mayor != null && mayor == npc;
    }

    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
}

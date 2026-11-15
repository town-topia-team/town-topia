package com.alex.test.network;

import com.alex.test.Tr;
import com.alex.test.DynamicVillages;
import com.alex.test.client.BuildingLocateHighlight;
import com.alex.test.entity.mayor.MayorEntity;
import com.alex.test.ui.ButcheryRoomMenu;
import com.alex.test.ui.HouseRoomMenu;
import com.alex.test.ui.MayorStatsMenu;
import com.alex.test.ui.MineRoomMenu;
import com.alex.test.ui.RanchRoomMenu;
import com.alex.test.ui.StorageRoomMenu;
import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.HouseBuilding;
import com.alex.test.village.building.BarracksBuilding;
import com.alex.test.village.building.ButcheryBuilding;
import com.alex.test.village.building.ButcheryRoomAccess;
import com.alex.test.village.building.MineBuilding;
import com.alex.test.village.building.MineRoomAccess;
import com.alex.test.village.building.RanchBuilding;
import com.alex.test.village.building.RanchRoomAccess;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.villagers.guard.behavior.GuardPostRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import javax.annotation.Nullable;
import java.util.UUID;

/** Messages of the Mayor's building overview: locating a building, opening a house, storage room, mine, Butchery or Ranch. */
public final class MayorBuildingPayloads {
    private MayorBuildingPayloads() {}

    private static final int HIGHLIGHT_TICKS = 20 * 30;

    /** Outline one building of the overview; {@code pos} names a guard post, which has no id. */
    public record Locate(int mayorId, UUID buildingId, BlockPos pos) implements CustomPacketPayload {
        public static final Type<Locate> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "mayor_locate_building"));
        public static final StreamCodec<FriendlyByteBuf, Locate> CODEC = new StreamCodec<>() {
            @Override public Locate decode(FriendlyByteBuf buf) {
                return new Locate(buf.readVarInt(), buf.readUUID(), buf.readBlockPos());
            }
            @Override public void encode(FriendlyByteBuf buf, Locate payload) {
                buf.writeVarInt(payload.mayorId());
                buf.writeUUID(payload.buildingId());
                buf.writeBlockPos(payload.pos());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client: draw a box around {@code min}..{@code max} and a beam over {@code door}. */
    public record Highlight(BlockPos min, BlockPos max, BlockPos door, int ticks) implements CustomPacketPayload {
        public static final Type<Highlight> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "mayor_building_highlight"));
        public static final StreamCodec<FriendlyByteBuf, Highlight> CODEC = new StreamCodec<>() {
            @Override public Highlight decode(FriendlyByteBuf buf) {
                return new Highlight(buf.readBlockPos(), buf.readBlockPos(), buf.readBlockPos(), buf.readVarInt());
            }
            @Override public void encode(FriendlyByteBuf buf, Highlight payload) {
                buf.writeBlockPos(payload.min());
                buf.writeBlockPos(payload.max());
                buf.writeBlockPos(payload.door());
                buf.writeVarInt(payload.ticks());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Open a house's bed screen from the Mayor, wherever the house is. */
    public record OpenHouse(int mayorId, UUID houseId) implements CustomPacketPayload {
        public static final Type<OpenHouse> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "mayor_open_house"));
        public static final StreamCodec<FriendlyByteBuf, OpenHouse> CODEC = new StreamCodec<>() {
            @Override public OpenHouse decode(FriendlyByteBuf buf) {
                return new OpenHouse(buf.readVarInt(), buf.readUUID());
            }
            @Override public void encode(FriendlyByteBuf buf, OpenHouse payload) {
                buf.writeVarInt(payload.mayorId());
                buf.writeUUID(payload.houseId());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Open a storage room's screen from the Mayor, wherever the room is. */
    public record OpenStorage(int mayorId, UUID storageId) implements CustomPacketPayload {
        public static final Type<OpenStorage> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "mayor_open_storage"));
        public static final StreamCodec<FriendlyByteBuf, OpenStorage> CODEC = new StreamCodec<>() {
            @Override public OpenStorage decode(FriendlyByteBuf buf) {
                return new OpenStorage(buf.readVarInt(), buf.readUUID());
            }
            @Override public void encode(FriendlyByteBuf buf, OpenStorage payload) {
                buf.writeVarInt(payload.mayorId());
                buf.writeUUID(payload.storageId());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Open a mine's Mine screen from the Mayor, wherever the mine is. */
    public record OpenMine(int mayorId, UUID mineId) implements CustomPacketPayload {
        public static final Type<OpenMine> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "mayor_open_mine"));
        public static final StreamCodec<FriendlyByteBuf, OpenMine> CODEC = new StreamCodec<>() {
            @Override public OpenMine decode(FriendlyByteBuf buf) {
                return new OpenMine(buf.readVarInt(), buf.readUUID());
            }
            @Override public void encode(FriendlyByteBuf buf, OpenMine payload) {
                buf.writeVarInt(payload.mayorId());
                buf.writeUUID(payload.mineId());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Open a Butchery's window from the Mayor, wherever the Butchery is (10-24). */
    public record OpenButchery(int mayorId, UUID butcheryId) implements CustomPacketPayload {
        public static final Type<OpenButchery> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "mayor_open_butchery"));
        public static final StreamCodec<FriendlyByteBuf, OpenButchery> CODEC = new StreamCodec<>() {
            @Override public OpenButchery decode(FriendlyByteBuf buf) {
                return new OpenButchery(buf.readVarInt(), buf.readUUID());
            }
            @Override public void encode(FriendlyByteBuf buf, OpenButchery payload) {
                buf.writeVarInt(payload.mayorId());
                buf.writeUUID(payload.butcheryId());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Open a Ranch's window from the Mayor, wherever the pen is (10-24). */
    public record OpenRanch(int mayorId, UUID ranchId) implements CustomPacketPayload {
        public static final Type<OpenRanch> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "mayor_open_ranch"));
        public static final StreamCodec<FriendlyByteBuf, OpenRanch> CODEC = new StreamCodec<>() {
            @Override public OpenRanch decode(FriendlyByteBuf buf) {
                return new OpenRanch(buf.readVarInt(), buf.readUUID());
            }
            @Override public void encode(FriendlyByteBuf buf, OpenRanch payload) {
                buf.writeVarInt(payload.mayorId());
                buf.writeUUID(payload.ranchId());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** From a house, storage, mine, Butchery or Ranch screen the Mayor opened: go back to the Mayor's screen. */
    public record BackToMayor(int mayorId) implements CustomPacketPayload {
        public static final Type<BackToMayor> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "mayor_back_from_house"));
        public static final StreamCodec<FriendlyByteBuf, BackToMayor> CODEC = new StreamCodec<>() {
            @Override public BackToMayor decode(FriendlyByteBuf buf) {
                return new BackToMayor(buf.readVarInt());
            }
            @Override public void encode(FriendlyByteBuf buf, BackToMayor payload) {
                buf.writeVarInt(payload.mayorId());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(Locate.TYPE, Locate.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            MayorEntity mayor = mayorMenuOf(player, payload.mayorId());
            VillageManager village = mayor == null ? null : mayor.resolveManager();
            if (village == null) return;
            locate(player, village, payload);
        }));
        registrar.playToClient(Highlight.TYPE, Highlight.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> BuildingLocateHighlight.track(payload)));
        registrar.playToServer(OpenHouse.TYPE, OpenHouse.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            MayorEntity mayor = mayorMenuOf(player, payload.mayorId());
            VillageManager village = mayor == null ? null : mayor.resolveManager();
            if (village == null || !(player.level() instanceof ServerLevel level)) return;
            for (Building building : village.getBuildings()) {
                if (HouseRoomMenu.isBedRoom(building)
                        && building.getItemFrameId().equals(payload.houseId())) {
                    player.openMenu(new SimpleMenuProvider(
                            (id, inventory, ignored) -> new HouseRoomMenu(id, inventory, village, building, level, mayor),
                            Component.literal(HouseRoomMenu.titleFor(building))),
                            buffer -> new HouseRoomMenu(0, player.getInventory(), village, building, level, mayor)
                                    .writeOpeningData(buffer));
                    mayor.markUiOpened(player);
                    return;
                }
            }
            player.sendSystemMessage(Tr.c("chat.dynamicvillages.mayor.that_bed_room_is_no_longer", "That bed room is no longer registered."));
        }));
        registrar.playToServer(OpenStorage.TYPE, OpenStorage.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            MayorEntity mayor = mayorMenuOf(player, payload.mayorId());
            VillageManager village = mayor == null ? null : mayor.resolveManager();
            if (village == null || !(player.level() instanceof ServerLevel level)) return;
            for (Building building : village.getBuildings()) {
                if (building instanceof StorageBuilding storage && storage.getItemFrameId().equals(payload.storageId())) {
                    if (!storage.isValid()) {
                        player.sendSystemMessage(Tr.c("chat.dynamicvillages.mayor.this_storage_room_is_not_working", "This storage room is not working right now."));
                        return;
                    }
                    // Village claims round 6: the storage room is the village's chests.
                    if (!com.alex.test.village.claims.ClaimLock.mayIn(player, village,
                            com.alex.test.village.claims.ClaimLock.Act.USE)) {
                        com.alex.test.village.claims.ClaimLock.tellWindowRefused(player, village);
                        return;
                    }
                    player.openMenu(new SimpleMenuProvider(
                            (id, inventory, ignored) -> new StorageRoomMenu(id, inventory, storage, level, null, mayor),
                            Tr.c("chat.dynamicvillages.mayor.manage_storage_room", "Manage Storage Room")),
                            buffer -> new StorageRoomMenu(0, player.getInventory(), storage, level, null, mayor)
                                    .writeOpeningData(buffer));
                    mayor.markUiOpened(player);
                    return;
                }
            }
            player.sendSystemMessage(Tr.c("chat.dynamicvillages.mayor.that_storage_room_is_no_longer", "That storage room is no longer registered."));
        }));
        registrar.playToServer(OpenMine.TYPE, OpenMine.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            MayorEntity mayor = mayorMenuOf(player, payload.mayorId());
            VillageManager village = mayor == null ? null : mayor.resolveManager();
            if (village == null) return;
            for (Building building : village.getBuildings()) {
                if (building instanceof MineBuilding mine && mine.getItemFrameId().equals(payload.mineId())) {
                    MineRoomAccess.open(player, village, mine, mayor);
                    mayor.markUiOpened(player);
                    return;
                }
            }
            player.sendSystemMessage(Tr.c("chat.dynamicvillages.mayor.that_mine_is_no_longer", "That mine is no longer registered."));
        }));
        registrar.playToServer(OpenButchery.TYPE, OpenButchery.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            MayorEntity mayor = mayorMenuOf(player, payload.mayorId());
            VillageManager village = mayor == null ? null : mayor.resolveManager();
            if (village == null) return;
            for (Building building : village.getBuildings()) {
                if (building instanceof ButcheryBuilding butchery && butchery.getItemFrameId().equals(payload.butcheryId())) {
                    ButcheryRoomAccess.open(player, village, butchery, mayor);
                    mayor.markUiOpened(player);
                    return;
                }
            }
            player.sendSystemMessage(Tr.c("chat.dynamicvillages.mayor.that_butchery_is_no_longer", "That Butchery is no longer registered."));
        }));
        registrar.playToServer(OpenRanch.TYPE, OpenRanch.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            MayorEntity mayor = mayorMenuOf(player, payload.mayorId());
            VillageManager village = mayor == null ? null : mayor.resolveManager();
            if (village == null) return;
            for (Building building : village.getBuildings()) {
                if (building instanceof RanchBuilding ranch && ranch.getItemFrameId().equals(payload.ranchId())) {
                    RanchRoomAccess.open(player, village, ranch, mayor);
                    mayor.markUiOpened(player);
                    return;
                }
            }
            player.sendSystemMessage(Tr.c("chat.dynamicvillages.mayor.that_ranch_is_no_longer", "That Ranch is no longer registered."));
        }));
        registrar.playToServer(BackToMayor.TYPE, BackToMayor.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            int openedBy = player.containerMenu instanceof HouseRoomMenu house ? house.mayorId()
                    : player.containerMenu instanceof StorageRoomMenu storage ? storage.mayorId()
                    : player.containerMenu instanceof MineRoomMenu mine ? mine.mayorId()
                    : player.containerMenu instanceof ButcheryRoomMenu butchery ? butchery.mayorId()
                    : player.containerMenu instanceof RanchRoomMenu ranch ? ranch.mayorId() : -1;
            if (openedBy < 0 || openedBy != payload.mayorId()) return;
            MayorEntity mayor = nearMayor(player, payload.mayorId());
            if (mayor == null) {
                player.closeContainer();
                return;
            }
            player.openMenu(mayor);
            mayor.markUiOpened(player);
        }));
    }

    /** Box around the building's room, or one block for a guard post. */
    private static void locate(ServerPlayer player, VillageManager village, Locate payload) {
        for (Building building : village.getBuildings()) {
            if (!building.getItemFrameId().equals(payload.buildingId())) continue;
            BlockPos door = building.getDoorPos();
            int minX = door.getX(), minY = door.getY(), minZ = door.getZ();
            int maxX = minX, maxY = minY + 1, maxZ = minZ;
            for (BlockPos tile : building.getNavMeshBlocks()) {
                minX = Math.min(minX, tile.getX());
                minY = Math.min(minY, tile.getY());
                minZ = Math.min(minZ, tile.getZ());
                maxX = Math.max(maxX, tile.getX());
                // Tiles are the cells villagers stand in; the room reaches a block above them.
                maxY = Math.max(maxY, tile.getY() + 1);
                maxZ = Math.max(maxZ, tile.getZ());
            }
            send(player, new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), door,
                    building.getDisplayName());
            return;
        }
        if (player.level() instanceof ServerLevel level
                && GuardPostRegistry.get(level).validPostPositions(level, village).contains(payload.pos())) {
            send(player, payload.pos(), payload.pos(), payload.pos(), "Guard post");
            return;
        }
        player.sendSystemMessage(Tr.c("chat.dynamicvillages.mayor.that_building_is_no_longer_registered", "That building is no longer registered."));
    }

    private static void send(ServerPlayer player, BlockPos min, BlockPos max, BlockPos door, String name) {
        PacketDistributor.sendToPlayer(player, new Highlight(min, max, door, HIGHLIGHT_TICKS));
        player.sendSystemMessage(Component.literal(name + " at " + door.getX() + ", " + door.getY() + ", "
                + door.getZ() + " has been outlined for 30 seconds in green"));
    }

    /** The Mayor whose screen the player has open, within reach. */
    @Nullable
    private static MayorEntity mayorMenuOf(ServerPlayer player, int mayorId) {
        if (!(player.containerMenu instanceof MayorStatsMenu menu) || menu.mayorId() != mayorId) return null;
        return nearMayor(player, mayorId);
    }

    @Nullable
    private static MayorEntity nearMayor(Player player, int mayorId) {
        if (!(player.level().getEntity(mayorId) instanceof MayorEntity mayor)
                || !mayor.isAlive() || player.distanceToSqr(mayor) >= 64.0) return null;
        return mayor;
    }
}

package com.alex.test.network;

import com.alex.test.DynamicVillages;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Small messages for opening and editing the storage room screen. */
public final class StorageRoomPayloads {
    private StorageRoomPayloads() {}

    public record Probe(BlockPos pos) implements CustomPacketPayload {
        public static final Type<Probe> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "storage_room_probe"));
        public static final StreamCodec<FriendlyByteBuf, Probe> CODEC = new StreamCodec<>() {
            @Override public Probe decode(FriendlyByteBuf buf) { return new Probe(buf.readBlockPos()); }
            @Override public void encode(FriendlyByteBuf buf, Probe payload) { buf.writeBlockPos(payload.pos()); }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Available(BlockPos pos) implements CustomPacketPayload {
        public static final Type<Available> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "storage_room_available"));
        public static final StreamCodec<FriendlyByteBuf, Available> CODEC = new StreamCodec<>() {
            @Override public Available decode(FriendlyByteBuf buf) { return new Available(buf.readBlockPos()); }
            @Override public void encode(FriendlyByteBuf buf, Available payload) { buf.writeBlockPos(payload.pos()); }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record OpenContainer(BlockPos pos) implements CustomPacketPayload {
        public static final Type<OpenContainer> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "open_storage_room_container"));
        public static final StreamCodec<FriendlyByteBuf, OpenContainer> CODEC = new StreamCodec<>() {
            @Override public OpenContainer decode(FriendlyByteBuf buf) { return new OpenContainer(buf.readBlockPos()); }
            @Override public void encode(FriendlyByteBuf buf, OpenContainer payload) { buf.writeBlockPos(payload.pos()); }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Switches one category on or off for one or more containers (several when shift-selected);
     * each keeps its other categories.
     */
    public record SetFilter(UUID frameId, List<BlockPos> positions, int filter, boolean on)
            implements CustomPacketPayload {
        public static final Type<SetFilter> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "set_storage_container_filter"));
        public static final StreamCodec<FriendlyByteBuf, SetFilter> CODEC = new StreamCodec<>() {
            @Override public SetFilter decode(FriendlyByteBuf buf) {
                return new SetFilter(buf.readUUID(), readPositions(buf), buf.readVarInt(), buf.readBoolean());
            }
            @Override public void encode(FriendlyByteBuf buf, SetFilter payload) {
                buf.writeUUID(payload.frameId());
                writePositions(buf, payload.positions());
                buf.writeVarInt(payload.filter());
                buf.writeBoolean(payload.on());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** The exact items one or more containers take; empty clears the pick. */
    public record SetItems(UUID frameId, List<BlockPos> positions, List<ResourceLocation> items)
            implements CustomPacketPayload {
        public static final Type<SetItems> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "set_storage_container_items"));
        public static final StreamCodec<FriendlyByteBuf, SetItems> CODEC = new StreamCodec<>() {
            @Override public SetItems decode(FriendlyByteBuf buf) {
                UUID frameId = buf.readUUID();
                List<BlockPos> positions = readPositions(buf);
                int count = Math.min(buf.readVarInt(), 256);
                List<ResourceLocation> items = new ArrayList<>();
                for (int i = 0; i < count; i++) items.add(buf.readResourceLocation());
                return new SetItems(frameId, positions, items);
            }
            @Override public void encode(FriendlyByteBuf buf, SetItems payload) {
                buf.writeUUID(payload.frameId());
                writePositions(buf, payload.positions());
                buf.writeVarInt(payload.items().size());
                for (ResourceLocation item : payload.items()) buf.writeResourceLocation(item);
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Locks or unlocks one or more containers' settings. */
    public record SetLocked(UUID frameId, List<BlockPos> positions, boolean locked) implements CustomPacketPayload {
        public static final Type<SetLocked> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "set_storage_container_locked"));
        public static final StreamCodec<FriendlyByteBuf, SetLocked> CODEC = new StreamCodec<>() {
            @Override public SetLocked decode(FriendlyByteBuf buf) {
                return new SetLocked(buf.readUUID(), readPositions(buf), buf.readBoolean());
            }
            @Override public void encode(FriendlyByteBuf buf, SetLocked payload) {
                buf.writeUUID(payload.frameId());
                writePositions(buf, payload.positions());
                buf.writeBoolean(payload.locked());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Stock tab: a new row for {@code item}. */
    public record StockAdd(UUID frameId, ResourceLocation item, boolean once, boolean forPlayer, int count)
            implements CustomPacketPayload {
        public static final Type<StockAdd> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "storage_stock_add"));
        public static final StreamCodec<FriendlyByteBuf, StockAdd> CODEC = new StreamCodec<>() {
            @Override public StockAdd decode(FriendlyByteBuf buf) {
                return new StockAdd(buf.readUUID(), buf.readResourceLocation(), buf.readBoolean(),
                        buf.readBoolean(), buf.readVarInt());
            }
            @Override public void encode(FriendlyByteBuf buf, StockAdd payload) {
                buf.writeUUID(payload.frameId());
                buf.writeResourceLocation(payload.item());
                buf.writeBoolean(payload.once());
                buf.writeBoolean(payload.forPlayer());
                buf.writeVarInt(payload.count());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Stock tab: a row's count and switches. */
    public record StockEdit(UUID frameId, UUID row, int count, boolean once, boolean forPlayer)
            implements CustomPacketPayload {
        public static final Type<StockEdit> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "storage_stock_edit"));
        public static final StreamCodec<FriendlyByteBuf, StockEdit> CODEC = new StreamCodec<>() {
            @Override public StockEdit decode(FriendlyByteBuf buf) {
                return new StockEdit(buf.readUUID(), buf.readUUID(), buf.readVarInt(), buf.readBoolean(),
                        buf.readBoolean());
            }
            @Override public void encode(FriendlyByteBuf buf, StockEdit payload) {
                buf.writeUUID(payload.frameId());
                buf.writeUUID(payload.row());
                buf.writeVarInt(payload.count());
                buf.writeBoolean(payload.once());
                buf.writeBoolean(payload.forPlayer());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Stock tab: removes a row; an order under way is cancelled. */
    public record StockRemove(UUID frameId, UUID row) implements CustomPacketPayload {
        public static final Type<StockRemove> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "storage_stock_remove"));
        public static final StreamCodec<FriendlyByteBuf, StockRemove> CODEC = new StreamCodec<>() {
            @Override public StockRemove decode(FriendlyByteBuf buf) {
                return new StockRemove(buf.readUUID(), buf.readUUID());
            }
            @Override public void encode(FriendlyByteBuf buf, StockRemove payload) {
                buf.writeUUID(payload.frameId());
                buf.writeUUID(payload.row());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Stock tab open: asks for a fresh {@link StockStatus}. */
    public record StockRequest(UUID frameId) implements CustomPacketPayload {
        public static final Type<StockRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "storage_stock_request"));
        public static final StreamCodec<FriendlyByteBuf, StockRequest> CODEC = new StreamCodec<>() {
            @Override public StockRequest decode(FriendlyByteBuf buf) {
                return new StockRequest(buf.readUUID());
            }
            @Override public void encode(FriendlyByteBuf buf, StockRequest payload) {
                buf.writeUUID(payload.frameId());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * One Stock tab row as the screen shows it. {@code tone}: 0 fine, 1 waiting, 2 blocked,
     * 3 quiet. {@code have}: what storage holds of the item.
     */
    public record StockRowView(UUID id, ResourceLocation item, int count, boolean once, boolean forPlayer,
                               int made, int delivered, int ready, int have, String status, int tone,
                               String by) {
        /** The screen's copy after a local edit, until the server's next status arrives. */
        public StockRowView edited(int count, boolean once, boolean forPlayer) {
            return new StockRowView(id, item, count, once, forPlayer, made, delivered, ready, have, status,
                    tone, by);
        }
    }

    /** One finished order. {@code outcome}: StockReserves.Outcome ordinal. */
    public record StockDoneView(ResourceLocation item, int count, int delivered, int outcome, boolean forPlayer,
                                String by, int day) {}

    /**
     * The Stock tab's live state: rows, finished orders, why an item cannot be ordered
     * right now, and what storage holds of every ingredient and material.
     */
    public record StockStatus(UUID frameId, List<StockRowView> rows, List<StockDoneView> history,
                              java.util.Map<ResourceLocation, String> blocked,
                              java.util.Map<ResourceLocation, Integer> totals) implements CustomPacketPayload {
        public static final Type<StockStatus> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "storage_stock_status"));
        public static final StreamCodec<FriendlyByteBuf, StockStatus> CODEC = new StreamCodec<>() {
            @Override public StockStatus decode(FriendlyByteBuf buf) {
                UUID frameId = buf.readUUID();
                List<StockRowView> rows = buf.readList(b -> new StockRowView(b.readUUID(), b.readResourceLocation(),
                        b.readVarInt(), b.readBoolean(), b.readBoolean(), b.readVarInt(), b.readVarInt(),
                        b.readVarInt(), b.readVarInt(), b.readUtf(256), b.readVarInt(), b.readUtf(64)));
                List<StockDoneView> history = buf.readList(b -> new StockDoneView(b.readResourceLocation(),
                        b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readUtf(64),
                        b.readVarInt()));
                java.util.Map<ResourceLocation, String> blocked = new java.util.LinkedHashMap<>();
                int n = buf.readVarInt();
                for (int i = 0; i < n; i++) blocked.put(buf.readResourceLocation(), buf.readUtf(256));
                java.util.Map<ResourceLocation, Integer> totals = new java.util.LinkedHashMap<>();
                n = buf.readVarInt();
                for (int i = 0; i < n; i++) totals.put(buf.readResourceLocation(), buf.readVarInt());
                return new StockStatus(frameId, rows, history, blocked, totals);
            }
            @Override public void encode(FriendlyByteBuf buf, StockStatus payload) {
                buf.writeUUID(payload.frameId());
                buf.writeCollection(payload.rows(), (b, row) -> {
                    b.writeUUID(row.id());
                    b.writeResourceLocation(row.item());
                    b.writeVarInt(row.count());
                    b.writeBoolean(row.once());
                    b.writeBoolean(row.forPlayer());
                    b.writeVarInt(row.made());
                    b.writeVarInt(row.delivered());
                    b.writeVarInt(row.ready());
                    b.writeVarInt(row.have());
                    b.writeUtf(row.status(), 256);
                    b.writeVarInt(row.tone());
                    b.writeUtf(row.by(), 64);
                });
                buf.writeCollection(payload.history(), (b, done) -> {
                    b.writeResourceLocation(done.item());
                    b.writeVarInt(done.count());
                    b.writeVarInt(done.delivered());
                    b.writeVarInt(done.outcome());
                    b.writeBoolean(done.forPlayer());
                    b.writeUtf(done.by(), 64);
                    b.writeVarInt(done.day());
                });
                buf.writeVarInt(payload.blocked().size());
                payload.blocked().forEach((item, reason) -> {
                    buf.writeResourceLocation(item);
                    buf.writeUtf(reason, 256);
                });
                buf.writeVarInt(payload.totals().size());
                payload.totals().forEach((item, count) -> {
                    buf.writeResourceLocation(item);
                    buf.writeVarInt(count);
                });
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static List<BlockPos> readPositions(FriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), 256);
        List<BlockPos> positions = new ArrayList<>();
        for (int i = 0; i < count; i++) positions.add(buf.readBlockPos());
        return positions;
    }

    private static void writePositions(FriendlyByteBuf buf, List<BlockPos> positions) {
        buf.writeVarInt(positions.size());
        for (BlockPos pos : positions) buf.writeBlockPos(pos);
    }

    /**
     * Sets exactly these categories on every container of the room (the door in the schematic);
     * {@code filters} is a bit mask of category ordinals.
     */
    public record SetRoomFilter(UUID frameId, int filters) implements CustomPacketPayload {
        public static final Type<SetRoomFilter> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "set_storage_room_filter"));
        public static final StreamCodec<FriendlyByteBuf, SetRoomFilter> CODEC = new StreamCodec<>() {
            @Override public SetRoomFilter decode(FriendlyByteBuf buf) {
                return new SetRoomFilter(buf.readUUID(), buf.readVarInt());
            }
            @Override public void encode(FriendlyByteBuf buf, SetRoomFilter payload) {
                buf.writeUUID(payload.frameId());
                buf.writeVarInt(payload.filters());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}

package com.alex.test.network;

import com.alex.test.DynamicVillages;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/** The Stock tab's Enchanter page: ordering enchanted books (see {@code BookOrders}). */
public final class BookOrderPayloads {
    private BookOrderPayloads() {}

    /** A new order for {@code level} of enchantment {@code kind}. */
    public record Add(UUID frameId, String kind, int level, boolean once, int count) implements CustomPacketPayload {
        public static final Type<Add> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "book_order_add"));
        public static final StreamCodec<FriendlyByteBuf, Add> CODEC = new StreamCodec<>() {
            @Override public Add decode(FriendlyByteBuf buf) {
                return new Add(buf.readUUID(), buf.readUtf(64), buf.readVarInt(), buf.readBoolean(), buf.readVarInt());
            }
            @Override public void encode(FriendlyByteBuf buf, Add payload) {
                buf.writeUUID(payload.frameId());
                buf.writeUtf(payload.kind(), 64);
                buf.writeVarInt(payload.level());
                buf.writeBoolean(payload.once());
                buf.writeVarInt(payload.count());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** An order's count, or Once and Always. */
    public record Edit(UUID frameId, UUID order, int count, boolean once) implements CustomPacketPayload {
        public static final Type<Edit> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "book_order_edit"));
        public static final StreamCodec<FriendlyByteBuf, Edit> CODEC = new StreamCodec<>() {
            @Override public Edit decode(FriendlyByteBuf buf) {
                return new Edit(buf.readUUID(), buf.readUUID(), buf.readVarInt(), buf.readBoolean());
            }
            @Override public void encode(FriendlyByteBuf buf, Edit payload) {
                buf.writeUUID(payload.frameId());
                buf.writeUUID(payload.order());
                buf.writeVarInt(payload.count());
                buf.writeBoolean(payload.once());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Removes an order. */
    public record Remove(UUID frameId, UUID order) implements CustomPacketPayload {
        public static final Type<Remove> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "book_order_remove"));
        public static final StreamCodec<FriendlyByteBuf, Remove> CODEC = new StreamCodec<>() {
            @Override public Remove decode(FriendlyByteBuf buf) {
                return new Remove(buf.readUUID(), buf.readUUID());
            }
            @Override public void encode(FriendlyByteBuf buf, Remove payload) {
                buf.writeUUID(payload.frameId());
                buf.writeUUID(payload.order());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** One order as the page shows it. {@code tone}: StockOrderStatus FINE, WAITING, BLOCKED or QUIET. */
    public record RowView(UUID id, String kind, int level, int count, boolean once, int made, int stored,
                          String status, int tone, String by) {}

    /** One finished order. {@code outcome}: BookOrders.COLLECTED or CANCELLED. */
    public record DoneView(String kind, int level, int count, int outcome, String by, int day) {}

    /** A book the page offers; {@code blocked} says why it cannot be ordered now, empty when it can. */
    public record ChoiceView(String kind, int level, String blocked) {}

    /** The Enchanter page's live state. */
    public record Status(UUID frameId, List<RowView> rows, List<DoneView> history, List<ChoiceView> choices,
                         String header) implements CustomPacketPayload {
        public static final Type<Status> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "book_order_status"));
        public static final StreamCodec<FriendlyByteBuf, Status> CODEC = new StreamCodec<>() {
            @Override public Status decode(FriendlyByteBuf buf) {
                UUID frameId = buf.readUUID();
                List<RowView> rows = buf.readList(b -> new RowView(b.readUUID(), b.readUtf(64), b.readVarInt(),
                        b.readVarInt(), b.readBoolean(), b.readVarInt(), b.readVarInt(), b.readUtf(256),
                        b.readVarInt(), b.readUtf(64)));
                List<DoneView> history = buf.readList(b -> new DoneView(b.readUtf(64), b.readVarInt(),
                        b.readVarInt(), b.readVarInt(), b.readUtf(64), b.readVarInt()));
                List<ChoiceView> choices = buf.readList(b -> new ChoiceView(b.readUtf(64), b.readVarInt(),
                        b.readUtf(256)));
                return new Status(frameId, rows, history, choices, buf.readUtf(256));
            }
            @Override public void encode(FriendlyByteBuf buf, Status payload) {
                buf.writeUUID(payload.frameId());
                buf.writeCollection(payload.rows(), (b, row) -> {
                    b.writeUUID(row.id());
                    b.writeUtf(row.kind(), 64);
                    b.writeVarInt(row.level());
                    b.writeVarInt(row.count());
                    b.writeBoolean(row.once());
                    b.writeVarInt(row.made());
                    b.writeVarInt(row.stored());
                    b.writeUtf(row.status(), 256);
                    b.writeVarInt(row.tone());
                    b.writeUtf(row.by(), 64);
                });
                buf.writeCollection(payload.history(), (b, done) -> {
                    b.writeUtf(done.kind(), 64);
                    b.writeVarInt(done.level());
                    b.writeVarInt(done.count());
                    b.writeVarInt(done.outcome());
                    b.writeUtf(done.by(), 64);
                    b.writeVarInt(done.day());
                });
                buf.writeCollection(payload.choices(), (b, choice) -> {
                    b.writeUtf(choice.kind(), 64);
                    b.writeVarInt(choice.level());
                    b.writeUtf(choice.blocked(), 256);
                });
                buf.writeUtf(payload.header(), 256);
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static Status empty(UUID frameId) {
            return new Status(frameId, List.of(), List.of(), List.of(), "");
        }
    }
}

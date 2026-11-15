package com.alex.test.network;

import com.alex.test.DynamicVillages;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public final class MerchantTradePayloads {
    private MerchantTradePayloads() {}

    /** Client names only a catalog row and stack size; price is never trusted. */
    public record Sell(int merchantId, int offerIndex, boolean half) implements CustomPacketPayload {
        public static final Type<Sell> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "merchant_sell"));
        public static final StreamCodec<FriendlyByteBuf, Sell> CODEC = new StreamCodec<>() {
            @Override public Sell decode(FriendlyByteBuf buf) {
                return new Sell(buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
            }
            @Override public void encode(FriendlyByteBuf buf, Sell value) {
                buf.writeVarInt(value.merchantId());
                buf.writeVarInt(value.offerIndex());
                buf.writeBoolean(value.half());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * The outcome, with storage counts and today's prices per offer, both
     * already moved by the sale; {@code credit} is the player's saved change.
     */
    public record Result(int merchantId, boolean success, String message,
                         List<Integer> counts, List<Integer> prices, int purse, int credit)
            implements CustomPacketPayload {
        public static final Type<Result> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "merchant_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Result> CODEC = new StreamCodec<>() {
            @Override public Result decode(RegistryFriendlyByteBuf buf) {
                int id = buf.readVarInt();
                boolean success = buf.readBoolean();
                String message = buf.readUtf(160);
                List<Integer> counts = buf.readList(FriendlyByteBuf::readVarInt);
                List<Integer> prices = buf.readList(FriendlyByteBuf::readVarInt);
                int purse = buf.readInt();
                int credit = buf.readInt();
                return new Result(id, success, message, List.copyOf(counts), List.copyOf(prices), purse, credit);
            }
            @Override public void encode(RegistryFriendlyByteBuf buf, Result value) {
                buf.writeVarInt(value.merchantId());
                buf.writeBoolean(value.success());
                buf.writeUtf(value.message(), 160);
                buf.writeCollection(value.counts(), (b, count) -> b.writeVarInt(count));
                buf.writeCollection(value.prices(), (b, price) -> b.writeVarInt(price));
                buf.writeInt(value.purse());
                buf.writeInt(value.credit());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}

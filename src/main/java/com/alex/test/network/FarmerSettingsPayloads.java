package com.alex.test.network;

import com.alex.test.DynamicVillages;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Farmer-specific settings open and update messages. */
public final class FarmerSettingsPayloads {

    private FarmerSettingsPayloads() {}

    public record Open(int villagerId) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "open_farmer_settings"));
        public static final StreamCodec<FriendlyByteBuf, Open> CODEC = new StreamCodec<>() {
            @Override public Open decode(FriendlyByteBuf buffer) {
                return new Open(buffer.readVarInt());
            }

            @Override public void encode(FriendlyByteBuf buffer, Open payload) {
                buffer.writeVarInt(payload.villagerId());
            }
        };

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Update(int villagerId, ResourceLocation settingId, int mode)
            implements CustomPacketPayload {
        public static final Type<Update> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "update_farmer_settings"));
        public static final StreamCodec<FriendlyByteBuf, Update> CODEC = new StreamCodec<>() {
            @Override public Update decode(FriendlyByteBuf buffer) {
                return new Update(buffer.readVarInt(), buffer.readResourceLocation(),
                        buffer.readVarInt());
            }

            @Override public void encode(FriendlyByteBuf buffer, Update payload) {
                buffer.writeVarInt(payload.villagerId());
                buffer.writeResourceLocation(payload.settingId());
                buffer.writeVarInt(payload.mode());
            }
        };

        public boolean enabled() { return mode != 0; }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}

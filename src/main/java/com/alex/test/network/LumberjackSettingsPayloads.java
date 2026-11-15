package com.alex.test.network;

import com.alex.test.DynamicVillages;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The two small client-to-server messages used by Lumberjack settings. */
public final class LumberjackSettingsPayloads {

    private LumberjackSettingsPayloads() {}

    public record Open(int villagerId) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "open_lumberjack_settings"));
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

    public record Update(
            int villagerId,
            ResourceLocation treeType,
            boolean workSetting,
            int mode) implements CustomPacketPayload {

        private static final ResourceLocation NO_TREE =
                ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "none");
        public static final Type<Update> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "update_lumberjack_settings"));
        public static final StreamCodec<FriendlyByteBuf, Update> CODEC = new StreamCodec<>() {
            @Override public Update decode(FriendlyByteBuf buffer) {
                return new Update(
                        buffer.readVarInt(),
                        buffer.readResourceLocation(),
                        buffer.readBoolean(),
                        buffer.readVarInt());
            }
            @Override public void encode(FriendlyByteBuf buffer, Update payload) {
                buffer.writeVarInt(payload.villagerId());
                buffer.writeResourceLocation(payload.treeType());
                buffer.writeBoolean(payload.workSetting());
                buffer.writeVarInt(payload.mode());
            }
        };

        public static Update works(int villagerId, boolean enabled) {
            return new Update(villagerId, NO_TREE, true, enabled ? 1 : 0);
        }

        public static Update setting(
                int villagerId, ResourceLocation settingId, boolean enabled) {
            return new Update(villagerId, settingId, false, enabled ? 1 : 0);
        }

        public static Update tree(
                int villagerId, ResourceLocation treeType, int mode) {
            return new Update(villagerId, treeType, false, mode);
        }

        public boolean enabled() { return mode != 0; }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}

package com.alex.test.network;

import com.alex.test.DynamicVillages;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client-to-server messages used by Guard settings. */
public final class GuardSettingsPayloads {

    private GuardSettingsPayloads() {}

    public record Open(int villagerId) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "open_guard_settings"));
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
            ResourceLocation settingId,
            boolean mobSetting,
            boolean behaviorSetting,
            boolean enabled) implements CustomPacketPayload {

        public static final Type<Update> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "update_guard_settings"));
        public static final StreamCodec<FriendlyByteBuf, Update> CODEC = new StreamCodec<>() {
            @Override public Update decode(FriendlyByteBuf buffer) {
                return new Update(
                        buffer.readVarInt(),
                        buffer.readResourceLocation(),
                        buffer.readBoolean(),
                        buffer.readBoolean(),
                        buffer.readBoolean());
            }
            @Override public void encode(FriendlyByteBuf buffer, Update payload) {
                buffer.writeVarInt(payload.villagerId());
                buffer.writeResourceLocation(payload.settingId());
                buffer.writeBoolean(payload.mobSetting());
                buffer.writeBoolean(payload.behaviorSetting());
                buffer.writeBoolean(payload.enabled());
            }
        };

        public static Update sword(int villagerId, ResourceLocation swordTier, boolean enabled) {
            return new Update(villagerId, swordTier, false, false, enabled);
        }

        public static Update mob(int villagerId, ResourceLocation mobId, boolean enabled) {
            return new Update(villagerId, mobId, true, false, enabled);
        }

        public static Update behavior(int villagerId, ResourceLocation behaviorId, boolean enabled) {
            return new Update(villagerId, behaviorId, false, true, enabled);
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record UpdateGuardPostTime(int villagerId, int seconds) implements CustomPacketPayload {
        public static final Type<UpdateGuardPostTime> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(
                        DynamicVillages.MODID, "update_guard_post_time"));
        public static final StreamCodec<FriendlyByteBuf, UpdateGuardPostTime> CODEC =
                new StreamCodec<>() {
                    @Override public UpdateGuardPostTime decode(FriendlyByteBuf buffer) {
                        return new UpdateGuardPostTime(buffer.readVarInt(), buffer.readVarInt());
                    }

                    @Override public void encode(FriendlyByteBuf buffer, UpdateGuardPostTime payload) {
                        buffer.writeVarInt(payload.villagerId());
                        buffer.writeVarInt(payload.seconds());
                    }
                };

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}

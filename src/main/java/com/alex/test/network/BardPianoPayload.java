package com.alex.test.network;

import com.alex.test.DynamicVillages;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** Controls only this bard's sound instance, leaving other performances alone. */
public record BardPianoPayload(UUID bardId, int entityId, BlockPos stage, int action, float volume)
        implements CustomPacketPayload {
    public static final int START = 0, STOP = 1, VOLUME = 2;
    public static final Type<BardPianoPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "bard_piano"));
    public static final StreamCodec<FriendlyByteBuf, BardPianoPayload> CODEC = new StreamCodec<>() {
        @Override
        public BardPianoPayload decode(FriendlyByteBuf buf) {
            return new BardPianoPayload(buf.readUUID(), buf.readVarInt(), buf.readBlockPos(),
                    buf.readVarInt(), buf.readFloat());
        }

        @Override
        public void encode(FriendlyByteBuf buf, BardPianoPayload value) {
            buf.writeUUID(value.bardId());
            buf.writeVarInt(value.entityId());
            buf.writeBlockPos(value.stage());
            buf.writeVarInt(value.action());
            buf.writeFloat(value.volume());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

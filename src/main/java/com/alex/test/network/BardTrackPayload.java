package com.alex.test.network;

import com.alex.test.DynamicVillages;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Starts or stops one recording played from a bard (a serenade piece, or its part of
 * the tavern band's song). Only that bard's own recordings are touched, so a stop
 * never cuts another bard playing the same piece. {@code offsetTicks} starts the
 * recording that far in: a part that joins a song already playing, or a player who
 * walks in mid-song, hears it in time with the rest.
 */
public record BardTrackPayload(UUID bardId, int entityId, ResourceLocation sound, int action,
                               float volume, int durationTicks, int offsetTicks) implements CustomPacketPayload {
    /** START plays {@code sound}; STOP ends every recording this bard is playing. */
    public static final int START = 0, STOP = 1;
    public static final Type<BardTrackPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "bard_track"));
    public static final StreamCodec<FriendlyByteBuf, BardTrackPayload> CODEC = new StreamCodec<>() {
        @Override
        public BardTrackPayload decode(FriendlyByteBuf buf) {
            return new BardTrackPayload(buf.readUUID(), buf.readVarInt(), buf.readResourceLocation(),
                    buf.readVarInt(), buf.readFloat(), buf.readVarInt(), buf.readVarInt());
        }

        @Override
        public void encode(FriendlyByteBuf buf, BardTrackPayload value) {
            buf.writeUUID(value.bardId());
            buf.writeVarInt(value.entityId());
            buf.writeResourceLocation(value.sound());
            buf.writeVarInt(value.action());
            buf.writeFloat(value.volume());
            buf.writeVarInt(value.durationTicks());
            buf.writeVarInt(value.offsetTicks());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

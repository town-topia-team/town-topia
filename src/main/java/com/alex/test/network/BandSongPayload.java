package com.alex.test.network;

import com.alex.test.DynamicVillages;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * The tavern band's song, played on the client as one sound with its four parts mixed
 * sample by sample, so they can never drift apart. START begins a play-through
 * {@code offsetTicks} in (0 at the top; more for a player who comes by mid-song).
 * UPDATE changes who plays which part and how loud, at once, in the same sound.
 * STOP ends it. {@code holders} and {@code gains} are in {@link #PARTS} order; a
 * holder of -1 means nobody plays that part.
 */
public record BandSongPayload(UUID bandId, long songId, int action, int offsetTicks,
                              int[] holders, float[] gains) implements CustomPacketPayload {
    public static final int START = 0, UPDATE = 1, STOP = 2;
    /** Part order: violin, guitar, bass, bongos. */
    public static final int PARTS = 4;
    public static final Type<BandSongPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "band_song"));
    public static final StreamCodec<FriendlyByteBuf, BandSongPayload> CODEC = new StreamCodec<>() {
        @Override
        public BandSongPayload decode(FriendlyByteBuf buf) {
            UUID band = buf.readUUID();
            long song = buf.readLong();
            int action = buf.readVarInt();
            int offset = buf.readVarInt();
            int[] holders = new int[PARTS];
            float[] gains = new float[PARTS];
            for (int i = 0; i < PARTS; i++) {
                holders[i] = buf.readInt();
                gains[i] = buf.readFloat();
            }
            return new BandSongPayload(band, song, action, offset, holders, gains);
        }

        @Override
        public void encode(FriendlyByteBuf buf, BandSongPayload value) {
            buf.writeUUID(value.bandId());
            buf.writeLong(value.songId());
            buf.writeVarInt(value.action());
            buf.writeVarInt(value.offsetTicks());
            for (int i = 0; i < PARTS; i++) {
                buf.writeInt(value.holders()[i]);
                buf.writeFloat(value.gains()[i]);
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

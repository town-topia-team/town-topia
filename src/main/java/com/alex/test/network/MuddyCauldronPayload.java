package com.alex.test.network;

import com.alex.test.DynamicVillages;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Which cauldrons hold muddy wash water, so the client can tint them brown.
 * {@link #REPLACE} carries the whole set for the player's dimension (on login
 * and dimension change); {@link #ADD} and {@link #REMOVE} carry single changes.
 */
public record MuddyCauldronPayload(int mode, long[] positions) implements CustomPacketPayload {

    public static final int REPLACE = 0;
    public static final int ADD = 1;
    public static final int REMOVE = 2;

    public static final Type<MuddyCauldronPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "muddy_cauldrons"));

    public static final StreamCodec<FriendlyByteBuf, MuddyCauldronPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public MuddyCauldronPayload decode(FriendlyByteBuf buffer) {
                    return new MuddyCauldronPayload(buffer.readByte(), buffer.readLongArray());
                }

                @Override
                public void encode(FriendlyByteBuf buffer, MuddyCauldronPayload payload) {
                    buffer.writeByte(payload.mode());
                    buffer.writeLongArray(payload.positions());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

package com.alex.test.network;

import com.alex.test.DynamicVillages;
import com.alex.test.village.claims.ClaimLock;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server to game: the village squares where this player is refused something in their dimension, with what they are
 * refused there (village claims rounds 5-6; since round 9 a mask from the village's grid), so the game refuses the
 * same clicks the server does (no cracks, no ghost blocks, no door flicking open). The whole list each time.
 */
public record ClaimLandPayload(ResourceLocation dimension, List<ClaimLock.Locked> squares) implements CustomPacketPayload {

    /** More than this many villages in one dimension is not expected; the rest are left to the server. */
    private static final int MAX_SQUARES = 256;

    public static final Type<ClaimLandPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "claim_land"));

    public static final StreamCodec<FriendlyByteBuf, ClaimLandPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ClaimLandPayload decode(FriendlyByteBuf buf) {
            ResourceLocation dimension = buf.readResourceLocation();
            int count = Math.min(buf.readVarInt(), MAX_SQUARES);
            List<ClaimLock.Locked> squares = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                squares.add(new ClaimLock.Locked(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                        buf.readUtf(64), buf.readVarInt()));
            }
            return new ClaimLandPayload(dimension, squares);
        }

        @Override
        public void encode(FriendlyByteBuf buf, ClaimLandPayload payload) {
            buf.writeResourceLocation(payload.dimension());
            int count = Math.min(payload.squares().size(), MAX_SQUARES);
            buf.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                ClaimLock.Locked square = payload.squares().get(i);
                buf.writeVarInt(square.minX());
                buf.writeVarInt(square.minZ());
                buf.writeVarInt(square.maxX());
                buf.writeVarInt(square.maxZ());
                buf.writeUtf(square.ownerName(), 64);
                buf.writeVarInt(square.refused());
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

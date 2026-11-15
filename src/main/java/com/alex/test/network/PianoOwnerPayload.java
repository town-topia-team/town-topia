package com.alex.test.network;

import com.alex.test.DynamicVillages;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Whether this game's own player counts as an owner of ZalithYT's Piano ({@link com.alex.test.block.PianoLock}).
 * Sent at login and whenever the dev test switch changes.
 */
public record PianoOwnerPayload(boolean owner) implements CustomPacketPayload {
    public static final Type<PianoOwnerPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            DynamicVillages.MODID, "piano_owner"));
    public static final StreamCodec<ByteBuf, PianoOwnerPayload> CODEC =
            ByteBufCodecs.BOOL.map(PianoOwnerPayload::new, PianoOwnerPayload::owner);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

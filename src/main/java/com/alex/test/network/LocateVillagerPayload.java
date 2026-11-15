package com.alex.test.network;

import com.alex.test.DynamicVillages;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** Requests a temporary outline for one villager listed in the open Mayor menu. */
public record LocateVillagerPayload(int mayorId, UUID villagerId) implements CustomPacketPayload {

    public static final Type<LocateVillagerPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "locate_villager"));

    public static final StreamCodec<FriendlyByteBuf, LocateVillagerPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public LocateVillagerPayload decode(FriendlyByteBuf buffer) {
                    return new LocateVillagerPayload(buffer.readVarInt(), buffer.readUUID());
                }

                @Override
                public void encode(FriendlyByteBuf buffer, LocateVillagerPayload payload) {
                    buffer.writeVarInt(payload.mayorId());
                    buffer.writeUUID(payload.villagerId());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

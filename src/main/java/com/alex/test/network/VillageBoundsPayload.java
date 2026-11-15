package com.alex.test.network;

import com.alex.test.DynamicVillages;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Sent server→client whenever a village's navigation bounds are (re)established.
 * The client uses this list to render the glowing border effect.
 */
public record VillageBoundsPayload(List<AABB> bounds) implements CustomPacketPayload {

    public static final Type<VillageBoundsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "village_bounds"));

    public static final StreamCodec<FriendlyByteBuf, VillageBoundsPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public VillageBoundsPayload decode(FriendlyByteBuf buf) {
                    int count = buf.readVarInt();
                    List<AABB> list = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        list.add(new AABB(
                                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                                buf.readDouble(), buf.readDouble(), buf.readDouble()
                        ));
                    }
                    return new VillageBoundsPayload(list);
                }

                @Override
                public void encode(FriendlyByteBuf buf, VillageBoundsPayload payload) {
                    buf.writeVarInt(payload.bounds().size());
                    for (AABB b : payload.bounds()) {
                        buf.writeDouble(b.minX);
                        buf.writeDouble(b.minY);
                        buf.writeDouble(b.minZ);
                        buf.writeDouble(b.maxX);
                        buf.writeDouble(b.maxY);
                        buf.writeDouble(b.maxZ);
                    }
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

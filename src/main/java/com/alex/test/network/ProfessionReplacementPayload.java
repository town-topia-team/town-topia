package com.alex.test.network;

import com.alex.test.DynamicVillages;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A chosen profession-skill slot from the villager inventory screen. */
public record ProfessionReplacementPayload(int villagerId, int slot) implements CustomPacketPayload {
    public static final Type<ProfessionReplacementPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "replace_profession_slot"));
    public static final StreamCodec<FriendlyByteBuf, ProfessionReplacementPayload> CODEC =
            new StreamCodec<>() {
                @Override public ProfessionReplacementPayload decode(FriendlyByteBuf buffer) {
                    return new ProfessionReplacementPayload(buffer.readVarInt(), buffer.readVarInt());
                }
                @Override public void encode(FriendlyByteBuf buffer, ProfessionReplacementPayload payload) {
                    buffer.writeVarInt(payload.villagerId());
                    buffer.writeVarInt(payload.slot());
                }
            };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

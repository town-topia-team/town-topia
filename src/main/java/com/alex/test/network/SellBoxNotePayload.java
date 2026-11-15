package com.alex.test.network;

import com.alex.test.DynamicVillages;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * The sell box hint for the merchant stall barrel a player has open, drawn on
 * that barrel's screen. {@code containerId} ties it to the open menu; the first
 * line is the heading. Sent again while the barrel stays open whenever the
 * counts change.
 */
public record SellBoxNotePayload(int containerId, List<String> lines) implements CustomPacketPayload {
    public static final Type<SellBoxNotePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            DynamicVillages.MODID, "sell_box_note"));
    public static final StreamCodec<FriendlyByteBuf, SellBoxNotePayload> CODEC = new StreamCodec<>() {
        @Override public SellBoxNotePayload decode(FriendlyByteBuf buf) {
            int containerId = buf.readVarInt();
            return new SellBoxNotePayload(containerId, buf.readList(b -> b.readUtf(256)));
        }
        @Override public void encode(FriendlyByteBuf buf, SellBoxNotePayload payload) {
            buf.writeVarInt(payload.containerId());
            buf.writeCollection(payload.lines(), (b, line) -> b.writeUtf(line, 256));
        }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

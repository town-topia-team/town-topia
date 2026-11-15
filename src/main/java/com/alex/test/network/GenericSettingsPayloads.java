package com.alex.test.network;

import com.alex.test.DynamicVillages;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-to-server messages for the generic, schema-driven profession
 * settings screen ({@code GenericSettingsMenu}/{@code GenericSettingsScreen}).
 *
 * <p>Unlike Guard/Lumberjack's bespoke payload pairs, one {@link Update}
 * shape covers every row kind: {@code encodedValue} is {@code "true"}/
 * {@code "false"} for a toggle, an integer's string form for a stepper or
 * named choice, or
 * {@code "<entryId>:true"}/{@code "<entryId>:false"} for an allow-list/tier
 * entry (an entry id such as {@code "minecraft:zombie"} may itself contain a
 * colon, so the receiving side must split on the *last* colon).
 */
public final class GenericSettingsPayloads {

    private GenericSettingsPayloads() {}

    public record Open(int villagerId) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "open_profession_settings"));
        public static final StreamCodec<FriendlyByteBuf, Open> CODEC = new StreamCodec<>() {
            @Override public Open decode(FriendlyByteBuf buffer) {
                return new Open(buffer.readVarInt());
            }
            @Override public void encode(FriendlyByteBuf buffer, Open payload) {
                buffer.writeVarInt(payload.villagerId());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Update(
            int villagerId,
            String settingId,
            String encodedValue) implements CustomPacketPayload {

        public static final Type<Update> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "update_profession_settings"));
        public static final StreamCodec<FriendlyByteBuf, Update> CODEC = new StreamCodec<>() {
            @Override public Update decode(FriendlyByteBuf buffer) {
                return new Update(
                        buffer.readVarInt(),
                        buffer.readUtf(128),
                        buffer.readUtf(256));
            }
            @Override public void encode(FriendlyByteBuf buffer, Update payload) {
                buffer.writeVarInt(payload.villagerId());
                buffer.writeUtf(payload.settingId(), 128);
                buffer.writeUtf(payload.encodedValue(), 256);
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Changes one tradesman's choice for an item currently present in village storage. */
    public record SaleItemUpdate(int villagerId, ResourceLocation itemId, boolean allowed)
            implements CustomPacketPayload {
        public static final Type<SaleItemUpdate> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "update_tradesman_sale_item"));
        public static final StreamCodec<FriendlyByteBuf, SaleItemUpdate> CODEC = new StreamCodec<>() {
            @Override public SaleItemUpdate decode(FriendlyByteBuf buffer) {
                return new SaleItemUpdate(buffer.readVarInt(), buffer.readResourceLocation(), buffer.readBoolean());
            }
            @Override public void encode(FriendlyByteBuf buffer, SaleItemUpdate payload) {
                buffer.writeVarInt(payload.villagerId());
                buffer.writeResourceLocation(payload.itemId());
                buffer.writeBoolean(payload.allowed());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Sets one tradesman's own keep number for an item in village storage;
     * {@code keep} of {@code TradesmanSalePolicy.AUTO} goes back to the Behaviour tab's.
     */
    public record SaleKeepUpdate(int villagerId, ResourceLocation itemId, int keep)
            implements CustomPacketPayload {
        public static final Type<SaleKeepUpdate> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "update_tradesman_sale_keep"));
        public static final StreamCodec<FriendlyByteBuf, SaleKeepUpdate> CODEC = new StreamCodec<>() {
            @Override public SaleKeepUpdate decode(FriendlyByteBuf buffer) {
                return new SaleKeepUpdate(buffer.readVarInt(), buffer.readResourceLocation(), buffer.readInt());
            }
            @Override public void encode(FriendlyByteBuf buffer, SaleKeepUpdate payload) {
                buffer.writeVarInt(payload.villagerId());
                buffer.writeResourceLocation(payload.itemId());
                buffer.writeInt(payload.keep());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}

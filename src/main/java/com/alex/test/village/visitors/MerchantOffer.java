package com.alex.test.village.visitors;

import net.minecraft.network.RegistryFriendlyByteBuf;

/** One immutable line in a merchant's visit catalog. Counts are supplied separately. */
public record MerchantOffer(String profession, String itemId, int fullPrice) {
    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(profession, 40);
        buffer.writeUtf(itemId, 128);
        buffer.writeVarInt(fullPrice);
    }

    public static MerchantOffer read(RegistryFriendlyByteBuf buffer) {
        return new MerchantOffer(buffer.readUtf(40), buffer.readUtf(128), buffer.readVarInt());
    }
}

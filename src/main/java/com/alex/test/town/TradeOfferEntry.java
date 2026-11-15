package com.alex.test.town;

import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * One line of the Mayor's shop as the client sees it.
 *
 * <p>Prices are computed server-side from village size, so the client never
 * derives one — it renders {@link #price()} and, on a click, asks the server to
 * sell. Affordability is deliberately absent: the client knows its own emerald
 * count and greys the row out itself, which keeps the row correct the instant
 * the player's inventory changes.
 */
public record TradeOfferEntry(
        /** Registry id of the item sold, e.g. {@code dynamicvillages:farmer_token}. */
        String itemId,
        String displayName,
        /** Emeralds charged right now, already scaled for village size. */
        int price,
        /** {@code true} for profession tokens, {@code false} for structures. */
        boolean profession,
        /**
         * A one-line nudge rendered under the name — "no Farm registered yet",
         * "hang it beside a door". Never a purchase gate: a village that cannot
         * progress is the bug this shop exists to fix, so nothing here refuses
         * to sell.
         */
        String hint
) {
    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(itemId);
        buffer.writeUtf(displayName);
        buffer.writeVarInt(price);
        buffer.writeBoolean(profession);
        buffer.writeUtf(hint);
    }

    public static TradeOfferEntry read(RegistryFriendlyByteBuf buffer) {
        String itemId = buffer.readUtf();
        String displayName = buffer.readUtf();
        int price = buffer.readVarInt();
        boolean profession = buffer.readBoolean();
        String hint = buffer.readUtf();
        return new TradeOfferEntry(itemId, displayName, price, profession, hint);
    }
}

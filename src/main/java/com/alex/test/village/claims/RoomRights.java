package com.alex.test.village.claims;

import com.alex.test.village.VillageManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;

/**
 * Village claims round 7b: whether the player who opened a room window (mine, Butchery, storage room) may change
 * it, worked out on the server when the window opens and sent last in its opening data. False = look only; the
 * window greys its controls and names the owner. The server checks every change again ({@link ClaimLock#mayIn}).
 */
public record RoomRights(boolean mayChange, String ownerName) {

    /** Claims off, nobody's village, or a game that was not told: everything may be changed. */
    public static final RoomRights ALL = new RoomRights(true, "");

    /** On the server, for the player opening the window. */
    public static RoomRights of(@Nullable Player viewer, @Nullable VillageManager village, ClaimLock.Act act) {
        if (!(viewer instanceof ServerPlayer player) || village == null
                || ClaimLock.mayIn(player, village, act)) return ALL;
        return new RoomRights(false, village.claim().ownerName());
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeBoolean(mayChange);
        buffer.writeUtf(ownerName, 64);
    }

    public static RoomRights read(FriendlyByteBuf buffer) {
        return new RoomRights(buffer.readBoolean(), buffer.readUtf(64));
    }
}

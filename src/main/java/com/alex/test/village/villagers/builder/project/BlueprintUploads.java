package com.alex.test.village.villagers.builder.project;

import com.alex.test.village.villagers.builder.blueprint.BlueprintLimits;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A blueprint file coming in from a player in pieces before an order. One upload per
 * player at a time; a new one replaces the old. Kept 60 seconds, dropped on log out.
 * Server thread only.
 */
public final class BlueprintUploads {
    private BlueprintUploads() {}

    /** Bytes per piece: under the 32 KB a message from the client may carry. */
    public static final int CHUNK_BYTES = 30_000;
    private static final long KEEP_MS = 60_000;

    private static final class Upload {
        final int id;
        final byte[] bytes;
        int received;
        final long started = System.currentTimeMillis();

        Upload(int id, int size) {
            this.id = id;
            this.bytes = new byte[size];
        }
    }

    private static final Map<UUID, Upload> UPLOADS = new HashMap<>();

    /** Starts an upload; false when the file is too big. */
    public static boolean begin(ServerPlayer player, int id, int size) {
        UPLOADS.remove(player.getUUID());
        if (size <= 0 || size > BlueprintLimits.MAX_FILE_BYTES) return false;
        UPLOADS.put(player.getUUID(), new Upload(id, size));
        return true;
    }

    public static void chunk(ServerPlayer player, int id, byte[] data) {
        Upload upload = UPLOADS.get(player.getUUID());
        if (upload == null || upload.id != id) return;
        if (data.length > upload.bytes.length - upload.received) {
            UPLOADS.remove(player.getUUID());
            return;
        }
        System.arraycopy(data, 0, upload.bytes, upload.received, data.length);
        upload.received += data.length;
    }

    /** The whole file of this upload, taken out; null when missing, partial or too old. */
    @Nullable
    public static byte[] take(ServerPlayer player, int id) {
        Upload upload = UPLOADS.remove(player.getUUID());
        if (upload == null || upload.id != id || upload.received != upload.bytes.length
                || System.currentTimeMillis() - upload.started > KEEP_MS) {
            return null;
        }
        return upload.bytes;
    }

    public static void clear(ServerPlayer player) {
        UPLOADS.remove(player.getUUID());
    }

    public static void clearAll() {
        UPLOADS.clear();
    }
}

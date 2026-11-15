package com.alex.test.village.villagers.builder.blueprint;

import java.util.Locale;

/**
 * How big a blueprint may be. A file comes from the player's disk and later
 * travels to the server, so every size is capped before anything is allocated
 * for it: a huge or hand-made file must fail with a message, not run the game
 * out of memory.
 */
public final class BlueprintLimits {
    private BlueprintLimits() {
    }

    /** Largest file on disk (compressed), in bytes. */
    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    /** Largest amount of NBT one file may unpack to, in bytes. */
    public static final long MAX_NBT_BYTES = 32L * 1024 * 1024;
    /** Longest side, in blocks. */
    public static final int MAX_AXIS = 128;
    /** Most cells in the whole box, empty ones included. */
    public static final int MAX_VOLUME = 2_097_152;
    /** Most blocks the Builder would place or clear. */
    public static final int MAX_NON_VOID = 200_000;
    /** Most different block states in one file. */
    public static final int MAX_PALETTE = 4_096;

    /** Refuses a file over {@link #MAX_FILE_BYTES}. */
    public static void checkFileSize(long bytes) throws BlueprintException {
        if (bytes > MAX_FILE_BYTES) {
            throw new BlueprintException("This file is too big: " + megabytes(bytes)
                    + ", the limit is " + megabytes(MAX_FILE_BYTES) + ".");
        }
    }

    /** Refuses a box with a side out of range or too many cells. */
    public static void checkSize(int sizeX, int sizeY, int sizeZ) throws BlueprintException {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            throw new BlueprintException("This blueprint has no blocks in it.");
        }
        checkAxis(sizeX, "wide");
        checkAxis(sizeY, "tall");
        checkAxis(sizeZ, "long");
        long volume = (long) sizeX * sizeY * sizeZ;
        if (volume > MAX_VOLUME) {
            throw new BlueprintException("This blueprint is too big: " + volume
                    + " blocks in its box, the limit is " + MAX_VOLUME + ".");
        }
    }

    /** Refuses a file with too many different blocks. */
    public static void checkPalette(int size) throws BlueprintException {
        if (size > MAX_PALETTE) {
            throw new BlueprintException("This blueprint uses too many different blocks: " + size
                    + ", the limit is " + MAX_PALETTE + ".");
        }
    }

    /** Refuses a blueprint with too many blocks to place or clear. */
    public static void checkNonVoid(int count) throws BlueprintException {
        if (count > MAX_NON_VOID) {
            throw new BlueprintException("This blueprint has too many blocks: " + count
                    + ", the limit is " + MAX_NON_VOID + ".");
        }
    }

    private static void checkAxis(int size, String word) throws BlueprintException {
        if (size > MAX_AXIS) {
            throw new BlueprintException("This blueprint is too big: " + size + " blocks " + word
                    + ", the limit is " + MAX_AXIS + ".");
        }
    }

    private static String megabytes(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        return (mb == Math.rint(mb) ? String.format(Locale.ROOT, "%.0f", mb)
                : String.format(Locale.ROOT, "%.1f", mb)) + " MB";
    }
}

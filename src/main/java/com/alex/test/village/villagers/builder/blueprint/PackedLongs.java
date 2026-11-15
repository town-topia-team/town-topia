package com.alex.test.village.villagers.builder.blueprint;

/**
 * Unpacks Litematica's block array: palette indexes packed into a long[] with
 * a fixed number of bits each, where one entry may run over into the next long.
 *
 * <p>Kept free of Minecraft classes so the JUnit tests can run it.
 */
public final class PackedLongs {
    private PackedLongs() {
    }

    /**
     * Bits per entry Litematica uses for a palette of this size: enough for the
     * largest index, but never fewer than 2.
     */
    public static int bitsFor(int paletteSize) {
        return Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(0, paletteSize - 1)));
    }

    /**
     * The first {@code count} entries of {@code longs}, {@code bits} bits each.
     *
     * @throws IllegalArgumentException when the array is too short or the bit
     *         width makes no sense (a damaged file)
     */
    public static int[] unpack(long[] longs, int bits, int count) {
        if (bits < 1 || bits > 32) {
            throw new IllegalArgumentException("bits per entry out of range: " + bits);
        }
        if (count < 0) {
            throw new IllegalArgumentException("negative count");
        }
        long neededLongs = ((long) count * bits + 63L) >>> 6;
        if (longs.length < neededLongs) {
            throw new IllegalArgumentException("packed array too short: " + longs.length + " < " + neededLongs);
        }
        long mask = (1L << bits) - 1L;
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            long startBit = (long) i * bits;
            int startLong = (int) (startBit >>> 6);
            int endLong = (int) (((long) (i + 1) * bits - 1L) >>> 6);
            int shift = (int) (startBit & 63L);
            long value;
            if (startLong == endLong) {
                value = longs[startLong] >>> shift;
            } else {
                // The entry starts near the top of one long and ends in the next.
                value = (longs[startLong] >>> shift) | (longs[endLong] << (64 - shift));
            }
            out[i] = (int) (value & mask);
        }
        return out;
    }

    /** Packs entries the same way; the tests use it to build sample data. */
    public static long[] pack(int[] values, int bits) {
        long[] out = new long[(int) (((long) values.length * bits + 63L) >>> 6)];
        long mask = (1L << bits) - 1L;
        for (int i = 0; i < values.length; i++) {
            long v = values[i] & mask;
            long startBit = (long) i * bits;
            int startLong = (int) (startBit >>> 6);
            int endLong = (int) (((long) (i + 1) * bits - 1L) >>> 6);
            int shift = (int) (startBit & 63L);
            out[startLong] |= v << shift;
            if (startLong != endLong) {
                out[endLong] |= v >>> (64 - shift);
            }
        }
        return out;
    }
}

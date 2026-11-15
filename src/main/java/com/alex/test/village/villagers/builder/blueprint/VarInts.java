package com.alex.test.village.villagers.builder.blueprint;

import java.io.ByteArrayOutputStream;

/**
 * Reads the Sponge schematic block array: palette indexes as varints, seven
 * bits per byte, low bits first, the top bit set when more bytes follow.
 *
 * <p>Kept free of Minecraft classes so the JUnit tests can run it.
 */
public final class VarInts {
    private VarInts() {
    }

    /**
     * The first {@code count} varints of {@code data}.
     *
     * @throws IllegalArgumentException when the data ends early or a number is
     *         longer than five bytes (a damaged file)
     */
    public static int[] decode(byte[] data, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("negative count");
        }
        int[] out = new int[count];
        int pos = 0;
        for (int i = 0; i < count; i++) {
            int value = 0;
            int shift = 0;
            while (true) {
                if (pos >= data.length) {
                    throw new IllegalArgumentException("block data ends after " + i + " of " + count + " blocks");
                }
                byte b = data[pos++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
                if (shift > 28) {
                    throw new IllegalArgumentException("varint longer than five bytes at block " + i);
                }
            }
            out[i] = value;
        }
        return out;
    }

    /** Writes numbers as varints; the tests use it to build sample data. */
    public static byte[] encode(int[] values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(values.length);
        for (int v : values) {
            int value = v;
            while ((value & ~0x7F) != 0) {
                out.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            out.write(value);
        }
        return out.toByteArray();
    }
}

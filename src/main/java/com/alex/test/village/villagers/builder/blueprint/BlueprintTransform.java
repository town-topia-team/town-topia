package com.alex.test.village.villagers.builder.blueprint;

/**
 * Where a cell of a blueprint lands after a mirror and a turn, shifted back so
 * the corner is at 0,0,0 again.
 *
 * <p>Same maths as vanilla {@code StructureTemplate.transform} with the pivot
 * at 0: mirror first (LEFT_RIGHT flips Z, FRONT_BACK flips X), then turn around
 * Y. Turns are counted clockwise in quarters, which is the order of vanilla's
 * {@code Rotation} values (NONE, CLOCKWISE_90, CLOCKWISE_180,
 * COUNTERCLOCKWISE_90), so {@code rotation.ordinal()} can be passed straight in;
 * the mirror codes follow {@code Mirror}'s order the same way.
 * Kept free of Minecraft classes so the JUnit tests can run it.
 */
public final class BlueprintTransform {
    /** Mirror codes, in the order of vanilla's {@code Mirror} values. */
    public static final int MIRROR_NONE = 0, MIRROR_LEFT_RIGHT = 1, MIRROR_FRONT_BACK = 2;

    private final int sizeX;
    private final int sizeZ;
    private final int mirror;
    private final int turns;

    /**
     * @param mirror one of the MIRROR_ codes
     * @param clockwiseQuarterTurns 0..3; other values wrap
     */
    public BlueprintTransform(int sizeX, int sizeZ, int mirror, int clockwiseQuarterTurns) {
        if (mirror < MIRROR_NONE || mirror > MIRROR_FRONT_BACK) {
            throw new IllegalArgumentException("unknown mirror " + mirror);
        }
        this.sizeX = sizeX;
        this.sizeZ = sizeZ;
        this.mirror = mirror;
        this.turns = Math.floorMod(clockwiseQuarterTurns, 4);
    }

    /** Size along X after the turn: a quarter turn swaps X and Z. */
    public int newSizeX() {
        return (turns & 1) == 0 ? sizeX : sizeZ;
    }

    /** Size along Z after the turn. */
    public int newSizeZ() {
        return (turns & 1) == 0 ? sizeZ : sizeX;
    }

    /** New X of the cell at x,z. */
    public int mapX(int x, int z) {
        int mx = mirroredX(x);
        int mz = mirroredZ(z);
        return switch (turns) {
            case 1 -> sizeZ - 1 - mz;   // clockwise: (x, z) -> (-z, x)
            case 2 -> sizeX - 1 - mx;   // half turn: (x, z) -> (-x, -z)
            case 3 -> mz;               // counter-clockwise: (x, z) -> (z, -x)
            default -> mx;
        };
    }

    /** New Z of the cell at x,z. */
    public int mapZ(int x, int z) {
        int mx = mirroredX(x);
        int mz = mirroredZ(z);
        return switch (turns) {
            case 1 -> mx;
            case 2 -> sizeZ - 1 - mz;
            case 3 -> sizeX - 1 - mx;
            default -> mz;
        };
    }

    private int mirroredX(int x) {
        return mirror == MIRROR_FRONT_BACK ? sizeX - 1 - x : x;
    }

    private int mirroredZ(int z) {
        return mirror == MIRROR_LEFT_RIGHT ? sizeZ - 1 - z : z;
    }
}

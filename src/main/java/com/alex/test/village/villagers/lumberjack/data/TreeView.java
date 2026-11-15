package com.alex.test.village.villagers.lumberjack.data;

/**
 * What {@link TreeJudge} can see of the world: one block at a time, already
 * sorted into the few kinds the tree rules care about.
 *
 * <p>Kept free of Minecraft so the judge can be unit tested on hand-built
 * shapes. {@link LevelTreeView} is the real world behind it.
 */
public interface TreeView {

    enum Kind {
        AIR,
        /** A log with bark on its sides: what trees grow. */
        PLAIN_LOG,
        /** Stripped logs and all-bark blocks ({@code _wood}, {@code _hyphae}): no tree grows them. */
        BARK_OR_STRIPPED,
        /** Leaves that grew (not persistent). */
        NATURAL_LEAF,
        /** Leaves somebody placed (persistent). */
        PLACED_LEAF,
        /** A block only builds have: planks, stairs, glass, cobblestone, torches... (the build-marks tag). */
        BUILT,
        /** Anything else solid: dirt, grass, stone, sand, mangrove roots... */
        GROUND,
        /** Anything else that is not solid: water, flowers, vines, snow layers... */
        OTHER,
        /** In a chunk that is not loaded. */
        UNLOADED
    }

    /**
     * One block as the judge sees it.
     *
     * @param family  for logs, the wood: the same number is the same wood, -1 a wood no
     *                tree type grows. For leaves, the leaf block's number, for {@link #leafFits}.
     * @param upright for logs, standing on end (no axis, or the up-down axis)
     * @param solid   it stops movement, as Minecraft's {@code blocksMotion}
     */
    record Cell(Kind kind, int family, boolean upright, boolean solid) {
        public static final Cell UNLOADED = new Cell(Kind.UNLOADED, -1, false, false);

        public boolean isLog() {
            return kind == Kind.PLAIN_LOG || kind == Kind.BARK_OR_STRIPPED;
        }

        public boolean isLeaf() {
            return kind == Kind.NATURAL_LEAF || kind == Kind.PLACED_LEAF;
        }
    }

    Cell at(int x, int y, int z);

    /** A position the lumberjack must never cut: a building's shell, tended cocoa, outside the village. */
    boolean keepOut(int x, int y, int z);

    /** True when grown leaves with this leaf number can crown a tree of this wood. */
    boolean leafFits(int logFamily, int leafFamily);

    /** A block trees grow on (dirt kinds, mangrove roots). Unless told otherwise, all natural ground is. */
    default boolean soil(int x, int y, int z) {
        return at(x, y, z).kind() == Kind.GROUND;
    }
}

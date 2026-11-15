package com.alex.test.village.villagers.lumberjack.data;

import com.alex.test.village.villagers.lumberjack.data.TreeView.Cell;
import com.alex.test.village.villagers.lumberjack.data.TreeView.Kind;

/**
 * Where the tree that just grew from a sapling stands: the foot of its trunk.
 *
 * <p>Every vanilla grower puts the trunk on the sapling's own spot (a
 * mangrove's roots lift it 1-3; a big 2x2 tree grows on the square the
 * sapling is a corner of), so the sapling's own column is searched first,
 * then the ring round it, then the ring two out. The old 5x5 search took the
 * first standing log from its west/north corner and counted leaves as
 * ground, so a bent acacia's branch over its own leaves, or a grown tree two
 * blocks west, won, and the new tree was never listed.
 *
 * <p>Kept free of Minecraft so it can be unit tested on {@code VanillaTrees}.
 */
public final class GrowFoot {

    /** How far below the sapling a foot may be. */
    static final int BELOW = 1;
    /** How far above the sapling a foot may be: a mangrove's roots lift its trunk up to 3. */
    static final int ABOVE = 4;
    /** Rings round the sapling searched after its own column. */
    static final int RINGS = 2;

    private GrowFoot() {}

    /**
     * The packed foot of the tree grown from the sapling at x, y, z (for a big
     * 2x2, its low corner), or {@link TreeJudge#NO_POS} when no column near it
     * holds one.
     */
    public static long find(TreeView view, int x, int y, int z) {
        long foot = footInColumn(view, x, y, z);
        for (int ring = 1; ring <= RINGS && foot == TreeJudge.NO_POS; ring++) {
            foot = footInRing(view, x, y, z, ring);
        }
        return foot == TreeJudge.NO_POS ? foot : squareCorner(view, foot);
    }

    /** The lowest foot in one column, from {@link #BELOW} under the sapling to {@link #ABOVE} over it. */
    private static long footInColumn(TreeView view, int x, int y, int z) {
        for (int fy = y - BELOW; fy <= y + ABOVE; fy++) {
            if (isFoot(view, x, fy, z) && hasLogAbove(view, x, fy, z)) return TreeJudge.pack(x, fy, z);
        }
        return TreeJudge.NO_POS;
    }

    /**
     * The foot in the nearest column of one ring (sides before corners), the
     * lower one on a tie, then west before east and north before south.
     */
    private static long footInRing(TreeView view, int x, int y, int z, int ring) {
        long best = TreeJudge.NO_POS;
        int bestDistance = Integer.MAX_VALUE;
        int bestY = Integer.MAX_VALUE;
        for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                long foot = footInColumn(view, x + dx, y, z + dz);
                if (foot == TreeJudge.NO_POS) continue;
                int distance = dx * dx + dz * dz;
                int fy = TreeJudge.y(foot);
                if (distance < bestDistance || (distance == bestDistance && fy < bestY)) {
                    best = foot;
                    bestDistance = distance;
                    bestY = fy;
                }
            }
        }
        return best;
    }

    /**
     * The low corner of the full 2x2 of feet this foot is part of, in the
     * order the tree judge reads a square, so a grow lists the same base the
     * village scan finds (its west, then north column). The foot itself when
     * it is not part of one.
     */
    private static long squareCorner(TreeView view, long foot) {
        int x = TreeJudge.x(foot);
        int y = TreeJudge.y(foot);
        int z = TreeJudge.z(foot);
        int family = view.at(x, y, z).family();
        for (int ox = -1; ox <= 0; ox++) {
            for (int oz = -1; oz <= 0; oz++) {
                if (isSquare(view, x + ox, y, z + oz, family)) return TreeJudge.pack(x + ox, y, z + oz);
            }
        }
        return foot;
    }

    private static boolean isSquare(TreeView view, int x, int y, int z, int family) {
        for (int i = 0; i <= 1; i++) {
            for (int j = 0; j <= 1; j++) {
                if (!isFoot(view, x + i, y, z + j) || view.at(x + i, y, z + j).family() != family) return false;
            }
        }
        return true;
    }

    /** A plain standing log of a tree's wood on natural ground: not leaves, not a log, not a build. */
    private static boolean isFoot(TreeView view, int x, int y, int z) {
        Cell cell = view.at(x, y, z);
        return cell.kind() == Kind.PLAIN_LOG && cell.upright() && cell.family() >= 0
                && view.at(x, y - 1, z).kind() == Kind.GROUND;
    }

    /** A log of the same wood straight or diagonally above: the tree goes up from it. */
    private static boolean hasLogAbove(TreeView view, int x, int y, int z) {
        int family = view.at(x, y, z).family();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Cell above = view.at(x + dx, y + 1, z + dz);
                if (above.kind() == Kind.PLAIN_LOG && above.family() == family) return true;
            }
        }
        return false;
    }
}

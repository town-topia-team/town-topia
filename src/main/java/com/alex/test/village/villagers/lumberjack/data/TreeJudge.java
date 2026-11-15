package com.alex.test.village.villagers.lumberjack.data;

import com.alex.test.village.villagers.lumberjack.data.TreeView.Cell;
import com.alex.test.village.villagers.lumberjack.data.TreeView.Kind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * The one place that decides whether a group of logs is a tree, and which
 * logs and leaves are its own. Every caller (village scan, lumberjack, new
 * saplings, chop cleanup, {@code /villager treecheck}) goes through
 * {@link TreeStructure#judge}, which asks this class, so they cannot disagree.
 *
 * <p>No Minecraft in here: it reads blocks through {@link TreeView} and
 * passes positions as longs packed exactly like {@code BlockPos.asLong}, so it
 * runs in plain unit tests.
 *
 * <p>The rules come from Minecraft 1.21.1's own tree code: every grown tree
 * stands on dirt or mangrove roots (the trunk placers turn the block under the
 * trunk into dirt), no tree grows stripped logs or bark blocks, and every tree
 * top has grown leaves round it. {@link Rules#old()} switches the new rules off
 * and judges exactly as the mod did before 2026-10-19.
 *
 * <p>With {@link Rules#split()} a log group is shared out instead of judged
 * whole: every trunk of it, every build in it and the unknown past its edge
 * claim the logs nearest them, and the tree is what its own trunk claimed.
 */
public final class TreeJudge {

    /** A full 31-tall mega jungle is 124 trunk logs before its branches. */
    public static final int MAX_LOGS = 256;
    /**
     * No single vanilla tree has this many leaves (a tall mangrove 845 at
     * most); two or three tall mangroves grown together can.
     */
    public static final int MAX_LEAVES = 1024;
    static final int MAX_HORIZONTAL_RADIUS = 12;
    /**
     * How far above the base the tree may reach. Mega jungles grow to 31 and
     * mega spruces to 29; this used to be 24, and the flood fill quietly
     * stopped there -- the lumberjack felled the bottom 25 layers and left
     * the crown floating, where nothing would ever find it again.
     */
    static final int MAX_HEIGHT_ABOVE_BASE = 40;
    /** The base is the lowest trunk log on the ground; only roots and slopes dip below it. */
    static final int MAX_DEPTH_BELOW_BASE = 4;
    static final int LEAF_RADIUS = 2;
    /**
     * Of the five sides of a tree's top (up, north, south, east, west), how
     * many need grown leaves of its kind. Every vanilla top has four or five;
     * three leaves room for the random canopies of mangroves and azaleas. A
     * house pillar at the edge of a canopy has one or two.
     */
    static final int CROWN_SIDES_NEEDED = 3;
    /** Tops looked at, highest first, before the tree is called crownless. */
    private static final int MAX_TOPS_CHECKED = 32;
    /** How many logs high a pile of sideways logs can be and still count as lying on the ground. */
    private static final int MAX_PILE_HEIGHT = 4;
    /** How far below a base standing on nothing the real trunk foot is looked for. */
    private static final int FOOT_SEARCH_DEPTH = 40;
    private static final int FOOT_SEARCH_LOGS = 512;

    /**
     * Split: the whole log group is filled this far past the tree's own box on
     * every side (16 sideways, 44 up, 8 down), so the trunks and builds just
     * outside it still get their share.
     */
    static final int FILL_MARGIN = 4;
    /** Split: at most this many logs filled; the rest is "unknown" past the edge. */
    static final int FILL_CAP = 1024;
    /** Split: other trunks judged, nearest first; the ones past that count as unknown. */
    private static final int MAX_OTHER_TRUNKS_JUDGED = 24;
    /**
     * Split: a build touching the tree stands on its own when one of its logs
     * rests on ground (or a built block) no higher than this above the tree's
     * base. One that doesn't rests on the tree: a treehouse, a lantern hung on
     * a branch, a beam lying on a roof.
     */
    static final int BUILD_GROUND_RISE = 3;
    /**
     * Split: a lone standing log on the ground leaning on a lower log of the
     * group is a branch when fewer than this many logs go straight up from it.
     * A mangrove's low branch stands on its own roots; every vanilla trunk is
     * at least 4 logs straight before it leans or bends.
     */
    static final int MIN_STEM = 4;

    /** Split: who claimed a log. Trunks are their index, 0 and up. */
    private static final int BUILD = -2;
    private static final int UNKNOWN = -1;

    /** Lowest first, then by x, then by z: the same order whichever log the judge starts from. */
    private static final Comparator<Long> FIXED_ORDER = Comparator.comparingInt(TreeJudge::y)
            .thenComparingInt(TreeJudge::x)
            .thenComparingInt(TreeJudge::z);

    public static final long NO_POS = Long.MIN_VALUE;

    /** Directions a trunk may lean by as it goes up one block: sides first, then corners. */
    private static final int[][] LEAN_STEPS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int[][] SIDES = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}};

    private TreeJudge() {}

    /**
     * Which rules are on. Each one is a {@code /villager walkfeel} switch.
     *
     * @param plainLogs  stripped logs and bark blocks are never part of a tree ({@code treeplain})
     * @param logPiles   sideways logs stacked on a ground-lying log lie too ({@code treepiles})
     * @param buildMarks a log with a built block, stripped log or placed leaves right above or
     *                   below it belongs to a build ({@code treemarks})
     * @param crown      the trunk stands on natural ground, carries nothing built on top, and a
     *                   top has its own crown of leaves ({@code treecrown})
     * @param split      one tree per trunk: the trunks, builds and unknown edge of a log group
     *                   share it out, and a build resting on the tree keeps it whole
     *                   ({@code treesplit}). Off: the whole group is one tree, and a build mark
     *                   anywhere in it leaves all of it standing
     * @param twoByTwo   a big 2x2 trunk is the square of feet at the base's own layer, its low
     *                   corner the replant spot ({@code tree2x2}). Off: only at the group's lowest
     *                   layer, so a neighbour lower on a slope hid it
     * @param soil       with the crown rule, every foot of the trunk stands on soil
     *                   ({@link TreeView#soil}), and with the split another stem that does not is a
     *                   build's post ({@code treesoil}). Off: any natural ground holds a tree
     * @param ownLeaves  a leaf falls with the tree only when its logs are nearer than any other
     *                   log ({@code treeleaves}). Off: every leaf of its kind within two blocks
     * @param leafCap    more than {@link #MAX_LEAVES} falling leaves: the nearest ones fall, the
     *                   tree is still a tree ({@code leafcap}). Off: {@code TreeStructure} refuses it
     */
    public record Rules(boolean plainLogs, boolean logPiles, boolean buildMarks, boolean crown,
                        boolean split, boolean twoByTwo, boolean soil, boolean ownLeaves, boolean leafCap) {
        public static Rules old() {
            return new Rules(false, false, false, false, false, false, false, false, false);
        }

        public static Rules all() {
            return new Rules(true, true, true, true, true, true, true, true, true);
        }
    }

    public enum Verdict { TREE, NOT_TREE, CANT_SEE }

    public enum Reason {
        OK,
        /** The base is not a standing log of a wood any tree grows (or, with plain logs on, it is stripped). */
        NOT_A_LOG,
        /** A log in a building's shell, on a tended cocoa farm, or outside the village. */
        KEEP_OUT,
        TOO_SMALL,
        TOO_BIG,
        /** The trunk carries on past the top of the search: felling it would strand the crown. */
        TOO_TALL,
        NO_LEAVES,
        TOO_MANY_LEAVES,
        /** The trunk stands on a built block (cobblestone, planks, a slab...). */
        BUILT_GROUND,
        /** A foot of the trunk stands on natural ground that is not soil: stone, sand, ore, a metal block... */
        NOT_SOIL,
        /** A built block, stripped log or placed leaves right above or below one of the logs. */
        BUILD_MARK,
        /** The base stands on nothing solid: leaves, air, another log. */
        NO_GROUND,
        /** Nothing goes up from the base. */
        NO_TRUNK,
        /** Something built, or a log of another wood, sits right on top of the trunk. */
        BUILT_ON_TOP,
        /** No top of it has its own leaves round it. */
        NO_CROWN,
        /** Split: a short stem leaning on a lower log of the same wood, a branch of that trunk. */
        BRANCH,
        /** Split: a build joined to the tree has no ground of its own: felling the tree would drop it. */
        BUILD_RESTS_ON_TREE,
        UNLOADED
    }

    /**
     * @param where          the block the reason is about, or {@link #NO_POS}
     * @param logs           the tree's logs (for a refusal, whatever was gathered before it)
     * @param leaves         the grown leaves that fall with it, nearest its logs first: with
     *                       {@link Rules#ownLeaves()} only its own, with {@link Rules#leafCap()} at
     *                       most {@link #MAX_LEAVES}; else all of {@code nearLeaves}
     * @param nearLeaves     grown leaves of its kind within two blocks of its logs, its own or not
     * @param crownSides     sides of the best top that had leaves, -1 when the crown was not judged
     * @param crownTop       that top, or {@link #NO_POS}
     * @param suggestedFoot  when the base stands on nothing or is a branch, the foot of the trunk
     *                       it belongs to
     * @param twoByTwoAnchor the low corner of the tree's 2x2 trunk, or {@link #NO_POS}; whether
     *                       its kind can grow one is the caller's to say
     * @param groupLogs      logs in the whole joined group, the tree's own and everybody else's
     */
    public record Judgement(Verdict verdict, Reason reason, long where, Set<Long> logs, Set<Long> leaves,
                            Set<Long> nearLeaves, int crownSides, long crownTop, long suggestedFoot,
                            long twoByTwoAnchor, int groupLogs) {
        public boolean isTree() {
            return verdict == Verdict.TREE;
        }
    }

    public static Judgement judge(TreeView view, long base, Rules rules) {
        int bx = x(base);
        int by = y(base);
        int bz = z(base);
        Cell baseCell = view.at(bx, by, bz);
        if (baseCell.kind() == Kind.UNLOADED) return cantSee(base, Set.of());
        if (!isFamilyLog(baseCell, baseCell.family(), rules)
                || !baseCell.upright()
                || baseCell.family() < 0) {
            return no(Reason.NOT_A_LOG, base, Set.of());
        }
        if (view.keepOut(bx, by, bz)) return no(Reason.KEEP_OUT, base, Set.of());
        int family = baseCell.family();
        if (rules.split()) return judgeSplit(view, base, family, rules);

        Gathered gathered = gatherLogs(view, base, family, rules);
        if (gathered.refusal != null) return gathered.refusal;
        Set<Long> logs = gathered.logs;
        if (logs.size() < 2) return no(Reason.TOO_SMALL, base, logs);

        if (rules.buildMarks()) {
            Judgement marked = findBuildMark(view, Set.of(base), logs, logs);
            if (marked != null) return marked;
        }

        int crownSides = -1;
        long crownTop = NO_POS;
        if (rules.crown()) {
            Trunk trunk = checkTrunk(view, base, family, logs, rules);
            if (trunk.refusal != null) return trunk.refusal;
            crownSides = trunk.crownSides;
            crownTop = trunk.crownTop;
        }
        return withLeaves(view, base, family, logs, logs.size(), crownSides, crownTop,
                twoByTwoAnchor(view, logs, base, rules), rules);
    }

    /**
     * The tree's grown leaves of its kind within two blocks of its logs, the
     * ones of them that fall with it, and the verdict. Leaves shared with
     * another tree still make it a tree: one in a thick forest has its crown
     * in the others'.
     */
    private static Judgement withLeaves(TreeView view, long base, int family, Set<Long> logs, int groupLogs,
                                        int crownSides, long crownTop, long twoByTwoAnchor, Rules rules) {
        int bx = x(base);
        int by = y(base);
        int bz = z(base);
        Set<Long> leaves = new LinkedHashSet<>();
        for (long log : logs) {
            int lx = x(log);
            int ly = y(log);
            int lz = z(log);
            for (int dx = -LEAF_RADIUS; dx <= LEAF_RADIUS; dx++) {
                for (int dy = -LEAF_RADIUS; dy <= LEAF_RADIUS; dy++) {
                    for (int dz = -LEAF_RADIUS; dz <= LEAF_RADIUS; dz++) {
                        int cx = lx + dx;
                        int cy = ly + dy;
                        int cz = lz + dz;
                        if (!withinBounds(bx, by, bz, cx, cy, cz) || view.keepOut(cx, cy, cz)) continue;
                        Cell cell = view.at(cx, cy, cz);
                        if (cell.kind() == Kind.UNLOADED) return cantSee(pack(cx, cy, cz), logs);
                        if (isFittingLeaf(view, cell, family)) leaves.add(pack(cx, cy, cz));
                    }
                }
            }
        }
        if (leaves.isEmpty()) return no(Reason.NO_LEAVES, base, logs);
        return new Judgement(Verdict.TREE, Reason.OK, NO_POS, logs, fallingLeaves(view, logs, leaves, rules),
                leaves, crownSides, crownTop, NO_POS, twoByTwoAnchor, groupLogs);
    }

    /**
     * The leaves of {@code near} that fall with the tree, nearest first: the
     * ones touching its logs, then the ones two out. With own leaves on, a
     * leaf stays when another log (any wood, stripped too) is as near as the
     * tree's own or nearer: it is the neighbour's, or nobody's. A log in a
     * building's shell does not count, so leaves against a town building
     * still go. With the leaf cap, at most {@link #MAX_LEAVES}.
     */
    private static Set<Long> fallingLeaves(TreeView view, Set<Long> logs, Set<Long> near, Rules rules) {
        LeafGrid grid = new LeafGrid(near, logs);
        List<Long> touching = new ArrayList<>();
        List<Long> further = new ArrayList<>();
        for (long leaf : near) {
            int own = grid.ownLogWithin(leaf, 1) ? 1 : LEAF_RADIUS;
            if (rules.ownLeaves() && grid.otherLogWithin(view, leaf, own)) continue;
            (own == 1 ? touching : further).add(leaf);
        }
        Set<Long> falling = new LinkedHashSet<>();
        for (List<Long> ring : List.of(touching, further)) {
            for (long leaf : ring) {
                if (rules.leafCap() && falling.size() >= MAX_LEAVES) return falling;
                falling.add(leaf);
            }
        }
        return falling;
    }

    /**
     * The blocks round a tree's near leaves, as far out as a log could be
     * that is as near a leaf as the tree's own: each read from the world at
     * most once, however many leaves look at it.
     */
    private static final class LeafGrid {
        private static final byte UNREAD = 0;
        private static final byte OWN_LOG = 1;
        private static final byte OTHER_LOG = 2;
        private static final byte NO_LOG = 3;

        private final int x0;
        private final int y0;
        private final int z0;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final byte[] cells;

        LeafGrid(Set<Long> leaves, Set<Long> logs) {
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (long leaf : leaves) {
                minX = Math.min(minX, x(leaf));
                minY = Math.min(minY, y(leaf));
                minZ = Math.min(minZ, z(leaf));
                maxX = Math.max(maxX, x(leaf));
                maxY = Math.max(maxY, y(leaf));
                maxZ = Math.max(maxZ, z(leaf));
            }
            x0 = minX - LEAF_RADIUS;
            y0 = minY - LEAF_RADIUS;
            z0 = minZ - LEAF_RADIUS;
            sizeX = maxX - minX + 1 + 2 * LEAF_RADIUS;
            sizeY = maxY - minY + 1 + 2 * LEAF_RADIUS;
            sizeZ = maxZ - minZ + 1 + 2 * LEAF_RADIUS;
            cells = new byte[sizeX * sizeY * sizeZ];
            for (long leaf : leaves) cells[index(x(leaf), y(leaf), z(leaf))] = NO_LOG;
            for (long log : logs) {
                int i = index(x(log), y(log), z(log));
                if (i >= 0) cells[i] = OWN_LOG;
            }
        }

        boolean ownLogWithin(long leaf, int radius) {
            return anyWithin(null, leaf, radius, OWN_LOG);
        }

        boolean otherLogWithin(TreeView view, long leaf, int radius) {
            return anyWithin(view, leaf, radius, OTHER_LOG);
        }

        /** Whether a block within {@code radius} of the leaf is {@code wanted}; reads unread ones when given a view. */
        private boolean anyWithin(TreeView view, long leaf, int radius, byte wanted) {
            int lx = x(leaf);
            int ly = y(leaf);
            int lz = z(leaf);
            for (int x = lx - radius; x <= lx + radius; x++) {
                for (int y = ly - radius; y <= ly + radius; y++) {
                    for (int z = lz - radius; z <= lz + radius; z++) {
                        int i = index(x, y, z);
                        byte cell = cells[i];
                        if (cell == UNREAD && view != null) cell = cells[i] = read(view, x, y, z);
                        if (cell == wanted) return true;
                    }
                }
            }
            return false;
        }

        /** An unloaded block counts as no log: the lumberjack fells with the chunks round it loaded. */
        private static byte read(TreeView view, int x, int y, int z) {
            if (!view.at(x, y, z).isLog() || view.keepOut(x, y, z)) return NO_LOG;
            return OTHER_LOG;
        }

        /** Within the grid for every block at most {@link #LEAF_RADIUS} from a leaf; -1 outside it. */
        private int index(int x, int y, int z) {
            int ix = x - x0;
            int iy = y - y0;
            int iz = z - z0;
            if (ix < 0 || iy < 0 || iz < 0 || ix >= sizeX || iy >= sizeY || iz >= sizeZ) return -1;
            return (ix * sizeY + iy) * sizeZ + iz;
        }
    }

    // -------------------------------------------------------------------------
    // Gathering the logs
    // -------------------------------------------------------------------------

    private record Gathered(Set<Long> logs, Judgement refusal) {}

    /** Every log of the base's wood joined to it (corners count), as the mod always gathered them. */
    private static Gathered gatherLogs(TreeView view, long base, int family, Rules rules) {
        int bx = x(base);
        int by = y(base);
        int bz = z(base);
        Set<Long> logs = new LinkedHashSet<>();
        Queue<Long> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(base);
        visited.add(base);

        while (!queue.isEmpty()) {
            long current = queue.poll();
            int x = x(current);
            int y = y(current);
            int z = z(current);
            if (!withinBounds(bx, by, bz, x, y, z)) continue;
            Cell cell = view.at(x, y, z);
            if (cell.kind() == Kind.UNLOADED) return new Gathered(logs, cantSee(current, logs));
            if (!isFamilyLog(cell, family, rules)) continue;
            if (view.keepOut(x, y, z)) return new Gathered(logs, no(Reason.KEEP_OUT, current, logs));
            logs.add(current);
            if (logs.size() > MAX_LOGS) return new Gathered(logs, no(Reason.TOO_BIG, current, logs));

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        int nx = x + dx;
                        int ny = y + dy;
                        int nz = z + dz;
                        long next = pack(nx, ny, nz);
                        if (!visited.add(next)) continue;
                        boolean inBounds = withinBounds(bx, by, bz, nx, ny, nz);
                        // Sideways or below the bounds the fill just stops.
                        if (!inBounds && ny - by <= MAX_HEIGHT_ABOVE_BASE) continue;
                        Cell neighbour = view.at(nx, ny, nz);
                        if (neighbour.kind() == Kind.UNLOADED) return new Gathered(logs, cantSee(next, logs));
                        if (isFamilyLog(neighbour, family, rules)
                                && !isLyingOnGround(view, nx, ny, nz, neighbour, rules, 0)) {
                            // Trunk carries on past the top: too tall to fell
                            // whole, so leave it standing rather than cut its
                            // bottom off and strand the crown.
                            if (!inBounds) return new Gathered(logs, no(Reason.TOO_TALL, next, logs));
                            if (view.keepOut(nx, ny, nz)) {
                                return new Gathered(logs, no(Reason.KEEP_OUT, next, logs));
                            }
                            queue.add(next);
                        }
                    }
                }
            }
        }
        return new Gathered(logs, null);
    }

    private static boolean isFamilyLog(Cell cell, int family, Rules rules) {
        boolean log = rules.plainLogs() ? cell.kind() == Kind.PLAIN_LOG : cell.isLog();
        return log && cell.family() == family;
    }

    /**
     * A log on its side resting on the ground: a farm border, a fence rail, a
     * bench. Natural branches grow out in the air above the trunk's base, so
     * skipping these never cuts a real tree short -- it stops a tree standing
     * next to a build from swallowing the build through a corner. With log
     * piles on, a sideways log resting on such a log lies too (a log pile).
     */
    private static boolean isLyingOnGround(TreeView view, int x, int y, int z, Cell cell, Rules rules, int depth) {
        if (cell.upright()) return false;
        Cell below = view.at(x, y - 1, z);
        if (below.solid() && !below.isLog() && !below.isLeaf()) return true;
        return rules.logPiles()
                && depth < MAX_PILE_HEIGHT
                && below.isLog()
                && !below.upright()
                && isLyingOnGround(view, x, y - 1, z, below, rules, depth + 1);
    }

    // -------------------------------------------------------------------------
    // Build marks
    // -------------------------------------------------------------------------

    /**
     * The first of {@code checked} with something only builds have right above
     * or below it, as a refusal ({@code BUILT_GROUND} under one of the feet).
     */
    private static Judgement findBuildMark(TreeView view, Set<Long> feet, Set<Long> checked, Set<Long> logs) {
        for (long log : checked) {
            int x = x(log);
            int y = y(log);
            int z = z(log);
            for (int dy = -1; dy <= 1; dy += 2) {
                Cell cell = view.at(x, y + dy, z);
                if (!isBuildMark(cell)) continue;
                Reason reason = feet.contains(log) && dy < 0 ? Reason.BUILT_GROUND : Reason.BUILD_MARK;
                return no(reason, pack(x, y + dy, z), logs);
            }
        }
        return null;
    }

    /** A log a build owns: protected, or (build marks on) a built block, stripped log or placed leaves on or under it. */
    private static boolean isMarkLog(TreeView view, long log, Rules rules) {
        int x = x(log);
        int y = y(log);
        int z = z(log);
        if (view.keepOut(x, y, z)) return true;
        return rules.buildMarks() && (isBuildMark(view.at(x, y + 1, z)) || isBuildMark(view.at(x, y - 1, z)));
    }

    private static boolean isBuildMark(Cell cell) {
        return cell.kind() == Kind.BUILT
                || cell.kind() == Kind.BARK_OR_STRIPPED
                || cell.kind() == Kind.PLACED_LEAF;
    }

    // -------------------------------------------------------------------------
    // Trunk and crown
    // -------------------------------------------------------------------------

    private record Trunk(Judgement refusal, int crownSides, long crownTop) {}

    /**
     * The base must be the foot of a real trunk: on natural ground, going up,
     * with nothing built sitting on top of it, and with a top crowned by its
     * own leaves.
     */
    private static Trunk checkTrunk(TreeView view, long base, int family, Set<Long> logs, Rules rules) {
        int bx = x(base);
        int by = y(base);
        int bz = z(base);
        Cell ground = view.at(bx, by - 1, bz);
        if (ground.kind() == Kind.UNLOADED) return refused(cantSee(base, logs));
        if (ground.kind() == Kind.BUILT) {
            return refused(no(Reason.BUILT_GROUND, pack(bx, by - 1, bz), logs));
        }
        if (ground.kind() != Kind.GROUND) {
            long foot = findFoot(view, base, family, rules);
            return refused(new Judgement(Verdict.NOT_TREE, Reason.NO_GROUND, pack(bx, by - 1, bz),
                    logs, Set.of(), Set.of(), -1, NO_POS, foot, NO_POS, logs.size()));
        }
        if (!hasLogAbove(logs, bx, by, bz)) return refused(no(Reason.NO_TRUNK, base, logs));

        List<Long> footprint = rules.twoByTwo() ? tileContaining(view, logs, base) : footprint(view, logs, bx, by, bz);
        for (long foot : footprint) {
            if (!onSoil(view, foot, rules)) return refused(no(Reason.NOT_SOIL, below(foot), logs));
        }
        for (long foot : footprint) {
            long onTop = whatSitsOnTop(view, logs, family, foot, rules.split());
            if (onTop == NO_POS) continue;
            if (view.at(x(onTop), y(onTop), z(onTop)).kind() == Kind.UNLOADED) {
                return refused(cantSee(onTop, logs));
            }
            return refused(no(Reason.BUILT_ON_TOP, onTop, logs));
        }

        Crown crown = bestCrown(view, logs, family, footprint);
        if (crown.unseen) return refused(cantSee(crown.top, logs));
        if (crown.sides < CROWN_SIDES_NEEDED) {
            return refused(new Judgement(Verdict.NOT_TREE, Reason.NO_CROWN, crown.top, logs, Set.of(), Set.of(),
                    crown.sides, crown.top, NO_POS, NO_POS, logs.size()));
        }
        return new Trunk(null, crown.sides, crown.top);
    }

    private static Trunk refused(Judgement judgement) {
        return new Trunk(judgement, -1, NO_POS);
    }

    /** The foot stands on soil, or the soil rule is off. Only asked of a foot already on natural ground. */
    private static boolean onSoil(TreeView view, long foot, Rules rules) {
        return !rules.soil() || view.soil(x(foot), y(foot) - 1, z(foot));
    }

    private static long below(long pos) {
        return pack(x(pos), y(pos) - 1, z(pos));
    }

    private static boolean hasLogAbove(Set<Long> logs, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (logs.contains(pack(x + dx, y + 1, z + dz))) return true;
            }
        }
        return false;
    }

    /** The base alone, or the four feet of the full 2x2 trunk it is a corner of. */
    private static List<Long> footprint(TreeView view, Set<Long> logs, int bx, int by, int bz) {
        for (int ox = -1; ox <= 0; ox++) {
            for (int oz = -1; oz <= 0; oz++) {
                List<Long> square = new ArrayList<>(4);
                for (int i = 0; i <= 1 && square.size() == i * 2; i++) {
                    for (int j = 0; j <= 1; j++) {
                        int x = bx + ox + i;
                        int z = bz + oz + j;
                        if (isFoot(view, logs, x, by, z)) square.add(pack(x, by, z));
                    }
                }
                if (square.size() == 4) return square;
            }
        }
        return List.of(pack(bx, by, bz));
    }

    private static boolean isFoot(TreeView view, Set<Long> logs, int x, int y, int z) {
        return logs.contains(pack(x, y, z))
                && view.at(x, y, z).upright()
                && view.at(x, y - 1, z).kind() == Kind.GROUND;
    }

    // -------------------------------------------------------------------------
    // Big 2x2 trunks
    // -------------------------------------------------------------------------

    /**
     * The low corner of the tree's 2x2 trunk, or {@link #NO_POS}. With
     * {@code tree2x2} on, first the square of feet at the base's own layer
     * that holds the base; then (and with it off, only) a full square in the
     * lowest layer of its logs, as the mod always looked.
     */
    static long twoByTwoAnchor(TreeView view, Set<Long> logs, long base, Rules rules) {
        if (rules.twoByTwo()) {
            List<Long> tile = tileContaining(view, logs, base);
            if (tile.size() == 4) return tile.get(0);
        }
        return lowestSquare(logs);
    }

    private static long lowestSquare(Set<Long> logs) {
        int minY = Integer.MAX_VALUE;
        for (long log : logs) minY = Math.min(minY, y(log));
        Set<Long> bottom = new LinkedHashSet<>();
        for (long log : logs) {
            if (y(log) == minY) bottom.add(log);
        }
        for (long log : bottom) {
            for (int dx = -1; dx <= 0; dx++) {
                for (int dz = -1; dz <= 0; dz++) {
                    int ax = x(log) + dx;
                    int az = z(log) + dz;
                    if (bottom.contains(pack(ax, minY, az)) && bottom.contains(pack(ax + 1, minY, az))
                            && bottom.contains(pack(ax, minY, az + 1))
                            && bottom.contains(pack(ax + 1, minY, az + 1))) {
                        return pack(ax, minY, az);
                    }
                }
            }
        }
        return NO_POS;
    }

    /**
     * The base's trunk among the feet of its layer: its 2x2 (low corner
     * first) or the base alone. Feet are shared into squares lowest corner
     * first, so three feet in a row and a square beside them come out the
     * same whichever log is asked about.
     */
    private static List<Long> tileContaining(TreeView view, Set<Long> logs, long base) {
        List<Long> feet = new ArrayList<>();
        for (long log : logs) {
            if (y(log) == y(base) && isFoot(view, logs, x(log), y(log), z(log))
                    && hasLogAbove(logs, x(log), y(log), z(log))) {
                feet.add(log);
            }
        }
        for (List<Long> tile : tiles(feet)) {
            if (tile.contains(base)) return tile;
        }
        return List.of(base);
    }

    /** Feet of one layer shared into 2x2 squares (low corner first) and singles, in {@link #FIXED_ORDER}. */
    private static List<List<Long>> tiles(List<Long> feet) {
        List<Long> sorted = new ArrayList<>(feet);
        sorted.sort(FIXED_ORDER);
        Set<Long> free = new HashSet<>(sorted);
        List<List<Long>> tiles = new ArrayList<>();
        for (long foot : sorted) {
            if (!free.remove(foot)) continue;
            int x = x(foot);
            int y = y(foot);
            int z = z(foot);
            long east = pack(x + 1, y, z);
            long south = pack(x, y, z + 1);
            long corner = pack(x + 1, y, z + 1);
            if (free.contains(east) && free.contains(south) && free.contains(corner)) {
                free.remove(east);
                free.remove(south);
                free.remove(corner);
                tiles.add(List.of(foot, east, south, corner));
            } else {
                tiles.add(List.of(foot));
            }
        }
        return tiles;
    }

    /**
     * Climbs the trunk from one foot -- straight up, or one block aside where it
     * leans -- and returns the first block above it that only a build would
     * put there: a built block, stripped log, placed leaves or a log of another
     * wood. {@link #NO_POS} when there is none.
     *
     * <p>{@code ownColumn} (split): never leans onto a log standing on another
     * log, so a trunk touching a post or a second trunk does not climb on up
     * theirs. A real lean (acacia, dark oak, azalea) steps out over air.
     */
    private static long whatSitsOnTop(TreeView view, Set<Long> logs, int family, long foot, boolean ownColumn) {
        int x = x(foot);
        int y = y(foot);
        int z = z(foot);
        for (int step = 0; step <= MAX_HEIGHT_ABOVE_BASE + MAX_DEPTH_BELOW_BASE; step++) {
            if (logs.contains(pack(x, y + 1, z))) {
                y++;
                continue;
            }
            Cell above = view.at(x, y + 1, z);
            if (above.kind() == Kind.UNLOADED || sitsOnTop(above, family)) return pack(x, y + 1, z);
            boolean leaned = false;
            for (int[] lean : LEAN_STEPS) {
                if (logs.contains(pack(x + lean[0], y + 1, z + lean[1]))
                        && !(ownColumn && logs.contains(pack(x + lean[0], y, z + lean[1])))) {
                    x += lean[0];
                    z += lean[1];
                    y++;
                    leaned = true;
                    break;
                }
            }
            if (!leaned) return NO_POS;
        }
        return NO_POS;
    }

    private static boolean sitsOnTop(Cell cell, int family) {
        return isBuildMark(cell) || (cell.isLog() && cell.family() != family);
    }

    private record Crown(int sides, long top, boolean unseen) {}

    /**
     * The best-crowned top the trunk reaches by never going down. A top is a
     * log with no log of the tree above it, straight or aside; a 2x2 trunk's
     * top is judged as one square.
     */
    private static Crown bestCrown(TreeView view, Set<Long> logs, int family, List<Long> footprint) {
        Set<Long> reach = new LinkedHashSet<>(footprint);
        Queue<Long> queue = new ArrayDeque<>(footprint);
        while (!queue.isEmpty()) {
            long current = queue.poll();
            int x = x(current);
            int y = y(current);
            int z = z(current);
            for (int dy = 0; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        long next = pack(x + dx, y + dy, z + dz);
                        if (logs.contains(next) && reach.add(next)) queue.add(next);
                    }
                }
            }
        }

        List<Long> tops = new ArrayList<>();
        for (long log : reach) {
            if (!hasLogAbove(reach, x(log), y(log), z(log))) tops.add(log);
        }
        tops.sort(Comparator.comparingInt(TreeJudge::y).reversed());
        Set<Long> topSet = new HashSet<>(tops);
        Set<Long> judged = new HashSet<>();
        CrownReader reader = new CrownReader(view, family);

        int bestSides = -1;
        long bestTop = tops.isEmpty() ? NO_POS : tops.get(0);
        int checked = 0;
        for (long top : tops) {
            if (checked >= MAX_TOPS_CHECKED) break;
            if (judged.contains(top)) continue;
            int sides;
            long anchor = topSquare(topSet, top);
            if (anchor != NO_POS) {
                int ax = x(anchor);
                int az = z(anchor);
                for (int i = 0; i <= 1; i++) {
                    for (int j = 0; j <= 1; j++) judged.add(pack(ax + i, y(top), az + j));
                }
                sides = reader.squareSides(ax, y(top), az);
            } else {
                judged.add(top);
                sides = reader.sides(x(top), y(top), z(top));
            }
            checked++;
            if (sides > bestSides) {
                bestSides = sides;
                bestTop = top;
            }
            if (sides >= CROWN_SIDES_NEEDED) break;
        }
        return new Crown(bestSides, bestTop, bestSides < CROWN_SIDES_NEEDED && reader.sawUnloaded);
    }

    /** The low corner of a full 2x2 of tops at this top's height, or {@link #NO_POS}. */
    private static long topSquare(Set<Long> tops, long top) {
        int x = x(top);
        int y = y(top);
        int z = z(top);
        for (int ox = -1; ox <= 0; ox++) {
            for (int oz = -1; oz <= 0; oz++) {
                int ax = x + ox;
                int az = z + oz;
                if (tops.contains(pack(ax, y, az)) && tops.contains(pack(ax + 1, y, az))
                        && tops.contains(pack(ax, y, az + 1)) && tops.contains(pack(ax + 1, y, az + 1))) {
                    return pack(ax, y, az);
                }
            }
        }
        return NO_POS;
    }

    /**
     * Sides of a one-log top with grown leaves of the tree's kind: up is the
     * 3x3 one and two blocks above; each side is the line one and two blocks
     * out, from the row below to the row above.
     */
    static int crownSides(TreeView view, int family, int x, int y, int z) {
        return new CrownReader(view, family).sides(x, y, z);
    }

    /** {@link #crownSides} for the top of a 2x2 trunk whose low corner is (ax, az). */
    static int squareCrownSides(TreeView view, int family, int ax, int y, int az) {
        return new CrownReader(view, family).squareSides(ax, y, az);
    }

    /** Counts crown sides, remembering whether any block it needed was in an unloaded chunk. */
    private static final class CrownReader {
        private final TreeView view;
        private final int family;
        private boolean sawUnloaded;

        private CrownReader(TreeView view, int family) {
            this.view = view;
            this.family = family;
        }

        int sides(int x, int y, int z) {
            int sides = anyLeaf(x - 1, x + 1, y + 1, y + 2, z - 1, z + 1) ? 1 : 0;
            for (int[] side : SIDES) {
                boolean found = false;
                for (int k = 1; k <= 2 && !found; k++) {
                    int sx = x + side[0] * k;
                    int sz = z + side[1] * k;
                    found = anyLeaf(sx, sx, y - 1, y + 1, sz, sz);
                }
                if (found) sides++;
            }
            return sides;
        }

        int squareSides(int ax, int y, int az) {
            int sides = anyLeaf(ax - 1, ax + 2, y + 1, y + 2, az - 1, az + 2) ? 1 : 0;
            if (anyLeaf(ax - 2, ax - 1, y - 1, y + 1, az, az + 1)) sides++;
            if (anyLeaf(ax + 2, ax + 3, y - 1, y + 1, az, az + 1)) sides++;
            if (anyLeaf(ax, ax + 1, y - 1, y + 1, az - 2, az - 1)) sides++;
            if (anyLeaf(ax, ax + 1, y - 1, y + 1, az + 2, az + 3)) sides++;
            return sides;
        }

        private boolean anyLeaf(int x0, int x1, int y0, int y1, int z0, int z1) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        Cell cell = view.at(x, y, z);
                        if (cell.kind() == Kind.UNLOADED) sawUnloaded = true;
                        if (isFittingLeaf(view, cell, family)) return true;
                    }
                }
            }
            return false;
        }
    }

    private static boolean isFittingLeaf(TreeView view, Cell cell, int family) {
        return cell.kind() == Kind.NATURAL_LEAF && view.leafFits(family, cell.family());
    }

    /**
     * The foot of the trunk a log standing on nothing belongs to: the lowest
     * standing log of the same wood joined to it that is on natural ground
     * with the tree going up from it, nearest first. A fancy oak's branch over
     * its own leaves used to be taken for the tree's base.
     */
    private static long findFoot(TreeView view, long from, int family, Rules rules) {
        int fx = x(from);
        int fy = y(from);
        int fz = z(from);
        Set<Long> visited = new HashSet<>();
        Queue<Long> queue = new ArrayDeque<>();
        queue.add(from);
        visited.add(from);
        long best = NO_POS;
        int bestY = Integer.MAX_VALUE;
        int bestDistance = Integer.MAX_VALUE;
        int seen = 0;
        while (!queue.isEmpty() && seen++ < FOOT_SEARCH_LOGS) {
            long current = queue.poll();
            int x = x(current);
            int y = y(current);
            int z = z(current);
            if (current != from && view.at(x, y, z).upright()
                    && view.at(x, y - 1, z).kind() == Kind.GROUND
                    && isFamilyLogAbove(view, x, y, z, family, rules)) {
                int distance = (x - fx) * (x - fx) + (z - fz) * (z - fz);
                if (y < bestY || (y == bestY && distance < bestDistance)) {
                    best = current;
                    bestY = y;
                    bestDistance = distance;
                }
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        int nz = z + dz;
                        if (Math.abs(nx - fx) > MAX_HORIZONTAL_RADIUS || Math.abs(nz - fz) > MAX_HORIZONTAL_RADIUS
                                || fy - ny > FOOT_SEARCH_DEPTH || ny - fy > MAX_HEIGHT_ABOVE_BASE) {
                            continue;
                        }
                        long next = pack(nx, ny, nz);
                        if (!visited.add(next)) continue;
                        Cell cell = view.at(nx, ny, nz);
                        if (cell.kind() == Kind.UNLOADED) return NO_POS;
                        if (isFamilyLog(cell, family, rules)
                                && !isLyingOnGround(view, nx, ny, nz, cell, rules, 0)) {
                            queue.add(next);
                        }
                    }
                }
            }
        }
        return best;
    }

    private static boolean isFamilyLogAbove(TreeView view, int x, int y, int z, int family, Rules rules) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (isFamilyLog(view.at(x + dx, y + 1, z + dz), family, rules)) return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // One tree per trunk (split)
    // -------------------------------------------------------------------------

    private record Fill(Set<Long> logs, Set<Long> edges, Judgement refusal) {}

    /** What a stem (or a 2x2 of them) of the group is to the split. */
    private enum Stem {
        /** A real trunk: it claims its share. */
        TRUNK,
        /** A post of a build (marked, protected, on a built block, something built on top): the build's. */
        BUILD,
        /** No crown of its own (a dead trunk, a bare post), or could not be judged: counts as the unknown. */
        UNKNOWN,
        /** A branch resting on the ground: claims nothing, its logs go to whoever is nearest. */
        NONE
    }

    /**
     * The whole group is filled; its builds (marks and posts), the unknown at
     * its edge and its real trunks claim the logs round them, step by step,
     * builds first, then the unknown, then the trunks in a fixed order, so a
     * log as near to a build as to a tree is the build's. The tree is what
     * its own trunk claimed, and must fit the old box. A build joined to it
     * must stand on its own ground, or the tree is left whole.
     */
    private static Judgement judgeSplit(TreeView view, long base, int family, Rules rules) {
        int by = y(base);
        Fill fill = fillGroup(view, base, family, rules);
        if (fill.refusal != null) return fill.refusal;
        Set<Long> group = fill.logs;
        if (group.size() < 2) return no(Reason.TOO_SMALL, base, group);

        List<List<Long>> trunks = trunks(view, group);
        List<Long> ours = null;
        for (List<Long> trunk : trunks) {
            if (trunk.contains(base)) {
                ours = trunk;
                break;
            }
        }
        if (ours == null) {
            // On no ground of its own, or nothing above it: its own column (the crown rule refuses it below).
            ours = List.of(base);
            trunks.add(ours);
            trunks.sort(Comparator.comparing((List<Long> trunk) -> trunk.get(0), FIXED_ORDER));
        }

        // Our own trunk must be a real one.
        Set<Long> ourColumns = columns(group, ours);
        for (long log : ourColumns) {
            if (view.keepOut(x(log), y(log), z(log))) return no(Reason.KEEP_OUT, log, group);
        }
        if (rules.buildMarks()) {
            Judgement marked = findBuildMark(view, new HashSet<>(ours), ourColumns, group);
            if (marked != null) return marked;
        }
        if (isBranch(view, group, ours)) {
            return new Judgement(Verdict.NOT_TREE, Reason.BRANCH, lowerNeighbour(group, base), group, Set.of(),
                    Set.of(), -1, NO_POS, findFoot(view, base, family, rules), NO_POS, group.size());
        }

        // The other stems that are a build's posts, nearest first. Each crown
        // is then read on its own stem's logs: no build's logs and none of the
        // other stems' columns, so a post or a taller trunk beside the top
        // does not hide it.
        int ourIndex = trunks.indexOf(ours);
        Stem[] stems = new Stem[trunks.size()];
        List<Integer> others = new ArrayList<>();
        for (int i = 0; i < trunks.size(); i++) {
            if (i != ourIndex) others.add(i);
        }
        others.sort(Comparator.comparingLong((Integer i) -> distanceSquared(trunks.get(i).get(0), base))
                .thenComparingInt(i -> i));
        for (int n = 0; n < others.size(); n++) {
            int i = others.get(n);
            stems[i] = n < MAX_OTHER_TRUNKS_JUDGED ? buildStem(view, group, family, trunks.get(i), rules) : Stem.UNKNOWN;
        }
        stems[ourIndex] = Stem.TRUNK;
        Set<Long> natural = new LinkedHashSet<>(group);
        for (long log : group) {
            if (isMarkLog(view, log, rules)) natural.remove(log);
        }
        Set<Long> stemColumns = new HashSet<>();
        for (int i = 0; i < trunks.size(); i++) {
            if (stems[i] == Stem.BUILD) {
                natural.removeAll(columns(group, trunks.get(i)));
            } else if (stems[i] != Stem.NONE) {
                stemColumns.addAll(columns(group, trunks.get(i)));
            }
        }

        int crownSides = -1;
        long crownTop = NO_POS;
        if (rules.crown()) {
            Trunk trunk = checkTrunk(view, base, family, ownLogs(natural, stemColumns, group, ours), rules);
            if (trunk.refusal != null) return trunk.refusal;
            crownSides = trunk.crownSides;
            crownTop = trunk.crownTop;
            for (int i = 0; i < trunks.size(); i++) {
                if (stems[i] != null) continue;
                List<Long> stem = trunks.get(i);
                stems[i] = crownStem(view, ownLogs(natural, stemColumns, group, stem), family, stem);
            }
        }

        Map<Long, Integer> owner = shareOut(view, group, fill.edges, trunks, stems, rules);
        Set<Long> logs = new LinkedHashSet<>();
        for (long log : group) {
            Integer who = owner.get(log);
            if (who != null && who == ourIndex) logs.add(log);
        }

        if (logs.size() < 2) return no(Reason.TOO_SMALL, base, logs);
        if (logs.size() > MAX_LOGS) return no(Reason.TOO_BIG, base, logs);
        for (long log : logs) {
            if (!withinBounds(x(base), by, z(base), x(log), y(log), z(log))) {
                return no(y(log) - by > MAX_HEIGHT_ABOVE_BASE ? Reason.TOO_TALL : Reason.TOO_BIG, log, logs);
            }
        }
        Judgement resting = buildRestingOnTree(view, owner, logs, by);
        if (resting != null) return resting;
        return withLeaves(view, base, family, logs, group.size(), crownSides, crownTop,
                twoByTwoAnchor(view, logs, base, rules), rules);
    }

    /**
     * Every log of the base's wood joined to it (corners count), out to
     * {@link #FILL_MARGIN} past the tree's own box and at most
     * {@link #FILL_CAP} logs. A log with more of the group past that, or an
     * unloaded chunk outside the tree's own box beside it, is an edge: the
     * unknown starts there. Protected logs are filled like any other.
     */
    private static Fill fillGroup(TreeView view, long base, int family, Rules rules) {
        int bx = x(base);
        int by = y(base);
        int bz = z(base);
        Set<Long> logs = new LinkedHashSet<>();
        Set<Long> edges = new LinkedHashSet<>();
        Set<Long> visited = new HashSet<>();
        Set<Long> beyond = new HashSet<>();
        Queue<Long> queue = new ArrayDeque<>();
        logs.add(base);
        visited.add(base);
        queue.add(base);

        while (!queue.isEmpty()) {
            long current = queue.poll();
            int x = x(current);
            int y = y(current);
            int z = z(current);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        int nx = x + dx;
                        int ny = y + dy;
                        int nz = z + dz;
                        long next = pack(nx, ny, nz);
                        if (beyond.contains(next)) {
                            edges.add(current);
                            continue;
                        }
                        if (!visited.add(next)) continue;
                        Cell cell = view.at(nx, ny, nz);
                        if (cell.kind() == Kind.UNLOADED) {
                            if (Math.abs(nx - bx) <= MAX_HORIZONTAL_RADIUS
                                    && Math.abs(nz - bz) <= MAX_HORIZONTAL_RADIUS) {
                                return new Fill(logs, edges, cantSee(next, logs));
                            }
                            beyond.add(next);
                            edges.add(current);
                            continue;
                        }
                        if (!isFamilyLog(cell, family, rules) || isLyingOnGround(view, nx, ny, nz, cell, rules, 0)) {
                            continue;
                        }
                        if (!withinFill(bx, by, bz, nx, ny, nz) || logs.size() >= FILL_CAP) {
                            beyond.add(next);
                            edges.add(current);
                            continue;
                        }
                        logs.add(next);
                        queue.add(next);
                    }
                }
            }
        }
        return new Fill(logs, edges, null);
    }

    /** Every stem of the group, a full 2x2 of stems as one, low corner first, in {@link #FIXED_ORDER}. */
    private static List<List<Long>> trunks(TreeView view, Set<Long> group) {
        Map<Integer, List<Long>> byLayer = new HashMap<>();
        for (long log : group) {
            if (isStem(view, group, log)) byLayer.computeIfAbsent(y(log), ignored -> new ArrayList<>()).add(log);
        }
        List<List<Long>> trunks = new ArrayList<>();
        for (List<Long> layer : byLayer.values()) trunks.addAll(tiles(layer));
        trunks.sort(Comparator.comparing((List<Long> trunk) -> trunk.get(0), FIXED_ORDER));
        return trunks;
    }

    /**
     * A standing log of the group on ground of its own (solid, not a log, not
     * leaves; a built block counts, so a post on cobblestone is a stem too)
     * with the group going up from it.
     */
    private static boolean isStem(TreeView view, Set<Long> group, long log) {
        int x = x(log);
        int y = y(log);
        int z = z(log);
        if (!view.at(x, y, z).upright()) return false;
        Cell below = view.at(x, y - 1, z);
        return below.solid() && !below.isLog() && !below.isLeaf() && hasLogAbove(group, x, y, z);
    }

    /** Each foot's straight column up through the group. */
    private static Set<Long> columns(Set<Long> group, List<Long> trunk) {
        Set<Long> columns = new LinkedHashSet<>();
        for (long foot : trunk) {
            int x = x(foot);
            int z = z(foot);
            for (int y = y(foot); group.contains(pack(x, y, z)); y++) columns.add(pack(x, y, z));
        }
        return columns;
    }

    /**
     * A lone stem leaning on a lower log of the group, with fewer than
     * {@link #MIN_STEM} logs straight up: a branch of that trunk resting on
     * the ground (a mangrove's low branch on its roots), not a trunk.
     */
    private static boolean isBranch(TreeView view, Set<Long> group, List<Long> trunk) {
        if (trunk.size() != 1) return false;
        long foot = trunk.get(0);
        return isStem(view, group, foot)
                && lowerNeighbour(group, foot) != NO_POS
                && columns(group, trunk).size() < MIN_STEM;
    }

    /** A log of the group one lower beside the foot (straight below is its ground), or {@link #NO_POS}. */
    private static long lowerNeighbour(Set<Long> group, long foot) {
        int x = x(foot);
        int y = y(foot);
        int z = z(foot);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                long below = pack(x + dx, y - 1, z + dz);
                if (group.contains(below)) return below;
            }
        }
        return NO_POS;
    }

    /**
     * Where a felled tree's other trunks stood ({@code replantall}): without
     * the split, touching trees come down as one, and each of their trunks
     * wants its sapling back. A trunk is a stem of {@code logs} as the split
     * sees it, not a branch, every foot a plain log on natural ground; one
     * touching {@code skip} (the spot replanted already) is left out.
     *
     * @param squares the tree's kind grows 2x2 trunks; otherwise a square of
     *                feet is four trunks
     * @return each trunk's feet, low corner first, in {@link #FIXED_ORDER}
     */
    public static List<List<Long>> otherTrunks(TreeView view, Set<Long> logs, Set<Long> skip, boolean squares) {
        List<List<Long>> others = new ArrayList<>();
        for (List<Long> trunk : trunks(view, logs)) {
            List<List<Long>> parts = squares || trunk.size() == 1
                    ? List.of(trunk) : trunk.stream().map(List::of).toList();
            for (List<Long> part : parts) {
                if (isBranch(view, logs, part)) continue;
                boolean fits = true;
                for (long foot : part) {
                    fits &= !skip.contains(foot)
                            && view.at(x(foot), y(foot), z(foot)).kind() == Kind.PLAIN_LOG
                            && view.at(x(foot), y(foot) - 1, z(foot)).kind() == Kind.GROUND;
                }
                if (fits) others.add(part);
            }
        }
        return others;
    }

    /**
     * Who claims each log of the group: {@link #BUILD}, {@link #UNKNOWN} or a
     * trunk's index. Builds are queued first, then the unknown, then the
     * trunks in fixed order, and each step out goes in the same order, so
     * ties go the same way.
     */
    private static Map<Long, Integer> shareOut(TreeView view, Set<Long> group, Set<Long> edges,
                                               List<List<Long>> trunks, Stem[] stems, Rules rules) {
        Map<Long, Integer> owner = new HashMap<>();
        Queue<Long> queue = new ArrayDeque<>();
        for (long log : group) {
            if (isMarkLog(view, log, rules)) claim(owner, queue, log, BUILD);
        }
        for (int i = 0; i < trunks.size(); i++) {
            if (stems[i] == Stem.BUILD) columns(group, trunks.get(i)).forEach(log -> claim(owner, queue, log, BUILD));
        }
        for (long log : edges) claim(owner, queue, log, UNKNOWN);
        for (int i = 0; i < trunks.size(); i++) {
            if (stems[i] == Stem.UNKNOWN) columns(group, trunks.get(i)).forEach(log -> claim(owner, queue, log, UNKNOWN));
        }
        for (int i = 0; i < trunks.size(); i++) {
            int who = i;
            if (stems[i] == Stem.TRUNK) columns(group, trunks.get(i)).forEach(log -> claim(owner, queue, log, who));
        }

        while (!queue.isEmpty()) {
            long current = queue.poll();
            int who = owner.get(current);
            int x = x(current);
            int y = y(current);
            int z = z(current);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long next = pack(x + dx, y + dy, z + dz);
                        if (group.contains(next)) claim(owner, queue, next, who);
                    }
                }
            }
        }
        return owner;
    }

    private static void claim(Map<Long, Integer> owner, Queue<Long> queue, long log, int who) {
        if (owner.putIfAbsent(log, who) == null) queue.add(log);
    }

    /**
     * One other stem of the group, judged as a trunk would be, all but its
     * crown: null when only the crown is left to look at ({@link #crownStem}).
     */
    private static Stem buildStem(TreeView view, Set<Long> group, int family, List<Long> trunk, Rules rules) {
        for (long log : columns(group, trunk)) {
            if (isMarkLog(view, log, rules)) return Stem.BUILD;
        }
        if (isBranch(view, group, trunk)) return Stem.NONE;
        if (!rules.crown()) return Stem.TRUNK;
        for (long foot : trunk) {
            if (view.at(x(foot), y(foot) - 1, z(foot)).kind() == Kind.BUILT) return Stem.BUILD;
            // A post on stone, sand, a metal block... is somebody's too.
            if (!onSoil(view, foot, rules)) return Stem.BUILD;
            long onTop = whatSitsOnTop(view, group, family, foot, true);
            if (onTop == NO_POS) continue;
            return view.at(x(onTop), y(onTop), z(onTop)).kind() == Kind.UNLOADED ? Stem.UNKNOWN : Stem.BUILD;
        }
        return null;
    }

    /**
     * A stem's crown, read over its own logs. Without one it is a dead trunk
     * or a bare post: the unknown, so the tree beside it does not swallow it.
     */
    private static Stem crownStem(TreeView view, Set<Long> own, int family, List<Long> trunk) {
        Crown crown = bestCrown(view, own, family, trunk);
        return !crown.unseen && crown.sides >= CROWN_SIDES_NEEDED ? Stem.TRUNK : Stem.UNKNOWN;
    }

    /** The logs a stem's crown is read on: no build's, and of the stems' columns only its own. */
    private static Set<Long> ownLogs(Set<Long> natural, Set<Long> stemColumns, Set<Long> group, List<Long> trunk) {
        Set<Long> own = new LinkedHashSet<>(natural);
        own.removeAll(stemColumns);
        own.addAll(columns(group, trunk));
        return own;
    }

    /**
     * Every build joined to the tree must stand on ground of its own no higher
     * than {@link #BUILD_GROUND_RISE} above the base. One that doesn't rests
     * on the tree (a treehouse, a lantern on a branch): the tree is left whole.
     */
    private static Judgement buildRestingOnTree(TreeView view, Map<Long, Integer> owner, Set<Long> tree, int baseY) {
        Set<Long> seen = new HashSet<>();
        for (long log : tree) {
            int x = x(log);
            int y = y(log);
            int z = z(log);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long next = pack(x + dx, y + dy, z + dz);
                        Integer who = owner.get(next);
                        if (who == null || who != BUILD || !seen.add(next)) continue;
                        List<Long> part = buildPart(owner, next, seen);
                        if (standsOnItsOwn(view, part, baseY)) continue;
                        for (long piece : part) {
                            if (view.keepOut(x(piece), y(piece), z(piece))) return no(Reason.KEEP_OUT, piece, tree);
                        }
                        return no(Reason.BUILD_RESTS_ON_TREE, next, tree);
                    }
                }
            }
        }
        return null;
    }

    /** The build's logs joined to {@code start} (already in {@code seen}). */
    private static List<Long> buildPart(Map<Long, Integer> owner, long start, Set<Long> seen) {
        List<Long> part = new ArrayList<>();
        Queue<Long> queue = new ArrayDeque<>();
        part.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            long current = queue.poll();
            int x = x(current);
            int y = y(current);
            int z = z(current);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long next = pack(x + dx, y + dy, z + dz);
                        Integer who = owner.get(next);
                        if (who != null && who == BUILD && seen.add(next)) {
                            part.add(next);
                            queue.add(next);
                        }
                    }
                }
            }
        }
        return part;
    }

    private static boolean standsOnItsOwn(TreeView view, List<Long> part, int baseY) {
        for (long log : part) {
            if (y(log) > baseY + BUILD_GROUND_RISE) continue;
            Cell below = view.at(x(log), y(log) - 1, z(log));
            if (below.solid() && !below.isLog() && !below.isLeaf()) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean withinFill(int bx, int by, int bz, int x, int y, int z) {
        return Math.abs(x - bx) <= MAX_HORIZONTAL_RADIUS + FILL_MARGIN
                && Math.abs(z - bz) <= MAX_HORIZONTAL_RADIUS + FILL_MARGIN
                && y - by <= MAX_HEIGHT_ABOVE_BASE + FILL_MARGIN
                && by - y <= MAX_DEPTH_BELOW_BASE + FILL_MARGIN;
    }

    private static long distanceSquared(long a, long b) {
        long dx = x(a) - x(b);
        long dy = y(a) - y(b);
        long dz = z(a) - z(b);
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean withinBounds(int bx, int by, int bz, int x, int y, int z) {
        return Math.abs(x - bx) <= MAX_HORIZONTAL_RADIUS
                && Math.abs(z - bz) <= MAX_HORIZONTAL_RADIUS
                && y - by <= MAX_HEIGHT_ABOVE_BASE
                && by - y <= MAX_DEPTH_BELOW_BASE;
    }

    private static Judgement no(Reason reason, long where, Set<Long> logs) {
        return new Judgement(Verdict.NOT_TREE, reason, where, logs, Set.of(), Set.of(), -1, NO_POS, NO_POS,
                NO_POS, logs.size());
    }

    private static Judgement cantSee(long where, Set<Long> logs) {
        return new Judgement(Verdict.CANT_SEE, Reason.UNLOADED, where, logs, Set.of(), Set.of(), -1, NO_POS,
                NO_POS, NO_POS, logs.size());
    }

    // Packed exactly like BlockPos.asLong: x 26 bits, z 26 bits, y 12 bits.
    private static final int PACKED_XZ_LENGTH = 26;
    private static final int PACKED_Y_LENGTH = 12;
    private static final long PACKED_XZ_MASK = (1L << PACKED_XZ_LENGTH) - 1L;
    private static final long PACKED_Y_MASK = (1L << PACKED_Y_LENGTH) - 1L;
    private static final int Z_OFFSET = PACKED_Y_LENGTH;
    private static final int X_OFFSET = PACKED_Y_LENGTH + PACKED_XZ_LENGTH;

    public static long pack(int x, int y, int z) {
        return ((long) x & PACKED_XZ_MASK) << X_OFFSET
                | ((long) y & PACKED_Y_MASK)
                | ((long) z & PACKED_XZ_MASK) << Z_OFFSET;
    }

    public static int x(long packed) {
        return (int) (packed << 64 - X_OFFSET - PACKED_XZ_LENGTH >> 64 - PACKED_XZ_LENGTH);
    }

    public static int y(long packed) {
        return (int) (packed << 64 - PACKED_Y_LENGTH >> 64 - PACKED_Y_LENGTH);
    }

    public static int z(long packed) {
        return (int) (packed << 64 - Z_OFFSET - PACKED_XZ_LENGTH >> 64 - PACKED_XZ_LENGTH);
    }
}

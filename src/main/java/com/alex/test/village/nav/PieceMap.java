package com.alex.test.village.nav;

import java.util.Arrays;

/**
 * The village's pieces and the links between them, with the three answers worked out once at
 * build time: "can I get there" over ordinary links, token access over ordinary and failed links,
 * islands over every link. Built from plain ints so it can be tested without Minecraft; CellTable
 * turns pieces into those ints.
 */
public final class PieceMap {

    public static final class Builder {
        private final int pieceCount;
        private int[] from = new int[64];
        private int[] to = new int[64];
        private int[] kind = new int[64];
        private int count;

        public Builder(int pieceCount) {
            if (pieceCount < 0) throw new IllegalArgumentException("negative piece count " + pieceCount);
            this.pieceCount = pieceCount;
        }

        /** kind ORDINARY, FAILED or ENTER_OFF; duplicates allowed. */
        public void link(int from, int to, int kind) {
            checkPiece(from);
            checkPiece(to);
            if (kind != EdgeKind.ORDINARY && kind != EdgeKind.FAILED && kind != EdgeKind.ENTER_OFF) {
                throw new IllegalArgumentException("not a link kind: " + kind);
            }
            if (count == this.from.length) {
                int grown = count * 2;
                this.from = Arrays.copyOf(this.from, grown);
                this.to = Arrays.copyOf(this.to, grown);
                this.kind = Arrays.copyOf(this.kind, grown);
            }
            this.from[count] = from;
            this.to[count] = to;
            this.kind[count] = kind;
            count++;
        }

        /** -1 for either root means no root: no islands, or nothing accessible. Every piece counts as live. */
        public PieceMap build(int islandRoot, int accessRoot) {
            return build(islandRoot, accessRoot, null);
        }

        /**
         * As above with a mask of the ids in use: a free id (a piece that was
         * replaced) is never an island and never counted, so the table can
         * keep ids stable instead of renumbering every piece on every build.
         *
         * @param live which ids hold a piece, or null for all of them
         */
        public PieceMap build(int islandRoot, int accessRoot, boolean[] live) {
            checkRoot(islandRoot);
            checkRoot(accessRoot);
            if (live != null && live.length != pieceCount) {
                throw new IllegalArgumentException("live mask of " + live.length + " for " + pieceCount + " pieces");
            }
            int n = pieceCount;
            int[] offsets = new int[n + 1];
            for (int i = 0; i < count; i++) offsets[from[i] + 1]++;
            for (int p = 0; p < n; p++) offsets[p + 1] += offsets[p];
            int[] targets = new int[count];
            int[] kinds = new int[count];
            int[] fill = Arrays.copyOf(offsets, n);
            for (int i = 0; i < count; i++) {
                int at = fill[from[i]]++;
                targets[at] = to[i];
                kinds[at] = kind[i];
            }
            return new PieceMap(n, offsets, targets, kinds, islandRoot, accessRoot, live);
        }

        private void checkPiece(int piece) {
            if (piece < 0 || piece >= pieceCount) {
                throw new IllegalArgumentException("piece " + piece + " outside 0.." + (pieceCount - 1));
            }
        }

        private void checkRoot(int root) {
            if (root < -1 || root >= pieceCount) {
                throw new IllegalArgumentException("root " + root + " outside -1.." + (pieceCount - 1));
            }
        }
    }

    private final int pieceCount;
    // Links by source piece, compressed rows.
    private final int[] offsets;
    private final int[] targets;
    private final int[] kinds;
    private final int[] component;
    private final int componentCount;
    // The component graph over ordinary links, deduplicated; every edge runs from a higher id to a lower one.
    private final int[] compOffsets;
    private final int[] compTargets;
    private final boolean[] accessible;
    private final boolean[] island;
    private final int[] islands;
    // Scratch for reaches(): a stamp per component saves clearing a boolean array for every question.
    // One server thread asks, so there is no locking.
    private final int[] seen;
    private final int[] queue;
    private int stamp;

    private PieceMap(int pieceCount, int[] offsets, int[] targets, int[] kinds, int islandRoot, int accessRoot,
                     boolean[] live) {
        this.pieceCount = pieceCount;
        this.offsets = offsets;
        this.targets = targets;
        this.kinds = kinds;

        // Components follow ordinary links only.
        int[] ordOffsets = new int[pieceCount + 1];
        int ordCount = 0;
        for (int p = 0; p < pieceCount; p++) {
            for (int e = offsets[p]; e < offsets[p + 1]; e++) {
                if (kinds[e] == EdgeKind.ORDINARY) ordCount++;
            }
            ordOffsets[p + 1] = ordCount;
        }
        int[] ordTargets = new int[ordCount];
        int at = 0;
        for (int p = 0; p < pieceCount; p++) {
            for (int e = offsets[p]; e < offsets[p + 1]; e++) {
                if (kinds[e] == EdgeKind.ORDINARY) ordTargets[at++] = targets[e];
            }
        }
        Tarjan.Result scc = Tarjan.run(pieceCount, ordOffsets, ordTargets);
        this.component = scc.component();
        this.componentCount = scc.count();

        // Component graph: lay the cross-component edges out by source component, then drop repeats per row.
        int[] cOffsets = new int[componentCount + 1];
        int cross = 0;
        for (int p = 0; p < pieceCount; p++) {
            for (int e = ordOffsets[p]; e < ordOffsets[p + 1]; e++) {
                if (component[ordTargets[e]] != component[p]) {
                    cOffsets[component[p] + 1]++;
                    cross++;
                }
            }
        }
        for (int c = 0; c < componentCount; c++) cOffsets[c + 1] += cOffsets[c];
        int[] cTargets = new int[cross];
        int[] fill = Arrays.copyOf(cOffsets, componentCount);
        for (int p = 0; p < pieceCount; p++) {
            for (int e = ordOffsets[p]; e < ordOffsets[p + 1]; e++) {
                int d = component[ordTargets[e]];
                if (d != component[p]) cTargets[fill[component[p]]++] = d;
            }
        }
        int[] lastRow = new int[componentCount];
        Arrays.fill(lastRow, -1);
        int[] packedOffsets = new int[componentCount + 1];
        int w = 0;
        for (int c = 0; c < componentCount; c++) {
            for (int e = cOffsets[c]; e < cOffsets[c + 1]; e++) {
                int d = cTargets[e];
                if (lastRow[d] == c) continue;
                lastRow[d] = c;
                cTargets[w++] = d;
            }
            packedOffsets[c + 1] = w;
        }
        this.compOffsets = packedOffsets;
        this.compTargets = Arrays.copyOf(cTargets, w);

        this.accessible = flood(accessRoot, EdgeKind.FAILED);
        boolean[] reached = flood(islandRoot, EdgeKind.ENTER_OFF);
        this.island = new boolean[pieceCount];
        int islandCount = 0;
        if (islandRoot >= 0) {
            for (int p = 0; p < pieceCount; p++) {
                if (!reached[p] && (live == null || live[p])) {
                    island[p] = true;
                    islandCount++;
                }
            }
        }
        this.islands = new int[islandCount];
        at = 0;
        for (int p = 0; p < pieceCount; p++) {
            if (island[p]) islands[at++] = p;
        }

        this.seen = new int[componentCount];
        this.queue = new int[componentCount];
    }

    /** Pieces reached from root over links of at most maxKind; nothing when there is no root. */
    private boolean[] flood(int root, int maxKind) {
        boolean[] mark = new boolean[pieceCount];
        if (root < 0) return mark;
        int[] q = new int[pieceCount];
        int head = 0;
        int tail = 0;
        mark[root] = true;
        q[tail++] = root;
        while (head < tail) {
            int p = q[head++];
            for (int e = offsets[p]; e < offsets[p + 1]; e++) {
                int t = targets[e];
                if (kinds[e] <= maxKind && !mark[t]) {
                    mark[t] = true;
                    q[tail++] = t;
                }
            }
        }
        return mark;
    }

    public int pieceCount() {
        return pieceCount;
    }

    public int componentCount() {
        return componentCount;
    }

    public int componentOf(int piece) {
        return component[piece];
    }

    /** Over ORDINARY links; true when both are the same piece. */
    public boolean reaches(int fromPiece, int toPiece) {
        int ca = component[fromPiece];
        int cb = component[toPiece];
        if (ca == cb) return true;
        // Links only ever lead to lower component ids, so a lower start can never arrive.
        if (ca < cb) return false;
        if (++stamp == Integer.MAX_VALUE) {
            Arrays.fill(seen, 0);
            stamp = 1;
        }
        int head = 0;
        int tail = 0;
        queue[tail++] = ca;
        seen[ca] = stamp;
        while (head < tail) {
            int c = queue[head++];
            for (int e = compOffsets[c]; e < compOffsets[c + 1]; e++) {
                int d = compTargets[e];
                if (d == cb) return true;
                // Anything below the goal cannot climb back up to it.
                if (d < cb || seen[d] == stamp) continue;
                seen[d] = stamp;
                queue[tail++] = d;
            }
        }
        return false;
    }

    /** Reached from accessRoot over ORDINARY and FAILED links; all false when there was no access root. */
    public boolean accessible(int piece) {
        return accessible[piece];
    }

    /** Not reached from islandRoot over every kind; all false when there was no island root. */
    public boolean isIsland(int piece) {
        return island[piece];
    }

    /** Ascending piece indices that are islands. */
    public int[] islands() {
        return islands.clone();
    }
}

package com.alex.test.village.nav;

import java.util.Arrays;

/**
 * Strongly connected components over compressed rows, without recursion: a cell can hold hundreds
 * of tiles and the piece map thousands of pieces, more than the Java stack cares to nest. Both the
 * grouper and the piece map use this one search so they cannot drift apart.
 */
final class Tarjan {
    /**
     * component[v] for every node. Components are numbered in the order they complete, so the
     * first finished one is 0 and every edge between different components runs from a higher
     * id to a lower one (a component only completes once everything it can reach has).
     */
    record Result(int[] component, int count) {}

    private Tarjan() {}

    static Result run(int n, int[] offsets, int[] targets) {
        int[] index = new int[n];
        Arrays.fill(index, -1);
        int[] low = new int[n];
        int[] component = new int[n];
        boolean[] onStack = new boolean[n];
        int[] stack = new int[n];
        int sp = 0;
        // The would-be call stack: the node being explored and where its neighbour scan is up to.
        int[] callNode = new int[n];
        int[] callCursor = new int[n];
        int cp = 0;
        int next = 0;
        int count = 0;
        for (int root = 0; root < n; root++) {
            if (index[root] != -1) continue;
            index[root] = next;
            low[root] = next++;
            stack[sp++] = root;
            onStack[root] = true;
            callNode[cp] = root;
            callCursor[cp] = offsets[root];
            cp++;
            while (cp > 0) {
                int v = callNode[cp - 1];
                int cursor = callCursor[cp - 1];
                if (cursor < offsets[v + 1]) {
                    callCursor[cp - 1] = cursor + 1;
                    int w = targets[cursor];
                    if (index[w] == -1) {
                        index[w] = next;
                        low[w] = next++;
                        stack[sp++] = w;
                        onStack[w] = true;
                        callNode[cp] = w;
                        callCursor[cp] = offsets[w];
                        cp++;
                    } else if (onStack[w]) {
                        low[v] = Math.min(low[v], index[w]);
                    }
                    continue;
                }
                if (low[v] == index[v]) {
                    int w;
                    do {
                        w = stack[--sp];
                        onStack[w] = false;
                        component[w] = count;
                    } while (w != v);
                    count++;
                }
                cp--;
                if (cp > 0) {
                    int parent = callNode[cp - 1];
                    low[parent] = Math.min(low[parent], low[v]);
                }
            }
        }
        return new Result(component, count);
    }
}

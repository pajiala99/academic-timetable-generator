package edu.timetable.solver;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;

/**
 * Maximum bipartite matching (augmenting paths) plus extraction of a Hall violator: when the
 * matching is not perfect on the left side, a set A of left vertices whose neighbourhood N(A)
 * is strictly smaller than A. That set is a human-readable certificate of infeasibility.
 */
final class BipartiteMatching {
    private final int[][] adj;
    private final int[] matchLeft;
    private final int[] matchRight;
    private final int[] seen;
    private int stamp;
    private final int size;

    /** A Hall violator: every neighbour of a vertex in {@code left} is in {@code right}, and right is smaller. */
    record Deficiency(int[] left, int[] right) {
    }

    BipartiteMatching(int[][] adj, int rightCount) {
        this.adj = adj;
        this.matchLeft = new int[adj.length];
        this.matchRight = new int[rightCount];
        this.seen = new int[rightCount];
        Arrays.fill(matchLeft, -1);
        Arrays.fill(matchRight, -1);
        // Cheap greedy start, then augment.
        for (int u = 0; u < adj.length; u++) {
            for (int v : adj[u]) {
                if (matchRight[v] < 0) {
                    matchLeft[u] = v;
                    matchRight[v] = u;
                    break;
                }
            }
        }
        int matched = 0;
        for (int u = 0; u < adj.length; u++) {
            if (matchLeft[u] < 0) {
                stamp++;
                augment(u);
            }
            if (matchLeft[u] >= 0) matched++;
        }
        this.size = matched;
    }

    static BipartiteMatching fromBitSets(BitSet[] rows, int rightCount) {
        int[][] adj = new int[rows.length][];
        for (int i = 0; i < rows.length; i++) adj[i] = rows[i].stream().toArray();
        return new BipartiteMatching(adj, rightCount);
    }

    private boolean augment(int u) {
        for (int v : adj[u]) {
            if (seen[v] == stamp) continue;
            seen[v] = stamp;
            if (matchRight[v] < 0 || augment(matchRight[v])) {
                matchLeft[u] = v;
                matchRight[v] = u;
                return true;
            }
        }
        return false;
    }

    int size() {
        return size;
    }

    boolean isLeftPerfect() {
        return size == adj.length;
    }

    /**
     * Alternating-path search from the unmatched left vertices. Every right vertex reached is
     * matched (otherwise the matching would not be maximum) and its partner is reached too, so
     * |N(A)| = |A| - (number of unmatched vertices in A) &lt; |A|.
     */
    Deficiency deficiency() {
        BitSet leftSeen = new BitSet(adj.length);
        BitSet rightSeen = new BitSet(matchRight.length);
        Deque<Integer> queue = new ArrayDeque<>();
        for (int u = 0; u < adj.length; u++) {
            if (matchLeft[u] < 0) {
                leftSeen.set(u);
                queue.add(u);
            }
        }
        while (!queue.isEmpty()) {
            int u = queue.poll();
            for (int v : adj[u]) {
                if (rightSeen.get(v)) continue;
                rightSeen.set(v);
                int w = matchRight[v];
                if (w >= 0 && !leftSeen.get(w)) {
                    leftSeen.set(w);
                    queue.add(w);
                }
            }
        }
        return new Deficiency(leftSeen.stream().toArray(), rightSeen.stream().toArray());
    }
}

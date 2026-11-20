import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Exact elementary-path pricing without a mathematical-programming solver.
 * Physical vertices are 0..n-1 and n is a virtual sink. All objective constants
 * must already be included in the terminal arcs. The exact real values of the
 * supplied binary64 coefficients are used, including in dominance and bounds.
 */
public final class BpaExactLabelPricing {
    private BpaExactLabelPricing() { }

    public static final class Options {
        /** Return a new path below the threshold, or certify none can be below it. */
        public boolean stopAfterNegative = false;
        public double negativeThreshold = Double.NEGATIVE_INFINITY;
        /** Maximum useful negative paths collected per call (1..64, not an enumeration guarantee). */
        public int maxColumns = 1;
        /** Maximum expanded labels; zero is a deterministic interruption. */
        public long maxLabels = Long.MAX_VALUE;
        /** Maximum distinct retained labels, including queued/bucket labels and their ancestors. */
        public long maxStoredLabels = 500000L;
        public boolean useCompactDominance = true;
        public boolean useDegreeNegativeBound = true;
        /** With two-word visited sets, reject incompatible pairs before resource comparisons. */
        public boolean useGeometryFirstDominance = true;
        /** Exact visited-set inverted index for sufficiently large compact buckets. */
        public boolean useVisitedSetIndex = true;
        public int visitedSetIndexThreshold = 128;
        /** Budget for published indexes' long words, not JVM heap bytes. Zero uses scans.
         * Rebuilding may temporarily keep both the old and replacement index alive. */
        public long maxVisitedIndexWords = 2097152L;
    }

    public static final class Result {
        public int[] pathEdges;
        /** Independently feasible, mutually distinct paths with exact cost below the threshold. */
        public final List<int[]> negativePathEdges = new ArrayList<>();
        /** Upper-rounded costs; a strict exact negative cost can round to the threshold. */
        public final List<Double> negativeObjectives = new ArrayList<>();
        /** Upper-rounded cost of the returned feasible path. */
        public double objective = Double.POSITIVE_INFINITY;
        /** Lower-rounded bound for ALL feasible missing paths. */
        public double lowerBound = Double.NEGATIVE_INFINITY;
        public boolean infeasible;
        public boolean optimal;
        public String status = "UNKNOWN";
        public long labelsExpanded, labelsGenerated, dominanceChecks;
        public long labelsDominated, boundPruned, peakPending;
        public long timePruned, requiredPruned, excludedTerminals;
        public long negativeArcBoundChecks, negativeArcBoundImprovements;
        /** visitedSubsetChecks also includes exact mask equality in geometry-first scans. */
        public long retainedLabels, peakRetainedLabels, visitedSubsetChecks, dominanceCostComparisons;
        /** Pair visits in the shared compact scan; legacy directional scans are not counted here. */
        public long dominancePairVisits;
        /** Actual exact time comparisons in shared and legacy directional scans. */
        public long dominanceTimeComparisons;
        /** These count exact geometry screens, not heuristic pruning or changed dominance rules. */
        public long geometryFirstPairChecks, geometryFirstRejectedPairs;
        public long visitedIndexQueries, visitedIndexBuilds, visitedIndexCandidates, visitedIndexSkippedPairs;
        public long visitedIndexPostingOperations, visitedIndexBudgetFallbacks;
        /** Published posting/active/scratch capacity; excludes headers and an uncommitted rebuild. */
        public long visitedIndexWords, peakVisitedIndexWords;
        public long negativeAdjacencyProbes;
        public long inactiveQueueLabelsReclaimed;
        public double preprocessingSeconds, searchSeconds;
    }

    public static Result solve(int n, int source, int[] edgeU, int[] edgeV,
                               double[] edgeCost, double[] edgeTime, double timeWindow,
                               boolean[] allowed, boolean[] required,
                               List<int[]> excludedPaths, double timeSeconds) {
        return solve(n, source, edgeU, edgeV, edgeCost, edgeTime, timeWindow,
                allowed, required, excludedPaths, timeSeconds, new Options());
    }

    /**
     * Complete optimum/infeasibility, FOUND_COLUMNS, NO_NEGATIVE_CERTIFIED,
     * or UNKNOWN with a valid
     * lower bound. A stopped search never silently becomes a no-column proof.
     * Excluded edge arrays may be unordered but must each be one complete simple
     * source-to-virtual-sink path. The inputs must remain fixed during this call.
     */
    public static Result solve(int n, int source, int[] edgeU, int[] edgeV,
                               double[] edgeCost, double[] edgeTime, double timeWindow,
                               boolean[] allowed, boolean[] required,
                               List<int[]> excludedPaths, double timeSeconds, Options options) {
        Engine engine = new Engine(timeSeconds, options);
        try {
            engine.prepare(n, source, edgeU, edgeV, edgeCost, edgeTime, timeWindow,
                    allowed, required, excludedPaths);
            return engine.search();
        } catch (Stop stop) {
            return engine.finish(stop.reason, false, false);
        } catch (RuntimeException error) {
            // Malformed input or an internal invariant failure is not a proof.
            Result result = engine.result;
            result.pathEdges = null;
            result.objective = Double.POSITIVE_INFINITY;
            result.lowerBound = Double.NEGATIVE_INFINITY;
            result.infeasible = result.optimal = false;
            result.status = "UNKNOWN:" + error.getClass().getSimpleName() + ":" + error.getMessage();
            engine.recordTimes();
            return result;
        }
    }

    private static final class Stop extends RuntimeException {
        final String reason;
        Stop(String reason) { super(null, null, false, false); this.reason = reason; }
    }

    /** A finite double is exactly significand * 2^exponent. */
    private static final class Dyadic {
        final long significand;
        final int exponent;
        Dyadic(double value) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite dyadic value");
            long bits = Double.doubleToRawLongBits(value);
            int rawExponent = (int) ((bits >>> 52) & 2047L);
            long mantissa = bits & ((1L << 52) - 1L);
            if (rawExponent != 0) mantissa |= 1L << 52;
            int exp = rawExponent == 0 ? -1074 : rawExponent - 1075;
            if (mantissa == 0) { significand = 0; exponent = 0; return; }
            int zeros = Long.numberOfTrailingZeros(mantissa);
            mantissa >>>= zeros;
            significand = bits < 0 ? -mantissa : mantissa;
            exponent = exp + zeros;
        }
    }

    private static final class Scale {
        final int exponent;
        Scale(double[] values, double extra) {
            int min = 0;
            for (double value : values) {
                Dyadic dyadic = new Dyadic(value);
                if (dyadic.significand != 0) min = Math.min(min, dyadic.exponent);
            }
            if (Double.isFinite(extra)) {
                Dyadic dyadic = new Dyadic(extra);
                if (dyadic.significand != 0) min = Math.min(min, dyadic.exponent);
            }
            exponent = min;
        }
        BigInteger encode(double value) {
            Dyadic dyadic = new Dyadic(value);
            return BigInteger.valueOf(dyadic.significand).shiftLeft(dyadic.exponent - exponent);
        }
        /** Smallest grid integer at least value, without refining the coefficient grid. */
        BigInteger ceil(double value) {
            Dyadic dyadic = new Dyadic(value);
            BigInteger mantissa = BigInteger.valueOf(dyadic.significand);
            int shift = dyadic.exponent - exponent;
            if (shift >= 0) return mantissa.shiftLeft(shift);
            BigInteger floor = mantissa.shiftRight(-shift);
            return floor.shiftLeft(-shift).equals(mantissa) ? floor : floor.add(BigInteger.ONE);
        }
        /** Compare the exact finite double to the scaled integer. */
        int compare(double value, BigInteger integer) {
            if (value == Double.NEGATIVE_INFINITY) return -1;
            if (value == Double.POSITIVE_INFINITY) return 1;
            Dyadic dyadic = new Dyadic(value);
            BigInteger mantissa = BigInteger.valueOf(dyadic.significand);
            int shift = dyadic.exponent - exponent;
            return shift >= 0 ? mantissa.shiftLeft(shift).compareTo(integer)
                    : mantissa.compareTo(integer.shiftLeft(-shift));
        }
        double down(BigInteger integer) {
            if (integer.signum() == 0) return 0.0;
            // Keep the leading 53 bits, avoiding intermediate double overflow
            // even when a common denominator requires thousands of integer bits.
            int shift = Math.max(0, integer.abs().bitLength() - 53);
            double value = Math.scalb(integer.shiftRight(shift).doubleValue(), exponent + shift);
            if (value == Double.POSITIVE_INFINITY) value = Double.MAX_VALUE;
            if (value == Double.NEGATIVE_INFINITY) {
                if (compare(-Double.MAX_VALUE, integer) > 0) return Double.NEGATIVE_INFINITY;
                value = -Double.MAX_VALUE;
            }
            while (compare(value, integer) > 0) value = Math.nextDown(value);
            double next = Math.nextUp(value);
            while (Double.isFinite(next) && compare(next, integer) <= 0) {
                value = next;
                next = Math.nextUp(value);
            }
            return value;
        }
        double up(BigInteger integer) {
            double lower = down(integer);
            return compare(lower, integer) == 0 ? lower : Math.nextUp(lower);
        }
    }

    private static final class TrieNode {
        final HashMap<Integer, Integer> children = new HashMap<>();
        boolean terminal;
    }

    private static final class StateKey {
        final int node, trie;
        final BigInteger required;
        StateKey(int node, int trie, BigInteger required) {
            this.node = node; this.trie = trie; this.required = required;
        }
        @Override public int hashCode() { return 31 * (31 * node + trie) + required.hashCode(); }
        @Override public boolean equals(Object other) {
            if (!(other instanceof StateKey)) return false;
            StateKey that = (StateKey) other;
            return node == that.node && trie == that.trie && required.equals(that.required);
        }
    }

    /** Two primitive words on the production network; exact arbitrary-size fallback. */
    private static final class Visited {
        final long low, high;
        final BigInteger large;
        Visited(long low, long high, BigInteger large) { this.low = low; this.high = high; this.large = large; }
        static Visited root(int node, boolean compact) {
            return compact ? new Visited(node < 64 ? 1L << node : 0L, node >= 64 ? 1L << (node - 64) : 0L, null)
                    : new Visited(0, 0, BigInteger.ONE.shiftLeft(node));
        }
        boolean contains(int node) {
            return large != null ? large.testBit(node)
                    : node < 64 ? (low & (1L << node)) != 0 : (high & (1L << (node - 64))) != 0;
        }
        Visited add(int node) {
            if (large != null) return new Visited(0, 0, large.setBit(node));
            return node < 64 ? new Visited(low | (1L << node), high, null)
                    : new Visited(low, high | (1L << (node - 64)), null);
        }
        boolean subsetOf(Visited other) {
            return large != null ? large.andNot(other.large).signum() == 0
                    : (low & ~other.low) == 0 && (high & ~other.high) == 0;
        }
    }

    private static final class Label {
        final int node, trie, visitedCount, edge;
        final Visited visited;
        final BigInteger required, cost, time, bound;
        final Label parent;
        final long sequence;
        boolean active = true;
        int references;
        Label(int node, int trie, int visitedCount, Visited visited, BigInteger required,
              BigInteger cost, BigInteger time, BigInteger bound, Label parent, int edge, long sequence) {
            this.node = node; this.trie = trie; this.visitedCount = visitedCount;
            this.visited = visited; this.required = required; this.cost = cost; this.time = time;
            this.bound = bound; this.parent = parent; this.edge = edge; this.sequence = sequence;
        }
    }

    private static final class Distance {
        final int node;
        final BigInteger value;
        Distance(int node, BigInteger value) { this.node = node; this.value = value; }
    }

    /** IDs are current bucket positions; this structure owns no Label references. */
    private static final class VisitedSetIndex {
        final int capacity;
        final long words;
        final BitSet[] containing;
        final BitSet active, subsets, supersets;

        VisitedSetIndex(Engine engine, int capacity) {
            this.capacity = capacity;
            words = (engine.n + 3L) * ((capacity + 63L) / 64L);
            containing = new BitSet[engine.n];
            for (int node = 0; node < engine.n; node++) {
                engine.checkTime();
                containing[node] = new BitSet(capacity);
            }
            active = new BitSet(capacity);
            subsets = new BitSet(capacity);
            supersets = new BitSet(capacity);
        }

        void add(int id, Visited visited) {
            if (id < 0 || id >= capacity) throw new IllegalStateException("Visited index capacity exceeded");
            active.set(id);
            for (long bits = visited.low; bits != 0; bits &= bits - 1)
                containing[Long.numberOfTrailingZeros(bits)].set(id);
            for (long bits = visited.high; bits != 0; bits &= bits - 1)
                containing[64 + Long.numberOfTrailingZeros(bits)].set(id);
        }
    }

    private static final class Engine {
        final long started = System.nanoTime();
        final Result result = new Result();
        final double seconds;
        final Options options;
        long searchStarted;
        int n, source;
        int[] u, v, requiredIndices, negativePhysicalIndices;
        int[][] outgoing, incoming, negativeOutgoing, negativeIncoming;
        BigInteger[] cost, time, requiredBit, minTime, nonnegativeCostBound;
        BigInteger[] positivePhysicalCostBound;
        BigInteger[][] hopBound;
        BigInteger window, allRequired, threshold;
        Scale costScale;
        boolean[] allowed;
        boolean contradiction;
        final ArrayList<TrieNode> trie = new ArrayList<>();
        final HashMap<StateKey, ArrayList<Label>> buckets = new HashMap<>();
        final IdentityHashMap<ArrayList<Label>, VisitedSetIndex> visitedIndexes = new IdentityHashMap<>();
        final PriorityQueue<Label> queue = new PriorityQueue<>(Comparator
                .comparing((Label label) -> label.bound)
                .thenComparing(label -> label.cost).thenComparingLong(label -> label.sequence));
        Label incumbent, partial;
        BigInteger initialBound;
        boolean frontierReady;
        long sequence;

        Engine(double seconds, Options options) { this.seconds = seconds; this.options = options; }

        void checkTime() {
            if ((System.nanoTime() - started) / 1e9 >= seconds)
                throw new Stop("UNKNOWN_TIME_LIMIT");
        }

        void prepare(int physicalNodes, int sourceNode, int[] edgeU, int[] edgeV,
                     double[] edgeCost, double[] edgeTime, double timeWindow,
                     boolean[] available, boolean[] required, List<int[]> exclusions) {
            if (!Double.isFinite(seconds)) throw new IllegalArgumentException("Nonfinite time limit");
            if (options == null) throw new IllegalArgumentException("Null options");
            if (options.maxColumns < 1 || options.maxColumns > 64)
                throw new IllegalArgumentException("maxColumns must be 1..64");
            if (options.maxLabels < 0 || options.maxStoredLabels < 1)
                throw new IllegalArgumentException("Invalid label limit");
            if (options.visitedSetIndexThreshold < 1 || options.maxVisitedIndexWords < 0)
                throw new IllegalArgumentException("Invalid visited index limit");
            if (Double.isNaN(options.negativeThreshold)
                    || options.negativeThreshold == Double.POSITIVE_INFINITY
                    || (options.stopAfterNegative && !Double.isFinite(options.negativeThreshold)))
                throw new IllegalArgumentException("Invalid negative threshold");
            if (physicalNodes < 1 || sourceNode < 0 || sourceNode >= physicalNodes)
                throw new IllegalArgumentException("Invalid physical node/source");
            if (edgeU == null || edgeV == null || edgeCost == null || edgeTime == null
                    || available == null || required == null)
                throw new IllegalArgumentException("Null pricing array");
            int m = edgeU.length;
            if (edgeV.length != m || edgeCost.length != m || edgeTime.length != m
                    || available.length != m || required.length != m)
                throw new IllegalArgumentException("Pricing array lengths differ");
            if (!Double.isFinite(timeWindow) || timeWindow < 0)
                throw new IllegalArgumentException("Invalid time window");
            n = physicalNodes; source = sourceNode; u = edgeU; v = edgeV; allowed = available;
            for (int e = 0; e < m; e++) {
                if (u[e] < 0 || u[e] >= n || v[e] < 0 || v[e] > n)
                    throw new IllegalArgumentException("Invalid edge endpoint " + e);
                if (!Double.isFinite(edgeCost[e]) || !Double.isFinite(edgeTime[e]) || edgeTime[e] < 0)
                    throw new IllegalArgumentException("Invalid cost/time " + e);
                if ((e & 255) == 0) checkTime();
            }
            checkTime();
            trie.add(new TrieNode());
            if (exclusions != null) for (int[] path : exclusions) {
                checkTime();
                int[] canonical = canonical(path);
                int q = 0;
                for (int edge : canonical) {
                    Integer child = trie.get(q).children.get(edge);
                    if (child == null) {
                        child = trie.size();
                        trie.get(q).children.put(edge, child);
                        trie.add(new TrieNode());
                    }
                    q = child;
                }
                trie.get(q).terminal = true;
            }
            // A threshold is a comparison, not another additive coefficient.
            // Integer x < real threshold/grid iff x < ceil(threshold/grid).
            costScale = new Scale(edgeCost, Double.NEGATIVE_INFINITY);
            Scale timeScale = new Scale(edgeTime, timeWindow);
            window = timeScale.encode(timeWindow);
            if (options.stopAfterNegative) threshold = costScale.ceil(options.negativeThreshold);
            cost = new BigInteger[m]; time = new BigInteger[m]; requiredBit = new BigInteger[m];
            int[] outCount = new int[n], inCount = new int[n + 1];
            ArrayList<Integer> requiredList = new ArrayList<>();
            boolean nonnegativePhysical = true;
            for (int e = 0; e < m; e++) {
                cost[e] = costScale.encode(edgeCost[e]);
                time[e] = timeScale.encode(edgeTime[e]);
                if (required[e]) {
                    requiredBit[e] = BigInteger.ONE.shiftLeft(requiredList.size());
                    requiredList.add(e);
                    if (!allowed[e]) contradiction = true;
                } else requiredBit[e] = BigInteger.ZERO;
                if (allowed[e]) {
                    outCount[u[e]]++; inCount[v[e]]++;
                    if (v[e] != n && cost[e].signum() < 0) nonnegativePhysical = false;
                }
                if ((e & 255) == 0) checkTime();
            }
            allRequired = BigInteger.ONE.shiftLeft(requiredList.size()).subtract(BigInteger.ONE);
            requiredIndices = requiredList.stream().mapToInt(Integer::intValue).toArray();
            outgoing = new int[n][]; incoming = new int[n + 1][];
            for (int i = 0; i < n; i++) outgoing[i] = new int[outCount[i]];
            for (int i = 0; i <= n; i++) incoming[i] = new int[inCount[i]];
            Arrays.fill(outCount, 0); Arrays.fill(inCount, 0);
            for (int e = 0; e < m; e++) if (allowed[e]) {
                outgoing[u[e]][outCount[u[e]]++] = e;
                incoming[v[e]][inCount[v[e]]++] = e;
            }
            checkTime();
            if (contradiction) return;
            minTime = reverseDistances(time, false);
            if (nonnegativePhysical) {
                // Terminal costs may be negative: seed all terminal predecessors
                // first, then propagate only nonnegative physical arc weights.
                nonnegativeCostBound = reverseDistances(cost, true);
            } else {
                // A simple suffix can use each negative physical arc at most
                // once. Its nonnegative physical-cost part is bounded by a
                // reverse shortest path with original terminal costs retained.
                // Together with a superset of available negative physical arcs,
                // this supplies a second bound that cannot reuse a negative cycle.
                BigInteger[] positivePhysical = cost.clone();
                ArrayList<Integer> negativePhysical = new ArrayList<>();
                for (int edge = 0; edge < m; edge++) {
                    if (allowed[edge] && v[edge] != n && cost[edge].signum() < 0) {
                        positivePhysical[edge] = BigInteger.ZERO;
                        negativePhysical.add(edge);
                    }
                }
                negativePhysicalIndices = negativePhysical.stream().mapToInt(Integer::intValue).toArray();
                if (options.useDegreeNegativeBound) buildNegativeAdjacency();
                positivePhysicalCostBound = reverseDistances(positivePhysical, true);
                // null denotes +infinity. The finite hop budget makes negative
                // cycles harmless and relaxes time/visited/required/exclusions.
                hopBound = new BigInteger[n + 1][n + 1];
                hopBound[0][n] = BigInteger.ZERO;
                for (int h = 1; h <= n; h++) {
                    checkTime();
                    hopBound[h] = hopBound[h - 1].clone();
                    for (int e = 0; e < m; e++) if (allowed[e] && hopBound[h - 1][v[e]] != null) {
                        BigInteger next = cost[e].add(hopBound[h - 1][v[e]]);
                        if (hopBound[h][u[e]] == null || next.compareTo(hopBound[h][u[e]]) < 0)
                            hopBound[h][u[e]] = next;
                        if ((e & 255) == 0) checkTime();
                    }
                }
            }
            initialBound = suffixBound(source, 1, Visited.root(source, options.useCompactDominance && n <= 128));
            searchStarted = System.nanoTime();
            result.preprocessingSeconds = (searchStarted - started) / 1e9;
        }

        int[] canonical(int[] edges) {
            if (edges == null || edges.length == 0 || edges.length > n)
                throw new IllegalArgumentException("Excluded path must contain 1..n arcs including terminal");
            int[] next = new int[n]; Arrays.fill(next, -1);
            for (int edge : edges) {
                if (edge < 0 || edge >= u.length || next[u[edge]] != -1)
                    throw new IllegalArgumentException("Invalid/duplicate/branching excluded path");
                next[u[edge]] = edge;
            }
            boolean[] visited = new boolean[n];
            int[] ordered = new int[edges.length];
            int node = source, length = 0;
            while (node != n) {
                if (visited[node] || next[node] < 0 || length >= edges.length)
                    throw new IllegalArgumentException("Disconnected or cyclic excluded path");
                visited[node] = true;
                int edge = next[node]; ordered[length++] = edge; node = v[edge];
            }
            if (length != edges.length) throw new IllegalArgumentException("Excluded path has leftover edges");
            return ordered;
        }

        BigInteger[] reverseDistances(BigInteger[] weights, boolean terminalMayBeNegative) {
            BigInteger[] distance = new BigInteger[n + 1];
            distance[n] = BigInteger.ZERO;
            PriorityQueue<Distance> pending = new PriorityQueue<>(Comparator
                    .comparing((Distance item) -> item.value).thenComparingInt(item -> item.node));
            if (terminalMayBeNegative) {
                for (int edge : incoming[n]) {
                    int node = u[edge]; BigInteger candidate = weights[edge];
                    if (distance[node] == null || candidate.compareTo(distance[node]) < 0) {
                        distance[node] = candidate;
                        pending.add(new Distance(node, candidate));
                    }
                }
            } else pending.add(new Distance(n, BigInteger.ZERO));
            while (!pending.isEmpty()) {
                checkTime();
                Distance item = pending.poll();
                if (!item.value.equals(distance[item.node])) continue;
                for (int edge : incoming[item.node]) {
                    BigInteger candidate = item.value.add(weights[edge]);
                    int node = u[edge];
                    if (distance[node] == null || candidate.compareTo(distance[node]) < 0) {
                        distance[node] = candidate;
                        pending.add(new Distance(node, candidate));
                    }
                }
            }
            return distance;
        }

        void buildNegativeAdjacency() {
            ArrayList<Integer>[] out = new ArrayList[n], in = new ArrayList[n];
            for (int node = 0; node < n; node++) { out[node] = new ArrayList<>(); in[node] = new ArrayList<>(); }
            for (int edge : negativePhysicalIndices) { out[u[edge]].add(edge); in[v[edge]].add(edge); }
            negativeOutgoing = new int[n][]; negativeIncoming = new int[n][];
            Comparator<Integer> order = (a, b) -> {
                int c = cost[a].compareTo(cost[b]); return c == 0 ? Integer.compare(a, b) : c;
            };
            for (int node = 0; node < n; node++) {
                out[node].sort(order); in[node].sort(order);
                negativeOutgoing[node] = out[node].stream().mapToInt(Integer::intValue).toArray();
                negativeIncoming[node] = in[node].stream().mapToInt(Integer::intValue).toArray();
            }
        }

        BigInteger suffixBound(int node, int visitedCount, Visited visited) {
            if (nonnegativeCostBound != null) return nonnegativeCostBound[node];
            BigInteger hop = hopBound[n - visitedCount + 1][node];
            BigInteger positive = positivePhysicalCostBound[node];
            if (hop == null || positive == null) return null;
            result.negativeArcBoundChecks++;
            BigInteger eligibleNegativeSum;
            if (options.useDegreeNegativeBound) {
                // A simple completion has at most one outgoing and one incoming
                // physical edge at each vertex. Each sum of per-vertex negative
                // minima is therefore <= its used negative costs. Taking their
                // maximum is valid and at least as strong as summing every arc.
                // Sorted sparse adjacency stops at the first eligible minimum.
                BigInteger outSum = BigInteger.ZERO, inSum = BigInteger.ZERO;
                for (int vertex = 0; vertex < n; vertex++) {
                    if ((vertex & 31) == 0) checkTime();
                    if (vertex == node || !visited.contains(vertex)) {
                        for (int edge : negativeOutgoing[vertex]) {
                            result.negativeAdjacencyProbes++;
                            if (!visited.contains(v[edge])) { outSum = outSum.add(cost[edge]); break; }
                        }
                    }
                    if (!visited.contains(vertex)) {
                        for (int edge : negativeIncoming[vertex]) {
                            result.negativeAdjacencyProbes++;
                            if (u[edge] == node || !visited.contains(u[edge])) { inSum = inSum.add(cost[edge]); break; }
                        }
                    }
                }
                eligibleNegativeSum = outSum.max(inSum);
            } else {
                eligibleNegativeSum = BigInteger.ZERO;
                for (int edge : negativePhysicalIndices) {
                    result.negativeAdjacencyProbes++;
                    if ((u[edge] == node || !visited.contains(u[edge])) && !visited.contains(v[edge]))
                        eligibleNegativeSum = eligibleNegativeSum.add(cost[edge]);
                }
            }
            BigInteger oncePerArc = positive.add(eligibleNegativeSum);
            if (oncePerArc.compareTo(hop) > 0) {
                result.negativeArcBoundImprovements++;
                return oncePerArc;
            }
            return hop;
        }

        /** Reference counts cover queue, dominance buckets, current expansion,
         * incumbent, and the transitive parent closure, without double counting. */
        void retain(Label label) {
            if (label == null) return;
            if (label.references == 0) {
                if (result.retainedLabels >= options.maxStoredLabels) reclaimInactiveQueue();
                if (result.retainedLabels >= options.maxStoredLabels)
                    throw new Stop("UNKNOWN_MEMORY_LABEL_LIMIT");
                // Every new candidate's parent is the retained current expansion;
                // existing retained labels already retain their own ancestors.
                if (label.parent != null && label.parent.references == 0)
                    throw new IllegalStateException("Unretained label ancestor");
                result.retainedLabels++;
                result.peakRetainedLabels = Math.max(result.peakRetainedLabels, result.retainedLabels);
                if (label.parent != null) label.parent.references++;
            }
            label.references++;
        }

        void reclaimInactiveQueue() {
            Iterator<Label> iterator = queue.iterator();
            while (iterator.hasNext()) {
                Label label = iterator.next();
                if (!label.active) {
                    iterator.remove(); release(label); result.inactiveQueueLabelsReclaimed++;
                }
            }
        }

        void release(Label label) {
            while (label != null) {
                if (label.references <= 0) throw new IllegalStateException("Label reference underflow");
                if (--label.references != 0) return;
                result.retainedLabels--;
                label = label.parent;
            }
        }

        void discardInactiveQueueHeads() {
            while (!queue.isEmpty() && !queue.peek().active) release(queue.poll());
        }

        void setIncumbent(Label label) {
            retain(label);
            Label previous = incumbent; incumbent = label;
            release(previous);
        }

        void collectNegative(Label terminal) {
            if (!options.stopAfterNegative || terminal.cost.compareTo(threshold) >= 0) return;
            int[] edges = reconstruct(terminal);
            for (int[] previous : result.negativePathEdges) if (Arrays.equals(previous, edges)) return;
            result.negativePathEdges.add(edges);
            result.negativeObjectives.add(costScale.up(terminal.cost));
        }

        int trieStep(int q, int edge) {
            return q < 0 ? -1 : trie.get(q).children.getOrDefault(edge, -1);
        }

        Result search() {
            if (contradiction) return finish("INFEASIBLE_REQUIRED_FORBIDDEN", true, false);
            if (initialBound == null || minTime[source] == null || minTime[source].compareTo(window) > 0)
                return finish("INFEASIBLE_REACHABILITY", true, false);
            Label root = new Label(source, 0, 1, Visited.root(source, options.useCompactDominance && n <= 128), BigInteger.ZERO,
                    BigInteger.ZERO, BigInteger.ZERO, initialBound, null, -1, sequence++);
            retain(root); queue.add(root);
            ArrayList<Label> rootBucket = new ArrayList<>(); rootBucket.add(root);
            retain(root);
            buckets.put(new StateKey(source, 0, BigInteger.ZERO), rootBucket);
            frontierReady = true;
            result.labelsGenerated = result.peakPending = 1;
            while (true) {
                discardInactiveQueueHeads();
                if (queue.isEmpty() && !result.negativePathEdges.isEmpty()) return finish("FOUND_COLUMNS", false, false);
                if (queue.isEmpty()) return finish(incumbent == null ? "INFEASIBLE_EXHAUSTED"
                        : "OPTIMAL_EXHAUSTED_OR_BOUND", incumbent == null, incumbent != null);
                if ((!options.stopAfterNegative || result.negativePathEdges.isEmpty()) && incumbent != null
                        && queue.peek().bound.compareTo(incumbent.cost) >= 0)
                    return finish("OPTIMAL_EXHAUSTED_OR_BOUND", false, true);
                // At this loop boundary no label is partially expanded. The
                // incumbent and the live frontier cover every possible optimum;
                // their minimum is a valid global bound, including negative
                // values above the threshold. No threshold-pruned regions are
                // removed, and finish preserves this actual bound rather than 0.
                if (options.stopAfterNegative) {
                    BigInteger frontierBound = queue.peek().bound;
                    if (frontierBound.compareTo(threshold) >= 0)
                        return finish(result.negativePathEdges.isEmpty() ? "NO_NEGATIVE_CERTIFIED" : "FOUND_COLUMNS", false, false);
                }
                checkTime();
                if (result.labelsExpanded >= options.maxLabels) throw new Stop("UNKNOWN_LABEL_LIMIT");
                Label label = queue.poll();
                retain(label); // Transfer the queue reference into the partial expansion.
                partial = label; // Retained until ALL its outgoing arcs have been processed.
                release(label);
                result.labelsExpanded++;
                for (int edge : outgoing[label.node]) {
                    checkTime();
                    int next = v[edge];
                    if (next != n && label.visited.contains(next)) continue;
                    BigInteger nextTime = label.time.add(time[edge]);
                    if (nextTime.compareTo(window) > 0 || minTime[next] == null
                            || nextTime.add(minTime[next]).compareTo(window) > 0) {
                        result.timePruned++; continue;
                    }
                    BigInteger nextCost = label.cost.add(cost[edge]);
                    BigInteger nextRequired = requiredBit[edge].signum() == 0 ? label.required : label.required.or(requiredBit[edge]);
                    int nextTrie = trieStep(label.trie, edge);
                    if (next == n) {
                        if (!nextRequired.equals(allRequired)) { result.requiredPruned++; continue; }
                        if (nextTrie >= 0 && trie.get(nextTrie).terminal) { result.excludedTerminals++; continue; }
                        boolean improves = incumbent == null || nextCost.compareTo(incumbent.cost) < 0;
                        boolean negative = options.stopAfterNegative && nextCost.compareTo(threshold) < 0;
                        if (improves || negative) {
                            Label terminal = new Label(n, nextTrie, label.visitedCount, label.visited,
                                    nextRequired, nextCost, nextTime, nextCost, label, edge, sequence++);
                            if (improves) setIncumbent(terminal);
                            if (negative) collectNegative(terminal);
                            if (result.negativePathEdges.size() >= options.maxColumns)
                                return finish("FOUND_COLUMNS", false, false);
                        }
                        continue;
                    }
                    Visited visited = label.visited.add(next);
                    if (requiredImpossible(next, visited, nextRequired)) { result.requiredPruned++; continue; }
                    BigInteger suffix = suffixBound(next, label.visitedCount + 1, visited);
                    if (suffix == null) { result.boundPruned++; continue; }
                    BigInteger bound = nextCost.add(suffix);
                    if ((!options.stopAfterNegative || result.negativePathEdges.isEmpty()) && incumbent != null
                            && bound.compareTo(incumbent.cost) >= 0) { result.boundPruned++; continue; }
                    Label candidate = new Label(next, nextTrie, label.visitedCount + 1, visited,
                            nextRequired, nextCost, nextTime, bound, label, edge, sequence++);
                    result.labelsGenerated++;
                    if (insert(candidate)) {
                        retain(candidate);
                        queue.add(candidate);
                        result.peakPending = Math.max(result.peakPending, queue.size());
                    }
                }
                partial = null; release(label);
            }
        }

        boolean requiredImpossible(int node, Visited visited, BigInteger completed) {
            for (int edge : requiredIndices) if (completed.and(requiredBit[edge]).signum() == 0) {
                if ((u[edge] != node && visited.contains(u[edge]))
                        || (v[edge] != n && visited.contains(v[edge]))) return true;
            }
            return false;
        }

        boolean insert(Label candidate) {
            StateKey key = new StateKey(candidate.node, candidate.trie, candidate.required);
            ArrayList<Label> bucket = buckets.computeIfAbsent(key, unused -> new ArrayList<>());
            if (!options.useCompactDominance) {
                for (Label other : bucket) if (other.active && dominates(other, candidate)) {
                    result.labelsDominated++; return false;
                }
                boolean changed = false;
                try {
                    for (Label other : bucket) if (other.active && dominates(candidate, other)) {
                        other.active = false; changed = true; result.labelsDominated++;
                    }
                } finally { if (changed) compactBucket(bucket); }
            } else {
                boolean changed = false;
                // Arbitrary-size BigInteger visited sets retain the established
                // resource-first order. This optimization uses only two-word sets.
                boolean geometryFirst = options.useGeometryFirstDominance && n <= 128;
                VisitedSetIndex index = geometryFirst && options.useVisitedSetIndex
                        && bucket.size() >= options.visitedSetIndexThreshold ? visitedIndexFor(bucket) : null;
                BitSet matches = index == null ? null : queryVisitedIndex(index, candidate.visited);
                try {
                    // Bitmap IDs preserve the original insertion order, including ties
                    // and reverse removals before a later old label rejects this one.
                    for (int id = matches == null ? 0 : matches.nextSetBit(0);
                         id >= 0 && id < bucket.size();
                         id = matches == null ? id + 1 : matches.nextSetBit(id + 1)) {
                        Label other = bucket.get(id);
                        touchDominance();
                        result.dominancePairVisits++;
                        boolean otherSetPossible = other.visitedCount <= candidate.visitedCount;
                        boolean candidateSetPossible = candidate.visitedCount <= other.visitedCount;
                        if (geometryFirst) {
                            result.geometryFirstPairChecks++;
                            boolean compatible;
                            if (other.visitedCount < candidate.visitedCount) compatible = subset(other, candidate);
                            else if (other.visitedCount > candidate.visitedCount) compatible = subset(candidate, other);
                            else {
                                // Equal cardinality admits inclusion only for identical sets.
                                result.visitedSubsetChecks++;
                                compatible = other.visited.low == candidate.visited.low
                                        && other.visited.high == candidate.visited.high;
                            }
                            if (!compatible) {
                                result.geometryFirstRejectedPairs++;
                                // Keep both directional checks in the clock cadence,
                                // even when no expensive resource comparison is needed.
                                touchDominance();
                                continue;
                            }
                        }
                        // Exact cost/time tests are unchanged. Geometric screens
                        // establish the only possible direction(s), never dominance.
                        int cc = other.cost.compareTo(candidate.cost);
                        result.dominanceCostComparisons++;
                        boolean otherPossible = otherSetPossible && cc <= 0;
                        boolean candidatePossible = candidateSetPossible && cc >= 0;
                        int tc = 0;
                        if (otherPossible || candidatePossible) {
                            result.dominanceTimeComparisons++;
                            tc = other.time.compareTo(candidate.time);
                        }
                        if (otherPossible && tc <= 0 && (geometryFirst || subset(other, candidate))) {
                            result.labelsDominated++; return false;
                        }
                        touchDominance();
                        if (candidatePossible && tc >= 0 && (geometryFirst || subset(candidate, other))) {
                            other.active = false; changed = true; result.labelsDominated++;
                        }
                    }
                } finally {
                    // If a later label dominates the candidate, transitivity
                    // covers already removed labels. If interrupted, partial's
                    // bound covers this candidate and all labels it dominates.
                    if (changed) compactBucket(bucket);
                }
            }
            retain(candidate); // Actual bucket retention, not cumulative creation.
            bucket.add(candidate);
            VisitedSetIndex index = visitedIndexes.get(bucket);
            if (index != null) index.add(bucket.size() - 1, candidate.visited);
            return true;
        }

        VisitedSetIndex visitedIndexFor(ArrayList<Label> bucket) {
            VisitedSetIndex previous = visitedIndexes.get(bucket);
            if (previous != null && previous.capacity > bucket.size()) return previous;
            checkTime();
            // Reserve one insertion as well; BitSet never grows beyond the charged capacity.
            int capacity = 64;
            while (capacity <= bucket.size()) {
                if (capacity > (1 << 29)) {
                    dropVisitedIndex(bucket); result.visitedIndexBudgetFallbacks++; return null;
                }
                capacity *= 2;
            }
            long words = (n + 3L) * ((capacity + 63L) / 64L);
            long previousWords = previous == null ? 0 : previous.words;
            if (words > options.maxVisitedIndexWords - (result.visitedIndexWords - previousWords)) {
                dropVisitedIndex(bucket); result.visitedIndexBudgetFallbacks++; return null;
            }
            VisitedSetIndex next = new VisitedSetIndex(this, capacity);
            for (int id = 0; id < bucket.size(); id++) {
                if ((id & 63) == 0) checkTime();
                Label label = bucket.get(id);
                if (!label.active) throw new IllegalStateException("Inactive label in compact index bucket");
                next.add(id, label.visited);
            }
            checkTime();
            // Publish only a complete index. Interruption leaves the previous one intact.
            visitedIndexes.put(bucket, next);
            result.visitedIndexWords += words - previousWords;
            result.peakVisitedIndexWords = Math.max(result.peakVisitedIndexWords, result.visitedIndexWords);
            result.visitedIndexBuilds++;
            return next;
        }

        BitSet queryVisitedIndex(VisitedSetIndex index, Visited visited) {
            checkTime();
            result.visitedIndexQueries++;
            BitSet subsets = index.subsets, supersets = index.supersets;
            subsets.clear(); subsets.or(index.active);
            supersets.clear(); supersets.or(index.active);
            // oldVisited subset candidateVisited: remove every old label containing
            // a node outside candidateVisited. The reverse uses an intersection.
            for (int node = 0; node < n; node++) {
                checkTime();
                if (visited.contains(node)) {
                    if (!supersets.isEmpty()) {
                        supersets.and(index.containing[node]); result.visitedIndexPostingOperations++;
                    }
                } else if (!subsets.isEmpty()) {
                    subsets.andNot(index.containing[node]); result.visitedIndexPostingOperations++;
                }
                if (subsets.isEmpty() && supersets.isEmpty()) break;
            }
            subsets.or(supersets);
            long candidates = subsets.cardinality();
            result.visitedIndexCandidates += candidates;
            result.visitedIndexSkippedPairs += index.active.cardinality() - candidates;
            checkTime();
            return subsets;
        }

        void dropVisitedIndex(ArrayList<Label> bucket) {
            VisitedSetIndex removed = visitedIndexes.remove(bucket);
            if (removed != null) result.visitedIndexWords -= removed.words;
        }

        void compactBucket(ArrayList<Label> bucket) {
            // Compaction changes IDs. Drop even when insertion is rejected or interrupted.
            dropVisitedIndex(bucket);
            int retained = 0;
            for (int i = 0, size = bucket.size(); i < size; i++) {
                Label other = bucket.get(i);
                if (other.active) bucket.set(retained++, other);
                else release(other);
            }
            if (retained < bucket.size()) bucket.subList(retained, bucket.size()).clear();
        }

        void touchDominance() {
            result.dominanceChecks++;
            if ((result.dominanceChecks & 4095L) == 0L) checkTime();
        }

        boolean subset(Label a, Label b) {
            result.visitedSubsetChecks++;
            return a.visited.subsetOf(b.visited);
        }

        boolean dominates(Label a, Label b) {
            touchDominance(); result.dominanceCostComparisons++;
            if (a.cost.compareTo(b.cost) > 0) return false;
            result.dominanceTimeComparisons++;
            return a.time.compareTo(b.time) <= 0 && subset(a, b);
        }

        Result finish(String status, boolean infeasible, boolean optimal) {
            result.status = status; result.infeasible = infeasible; result.optimal = optimal;
            if (incumbent != null) {
                result.pathEdges = reconstruct(incumbent);
                result.objective = costScale.up(incumbent.cost);
            }
            if (infeasible) result.lowerBound = Double.POSITIVE_INFINITY;
            else if (optimal) result.lowerBound = costScale.down(incumbent.cost);
            else if (frontierReady) {
                BigInteger bound = incumbent == null ? null : incumbent.cost;
                if (partial != null) bound = minimum(bound, partial.bound);
                for (Label label : queue) if (label.active) bound = minimum(bound, label.bound);
                result.lowerBound = bound == null ? Double.POSITIVE_INFINITY : costScale.down(bound);
            }
            recordTimes();
            return result;
        }

        void recordTimes() {
            long now = System.nanoTime();
            if (searchStarted == 0) result.preprocessingSeconds = (now - started) / 1e9;
            else result.searchSeconds = (now - searchStarted) / 1e9;
        }

        int[] reconstruct(Label terminal) {
            int count = 0;
            for (Label label = terminal; label.parent != null; label = label.parent) count++;
            int[] edges = new int[count];
            Label label = terminal;
            for (int i = count - 1; i >= 0; i--) { edges[i] = label.edge; label = label.parent; }
            return edges;
        }
    }

    private static BigInteger minimum(BigInteger a, BigInteger b) {
        return a == null || b.compareTo(a) < 0 ? b : a;
    }
}

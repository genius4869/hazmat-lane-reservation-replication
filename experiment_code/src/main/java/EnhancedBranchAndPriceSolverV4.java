import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloLinearNumExprIterator;
import ilog.concert.IloColumn;
import ilog.concert.IloNumVar;
import ilog.concert.IloObjective;
import ilog.concert.IloRange;
import ilog.cplex.IloCplex;

import java.math.BigInteger;
import java.util.*;

/**
 * enhanced_bpa_v4.
 *
 * Source-facility assignment, y-branching, initial paths, carrier multi-cuts,
 * elastic Phase I, and frontier-based bounds are retained. Missing-column
 * pricing uses an exact elementary label algorithm, without a pricing MILP.
 *
 * A solve may retain one incremental CPLEX LP across branch-and-price nodes.
 * Columns and globally valid cuts remain installed; branch rows are activated
 * for the current node only. Phase I and integer-polish state are restored on
 * every switch. CPLEX may reuse its current basis; no saved node basis is loaded.
 *
 * Exact labels retain required-arc progress and excluded-path prefix identity,
 * as well as time, cost and visited nodes. Bounded-hop relaxed suffix costs
 * give valid bounds even with negative reduced costs. A legal negative column
 * can return early; unfinished searches cannot certify absence of columns.
 *
 * Reduced costs are still validated against CPLEX for all generated lambda
 * columns before any pricing conclusion is accepted. Incremental carrier cuts
 * add a row for the named selected government path only; future columns receive
 * a carrier-cut coefficient only when they exactly match that selected path key.
 */
public class EnhancedBranchAndPriceSolverV4 {
    public static final String ALGORITHM_VERSION = "enhanced_bpa_v4_persistent_rmp_nonredundant";

    /** Search controls only: a bounded quick pass never supplies an unproved node bound. */
    public static class PricingSchedule {
        public boolean useQuickPass = true;
        public long quickMaxLabels = 2000;
        public double quickTimeSeconds = 0.05;
        public int roundColumnLimit = 32;
    }

    /** Matrix lifetime only; pricing, branching and certificate rules are unchanged. */
    public static class RmpReuseOptions {
        public boolean usePersistentRmp = true;
    }

    public static class Result {
        public String algorithmVersion = ALGORITHM_VERSION;
        public boolean solved;
        public boolean optimal;
        public boolean globalBoundValid = true;
        public boolean optimalityCertified;
        public boolean noUnusedReservationsEnforced = true;
        public String terminationReason = "NOT_STARTED";
        public int unresolvedNodes;
        public int pendingNodes;
        public int nodesInfeasibleCertified;
        public int pricingCertificationCalls;
        public int pricingQuickPassCalls;
        public int pricingUnknownCalls;
        public int pricingQuickUnknownCalls;
        public int pricingCertificationUnknownCalls;
        public int pricingQuickCertifiedReuses;
        public int pricingCompleteDualRounds;
        public int pricingColumnsFromInterruptedCalls;
        public boolean pricingQuickPassEnabled;
        public long pricingQuickLabelLimit;
        public double pricingQuickTimeLimitSeconds;
        public int pricingRoundColumnLimit;
        public int pricingMaxColumnsPerCall;
        public int observedMaxColumnsPerCall;
        public int pricingRoundColumnLimitStops;
        public int pricingIncompleteCalls;
        public String pricingOracle = "EXACT_LABEL";
        public int exactLabelPricingCalls;
        public int exactLabelEarlyColumnReturns;
        public int exactLabelNoNegativeCertificates;
        public int milpPricingCalls;
        public double exactLabelPreprocessingSeconds;
        public double exactLabelSearchSeconds;
        public long exactLabelPeakPending;
        public long exactLabelPeakRetained;
        public long dominanceCostComparisons;
        public long dominancePairVisits;
        public long dominanceTimeComparisons;
        public long geometryFirstPairChecks;
        public long geometryFirstRejectedPairs;
        public long visitedIndexQueries;
        public long visitedIndexBuilds;
        public long visitedIndexCandidates;
        public long visitedIndexSkippedPairs;
        public long visitedIndexPostingOperations;
        public long visitedIndexBudgetFallbacks;
        public long peakVisitedIndexWords;
        public long visitedSubsetChecks;
        public long negativeAdjacencyProbes;
        public long negativeArcBoundChecks;
        public long negativeArcBoundImprovements;
        public double longestExactLabelCallSeconds;
        public ArrayList<String> slowPricingCalls = new ArrayList<>();
        public long heuristicPricingTruncations;
        public int heuristicPricingLabelLimit;
        public int heuristicPricingTimeLimitMillis;
        public int phaseOneIterations;
        public double absoluteOptimalityTolerance = 1e-4;
        public double relativeGap = Double.POSITIVE_INFINITY;
        public double closedLeafLowerBound = Double.POSITIVE_INFINITY;
        public ArrayList<Double> pendingNodeBounds = new ArrayList<>();
        public ArrayList<String> nodeAudit = new ArrayList<>();
        public boolean rootIntegral;
        public boolean rootVerified;
        public double rootObjective = Double.NaN;
        public double rootLowerBound = Double.NaN;
        public int rootColumns;
        public int rootCgIterations;
        public long rootPricingTimeMillis;
        public double bestObjective = Double.POSITIVE_INFINITY;
        public double bestLowerBound = Double.NEGATIVE_INFINITY;
        public double gap = Double.POSITIVE_INFINITY;
        public int nodesProcessed;
        public int nodesPruned;
        public int cgIterations;
        public int initialColumns;
        public int carrierCuts;
        public int carrierColumns;
        public int pricingRoundsWithMultiColumns;
        public int maxColumnsPerPricingRound;
        public int pricingColumns;
        public int pathCount;
        public int yBranchCount;
        public int usageBranchCount;
        public int zBranchCount;
        public int rootFractionalYCount;
        public int rootFractionalUsageCount;
        public long labelsExpanded;
        public long labelsGenerated;
        public long labelsPrunedByDominance;
        public long labelsPrunedByTimeReachability;
        public long labelsPrunedByGlobalReducedCostBound;
        public long globalReducedCostBoundChecks;
        public long dominanceChecks;
        public long solveTimeMillis;
        public boolean basisReuseEnabled = true;
        public int incrementalLpSolves;
        public int rmpRebuilds;
        public boolean persistentRmpEnabled;
        public boolean explicitCrossNodeBasisRestore;
        public int rmpNodeSwitches;
        public int rmpBranchRowsCreated;
        public int rmpPeakBranchRows;
        public int rmpGlobalColumnsSynchronized;
        public int rmpGlobalCutsSynchronized;
        public int rmpPolishBoundsRestored;
        public long rmpNodeSwitchTimeMillis;
        public long firstIncumbentTimeMillis = -1;
        public int columnsAddedIncrementally;
        public int carrierCutsAddedIncrementally;
        public long rmpBuildTimeMillis;
        public long rmpSolveTimeMillis;
        public long pricingTimeMillis;
        public long carrierCheckTimeMillis;
        public String selectedRoadEdges = "";
        public String selectedRouteEdges = "";
        /** All opened facility nodes, read from the incumbent virtual-arc design. */
        public String selectedFacilityNodes = "";
        /** Ordered physical nodes by task; a local-disposal route has one node. */
        public List<int[]> selectedRouteNodes = new ArrayList<>();
        public String message = "";
    }

    private static class Edge {
        final int u;
        final int v;
        final boolean virtual;
        final int candidateIndex;
        Edge(int u, int v, boolean virtual, int candidateIndex) {
            this.u = u;
            this.v = v;
            this.virtual = virtual;
            this.candidateIndex = candidateIndex;
        }
    }

    private static class Path {
        final int sourceIndex;
        final int sourceNode;
        final ArrayList<Integer> edgeIndices;
        final ArrayList<Integer> nodePath;
        final double travelCost;
        final double reducedCost;
        final double time;
        final int targetFacilityNode;
        final int targetFacilityIndex;
        final String key;
        Path(int sourceIndex, int sourceNode, ArrayList<Integer> edgeIndices, ArrayList<Integer> nodePath, double travelCost, double reducedCost, double time, int targetFacilityNode, int targetFacilityIndex) {
            this.sourceIndex = sourceIndex;
            this.sourceNode = sourceNode;
            this.edgeIndices = edgeIndices;
            this.nodePath = nodePath;
            this.travelCost = travelCost;
            this.reducedCost = reducedCost;
            this.time = time;
            this.targetFacilityNode = targetFacilityNode;
            this.targetFacilityIndex = targetFacilityIndex;
            this.key = edgeIndices.toString();
        }
        boolean uses(int e) { return edgeIndices.contains(e); }
    }

    private static class Label {
        final int node;
        final BigInteger visitedMask;
        final double cost;
        final double travelCost;
        final double time;
        final Label prev;
        final int edgeFromPrev;
        Label(int node, BigInteger visitedMask, double cost, double travelCost, double time, Label prev, int edgeFromPrev) {
            this.node = node;
            this.visitedMask = visitedMask;
            this.cost = cost;
            this.travelCost = travelCost;
            this.time = time;
            this.prev = prev;
            this.edgeFromPrev = edgeFromPrev;
        }
    }

    private static class PricingStats {
        long heuristicTruncations;
        long labelsExpanded;
        long labelsGenerated;
        long labelsPrunedByDominance;
        long labelsPrunedByTimeReachability;
        long labelsPrunedByGlobalReducedCostBound;
        long globalReducedCostBoundChecks;
        long dominanceChecks;
        void addTo(Result result) {
            result.heuristicPricingTruncations += heuristicTruncations;
            result.labelsExpanded += labelsExpanded;
            result.labelsGenerated += labelsGenerated;
            result.labelsPrunedByDominance += labelsPrunedByDominance;
            result.labelsPrunedByTimeReachability += labelsPrunedByTimeReachability;
            result.labelsPrunedByGlobalReducedCostBound += labelsPrunedByGlobalReducedCostBound;
            result.globalReducedCostBoundChecks += globalReducedCostBoundChecks;
            result.dominanceChecks += dominanceChecks;
        }
    }

    private static class BranchNode {
        final HashMap<Integer, Integer> fixedZ = new HashMap<>();
        final HashMap<String, Integer> fixedUsage = new HashMap<>();
        final HashMap<String, Integer> fixedAssignment = new HashMap<>();
        int depth;
        long id;
        double certifiedBound = Double.NEGATIVE_INFINITY;
        BranchNode copy() {
            BranchNode n = new BranchNode();
            n.fixedZ.putAll(fixedZ);
            n.fixedUsage.putAll(fixedUsage);
            n.fixedAssignment.putAll(fixedAssignment);
            n.depth = depth + 1;
            n.certifiedBound = certifiedBound;
            return n;
        }
    }

    private static class PathOptimalityCut {
        final int sourceIndex;
        final String selectedPathKey;
        final HashSet<Integer> cheaperPathExtraEdges;
        PathOptimalityCut(int sourceIndex, String selectedPathKey, HashSet<Integer> cheaperPathExtraEdges) {
            this.sourceIndex = sourceIndex;
            this.selectedPathKey = selectedPathKey;
            this.cheaperPathExtraEdges = cheaperPathExtraEdges;
        }
        String signature() {
            return sourceIndex + "|" + selectedPathKey + "|" + cheaperPathExtraEdges;
        }
    }

    private static class RmpSolution {
        boolean solved;
        boolean phaseOne;
        String status;
        IncrementalRmpModel model;
        double objective;
        boolean integral = true;
        double[] zValues;
        double[][] yValues;
        double[][] lambdaValues;
        double[] lambdaReducedCostsFlat;
        IloCplex cplex;
        IloRange[] chooseRows;
        IloRange[][] assignmentRows;
        IloRange[][] assignmentOpenRows;
        IloRange[][] useRows;
        IloRange[] reservationUsageRows;
        IloRange[][] dualWRows;
        IloRange[] dualURows;
        IloRange[] dualVRows;
        IloRange[] carrierCutRows;
        HashMap<String, IloRange> branchUsageRows;
        double[] chooseDuals;
        double[][] assignmentDuals;
        double[][] useDuals;
        double[] reservationUsageDuals;
        double[][] dualWDuals;
        double[] dualUDuals;
        double[] dualVDuals;
        double[] carrierCutDuals;
        HashMap<String, Double> branchUsageDuals;
    }

    private static class IncrementalRmpModel {
        IloCplex cplex;
        IloObjective objective;
        IloNumVar[] z;
        IloNumVar[][] y;
        ArrayList<ArrayList<IloNumVar>> lambdaVars = new ArrayList<>();
        IloRange[] chooseRows;
        IloRange[][] assignmentRows;
        IloRange[][] assignmentOpenRows;
        IloRange[][] useRows;
        IloRange[] reservationUsageRows;
        IloRange[][] dualWRows;
        IloRange[] dualURows;
        IloRange[] dualVRows;
        IloRange[] carrierCutRows;
        HashMap<String, IloRange> branchUsageRows = new HashMap<>();
        HashMap<Integer, IloRange> branchZRows = new HashMap<>();
        HashMap<String, IloRange> branchAssignmentRows = new HashMap<>();
        HashSet<Integer> activeBranchZ = new HashSet<>();
        HashSet<String> activeBranchAssignment = new HashSet<>();
        HashSet<String> activeBranchUsage = new HashSet<>();
        boolean polishedBounds;
        boolean phaseOne;
        final ArrayList<IloNumVar> artificials = new ArrayList<>();
        final HashMap<IloRange, IloNumVar[]> elasticVariables = new HashMap<>();
        final LinkedHashMap<IloNumVar, Double> originalCosts = new LinkedHashMap<>();
    }

    private static class NodeSolution {
        boolean infeasibleCertified;
        boolean feasible;
        boolean pricingConverged;
        boolean reducedCostOk;
        boolean integral;
        double lowerBound = Double.NEGATIVE_INFINITY;
        RmpSolution lp;
        String message = "";
    }

    private static class ReducedCostMode {
        final boolean ok;
        final double sign;
        final String message;
        ReducedCostMode(boolean ok, double sign, String message) {
            this.ok = ok;
            this.sign = sign;
            this.message = message;
        }
    }

    private final DataCreator data;
    private final double eps;
    private final int initialPathsPerSourceFacility;
    private final int pricingColumnsPerSource;
    private final int carrierCutsPerSource;
    private final int maxCgIterations;
    private final int maxNodes;
    private final int maxLabelsPerPricing;
    private final PricingSchedule pricingSchedule;
    private final RmpReuseOptions rmpReuseOptions;
    private boolean reuseSessionActive;
    private IncrementalRmpModel persistentRmp;
    private final double timeLimitSeconds;
    private final int nodeCount;
    private final int virtualSink;
    private final ArrayList<Edge> edges = new ArrayList<>();
    private final HashMap<String, Integer> edgeIndex = new HashMap<>();
    private final ArrayList<ArrayList<Path>> pathsBySource = new ArrayList<>();
    private final ArrayList<HashSet<String>> pathKeysBySource = new ArrayList<>();
    private final ArrayList<PathOptimalityCut> pathOptimalityCuts = new ArrayList<>();
    private final HashSet<String> pathOptimalityCutKeys = new HashSet<>();
    private final long constructionStartMillis = System.currentTimeMillis();
    private long deadlineMillis = Long.MAX_VALUE;

    public EnhancedBranchAndPriceSolverV4(DataCreator data) {
        this(data, 1e-6, 3, 5, 5, 300, 4000, 600.0, 500000);
    }

    public EnhancedBranchAndPriceSolverV4(DataCreator data, double eps, int initialPathsPerSourceFacility, int pricingColumnsPerSource, int carrierCutsPerSource, int maxCgIterations, int maxNodes, double timeLimitSeconds, int maxLabelsPerPricing) {
        this(data, eps, initialPathsPerSourceFacility, pricingColumnsPerSource, carrierCutsPerSource,
                maxCgIterations, maxNodes, timeLimitSeconds, maxLabelsPerPricing, new PricingSchedule());
    }

    public EnhancedBranchAndPriceSolverV4(DataCreator data, double eps, int initialPathsPerSourceFacility, int pricingColumnsPerSource, int carrierCutsPerSource, int maxCgIterations, int maxNodes, double timeLimitSeconds, int maxLabelsPerPricing, PricingSchedule schedule) {
        this(data, eps, initialPathsPerSourceFacility, pricingColumnsPerSource, carrierCutsPerSource,
                maxCgIterations, maxNodes, timeLimitSeconds, maxLabelsPerPricing, schedule, new RmpReuseOptions());
    }

    public EnhancedBranchAndPriceSolverV4(DataCreator data, double eps, int initialPathsPerSourceFacility, int pricingColumnsPerSource, int carrierCutsPerSource, int maxCgIterations, int maxNodes, double timeLimitSeconds, int maxLabelsPerPricing, PricingSchedule schedule, RmpReuseOptions reuseOptions) {
        if (schedule == null || schedule.quickMaxLabels < 0 || !Double.isFinite(schedule.quickTimeSeconds)
                || schedule.quickTimeSeconds < 0 || schedule.roundColumnLimit < 1)
            throw new IllegalArgumentException("Invalid pricing schedule");
        if (reuseOptions == null) throw new IllegalArgumentException("Null RMP reuse options");
        this.rmpReuseOptions = new RmpReuseOptions();
        this.rmpReuseOptions.usePersistentRmp = reuseOptions.usePersistentRmp;
        this.pricingSchedule = new PricingSchedule();
        this.pricingSchedule.useQuickPass = schedule.useQuickPass;
        this.pricingSchedule.quickMaxLabels = schedule.quickMaxLabels;
        this.pricingSchedule.quickTimeSeconds = schedule.quickTimeSeconds;
        this.pricingSchedule.roundColumnLimit = schedule.roundColumnLimit;
        this.data = data;
        this.eps = eps;
        this.initialPathsPerSourceFacility = initialPathsPerSourceFacility;
        this.pricingColumnsPerSource = pricingColumnsPerSource;
        this.carrierCutsPerSource = carrierCutsPerSource;
        this.maxCgIterations = maxCgIterations;
        this.maxNodes = maxNodes;
        this.timeLimitSeconds = timeLimitSeconds;
        this.maxLabelsPerPricing = maxLabelsPerPricing;
        this.nodeCount = data.getGraph().size();
        this.virtualSink = nodeCount;
        deadlineMillis = constructionStartMillis + (long) (1000.0 * Math.max(0.0, timeLimitSeconds));
        buildEdges();
        initializePathPool();
    }

    public Result solve() {
        long start = constructionStartMillis;
        deadlineMillis = start + (long) (1000.0 * Math.max(0.0, timeLimitSeconds));
        Result result = new Result();
        reuseSessionActive = rmpReuseOptions.usePersistentRmp;
        result.persistentRmpEnabled = reuseSessionActive;
        result.initialColumns = totalPathCount();
        result.pricingQuickPassEnabled = pricingSchedule.useQuickPass;
        result.pricingQuickLabelLimit = pricingSchedule.quickMaxLabels;
        result.pricingQuickTimeLimitSeconds = pricingSchedule.quickTimeSeconds;
        result.pricingRoundColumnLimit = pricingSchedule.roundColumnLimit;
        result.pricingMaxColumnsPerCall = Math.max(1, Math.min(64, pricingColumnsPerSource));
        PriorityQueue<BranchNode> queue = new PriorityQueue<>(Comparator
                .comparingDouble((BranchNode n) -> n.certifiedBound).thenComparingLong(n -> n.id));
        queue.add(new BranchNode());
        long nextId = 1;
        BranchNode active = null;
        RmpSolution activeLp = null;
        try {
            while (!queue.isEmpty()) {
                if (remainingSeconds() <= 0) { result.terminationReason = "TIME_LIMIT"; break; }
                if (result.nodesProcessed >= maxNodes) { result.terminationReason = "NODE_LIMIT"; break; }
                active = queue.poll();
                result.nodesProcessed++;
                // A child's feasible region is contained in its parent's. Its
                // inherited certified bound needs no new RMP/pricing solve.
                if (fathomByInheritedBound(active, result)) {
                    active = null;
                    continue;
                }
                int cgBeforeNode = result.cgIterations;
                long pricingBeforeNode = result.pricingTimeMillis;
                NodeSolution ns = solveNode(active, result, start);
                activeLp = ns.lp;
                if (result.nodesProcessed == 1) {
                    result.rootCgIterations = result.cgIterations - cgBeforeNode;
                    result.rootPricingTimeMillis = result.pricingTimeMillis - pricingBeforeNode;
                    result.rootColumns = totalPathCount();
                    if (ns.lp != null) result.rootObjective = ns.lp.objective;
                    result.rootLowerBound = ns.lowerBound;
                }
                if (ns.infeasibleCertified) {
                    result.nodesInfeasibleCertified++;
                    result.nodesPruned++;
                    result.nodeAudit.add(active.id + ": INFEASIBLE_CERTIFIED by Phase I");
                    active = null;
                    continue;
                }
                if (!ns.feasible || !ns.pricingConverged || !ns.reducedCostOk) {
                    result.terminationReason = remainingSeconds() <= 0.0 ? "TIME_LIMIT" : "UNRESOLVED_NODE";
                    result.message = append(result.message, ns.message);
                    result.nodeAudit.add(active.id + ": UNRESOLVED inheritedLB=" + active.certifiedBound + " " + ns.message);
                    break; // Keep this region in the frontier; a failure is never a pruning proof.
                }
                active.certifiedBound = Math.max(active.certifiedBound, ns.lowerBound);
                result.nodeAudit.add(active.id + ": CERTIFIED_LP depth=" + active.depth + " LB=" + active.certifiedBound);
                if (result.nodesProcessed == 1) {
                    result.rootIntegral = ns.integral;
                    result.rootFractionalYCount = countFractionalY(ns.lp);
                    result.rootFractionalUsageCount = countFractionalUsage(ns.lp);
                }
                if (active.certifiedBound >= result.bestObjective - result.absoluteOptimalityTolerance) {
                    result.closedLeafLowerBound = Math.min(result.closedLeafLowerBound, active.certifiedBound);
                    result.nodesPruned++;
                    result.nodeAudit.add(active.id + ": BOUND_FATHOMED");
                    close(activeLp); activeLp = null; active = null;
                    continue;
                }
                if (ns.integral) {
                    ns.lp = polishIntegerSolution(ns.lp, result);
                    activeLp = ns.lp;
                    if (!ns.lp.solved) {
                        result.terminationReason = "UNRESOLVED_INTEGER_POLISH";
                        break;
                    }
                    if (!carrierOptimal(ns.lp)) {
                        result.terminationReason = "UNRESOLVED_CARRIER_CHECK";
                        break;
                    }
                    result.rootVerified |= result.nodesProcessed == 1;
                    if (ns.lp.objective < result.bestObjective) {
                        if (!Double.isFinite(result.bestObjective))
                            result.firstIncumbentTimeMillis = System.currentTimeMillis() - start;
                        result.bestObjective = ns.lp.objective;
                        result.selectedRoadEdges = selectedRoadEdges(ns.lp.zValues);
                        result.selectedRouteEdges = selectedRouteEdges(ns.lp.lambdaValues);
                        result.selectedFacilityNodes = selectedFacilityNodes(ns.lp.zValues);
                        result.selectedRouteNodes = selectedRouteNodes(ns.lp.lambdaValues);
                    }
                    if (ns.lp.objective - active.certifiedBound > result.absoluteOptimalityTolerance) {
                        result.terminationReason = "UNRESOLVED_NUMERICAL_GAP";
                        break;
                    }
                    result.closedLeafLowerBound = Math.min(result.closedLeafLowerBound, active.certifiedBound);
                    result.nodesPruned++;
                    result.nodeAudit.add(active.id + ": INTEGER_FATHOMED UB=" + ns.lp.objective);
                    close(activeLp); activeLp = null; active = null;
                    continue;
                }
                BranchDecision decision = chooseBranch(ns.lp);
                if (decision == null) {
                    result.terminationReason = "UNRESOLVED_BRANCHING";
                    break;
                }
                close(activeLp); activeLp = null;
                BranchNode left = active.copy(), right = active.copy();
                left.id = nextId++; right.id = nextId++;
                if (decision.type == BranchType.ASSIGNMENT) {
                    left.fixedAssignment.put(assignmentKey(decision.source, decision.facility), 0);
                    right.fixedAssignment.put(assignmentKey(decision.source, decision.facility), 1);
                    result.yBranchCount++;
                } else if (decision.type == BranchType.Z) {
                    left.fixedZ.put(decision.edge, 0);
                    right.fixedZ.put(decision.edge, 1);
                    result.zBranchCount++;
                } else {
                    left.fixedUsage.put(usageKey(decision.source, decision.edge), 0);
                    right.fixedUsage.put(usageKey(decision.source, decision.edge), 1);
                    result.usageBranchCount++;
                }
                result.nodeAudit.add(active.id + ": BRANCH children=" + left.id + "," + right.id
                        + " inheritedLB=" + active.certifiedBound);
                queue.add(left); queue.add(right); active = null;
            }
        } catch (Exception e) {
            result.terminationReason = "UNRESOLVED_ERROR";
            result.message = append(result.message, e.toString());
        } finally {
            close(activeLp);
            disposePersistentRmp();
            reuseSessionActive = false;
            if (active != null) queue.add(active);
        }
        result.pathCount = totalPathCount();
        result.pendingNodes = queue.size();
        result.unresolvedNodes = queue.size();
        double lower = result.closedLeafLowerBound;
        for (BranchNode node : queue) {
            result.pendingNodeBounds.add(node.certifiedBound);
            lower = Math.min(lower, node.certifiedBound);
        }
        result.bestLowerBound = lower;
        result.solved = Double.isFinite(result.bestObjective);
        result.gap = result.solved ? result.bestObjective - lower : Double.POSITIVE_INFINITY;
        result.relativeGap = result.solved ? result.gap / Math.max(1.0, Math.abs(result.bestObjective)) : Double.POSITIVE_INFINITY;
        // A negative gap is diagnostic data, not zero. Never hide a contradictory bound.
        if (result.solved && lower > result.bestObjective + 1e-7) {
            result.globalBoundValid = false;
            result.terminationReason = "BOUND_INVARIANT_ERROR";
        }
        result.optimalityCertified = result.globalBoundValid && result.solved && queue.isEmpty()
                && result.gap >= -1e-7 && result.gap <= result.absoluteOptimalityTolerance;
        result.optimal = result.optimalityCertified;
        if (result.optimal) result.terminationReason = "OPTIMAL_WITHIN_TOLERANCE";
        else if (queue.isEmpty() && !result.solved && result.terminationReason.equals("NOT_STARTED"))
            result.terminationReason = "INFEASIBLE_CERTIFIED";
        result.solveTimeMillis = System.currentTimeMillis() - start;
        return result;
    }

    private double remainingSeconds() {
        return Math.max(0.0, (deadlineMillis - System.currentTimeMillis()) / 1000.0);
    }

    private boolean fathomByInheritedBound(BranchNode node, Result result) {
        if (!Double.isFinite(result.bestObjective)
                || !(node.certifiedBound >= result.bestObjective - result.absoluteOptimalityTolerance)) return false;
        result.closedLeafLowerBound = Math.min(result.closedLeafLowerBound, node.certifiedBound);
        result.nodesPruned++;
        result.nodeAudit.add(node.id + ": INHERITED_BOUND_FATHOMED LB=" + node.certifiedBound
                + " UB=" + result.bestObjective);
        return true;
    }

    private String unfinishedCgMessage() {
        return remainingSeconds() <= 0.0 ? "CG time limit reached" : "CG iteration limit reached";
    }

    private RmpSolution polishIntegerSolution(RmpSolution lp, Result result) throws Exception {
        // A near-integer LP point is only a candidate. Fix every discrete value
        // exactly, then re-optimize the continuous risk certificate and check
        // all rows before treating its objective as a feasible upper bound.
        IncrementalRmpModel model = lp.model;
        model.polishedBounds = true;
        for (int e = 0; e < model.z.length; e++) fixRounded(model.z[e], lp.zValues[e]);
        for (int s = 0; s < model.y.length; s++) {
            for (int m = 0; m < model.y[s].length; m++) fixRounded(model.y[s][m], lp.yValues[s][m]);
            for (int p = 0; p < model.lambdaVars.get(s).size(); p++)
                fixRounded(model.lambdaVars.get(s).get(p), lp.lambdaValues[s][p]);
        }
        model.cplex.setParam(IloCplex.Param.TimeLimit, Math.max(0.001, remainingSeconds()));
        return solveIncrementalLp(model, result);
    }

    private void fixRounded(IloNumVar var, double value) throws Exception {
        double integer = Math.rint(value);
        var.setLB(integer); var.setUB(integer);
    }

    private IncrementalRmpModel acquireNodeRmp(BranchNode node, Result result) throws Exception {
        if (!reuseSessionActive) {
            long started = System.currentTimeMillis();
            try { return buildIncrementalRmp(node); }
            finally { result.rmpRebuilds++; result.rmpBuildTimeMillis += System.currentTimeMillis() - started; }
        }
        if (persistentRmp == null) {
            long started = System.currentTimeMillis();
            try { persistentRmp = buildIncrementalRmp(new BranchNode()); }
            finally { result.rmpRebuilds++; result.rmpBuildTimeMillis += System.currentTimeMillis() - started; }
        }
        long started = System.currentTimeMillis();
        try { activateRmpForNode(persistentRmp, node, result); }
        finally { result.rmpNodeSwitchTimeMillis += System.currentTimeMillis() - started; }
        result.rmpNodeSwitches++;
        return persistentRmp;
    }

    /** Restore the original-variable model before activating the target branch. */
    private void activateRmpForNode(IncrementalRmpModel model, BranchNode node, Result result) throws Exception {
        if (model.phaseOne) leavePhaseOne(model);
        if (model.polishedBounds) {
            for (IloNumVar var : model.z) { var.setLB(0.0); var.setUB(1.0); }
            for (IloNumVar[] row : model.y) for (IloNumVar var : row) { var.setLB(0.0); var.setUB(1.0); }
            for (ArrayList<IloNumVar> row : model.lambdaVars) for (IloNumVar var : row) {
                var.setLB(0.0); var.setUB(Double.MAX_VALUE);
            }
            model.polishedBounds = false;
            result.rmpPolishBoundsRestored++;
        }
        // Normally already synchronized by addPathAndColumn/addCarrierCuts. Keep
        // the invariant explicit for global additions made between node visits.
        for (int s = 0; s < pathsBySource.size(); s++) {
            while (model.lambdaVars.get(s).size() < pathsBySource.get(s).size()) {
                addLambdaColumn(model, pathsBySource.get(s).get(model.lambdaVars.get(s).size()));
                result.rmpGlobalColumnsSynchronized++;
            }
        }
        for (int c = 0; c < pathOptimalityCuts.size(); c++) {
            if (c >= model.carrierCutRows.length || model.carrierCutRows[c] == null) {
                addIncrementalPathOptimalityCut(model, c);
                result.rmpGlobalCutsSynchronized++;
            }
        }
        validateIncrementalColumnCounts(model);
        for (int key : model.activeBranchZ) if (!node.fixedZ.containsKey(key))
            model.branchZRows.get(key).setBounds(-Double.MAX_VALUE, Double.MAX_VALUE);
        for (String key : model.activeBranchAssignment) if (!node.fixedAssignment.containsKey(key))
            model.branchAssignmentRows.get(key).setBounds(-Double.MAX_VALUE, Double.MAX_VALUE);
        for (String key : model.activeBranchUsage) if (!node.fixedUsage.containsKey(key))
            model.branchUsageRows.get(key).setBounds(-Double.MAX_VALUE, Double.MAX_VALUE);
        for (Map.Entry<Integer, Integer> fix : node.fixedZ.entrySet()) {
            IloRange row = model.branchZRows.get(fix.getKey());
            if (row == null) {
                row = model.cplex.addEq(model.z[fix.getKey()], fix.getValue(), "branch_z_" + fix.getKey());
                model.branchZRows.put(fix.getKey(), row); result.rmpBranchRowsCreated++;
            } else if (row.getLB() != fix.getValue() || row.getUB() != fix.getValue())
                row.setBounds(fix.getValue(), fix.getValue());
        }
        for (Map.Entry<String, Integer> fix : node.fixedAssignment.entrySet()) {
            IloRange row = model.branchAssignmentRows.get(fix.getKey());
            if (row == null) {
                String[] parts = fix.getKey().split("_");
                int s = Integer.parseInt(parts[0]), m = Integer.parseInt(parts[1]);
                row = model.cplex.addEq(model.y[s][m], fix.getValue(), "branch_assign_" + fix.getKey());
                model.branchAssignmentRows.put(fix.getKey(), row); result.rmpBranchRowsCreated++;
            } else if (row.getLB() != fix.getValue() || row.getUB() != fix.getValue())
                row.setBounds(fix.getValue(), fix.getValue());
        }
        for (Map.Entry<String, Integer> fix : node.fixedUsage.entrySet()) {
            IloRange row = model.branchUsageRows.get(fix.getKey());
            if (row == null) {
                String[] parts = fix.getKey().split("_");
                int s = Integer.parseInt(parts[0]), e = Integer.parseInt(parts[1]);
                IloLinearNumExpr usage = model.cplex.linearNumExpr();
                for (int p = 0; p < pathsBySource.get(s).size(); p++)
                    if (pathsBySource.get(s).get(p).uses(e)) usage.addTerm(1.0, model.lambdaVars.get(s).get(p));
                row = model.cplex.addEq(usage, fix.getValue(), "branch_usage_" + fix.getKey());
                model.branchUsageRows.put(fix.getKey(), row); result.rmpBranchRowsCreated++;
            } else if (row.getLB() != fix.getValue() || row.getUB() != fix.getValue())
                row.setBounds(fix.getValue(), fix.getValue());
        }
        model.activeBranchZ.clear(); model.activeBranchZ.addAll(node.fixedZ.keySet());
        model.activeBranchAssignment.clear(); model.activeBranchAssignment.addAll(node.fixedAssignment.keySet());
        model.activeBranchUsage.clear(); model.activeBranchUsage.addAll(node.fixedUsage.keySet());
        result.rmpPeakBranchRows = Math.max(result.rmpPeakBranchRows,
                model.branchZRows.size() + model.branchAssignmentRows.size() + model.branchUsageRows.size());
    }

    private NodeSolution solveNode(BranchNode node, Result result, long start) throws Exception {
        NodeSolution out = new NodeSolution();
        IncrementalRmpModel model = null;
        boolean transferOwnership = false;
        int pricingStartSource = 0;
        int maxColumnsPerCall = Math.max(1, Math.min(64, pricingColumnsPerSource));
        try {
            for (int iter = 1; iter <= maxCgIterations; iter++) {
                if (remainingSeconds() <= 0) { out.message = "CG time limit reached"; break; }
                if (model == null) {
                    model = acquireNodeRmp(node, result);
                }
                model.cplex.setParam(IloCplex.Param.TimeLimit, Math.max(0.001, remainingSeconds()));
                RmpSolution lp = solveIncrementalLp(model, result);
                result.cgIterations++;
                if (!lp.solved) {
                    if (!model.phaseOne && "Infeasible".equals(lp.status)) {
                        enterPhaseOne(model);
                        continue;
                    }
                    out.message = "RMP not LP-optimal: " + lp.status;
                    break;
                }
                if (model.phaseOne) {
                    result.phaseOneIterations++;
                    if (lp.objective <= 1e-8) { leavePhaseOne(model); continue; }
                }
                ReducedCostMode mode = detectReducedCostMode(lp);
                if (!mode.ok) { out.message = mode.message; break; }
                out.reducedCostOk = true;
                int added = 0;
                long pricingStart = System.currentTimeMillis();
                boolean complete = true;
                double correction = 0.0;
                int taskCount = pathsBySource.size();
                // These certificates are scoped to this exact LP dual. They are
                // discarded on every column/row insertion followed by re-solving.
                BpaExactLabelPricing.Result[] certificates = new BpaExactLabelPricing.Result[taskCount];
                if (pricingSchedule.useQuickPass) {
                    for (int offset = 0; offset < taskCount; offset++) {
                        if (remainingSeconds() <= 0) break;
                        int source = (pricingStartSource + offset) % taskCount;
                        int capacity = Math.min(maxColumnsPerCall, pricingSchedule.roundColumnLimit - added);
                        BpaExactLabelPricing.Result quick = certifyMissingPaths(lp, mode.sign, source, node, result, true, capacity);
                        recordPricingCall(result, quick, node.id, source, true);
                        added += addPricingColumns(model, source, quick, result);
                        if (certifiesNoNegative(quick)) certificates[source] = quick;
                        if (added >= pricingSchedule.roundColumnLimit) {
                            result.pricingRoundColumnLimitStops++;
                            pricingStartSource = (source + 1) % taskCount;
                            break;
                        }
                    }
                }
                if (added == 0) {
                    for (int offset = 0; offset < taskCount; offset++) {
                        if (remainingSeconds() <= 0) { complete = false; break; }
                        int source = (pricingStartSource + offset) % taskCount;
                        BpaExactLabelPricing.Result cert = certificates[source];
                        if (cert == null) {
                            cert = certifyMissingPaths(lp, mode.sign, source, node, result, false,
                                    Math.min(maxColumnsPerCall, pricingSchedule.roundColumnLimit));
                            recordPricingCall(result, cert, node.id, source, false);
                            added += addPricingColumns(model, source, cert, result);
                        } else result.pricingQuickCertifiedReuses++;
                        // Once a useful batch exists, update the dual before
                        // spending an unbounded search on the remaining tasks.
                        if (added > 0) {
                            if (added >= pricingSchedule.roundColumnLimit) result.pricingRoundColumnLimitStops++;
                            pricingStartSource = (source + 1) % taskCount;
                            complete = false;
                            break;
                        }
                        if (!certifiesNoNegative(cert)) {
                            complete = false;
                            out.message = "Incomplete exact pricing at source " + source + ": " + cert.status;
                            break;
                        }
                        double sourceBound = Math.min(0.0, cert.lowerBound);
                        // Existing columns also contribute numerical dual residuals.
                        for (Path path : pathsBySource.get(source))
                            sourceBound = Math.min(sourceBound, manualReducedCost(lp, mode.sign, path));
                        correction += Math.min(0.0, sourceBound - 1e-8);
                    }
                }
                result.pricingTimeMillis += System.currentTimeMillis() - pricingStart;
                result.maxColumnsPerPricingRound = Math.max(result.maxColumnsPerPricingRound, added);
                if (added > 1) result.pricingRoundsWithMultiColumns++;
                if (added > 0) continue;
                if (!complete) { result.pricingIncompleteCalls++; break; }
                result.pricingCompleteDualRounds++;
                double certifiedBound = lp.objective + correction - 1e-8;
                if (model.phaseOne) {
                    if (certifiedBound > 1e-7) {
                        out.infeasibleCertified = true;
                        out.message = "Phase I positive certified lower bound: " + certifiedBound;
                        return out;
                    }
                    out.message = "Phase I feasibility remains numerically unresolved";
                    break;
                }
                if (lp.integral) {
                    int beforePaths = totalPathCount();
                    long carrierStart = System.currentTimeMillis();
                    int cuts = addCarrierCuts(lp, model, result);
                    result.carrierCheckTimeMillis += System.currentTimeMillis() - carrierStart;
                    result.carrierCuts += cuts;
                    result.carrierColumns += Math.max(0, totalPathCount() - beforePaths);
                    if (cuts > 0) continue;
                }
                out.feasible = true;
                out.pricingConverged = true;
                out.integral = lp.integral;
                out.lowerBound = certifiedBound;
                out.lp = lp;
                transferOwnership = true;
                return out;
            }
            if (out.message.isEmpty()) out.message = unfinishedCgMessage();
            return out;
        } finally {
            if (!transferOwnership && model != null) close(model);
        }
    }

    private void enterPhaseOne(IncrementalRmpModel model) throws Exception {
        if (model.phaseOne) throw new IllegalStateException("RMP already in Phase I");
        // Preserve the objective before adding elastic variables. The choose rows
        // only get a deficit variable, so total real path mass stays <= 1/source.
        model.originalCosts.clear();
        IloLinearNumExpr expr = (IloLinearNumExpr) model.objective.getExpr();
        IloLinearNumExprIterator terms = expr.linearIterator();
        while (terms.hasNext()) {
            IloNumVar var = terms.nextNumVar();
            model.originalCosts.put(var, terms.getValue());
        }
        ArrayList<IloRange> rows = new ArrayList<>();
        Iterator<?> iterator = model.cplex.iterator();
        while (iterator.hasNext()) {
            Object item = iterator.next();
            if (item instanceof IloRange) rows.add((IloRange) item);
        }
        HashSet<IloRange> choose = new HashSet<>(Arrays.asList(model.chooseRows));
        for (IloRange row : rows) {
            // A historical branch row can be free now and fixed in a later node.
            // Register each direction when it first becomes necessary; do not
            // mark a free row as permanently processed without elastic variables.
            IloNumVar[] elastic = model.elasticVariables.computeIfAbsent(row, ignored -> new IloNumVar[2]);
            boolean lower = row.getLB() > -Double.MAX_VALUE;
            boolean upper = !choose.contains(row) && row.getUB() < Double.MAX_VALUE;
            if (lower && elastic[0] == null) elastic[0] = addArtificial(model, row, 1.0);
            if (upper && elastic[1] == null) elastic[1] = addArtificial(model, row, -1.0);
            if (elastic[0] != null) elastic[0].setUB(lower ? Double.MAX_VALUE : 0.0);
            if (elastic[1] != null) elastic[1].setUB(upper ? Double.MAX_VALUE : 0.0);
        }
        IloLinearNumExpr objective = model.cplex.linearNumExpr();
        for (IloNumVar var : model.artificials) objective.addTerm(1.0, var);
        model.objective.setExpr(objective);
        model.phaseOne = true;
    }

    private IloNumVar addArtificial(IncrementalRmpModel model, IloRange row, double sign) throws Exception {
        IloNumVar var = model.cplex.numVar(model.cplex.column(row, sign), 0.0, Double.MAX_VALUE,
                "phase_one_" + model.artificials.size());
        model.artificials.add(var);
        return var;
    }

    private void leavePhaseOne(IncrementalRmpModel model) throws Exception {
        if (!model.phaseOne) return;
        IloLinearNumExpr original = model.cplex.linearNumExpr();
        for (Map.Entry<IloNumVar, Double> term : model.originalCosts.entrySet()) original.addTerm(term.getValue(), term.getKey());
        for (IloNumVar var : model.artificials) var.setUB(0.0);
        model.objective.setExpr(original);
        model.phaseOne = false;
    }

    private boolean certifiesNoNegative(BpaExactLabelPricing.Result cert) {
        // UNKNOWN can carry valid columns, but is never a completed certificate.
        return !cert.status.startsWith("UNKNOWN") && cert.lowerBound >= -eps
                && (cert.infeasible || cert.optimal || "NO_NEGATIVE_CERTIFIED".equals(cert.status));
    }

    private int addPricingColumns(IncrementalRmpModel model, int source, BpaExactLabelPricing.Result cert, Result metrics) throws Exception {
        int added = 0;
        // List membership certifies strict negativity using the exact dyadic sum,
        // even when the upper-rounded reported cost equals the threshold.
        // In particular, an interrupted call may still have useful valid columns.
        for (int i = 0; i < cert.negativePathEdges.size(); i++) {
            Path path = pathFromEdges(source, cert.negativePathEdges.get(i), cert.negativeObjectives.get(i));
            if (addPathAndColumn(model, path, metrics)) added++;
        }
        metrics.pricingColumns += added;
        metrics.observedMaxColumnsPerCall = Math.max(metrics.observedMaxColumnsPerCall, added);
        if (cert.status.startsWith("UNKNOWN")) metrics.pricingColumnsFromInterruptedCalls += added;
        return added;
    }

    private void recordPricingCall(Result metrics, BpaExactLabelPricing.Result cert, long nodeId, int source, boolean quick) {
        if (quick) metrics.pricingQuickPassCalls++;
        else metrics.pricingCertificationCalls++;
        metrics.exactLabelPricingCalls++;
        if (cert.status.startsWith("UNKNOWN")) {
            metrics.pricingUnknownCalls++;
            if (quick) metrics.pricingQuickUnknownCalls++;
            else metrics.pricingCertificationUnknownCalls++;
        }
        metrics.exactLabelPreprocessingSeconds += cert.preprocessingSeconds;
        metrics.exactLabelSearchSeconds += cert.searchSeconds;
        metrics.exactLabelPeakPending = Math.max(metrics.exactLabelPeakPending, cert.peakPending);
        metrics.exactLabelPeakRetained = Math.max(metrics.exactLabelPeakRetained, cert.peakRetainedLabels);
        metrics.dominanceCostComparisons += cert.dominanceCostComparisons;
        metrics.dominancePairVisits += cert.dominancePairVisits;
        metrics.dominanceTimeComparisons += cert.dominanceTimeComparisons;
        metrics.geometryFirstPairChecks += cert.geometryFirstPairChecks;
        metrics.geometryFirstRejectedPairs += cert.geometryFirstRejectedPairs;
        metrics.visitedIndexQueries += cert.visitedIndexQueries;
        metrics.visitedIndexBuilds += cert.visitedIndexBuilds;
        metrics.visitedIndexCandidates += cert.visitedIndexCandidates;
        metrics.visitedIndexSkippedPairs += cert.visitedIndexSkippedPairs;
        metrics.visitedIndexPostingOperations += cert.visitedIndexPostingOperations;
        metrics.visitedIndexBudgetFallbacks += cert.visitedIndexBudgetFallbacks;
        metrics.peakVisitedIndexWords = Math.max(metrics.peakVisitedIndexWords, cert.peakVisitedIndexWords);
        metrics.visitedSubsetChecks += cert.visitedSubsetChecks;
        metrics.negativeAdjacencyProbes += cert.negativeAdjacencyProbes;
        metrics.negativeArcBoundChecks += cert.negativeArcBoundChecks;
        metrics.negativeArcBoundImprovements += cert.negativeArcBoundImprovements;
        metrics.labelsExpanded += cert.labelsExpanded;
        metrics.labelsGenerated += cert.labelsGenerated;
        metrics.dominanceChecks += cert.dominanceChecks;
        metrics.labelsPrunedByDominance += cert.labelsDominated;
        metrics.labelsPrunedByGlobalReducedCostBound += cert.boundPruned;
        metrics.labelsPrunedByTimeReachability += cert.timePruned;
        if ("FOUND_COLUMNS".equals(cert.status)) metrics.exactLabelEarlyColumnReturns++;
        if (certifiesNoNegative(cert)) metrics.exactLabelNoNegativeCertificates++;
        double seconds = cert.preprocessingSeconds + cert.searchSeconds;
        metrics.longestExactLabelCallSeconds = Math.max(metrics.longestExactLabelCallSeconds, seconds);
        if (seconds >= 0.1 && metrics.slowPricingCalls.size() < 20)
            metrics.slowPricingCalls.add("node=" + nodeId + ",source=" + source + ",pass=" + (quick ? "quick" : "full")
                    + ",seconds=" + seconds + ",labels=" + cert.labelsExpanded + ",comparisons=" + cert.dominanceChecks
                    + ",columns=" + cert.negativePathEdges.size() + ",status=" + cert.status);
    }

    private BpaExactLabelPricing.Result certifyMissingPaths(RmpSolution lp, double sign, int source, BranchNode node, Result metrics, boolean quick, int maxColumns) throws Exception {
        int[] u = new int[edges.size()], v = new int[edges.size()];
        double[] times = new double[edges.size()];
        double[] costs = buildArcReducedCosts(lp, sign, source);
        double[] terminal = buildFacilityReducedCostConstants(lp, sign, source, sign * dualChoose(lp, source));
        boolean[] allowed = allAllowed(), required = new boolean[edges.size()];
        for (int e = 0; e < edges.size(); e++) {
            Edge edge = edges.get(e); u[e] = edge.u; v[e] = edge.v;
            if (edge.virtual) {
                costs[e] += terminal[edge.candidateIndex];
                allowed[e] = assignmentAllowed(node, source, edge.candidateIndex);
            } else times[e] = data.getNodeTime().get(edge.v).get(edge.u);
            if (Integer.valueOf(0).equals(node.fixedZ.get(e))) allowed[e] = false;
            Integer usage = node.fixedUsage.get(usageKey(source, e));
            if (usage != null) { if (usage == 0) allowed[e] = false; else required[e] = true; }
        }
        ArrayList<int[]> excluded = new ArrayList<>();
        for (Path path : pathsBySource.get(source)) excluded.add(path.edgeIndices.stream().mapToInt(Integer::intValue).toArray());
        BpaExactLabelPricing.Options options = new BpaExactLabelPricing.Options();
        options.stopAfterNegative = true;
        options.negativeThreshold = -eps;
        options.maxLabels = Math.max(0, maxLabelsPerPricing);
        options.maxColumns = maxColumns;
        double pricingSeconds = remainingSeconds();
        if (quick) {
            options.maxLabels = Math.min(options.maxLabels, pricingSchedule.quickMaxLabels);
            pricingSeconds = Math.min(pricingSeconds, pricingSchedule.quickTimeSeconds);
        }
        BpaExactLabelPricing.Result priced = BpaExactLabelPricing.solve(nodeCount, data.getStartPoints().get(source), u, v, costs, times,
                data.getTimeWindow().get(source), allowed, required, excluded, pricingSeconds, options);
        // Opt-in diagnostic snapshots, outside all formal experiment output paths.
        // Disabled by default; capturing never changes the pricing result.
        String capture = System.getProperty("hazmat.bpaPricingCaptureDir");
        if (capture != null && !capture.isEmpty() && priced.preprocessingSeconds + priced.searchSeconds >= 0.1) {
            try {
                java.nio.file.Path folder = java.nio.file.Paths.get(capture);
                java.nio.file.Files.createDirectories(folder);
                Properties p = new Properties();
                p.setProperty("n", Integer.toString(nodeCount));
                p.setProperty("source", Integer.toString(data.getStartPoints().get(source)));
                p.setProperty("u", arrayProperty(u)); p.setProperty("v", arrayProperty(v));
                p.setProperty("costs", arrayProperty(costs)); p.setProperty("times", arrayProperty(times));
                p.setProperty("window", Double.toString(data.getTimeWindow().get(source)));
                int[] a = new int[allowed.length], r = new int[required.length];
                for (int e = 0; e < a.length; e++) { a[e] = allowed[e] ? 1 : 0; r[e] = required[e] ? 1 : 0; }
                p.setProperty("allowed", arrayProperty(a)); p.setProperty("required", arrayProperty(r));
                p.setProperty("excluded_count", Integer.toString(excluded.size()));
                for (int i = 0; i < excluded.size(); i++) p.setProperty("excluded_" + i, arrayProperty(excluded.get(i)));
                p.setProperty("context", "node=" + node.id + ",task=" + source + ",status=" + priced.status);
                p.setProperty("quick_pass", Boolean.toString(quick));
                p.setProperty("max_columns", Integer.toString(options.maxColumns));
                p.setProperty("max_labels", Long.toString(options.maxLabels));
                p.setProperty("pricing_seconds", Double.toString(priced.preprocessingSeconds + priced.searchSeconds));
                java.nio.file.Path file = folder.resolve("node" + node.id + "_task" + source + "_" + System.nanoTime() + ".properties");
                try (java.io.Writer w = java.nio.file.Files.newBufferedWriter(file,
                        java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                    p.store(w, "Isolated pricing diagnostics; exact double inputs");
                }
            } catch (java.io.IOException error) { metrics.message = append(metrics.message, "Pricing snapshot failed: " + error); }
        }
        return priced;
    }

    private static String arrayProperty(int[] values) {
        return Arrays.toString(values).replace("[", "").replace("]", "").replace(" ", "");
    }

    private static String arrayProperty(double[] values) {
        return Arrays.toString(values).replace("[", "").replace("]", "").replace(" ", "");
    }

    private Path pathFromEdges(int source, int[] indices, double reducedCost) {
        ArrayList<Integer> pathEdges = new ArrayList<>(), nodes = new ArrayList<>();
        int sourceNode = data.getStartPoints().get(source);
        nodes.add(sourceNode);
        double cost = 0.0, time = 0.0;
        Edge terminal = null;
        for (int e : indices) {
            Edge edge = edges.get(e); pathEdges.add(e);
            if (edge.virtual) terminal = edge;
            else { nodes.add(edge.v); cost += data.getNodeDistance().get(edge.u).get(edge.v); time += data.getNodeTime().get(edge.v).get(edge.u); }
        }
        if (terminal == null) throw new IllegalStateException("Pricing path has no terminal edge");
        return new Path(source, sourceNode, pathEdges, nodes, cost, reducedCost, time, terminal.u, terminal.candidateIndex);
    }

    private enum BranchType { ASSIGNMENT, Z, USAGE }
    private static class BranchDecision {
        BranchType type;
        int source;
        int facility;
        int edge;
    }

    private BranchDecision chooseBranch(RmpSolution lp) {
        for (int s = 0; s < lp.yValues.length; s++) {
            for (int m = 0; m < lp.yValues[s].length; m++) {
                double v = lp.yValues[s][m];
                if (v > 1e-5 && v < 1.0 - 1e-5) {
                    BranchDecision d = new BranchDecision();
                    d.type = BranchType.ASSIGNMENT;
                    d.source = s;
                    d.facility = m;
                    return d;
                }
            }
        }
        for (int e = 0; e < lp.zValues.length; e++) {
            double v = lp.zValues[e];
            if (v > 1e-5 && v < 1.0 - 1e-5) {
                BranchDecision d = new BranchDecision();
                d.type = BranchType.Z;
                d.edge = e;
                return d;
            }
        }
        for (int s = 0; s < pathsBySource.size(); s++) {
            for (int e = 0; e < edges.size(); e++) {
                double usage = 0.0;
                for (int p = 0; p < pathsBySource.get(s).size(); p++) {
                    if (pathsBySource.get(s).get(p).uses(e)) usage += lp.lambdaValues[s][p];
                }
                if (usage > 1e-5 && usage < 1.0 - 1e-5) {
                    BranchDecision d = new BranchDecision();
                    d.type = BranchType.USAGE;
                    d.source = s;
                    d.edge = e;
                    return d;
                }
            }
        }
        return null;
    }

    private int countFractionalY(RmpSolution lp) {
        int count = 0;
        for (double[] row : lp.yValues) for (double v : row) if (v > 1e-5 && v < 1.0 - 1e-5) count++;
        return count;
    }

    private int countFractionalUsage(RmpSolution lp) {
        int count = 0;
        for (int s = 0; s < pathsBySource.size(); s++) {
            for (int e = 0; e < edges.size(); e++) {
                double usage = 0.0;
                for (int p = 0; p < pathsBySource.get(s).size(); p++) if (pathsBySource.get(s).get(p).uses(e)) usage += lp.lambdaValues[s][p];
                if (usage > 1e-5 && usage < 1.0 - 1e-5) count++;
            }
        }
        return count;
    }

    private boolean carrierOptimal(RmpSolution integer) {
        boolean[] allowed = new boolean[edges.size()];
        for (int e = 0; e < edges.size(); e++) allowed[e] = integer.zValues[e] > 0.5;
        double[] trueCosts = trueArcCosts();
        for (int s = 0; s < pathsBySource.size(); s++) {
            Path selected = null;
            for (int p = 0; p < pathsBySource.get(s).size(); p++) {
                if (integer.lambdaValues[s][p] > 0.5) {
                    selected = pathsBySource.get(s).get(p);
                    break;
                }
            }
            if (selected == null) return false;
            int targetFacility = selected.nodePath.get(selected.nodePath.size() - 1);
            ArrayList<Path> best = findBestPathsToAnyOpenFacility(s, trueCosts, 0.0, allowed, 1);
            if (best.isEmpty()) return false;
            if (best.get(0).travelCost < selected.travelCost - 1e-5 && !best.get(0).key.equals(selected.key)) return false;
        }
        return true;
    }

    private IncrementalRmpModel buildIncrementalRmp(BranchNode branch) throws Exception {
        IncrementalRmpModel model = new IncrementalRmpModel();
        IloCplex cplex = new IloCplex();
        model.cplex = cplex;
        try {
        cplex.setOut(null);
        cplex.setParam(IloCplex.Param.TimeLimit, timeLimitSeconds);
        cplex.setParam(IloCplex.Param.RootAlgorithm, IloCplex.Algorithm.Dual);
        cplex.setParam(IloCplex.Param.Simplex.Tolerances.Feasibility, 1e-9);
        cplex.setParam(IloCplex.Param.Simplex.Tolerances.Optimality, 1e-9);

        IloLinearNumExpr objective = cplex.linearNumExpr();
        IloLinearNumExpr budget = cplex.linearNumExpr();
        model.z = new IloNumVar[edges.size()];
        for (int e = 0; e < edges.size(); e++) {
            Edge edge = edges.get(e);
            model.z[e] = cplex.numVar(0.0, 1.0, "z_" + e);
            if (edge.virtual) objective.addTerm(data.getCandidateBuildWeights().get(edge.candidateIndex), model.z[e]);
            else {
                objective.addTerm(data.getDedicatedLaneCostWeight().get(edge.u).get(edge.v), model.z[e]);
                budget.addTerm(data.getDedicatedLaneBuildCostWeight().get(edge.u).get(edge.v), model.z[e]);
            }
        }
        cplex.addLe(budget, data.getGovernmentRoadBudget(), "road_budget");
        for (Map.Entry<Integer, Integer> fix : branch.fixedZ.entrySet())
            model.branchZRows.put(fix.getKey(), cplex.addEq(model.z[fix.getKey()], fix.getValue(), "branch_z_" + fix.getKey()));
        model.activeBranchZ.addAll(branch.fixedZ.keySet());

        int sourceCount = pathsBySource.size();
        int facilityCount = data.getCandidatePoints().size();
        model.y = new IloNumVar[sourceCount][facilityCount];
        for (int s = 0; s < sourceCount; s++) {
            for (int m = 0; m < facilityCount; m++) model.y[s][m] = cplex.numVar(0.0, 1.0, "y_" + s + "_" + m);
        }

        model.chooseRows = new IloRange[sourceCount];
        model.assignmentRows = new IloRange[sourceCount][facilityCount];
        model.assignmentOpenRows = new IloRange[sourceCount][facilityCount];
        model.useRows = new IloRange[sourceCount][edges.size()];
        model.reservationUsageRows = new IloRange[edges.size()];
        for (int e = 0; e < edges.size(); e++) {
            if (edges.get(e).virtual) continue;
            // A physical road may be reserved only if at least one task uses it:
            // z_e - sum_{s,p:e in p} lambda_sp <= 0. New columns get coefficient -1.
            model.reservationUsageRows[e] = cplex.addLe(model.z[e], 0.0, "reserve_only_if_used_" + e);
        }
        for (int s = 0; s < sourceCount; s++) {
            model.lambdaVars.add(new ArrayList<>());
            model.chooseRows[s] = cplex.addEq(cplex.linearNumExpr(), 1.0, "choose_" + s);
            for (int m = 0; m < facilityCount; m++) {
                IloLinearNumExpr assign = cplex.linearNumExpr();
                assign.addTerm(1.0, model.y[s][m]);
                model.assignmentRows[s][m] = cplex.addEq(assign, 0.0, "assign_" + s + "_" + m);

                int facilityNode = data.getCandidatePoints().get(m);
                Integer virtualEdge = edgeIndex.get(key(facilityNode, virtualSink));
                IloLinearNumExpr open = cplex.linearNumExpr();
                open.addTerm(1.0, model.y[s][m]);
                if (virtualEdge != null) open.addTerm(-1.0, model.z[virtualEdge]);
                model.assignmentOpenRows[s][m] = cplex.addLe(open, 0.0, "assign_open_" + s + "_" + m);
            }
            for (int e = 0; e < edges.size(); e++) {
                IloLinearNumExpr use = cplex.linearNumExpr();
                use.addTerm(-1.0, model.z[e]);
                model.useRows[s][e] = cplex.addLe(use, 0.0, "use_" + s + "_" + e);
            }
        }
        for (Map.Entry<String, Integer> fix : branch.fixedAssignment.entrySet()) {
            String[] parts = fix.getKey().split("_");
            int s = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            model.branchAssignmentRows.put(fix.getKey(), cplex.addEq(model.y[s][m], fix.getValue(), "branch_assign_" + s + "_" + m));
        }
        model.activeBranchAssignment.addAll(branch.fixedAssignment.keySet());
        for (Map.Entry<String, Integer> fix : branch.fixedUsage.entrySet()) {
            String[] parts = fix.getKey().split("_");
            int s = Integer.parseInt(parts[0]);
            int e = Integer.parseInt(parts[1]);
            IloLinearNumExpr usage = cplex.linearNumExpr();
            model.branchUsageRows.put(usageKey(s, e), cplex.addEq(usage, fix.getValue(), "branch_usage_" + s + "_" + e));
        }
        model.activeBranchUsage.addAll(branch.fixedUsage.keySet());

        addIncrementalRobustRiskDual(model, objective);
        model.objective = cplex.addMinimize(objective);

        model.carrierCutRows = new IloRange[pathOptimalityCuts.size()];
        for (int s = 0; s < pathsBySource.size(); s++) {
            ArrayList<Path> snapshot = new ArrayList<>(pathsBySource.get(s));
            for (Path path : snapshot) addLambdaColumn(model, path);
        }
        for (int c = 0; c < pathOptimalityCuts.size(); c++) addIncrementalPathOptimalityCut(model, c);
        validateIncrementalColumnCounts(model);
        return model;
        } catch (Exception error) {
            // The partially built model has not yet been published as persistentRmp.
            cplex.end(); model.cplex = null;
            throw error;
        }
    }

    private void addIncrementalRobustRiskDual(IncrementalRmpModel model, IloLinearNumExpr objective) throws Exception {
        IloCplex cplex = model.cplex;
        int sourceCount = data.getStartPoints().size();
        int edgeCount = edges.size();
        IloNumVar[] rho = new IloNumVar[sourceCount];
        IloNumVar[] xi = new IloNumVar[edgeCount];
        IloNumVar[][] eta = new IloNumVar[sourceCount][edgeCount];
        IloNumVar[][] pi = new IloNumVar[sourceCount][edgeCount];
        model.dualWRows = new IloRange[sourceCount][edgeCount];
        model.dualURows = new IloRange[sourceCount];
        model.dualVRows = new IloRange[edgeCount];

        for (int s = 0; s < sourceCount; s++) {
            rho[s] = cplex.numVar(0.0, Double.MAX_VALUE, "rho_" + s);
            objective.addTerm(1.0, rho[s]);
        }
        for (int e = 0; e < edgeCount; e++) {
            xi[e] = cplex.numVar(0.0, Double.MAX_VALUE, "xi_" + e);
            objective.addTerm(1.0, xi[e]);
        }
        for (int s = 0; s < sourceCount; s++) {
            for (int e = 0; e < edgeCount; e++) {
                eta[s][e] = cplex.numVar(0.0, Double.MAX_VALUE, "eta_" + s + "_" + e);
                pi[s][e] = cplex.numVar(0.0, Double.MAX_VALUE, "pi_" + s + "_" + e);
            }
        }
        IloNumVar thetaU = cplex.numVar(0.0, Double.MAX_VALUE, "theta_u");
        IloNumVar thetaV = cplex.numVar(0.0, Double.MAX_VALUE, "theta_v");
        objective.addTerm(data.getQuantityRiskExpectation(), thetaU);
        objective.addTerm(data.getRoadRiskExpectation(), thetaV);

        for (int s = 0; s < sourceCount; s++) {
            IloLinearNumExpr demand = cplex.linearNumExpr();
            demand.addTerm(1.0, rho[s]);
            demand.addTerm(1.0, thetaU);
            for (int e = 0; e < edgeCount; e++) {
                demand.addTerm(-1.0, eta[s][e]);
                IloLinearNumExpr w = cplex.linearNumExpr();
                w.addTerm(1.0, eta[s][e]);
                w.addTerm(1.0, pi[s][e]);
                model.dualWRows[s][e] = cplex.addGe(w, 0.0, "dual_w_" + s + "_" + e);
            }
            model.dualURows[s] = cplex.addGe(demand, 0.0, "dual_u_" + s);
        }
        for (int e = 0; e < edgeCount; e++) {
            IloLinearNumExpr v = cplex.linearNumExpr();
            v.addTerm(1.0, xi[e]);
            v.addTerm(1.0, thetaV);
            for (int s = 0; s < sourceCount; s++) v.addTerm(-1.0, pi[s][e]);
            model.dualVRows[e] = cplex.addGe(v, 0.0, "dual_v_" + e);
        }
    }

    private boolean addPathAndColumn(IncrementalRmpModel model, Path path, Result result) throws Exception {
        if (!addPath(path)) return false;
        addLambdaColumn(model, path);
        result.columnsAddedIncrementally++;
        validateIncrementalColumnCounts(model);
        return true;
    }

    private void addLambdaColumn(IncrementalRmpModel model, Path path) throws Exception {
        IloCplex cplex = model.cplex;
        int s = path.sourceIndex;
        double k = data.getTransportTaskBuffs().get(s);
        double n = data.getMinTransportTasks().get(s);
        IloColumn column = cplex.column(model.objective, model.phaseOne ? 0.0 : pathObjectiveCoefficient(path));
        column = column.and(cplex.column(model.chooseRows[s], 1.0));
        column = column.and(cplex.column(model.assignmentRows[s][path.targetFacilityIndex], -1.0));

        double dualUCoefficient = 0.0;
        for (int e : path.edgeIndices) {
            column = column.and(cplex.column(model.useRows[s][e], 1.0));
            if (model.reservationUsageRows[e] != null)
                column = column.and(cplex.column(model.reservationUsageRows[e], -1.0));
            IloRange branchUsageRow = model.branchUsageRows.get(usageKey(s, e));
            if (branchUsageRow != null) column = column.and(cplex.column(branchUsageRow, 1.0));
            double r = riskMin(edges.get(e), s);
            double q = riskWidth(edges.get(e), s);
            if (data.getIncludeNominalRiskInObjective()) {
                // This term is already inside pathObjectiveCoefficient.
            }
            if (Math.abs(k * q) > 1e-12) column = column.and(cplex.column(model.dualWRows[s][e], -k * q));
            dualUCoefficient += -k * r;
            if (Math.abs(n * q) > 1e-12) column = column.and(cplex.column(model.dualVRows[e], -n * q));
        }
        if (Math.abs(dualUCoefficient) > 1e-12) column = column.and(cplex.column(model.dualURows[s], dualUCoefficient));
        if (model.carrierCutRows != null) {
            for (int c = 0; c < pathOptimalityCuts.size(); c++) {
                PathOptimalityCut cut = pathOptimalityCuts.get(c);
                if (cut.sourceIndex == path.sourceIndex && cut.selectedPathKey.equals(path.key) && c < model.carrierCutRows.length && model.carrierCutRows[c] != null) {
                    column = column.and(cplex.column(model.carrierCutRows[c], 1.0));
                }
            }
        }
        IloNumVar var = cplex.numVar(column, 0.0, Double.MAX_VALUE, "lambda_" + s + "_" + model.lambdaVars.get(s).size());
        model.lambdaVars.get(s).add(var);
        if (model.phaseOne) model.originalCosts.put(var, pathObjectiveCoefficient(path));
    }

    private RmpSolution solveIncrementalLp(IncrementalRmpModel model, Result result) throws Exception {
        long solveStart = System.currentTimeMillis();
        RmpSolution out = new RmpSolution();
        out.model = model;
        out.cplex = model.cplex;
        out.chooseRows = model.chooseRows;
        out.assignmentRows = model.assignmentRows;
        out.assignmentOpenRows = model.assignmentOpenRows;
        out.useRows = model.useRows;
        out.reservationUsageRows = model.reservationUsageRows;
        out.dualWRows = model.dualWRows;
        out.dualURows = model.dualURows;
        out.dualVRows = model.dualVRows;
        out.carrierCutRows = model.carrierCutRows;
        out.branchUsageRows = model.branchUsageRows;
        boolean hasSolution = model.cplex.solve();
        out.status = model.cplex.getStatus().toString();
        out.phaseOne = model.phaseOne;
        out.solved = hasSolution && model.cplex.getStatus() == IloCplex.Status.Optimal;
        result.incrementalLpSolves++;
        result.rmpSolveTimeMillis += System.currentTimeMillis() - solveStart;
        if (!out.solved) return out;
        out.objective = model.cplex.getObjValue();
        out.integral = true;
        cacheDuals(model, out);
        out.zValues = new double[edges.size()];
        for (int e = 0; e < edges.size(); e++) {
            out.zValues[e] = model.cplex.getValue(model.z[e]);
            if (Math.abs(out.zValues[e] - Math.rint(out.zValues[e])) > 1e-5) out.integral = false;
        }
        out.yValues = new double[pathsBySource.size()][data.getCandidatePoints().size()];
        for (int s = 0; s < out.yValues.length; s++) {
            for (int m = 0; m < out.yValues[s].length; m++) {
                out.yValues[s][m] = model.cplex.getValue(model.y[s][m]);
                if (Math.abs(out.yValues[s][m] - Math.rint(out.yValues[s][m])) > 1e-5) out.integral = false;
            }
        }
        out.lambdaValues = new double[pathsBySource.size()][];
        out.lambdaReducedCostsFlat = new double[totalPathCount()];
        int flat = 0;
        for (int s = 0; s < pathsBySource.size(); s++) {
            out.lambdaValues[s] = new double[model.lambdaVars.get(s).size()];
            for (int p = 0; p < model.lambdaVars.get(s).size(); p++) {
                IloNumVar var = model.lambdaVars.get(s).get(p);
                out.lambdaValues[s][p] = model.cplex.getValue(var);
                out.lambdaReducedCostsFlat[flat++] = model.cplex.getReducedCost(var);
                if (Math.abs(out.lambdaValues[s][p] - Math.rint(out.lambdaValues[s][p])) > 1e-5) out.integral = false;
            }
        }
        return out;
    }

    private void cacheDuals(IncrementalRmpModel model, RmpSolution out) throws Exception {
        int sourceCount = pathsBySource.size();
        int facilityCount = data.getCandidatePoints().size();
        int edgeCount = edges.size();
        out.chooseDuals = new double[sourceCount];
        out.assignmentDuals = new double[sourceCount][facilityCount];
        out.useDuals = new double[sourceCount][edgeCount];
        out.reservationUsageDuals = new double[edgeCount];
        out.dualWDuals = new double[sourceCount][edgeCount];
        out.dualUDuals = new double[sourceCount];
        out.dualVDuals = new double[edgeCount];
        for (int s = 0; s < sourceCount; s++) {
            out.chooseDuals[s] = model.cplex.getDual(model.chooseRows[s]);
            out.dualUDuals[s] = model.cplex.getDual(model.dualURows[s]);
            for (int m = 0; m < facilityCount; m++) out.assignmentDuals[s][m] = model.cplex.getDual(model.assignmentRows[s][m]);
            for (int e = 0; e < edgeCount; e++) {
                out.useDuals[s][e] = model.cplex.getDual(model.useRows[s][e]);
                out.dualWDuals[s][e] = model.cplex.getDual(model.dualWRows[s][e]);
            }
        }
        for (int e = 0; e < edgeCount; e++) {
            out.dualVDuals[e] = model.cplex.getDual(model.dualVRows[e]);
            if (model.reservationUsageRows[e] != null)
                out.reservationUsageDuals[e] = model.cplex.getDual(model.reservationUsageRows[e]);
        }
        out.carrierCutDuals = new double[pathOptimalityCuts.size()];
        if (model.carrierCutRows != null) {
            for (int c = 0; c < model.carrierCutRows.length && c < out.carrierCutDuals.length; c++) {
                if (model.carrierCutRows[c] != null) out.carrierCutDuals[c] = model.cplex.getDual(model.carrierCutRows[c]);
            }
        }
        out.branchUsageDuals = new HashMap<>();
        for (Map.Entry<String, IloRange> row : model.branchUsageRows.entrySet()) {
            if (model.activeBranchUsage.contains(row.getKey()))
                out.branchUsageDuals.put(row.getKey(), model.cplex.getDual(row.getValue()));
        }
    }

    private void addIncrementalPathOptimalityCut(IncrementalRmpModel model, int cutIndex) throws Exception {
        if (cutIndex >= pathOptimalityCuts.size()) return;
        if (model.carrierCutRows == null || model.carrierCutRows.length < pathOptimalityCuts.size()) {
            IloRange[] grown = new IloRange[pathOptimalityCuts.size()];
            if (model.carrierCutRows != null) System.arraycopy(model.carrierCutRows, 0, grown, 0, model.carrierCutRows.length);
            model.carrierCutRows = grown;
        }
        if (model.carrierCutRows[cutIndex] != null) return;
        PathOptimalityCut cut = pathOptimalityCuts.get(cutIndex);
        int selected = -1;
        for (int p = 0; p < pathsBySource.get(cut.sourceIndex).size(); p++) {
            if (pathsBySource.get(cut.sourceIndex).get(p).key.equals(cut.selectedPathKey)) {
                selected = p;
                break;
            }
        }
        if (selected < 0 || selected >= model.lambdaVars.get(cut.sourceIndex).size()) return;
        IloLinearNumExpr expr = model.cplex.linearNumExpr();
        expr.addTerm(1.0, model.lambdaVars.get(cut.sourceIndex).get(selected));
        for (int e : cut.cheaperPathExtraEdges) expr.addTerm(1.0, model.z[e]);
        model.carrierCutRows[cutIndex] = model.cplex.addLe(expr, cut.cheaperPathExtraEdges.size(), "carrier_cut_" + cutIndex);
    }

    private void validateIncrementalColumnCounts(IncrementalRmpModel model) {
        for (int s = 0; s < pathsBySource.size(); s++) {
            if (model.lambdaVars.get(s).size() != pathsBySource.get(s).size()) {
                throw new IllegalStateException("Incremental lambda/path size mismatch for source " + s
                        + ": vars=" + model.lambdaVars.get(s).size() + ", paths=" + pathsBySource.get(s).size());
            }
        }
    }

    private RmpSolution solveRmp(boolean binary, BranchNode branch) throws Exception {
        RmpSolution out = new RmpSolution();
        IloCplex cplex = new IloCplex();
        out.cplex = cplex;
        cplex.setOut(null);
        cplex.setParam(IloCplex.Param.TimeLimit, timeLimitSeconds);

        IloNumVar[] z = new IloNumVar[edges.size()];
        for (int e = 0; e < edges.size(); e++) z[e] = binary ? cplex.boolVar("z_" + e) : cplex.numVar(0.0, 1.0, "z_" + e);
        ArrayList<IloNumVar[]> lambda = new ArrayList<>();
        for (int s = 0; s < pathsBySource.size(); s++) {
            IloNumVar[] vars = new IloNumVar[pathsBySource.get(s).size()];
            for (int p = 0; p < vars.length; p++) vars[p] = binary ? cplex.boolVar("lambda_" + s + "_" + p) : cplex.numVar(0.0, 1.0, "lambda_" + s + "_" + p);
            lambda.add(vars);
        }
        IloNumVar[][] y = new IloNumVar[pathsBySource.size()][data.getCandidatePoints().size()];
        for (int s = 0; s < y.length; s++) {
            for (int m = 0; m < y[s].length; m++) {
                y[s][m] = binary ? cplex.boolVar("y_" + s + "_" + m) : cplex.numVar(0.0, 1.0, "y_" + s + "_" + m);
            }
        }

        IloLinearNumExpr objective = cplex.linearNumExpr();
        IloLinearNumExpr budget = cplex.linearNumExpr();
        for (int e = 0; e < edges.size(); e++) {
            Edge edge = edges.get(e);
            if (edge.virtual) objective.addTerm(data.getCandidateBuildWeights().get(edge.candidateIndex), z[e]);
            else {
                objective.addTerm(data.getDedicatedLaneCostWeight().get(edge.u).get(edge.v), z[e]);
                budget.addTerm(data.getDedicatedLaneBuildCostWeight().get(edge.u).get(edge.v), z[e]);
            }
        }
        cplex.addLe(budget, data.getGovernmentRoadBudget(), "road_budget");
        for (Map.Entry<Integer, Integer> fix : branch.fixedZ.entrySet()) cplex.addEq(z[fix.getKey()], fix.getValue());

        out.chooseRows = new IloRange[pathsBySource.size()];
        out.assignmentRows = new IloRange[pathsBySource.size()][data.getCandidatePoints().size()];
        out.assignmentOpenRows = new IloRange[pathsBySource.size()][data.getCandidatePoints().size()];
        out.useRows = new IloRange[pathsBySource.size()][edges.size()];
        out.reservationUsageRows = new IloRange[edges.size()];
        for (int s = 0; s < pathsBySource.size(); s++) {
            IloLinearNumExpr choose = cplex.linearNumExpr();
            for (IloNumVar var : lambda.get(s)) choose.addTerm(1.0, var);
            out.chooseRows[s] = cplex.addEq(choose, 1.0, "choose_" + s);
            for (int m = 0; m < data.getCandidatePoints().size(); m++) {
                IloLinearNumExpr assign = cplex.linearNumExpr();
                assign.addTerm(1.0, y[s][m]);
                for (int p = 0; p < pathsBySource.get(s).size(); p++) {
                    if (pathsBySource.get(s).get(p).targetFacilityIndex == m) assign.addTerm(-1.0, lambda.get(s)[p]);
                }
                out.assignmentRows[s][m] = cplex.addEq(assign, 0.0, "assign_" + s + "_" + m);

                int facilityNode = data.getCandidatePoints().get(m);
                Integer virtualEdge = edgeIndex.get(key(facilityNode, virtualSink));
                IloLinearNumExpr open = cplex.linearNumExpr();
                open.addTerm(1.0, y[s][m]);
                if (virtualEdge != null) open.addTerm(-1.0, z[virtualEdge]);
                out.assignmentOpenRows[s][m] = cplex.addLe(open, 0.0, "assign_open_" + s + "_" + m);
            }
            for (int e = 0; e < edges.size(); e++) {
                IloLinearNumExpr use = cplex.linearNumExpr();
                for (int p = 0; p < pathsBySource.get(s).size(); p++) if (pathsBySource.get(s).get(p).uses(e)) use.addTerm(1.0, lambda.get(s)[p]);
                use.addTerm(-1.0, z[e]);
                out.useRows[s][e] = cplex.addLe(use, 0.0, "use_" + s + "_" + e);
            }
        }
        for (int e = 0; e < edges.size(); e++) {
            if (edges.get(e).virtual) continue;
            IloLinearNumExpr reserved = cplex.linearNumExpr();
            reserved.addTerm(1.0, z[e]);
            for (int s = 0; s < pathsBySource.size(); s++)
                for (int p = 0; p < pathsBySource.get(s).size(); p++)
                    if (pathsBySource.get(s).get(p).uses(e)) reserved.addTerm(-1.0, lambda.get(s)[p]);
            out.reservationUsageRows[e] = cplex.addLe(reserved, 0.0, "reserve_only_if_used_" + e);
        }
        for (Map.Entry<String, Integer> fix : branch.fixedAssignment.entrySet()) {
            String[] parts = fix.getKey().split("_");
            int s = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            cplex.addEq(y[s][m], fix.getValue(), "branch_assign_" + s + "_" + m);
        }
        for (Map.Entry<String, Integer> fix : branch.fixedUsage.entrySet()) {
            String[] parts = fix.getKey().split("_");
            int s = Integer.parseInt(parts[0]);
            int e = Integer.parseInt(parts[1]);
            IloLinearNumExpr usage = cplex.linearNumExpr();
            for (int p = 0; p < pathsBySource.get(s).size(); p++) if (pathsBySource.get(s).get(p).uses(e)) usage.addTerm(1.0, lambda.get(s)[p]);
            cplex.addEq(usage, fix.getValue(), "branch_usage_" + s + "_" + e);
        }
        out.carrierCutRows = addStoredPathOptimalityCuts(cplex, lambda, z);

        addRobustRiskDual(cplex, lambda, objective, out);
        cplex.addMinimize(objective);
        out.solved = cplex.solve();
        if (!out.solved) return out;
        out.objective = cplex.getObjValue();
        out.zValues = new double[edges.size()];
        for (int e = 0; e < edges.size(); e++) {
            out.zValues[e] = cplex.getValue(z[e]);
            if (Math.abs(out.zValues[e] - Math.rint(out.zValues[e])) > 1e-5) out.integral = false;
        }
        out.yValues = new double[pathsBySource.size()][data.getCandidatePoints().size()];
        for (int s = 0; s < out.yValues.length; s++) {
            for (int m = 0; m < out.yValues[s].length; m++) {
                out.yValues[s][m] = cplex.getValue(y[s][m]);
                if (Math.abs(out.yValues[s][m] - Math.rint(out.yValues[s][m])) > 1e-5) out.integral = false;
            }
        }
        out.lambdaValues = new double[pathsBySource.size()][];
        out.lambdaReducedCostsFlat = new double[totalPathCount()];
        int flat = 0;
        for (int s = 0; s < pathsBySource.size(); s++) {
            out.lambdaValues[s] = new double[lambda.get(s).length];
            for (int p = 0; p < lambda.get(s).length; p++) {
                out.lambdaValues[s][p] = cplex.getValue(lambda.get(s)[p]);
                if (!binary) out.lambdaReducedCostsFlat[flat++] = cplex.getReducedCost(lambda.get(s)[p]);
                if (Math.abs(out.lambdaValues[s][p] - Math.rint(out.lambdaValues[s][p])) > 1e-5) out.integral = false;
            }
        }
        return out;
    }

    private IloRange[] addStoredPathOptimalityCuts(IloCplex cplex, ArrayList<IloNumVar[]> lambda, IloNumVar[] z) throws Exception {
        IloRange[] rows = new IloRange[pathOptimalityCuts.size()];
        for (int c = 0; c < pathOptimalityCuts.size(); c++) {
            PathOptimalityCut cut = pathOptimalityCuts.get(c);
            int selected = -1;
            for (int p = 0; p < pathsBySource.get(cut.sourceIndex).size(); p++) {
                if (pathsBySource.get(cut.sourceIndex).get(p).key.equals(cut.selectedPathKey)) {
                    selected = p;
                    break;
                }
            }
            if (selected < 0) continue;
            IloLinearNumExpr expr = cplex.linearNumExpr();
            expr.addTerm(1.0, lambda.get(cut.sourceIndex)[selected]);
            for (int e : cut.cheaperPathExtraEdges) expr.addTerm(1.0, z[e]);
            rows[c] = cplex.addLe(expr, cut.cheaperPathExtraEdges.size(), "carrier_cut_" + c);
        }
        return rows;
    }

    private int addCarrierCuts(RmpSolution integer, IncrementalRmpModel model, Result result) throws Exception {
        int added = 0;
        boolean[] allowed = new boolean[edges.size()];
        for (int e = 0; e < edges.size(); e++) allowed[e] = integer.zValues[e] > 0.5;
        double[] trueCosts = trueArcCosts();
        for (int s = 0; s < pathsBySource.size(); s++) {
            Path selected = null;
            for (int p = 0; p < pathsBySource.get(s).size(); p++) {
                if (integer.lambdaValues[s][p] > 0.5) {
                    selected = pathsBySource.get(s).get(p);
                    break;
                }
            }
            if (selected == null) continue;
            int targetFacility = selected.nodePath.get(selected.nodePath.size() - 1);
            ArrayList<Path> shortest = findBestPathsToAnyOpenFacility(s, trueCosts, 0.0, allowed, Math.max(1, carrierCutsPerSource * 5));
            if (shortest.isEmpty()) continue;
            int addedForSource = 0;
            for (Path carrier : shortest) {
                if (addedForSource >= carrierCutsPerSource) break;
                if (!(carrier.travelCost < selected.travelCost - 1e-5) || carrier.key.equals(selected.key)) continue;
                addPathAndColumn(model, carrier, result);
                HashSet<Integer> selectedEdges = new HashSet<>(selected.edgeIndices);
                HashSet<Integer> extra = new HashSet<>();
                for (int e : carrier.edgeIndices) if (!selectedEdges.contains(e)) extra.add(e);
                if (extra.isEmpty()) continue;
                PathOptimalityCut cut = new PathOptimalityCut(s, selected.key, extra);
                if (pathOptimalityCutKeys.add(cut.signature())) {
                    pathOptimalityCuts.add(cut);
                    addIncrementalPathOptimalityCut(model, pathOptimalityCuts.size() - 1);
                    result.carrierCutsAddedIncrementally++;
                    added++;
                    addedForSource++;
                }
            }
        }
        return added;
    }

    private void addRobustRiskDual(IloCplex cplex, ArrayList<IloNumVar[]> lambda, IloLinearNumExpr objective, RmpSolution rows) throws Exception {
        int sourceCount = data.getStartPoints().size();
        int edgeCount = edges.size();
        IloNumVar[] rho = new IloNumVar[sourceCount];
        IloNumVar[] xi = new IloNumVar[edgeCount];
        IloNumVar[][] eta = new IloNumVar[sourceCount][edgeCount];
        IloNumVar[][] pi = new IloNumVar[sourceCount][edgeCount];
        rows.dualWRows = new IloRange[sourceCount][edgeCount];
        rows.dualURows = new IloRange[sourceCount];
        rows.dualVRows = new IloRange[edgeCount];

        for (int s = 0; s < sourceCount; s++) {
            rho[s] = cplex.numVar(0.0, Double.MAX_VALUE, "rho_" + s);
            objective.addTerm(1.0, rho[s]);
        }
        for (int e = 0; e < edgeCount; e++) {
            xi[e] = cplex.numVar(0.0, Double.MAX_VALUE, "xi_" + e);
            objective.addTerm(1.0, xi[e]);
        }
        for (int s = 0; s < sourceCount; s++) for (int e = 0; e < edgeCount; e++) {
            eta[s][e] = cplex.numVar(0.0, Double.MAX_VALUE, "eta_" + s + "_" + e);
            pi[s][e] = cplex.numVar(0.0, Double.MAX_VALUE, "pi_" + s + "_" + e);
        }
        IloNumVar thetaU = cplex.numVar(0.0, Double.MAX_VALUE, "theta_u");
        IloNumVar thetaV = cplex.numVar(0.0, Double.MAX_VALUE, "theta_v");
        objective.addTerm(data.getQuantityRiskExpectation(), thetaU);
        objective.addTerm(data.getRoadRiskExpectation(), thetaV);

        for (int s = 0; s < sourceCount; s++) {
            double n = data.getMinTransportTasks().get(s);
            double k = data.getTransportTaskBuffs().get(s);
            IloLinearNumExpr demand = cplex.linearNumExpr();
            demand.addTerm(1.0, rho[s]);
            demand.addTerm(1.0, thetaU);
            for (int e = 0; e < edgeCount; e++) {
                double r = riskMin(edges.get(e), s);
                double q = riskWidth(edges.get(e), s);
                if (data.getIncludeNominalRiskInObjective()) addPathEdgeTerms(objective, lambda, s, e, n * r);
                demand.addTerm(-1.0, eta[s][e]);
                addPathEdgeTerms(demand, lambda, s, e, -k * r);
                IloLinearNumExpr w = cplex.linearNumExpr();
                w.addTerm(1.0, eta[s][e]);
                w.addTerm(1.0, pi[s][e]);
                addPathEdgeTerms(w, lambda, s, e, -k * q);
                rows.dualWRows[s][e] = cplex.addGe(w, 0.0, "dual_w_" + s + "_" + e);
            }
            rows.dualURows[s] = cplex.addGe(demand, 0.0, "dual_u_" + s);
        }
        for (int e = 0; e < edgeCount; e++) {
            IloLinearNumExpr v = cplex.linearNumExpr();
            v.addTerm(1.0, xi[e]);
            v.addTerm(1.0, thetaV);
            for (int s = 0; s < sourceCount; s++) {
                double n = data.getMinTransportTasks().get(s);
                double q = riskWidth(edges.get(e), s);
                v.addTerm(-1.0, pi[s][e]);
                addPathEdgeTerms(v, lambda, s, e, -n * q);
            }
            rows.dualVRows[e] = cplex.addGe(v, 0.0, "dual_v_" + e);
        }
    }

    private void addPathEdgeTerms(IloLinearNumExpr expr, ArrayList<IloNumVar[]> lambda, int s, int e, double coefficient) throws Exception {
        if (Math.abs(coefficient) < 1e-12) return;
        for (int p = 0; p < pathsBySource.get(s).size(); p++) if (pathsBySource.get(s).get(p).uses(e)) expr.addTerm(coefficient, lambda.get(s)[p]);
    }

    private ReducedCostMode detectReducedCostMode(RmpSolution lp) throws Exception {
        double errMinus = reducedCostError(lp, -1.0);
        // CPLEX returns row duals pi with reduced cost c - A'pi. Do not
        // select a dual convention by fitting a potentially degenerate pool.
        if (errMinus < 1e-5) return new ReducedCostMode(true, -1.0, "");
        return new ReducedCostMode(false, -1.0, "reduced-cost check failed: " + errMinus);
    }

    private double reducedCostError(RmpSolution lp, double sign) throws Exception {
        double maxErr = 0.0;
        int flat = 0;
        for (int s = 0; s < pathsBySource.size(); s++) {
            for (Path path : pathsBySource.get(s)) {
                maxErr = Math.max(maxErr, Math.abs(manualReducedCost(lp, sign, path) - lp.lambdaReducedCostsFlat[flat++]));
            }
        }
        return maxErr;
    }

    private double manualReducedCost(RmpSolution lp, double sign, Path path) throws Exception {
        double value = lp.phaseOne ? 0.0 : pathObjectiveCoefficient(path);
        value += sign * dualChoose(lp, path.sourceIndex);
        value += sign * dualAssignment(lp, path.sourceIndex, path.targetFacilityIndex) * (-1.0);
        double dualU = dualU(lp, path.sourceIndex);
        double k = data.getTransportTaskBuffs().get(path.sourceIndex);
        double n = data.getMinTransportTasks().get(path.sourceIndex);
        for (int e : path.edgeIndices) {
            value += sign * dualUse(lp, path.sourceIndex, e);
            value -= sign * dualReservationUsage(lp, e);
            value += sign * dualBranchUsage(lp, path.sourceIndex, e);
            value += sign * dualW(lp, path.sourceIndex, e) * (-k * riskWidth(edges.get(e), path.sourceIndex));
            value += sign * dualU * (-k * riskMin(edges.get(e), path.sourceIndex));
            value += sign * dualV(lp, e) * (-n * riskWidth(edges.get(e), path.sourceIndex));
        }
        if (lp.carrierCutRows != null) {
            for (int c = 0; c < pathOptimalityCuts.size(); c++) {
                PathOptimalityCut cut = pathOptimalityCuts.get(c);
                if (cut.sourceIndex == path.sourceIndex && cut.selectedPathKey.equals(path.key) && lp.carrierCutRows[c] != null) {
                    value += sign * dualCarrier(lp, c);
                }
            }
        }
        return value;
    }

    private double[] buildFacilityReducedCostConstants(RmpSolution lp, double sign, int sourceIndex, double sourceConstant) throws Exception {
        double[] constants = new double[data.getCandidatePoints().size()];
        for (int m = 0; m < constants.length; m++) {
            constants[m] = sourceConstant + sign * dualAssignment(lp, sourceIndex, m) * (-1.0);
        }
        return constants;
    }

    private double[] buildArcReducedCosts(RmpSolution lp, double sign, int sourceIndex) throws Exception {
        double[] costs = new double[edges.size()];
        double dualU = dualU(lp, sourceIndex);
        double k = data.getTransportTaskBuffs().get(sourceIndex);
        double n = data.getMinTransportTasks().get(sourceIndex);
        for (int e = 0; e < edges.size(); e++) {
            costs[e] = !lp.phaseOne && data.getIncludeNominalRiskInObjective() ? n * riskMin(edges.get(e), sourceIndex) : 0.0;
            costs[e] += sign * dualUse(lp, sourceIndex, e);
            costs[e] -= sign * dualReservationUsage(lp, e);
            costs[e] += sign * dualBranchUsage(lp, sourceIndex, e);
            costs[e] += sign * dualW(lp, sourceIndex, e) * (-k * riskWidth(edges.get(e), sourceIndex));
            costs[e] += sign * dualU * (-k * riskMin(edges.get(e), sourceIndex));
            costs[e] += sign * dualV(lp, e) * (-n * riskWidth(edges.get(e), sourceIndex));
        }
        return costs;
    }

    private double dualChoose(RmpSolution lp, int s) throws Exception {
        return lp.chooseDuals != null ? lp.chooseDuals[s] : lp.cplex.getDual(lp.chooseRows[s]);
    }
    private double dualAssignment(RmpSolution lp, int s, int m) throws Exception {
        return lp.assignmentDuals != null ? lp.assignmentDuals[s][m] : lp.cplex.getDual(lp.assignmentRows[s][m]);
    }
    private double dualUse(RmpSolution lp, int s, int e) throws Exception {
        return lp.useDuals != null ? lp.useDuals[s][e] : lp.cplex.getDual(lp.useRows[s][e]);
    }
    private double dualReservationUsage(RmpSolution lp, int e) throws Exception {
        if (lp.reservationUsageDuals != null) return lp.reservationUsageDuals[e];
        return lp.reservationUsageRows != null && lp.reservationUsageRows[e] != null
                ? lp.cplex.getDual(lp.reservationUsageRows[e]) : 0.0;
    }
    private double dualW(RmpSolution lp, int s, int e) throws Exception {
        return lp.dualWDuals != null ? lp.dualWDuals[s][e] : lp.cplex.getDual(lp.dualWRows[s][e]);
    }
    private double dualU(RmpSolution lp, int s) throws Exception {
        return lp.dualUDuals != null ? lp.dualUDuals[s] : lp.cplex.getDual(lp.dualURows[s]);
    }
    private double dualV(RmpSolution lp, int e) throws Exception {
        return lp.dualVDuals != null ? lp.dualVDuals[e] : lp.cplex.getDual(lp.dualVRows[e]);
    }
    private double dualCarrier(RmpSolution lp, int c) throws Exception {
        return lp.carrierCutDuals != null && c < lp.carrierCutDuals.length ? lp.carrierCutDuals[c] : lp.cplex.getDual(lp.carrierCutRows[c]);
    }
    private double dualBranchUsage(RmpSolution lp, int s, int e) {
        if (lp.branchUsageDuals == null) return 0.0;
        Double value = lp.branchUsageDuals.get(usageKey(s, e));
        return value == null ? 0.0 : value;
    }

    private ArrayList<Path> findBestPaths(int sourceIndex, double[] arcCosts, double sourceConstant, int limit, BranchNode branch, boolean requireNegative) {
        double[] facilityConstants = constantByFacility(sourceConstant);
        return findBestPaths(sourceIndex, arcCosts, sourceConstant, facilityConstants, limit, branch, requireNegative, null);
    }

    private ArrayList<Path> findBestPaths(int sourceIndex, double[] arcCosts, double[] facilityConstants, int limit, BranchNode branch, boolean requireNegative) {
        return findBestPaths(sourceIndex, arcCosts, 0.0, facilityConstants, limit, branch, requireNegative, null);
    }

    private ArrayList<Path> findBestPaths(int sourceIndex, double[] arcCosts, double sourceConstant, double[] facilityConstants, int limit, BranchNode branch, boolean requireNegative, PricingStats stats) {
        boolean[] allowed = new boolean[edges.size()];
        Arrays.fill(allowed, true);
        HashSet<Integer> required = new HashSet<>();
        if (branch != null) {
            for (Map.Entry<Integer, Integer> fix : branch.fixedZ.entrySet()) if (fix.getValue() == 0) allowed[fix.getKey()] = false;
            for (Map.Entry<String, Integer> fix : branch.fixedUsage.entrySet()) {
                String[] parts = fix.getKey().split("_");
                int s = Integer.parseInt(parts[0]);
                int e = Integer.parseInt(parts[1]);
                if (s == sourceIndex) {
                    if (fix.getValue() == 0) allowed[e] = false;
                    else required.add(e);
                }
            }
        }
        return findBestPaths(sourceIndex, arcCosts, sourceConstant, facilityConstants, limit, branch, requireNegative, allowed, required, stats);
    }

    private ArrayList<Path> findBestPaths(int sourceIndex, double[] arcCosts, double sourceConstant, int limit, BranchNode branch, boolean requireNegative, boolean[] allowedEdges) {
        return findBestPaths(sourceIndex, arcCosts, sourceConstant, constantByFacility(sourceConstant), limit, branch, requireNegative, allowedEdges, new HashSet<>(), null);
    }

    private ArrayList<Path> findBestPaths(int sourceIndex, double[] arcCosts, double sourceConstant, double[] facilityConstants, int limit, BranchNode branch, boolean requireNegative, boolean[] allowedEdges, HashSet<Integer> requiredEdges, PricingStats stats) {
        // Legacy bounded proposal helper. Active reduced-cost pricing now uses
        // BpaExactLabelPricing; this heuristic is never a convergence certificate.
        // The carrier oracle uses a separate method and keeps its original cap.
        int heuristicLabelLimit = Math.min(10000, maxLabelsPerPricing);
        long heuristicDeadline = Math.min(deadlineMillis, System.currentTimeMillis() + 100L);
        int source = data.getStartPoints().get(sourceIndex);
        double timeLimit = data.getTimeWindow().get(sourceIndex);
        PriorityQueue<Label> queue = new PriorityQueue<>(Comparator.comparingDouble(a -> a.cost));
        ArrayList<Label>[] labelsByNode = new ArrayList[nodeCount];
        for (int i = 0; i < nodeCount; i++) labelsByNode[i] = new ArrayList<>();
        ArrayList<Path> found = new ArrayList<>();
        double[] minRemainingTime = shortestRemainingTimesToAllowedFacilities(sourceIndex, branch, allowedEdges);
        double minArcReduced = minAllowedArcReducedCost(arcCosts, allowedEdges);
        double minFacilityConstant = minAllowedFacilityConstant(sourceConstant, facilityConstants, sourceIndex, branch, allowedEdges);
        Label root = new Label(source, BigInteger.ONE.shiftLeft(source), 0.0, 0.0, 0.0, null, -1);
        queue.add(root);
        labelsByNode[source].add(root);
        int expanded = 0;
        while (!queue.isEmpty() && found.size() < limit && expanded < heuristicLabelLimit
                && System.currentTimeMillis() < heuristicDeadline) {
            Label label = queue.poll();
            if (!labelsByNode[label.node].contains(label)) continue;
            if (stats != null) stats.labelsExpanded++;
            expanded++;
            if (data.getCandidatePoints().contains(label.node)) {
                Integer virtual = edgeIndex.get(key(label.node, virtualSink));
                int facilityIndex = data.getCandidatePoints().indexOf(label.node);
                if (virtual != null && allowedEdges[virtual] && assignmentAllowed(branch, sourceIndex, facilityIndex)) {
                    Label terminal = new Label(virtualSink, label.visitedMask, label.cost + arcCosts[virtual], label.travelCost, label.time, label, virtual);
                    Path path = pathFromLabel(sourceIndex, source, terminal, facilityConstants[facilityIndex] + terminal.cost);
                    boolean newColumn = !pathKeysBySource.get(sourceIndex).contains(path.key);
                    if (path.edgeIndices.containsAll(requiredEdges)
                            && (!requireNegative || (path.reducedCost < -eps && newColumn))) {
                        found.add(path);
                    }
                }
            }
            for (int next = 0; next < nodeCount; next++) {
                if (label.visitedMask.testBit(next)) continue;
                if (data.getGraph().get(label.node).get(next) != 1) continue;
                Integer edge = edgeIndex.get(key(label.node, next));
                if (edge == null || !allowedEdges[edge]) continue;
                double nextTime = label.time + data.getNodeTime().get(next).get(label.node);
                if (nextTime > timeLimit + eps) continue;
                if (stats != null) stats.labelsGenerated++;
                if (minRemainingTime[next] == Double.POSITIVE_INFINITY || nextTime + minRemainingTime[next] > timeLimit + eps) {
                    if (stats != null) stats.labelsPrunedByTimeReachability++;
                    continue;
                }
                double travelCost = label.travelCost + data.getNodeDistance().get(label.node).get(next);
                Label candidate = new Label(next, label.visitedMask.setBit(next), label.cost + arcCosts[edge], travelCost, nextTime, label, edge);
                if (pruneByGlobalReducedCostBound(candidate, sourceConstant, minFacilityConstant, minArcReduced, stats)) continue;
                if (pruneByDominance(candidate, labelsByNode[next], stats)) continue;
                labelsByNode[next].add(candidate);
                queue.add(candidate);
            }
        }
        if (stats != null && !queue.isEmpty() && found.size() < limit
                && (expanded >= heuristicLabelLimit || System.currentTimeMillis() >= heuristicDeadline))
            stats.heuristicTruncations++;
        return found;
    }

    private double[] constantByFacility(double value) {
        double[] constants = new double[data.getCandidatePoints().size()];
        Arrays.fill(constants, value);
        return constants;
    }

    private boolean assignmentAllowed(BranchNode branch, int sourceIndex, int facilityIndex) {
        if (branch == null) return true;
        Integer fixed = branch.fixedAssignment.get(assignmentKey(sourceIndex, facilityIndex));
        if (fixed != null && fixed == 0) return false;
        for (Map.Entry<String, Integer> entry : branch.fixedAssignment.entrySet()) {
            if (entry.getValue() != 1) continue;
            String[] parts = entry.getKey().split("_");
            int s = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            if (s == sourceIndex && m != facilityIndex) return false;
        }
        return true;
    }

    private double[] shortestRemainingTimesToAllowedFacilities(int sourceIndex, BranchNode branch, boolean[] allowedEdges) {
        double[] dist = new double[nodeCount];
        boolean[] done = new boolean[nodeCount];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        for (int m = 0; m < data.getCandidatePoints().size(); m++) {
            int facility = data.getCandidatePoints().get(m);
            Integer virtual = edgeIndex.get(key(facility, virtualSink));
            if (virtual != null && allowedEdges[virtual] && assignmentAllowed(branch, sourceIndex, m)) dist[facility] = 0.0;
        }
        for (int iter = 0; iter < nodeCount; iter++) {
            int u = -1;
            double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i < nodeCount; i++) if (!done[i] && dist[i] < best) {
                best = dist[i];
                u = i;
            }
            if (u < 0) break;
            done[u] = true;
            for (int prev = 0; prev < nodeCount; prev++) {
                if (data.getGraph().get(prev).get(u) != 1) continue;
                Integer edge = edgeIndex.get(key(prev, u));
                if (edge == null || !allowedEdges[edge]) continue;
                double time = data.getNodeTime().get(u).get(prev);
                if (dist[u] + time < dist[prev]) dist[prev] = dist[u] + time;
            }
        }
        return dist;
    }

    private double minAllowedArcReducedCost(double[] arcCosts, boolean[] allowedEdges) {
        double min = Double.POSITIVE_INFINITY;
        for (int e = 0; e < arcCosts.length; e++) if (allowedEdges[e] && arcCosts[e] < min) min = arcCosts[e];
        return min == Double.POSITIVE_INFINITY ? 0.0 : min;
    }

    private double minAllowedFacilityConstant(double sourceConstant, double[] facilityConstants, int sourceIndex, BranchNode branch, boolean[] allowedEdges) {
        double min = Double.POSITIVE_INFINITY;
        for (int m = 0; m < data.getCandidatePoints().size(); m++) {
            int facility = data.getCandidatePoints().get(m);
            Integer virtual = edgeIndex.get(key(facility, virtualSink));
            if (virtual != null && allowedEdges[virtual] && assignmentAllowed(branch, sourceIndex, m)) {
                min = Math.min(min, facilityConstants[m] - sourceConstant);
            }
        }
        return min;
    }

    private boolean pruneByGlobalReducedCostBound(Label label, double sourceConstant, double minFacilityConstant, double minArcReduced, PricingStats stats) {
        if (stats != null) stats.globalReducedCostBoundChecks++;
        if (minFacilityConstant == Double.POSITIVE_INFINITY) return false;
        int remainingArcUpperBound = Math.max(1, nodeCount - label.visitedMask.bitCount() + 1);
        double lowerBound = minFacilityConstant + remainingArcUpperBound * Math.min(0.0, minArcReduced);
        if (sourceConstant + label.cost + lowerBound >= -eps) {
            if (stats != null) stats.labelsPrunedByGlobalReducedCostBound++;
            return true;
        }
        return false;
    }

    private boolean pruneByDominance(Label candidate, ArrayList<Label> labelsAtNode, PricingStats stats) {
        for (Label existing : labelsAtNode) {
            if (stats != null) stats.dominanceChecks++;
            if (dominates(existing, candidate)) {
                if (stats != null) stats.labelsPrunedByDominance++;
                return true;
            }
        }
        Iterator<Label> iterator = labelsAtNode.iterator();
        while (iterator.hasNext()) {
            Label existing = iterator.next();
            if (stats != null) stats.dominanceChecks++;
            if (dominates(candidate, existing)) iterator.remove();
        }
        return false;
    }

    private boolean dominates(Label a, Label b) {
        return a.cost <= b.cost + eps
                && a.time <= b.time + eps
                && a.visitedMask.andNot(b.visitedMask).signum() == 0;
    }

    private boolean emExactCarrierOracle = false;
    private ArrayList<Path> findBestPathsToAnyOpenFacility(int sourceIndex, double[] costs, double sourceConstant, boolean[] allowed, int limit) {
        boolean previous = emExactCarrierOracle;
        emExactCarrierOracle = true;
        try {
        ArrayList<Path> best = new ArrayList<>();
        for (int facility : data.getCandidatePoints()) {
            Integer virtual = edgeIndex.get(key(facility, virtualSink));
            if (virtual == null || !allowed[virtual]) continue;
            best.addAll(findBestPathToFacility(sourceIndex, costs, sourceConstant, facility, allowed));
        }
        best.sort(Comparator.comparingDouble(path -> path.travelCost));
        if (best.size() > limit) return new ArrayList<>(best.subList(0, limit));
        return best;
        } finally { emExactCarrierOracle = previous; }
    }

    private ArrayList<Path> findBestPathToFacility(int sourceIndex, double[] arcCosts, double sourceConstant, int targetFacility, boolean[] allowedEdges) {
        return findBestPathsToFacility(sourceIndex, arcCosts, sourceConstant, targetFacility, allowedEdges, 1);
    }

    private ArrayList<Path> findBestPathsToFacility(int sourceIndex, double[] arcCosts, double sourceConstant, int targetFacility, boolean[] allowedEdges, int limit) {
        int source = data.getStartPoints().get(sourceIndex);
        double timeLimit = data.getTimeWindow().get(sourceIndex);
        Integer virtual = edgeIndex.get(key(targetFacility, virtualSink));
        ArrayList<Path> found = new ArrayList<>();
        if (virtual == null || !allowedEdges[virtual]) return found;
        HashSet<String> foundKeys = new HashSet<>();
        PriorityQueue<Label> queue = new PriorityQueue<>(Comparator.comparingDouble(a -> a.cost));
        queue.add(new Label(source, BigInteger.ONE.shiftLeft(source), 0.0, 0.0, 0.0, null, -1));
        int expanded = 0;
        while (!queue.isEmpty() && found.size() < limit && expanded < maxLabelsPerPricing && System.currentTimeMillis() < deadlineMillis) {
            Label label = queue.poll();
            expanded++;
            if (label.node == targetFacility) {
                Label terminal = new Label(virtualSink, label.visitedMask, label.cost + arcCosts[virtual], label.travelCost, label.time, label, virtual);
                Path path = pathFromLabel(sourceIndex, source, terminal, sourceConstant + terminal.cost);
                if (foundKeys.add(path.key)) found.add(path);
                continue;
            }
            for (int next = 0; next < nodeCount; next++) {
                if (label.visitedMask.testBit(next)) continue;
                if (data.getGraph().get(label.node).get(next) != 1) continue;
                Integer edge = edgeIndex.get(key(label.node, next));
                if (edge == null || !allowedEdges[edge]) continue;
                double nextTime = label.time + data.getNodeTime().get(next).get(label.node);
                if (nextTime > timeLimit + eps) continue;
                double travelCost = label.travelCost + data.getNodeDistance().get(label.node).get(next);
                queue.add(new Label(next, label.visitedMask.setBit(next), label.cost + arcCosts[edge], travelCost, nextTime, label, edge));
            }
        }
        if (emExactCarrierOracle && !queue.isEmpty() && found.size() < limit
                && (expanded >= maxLabelsPerPricing || System.currentTimeMillis() >= deadlineMillis))
            throw new IllegalStateException("BPA carrier search resource limit reached; no optimality conclusion");
        return found;
    }

    private Path pathFromLabel(int sourceIndex, int source, Label terminal, double reducedCost) {
        ArrayList<Integer> reversedEdges = new ArrayList<>();
        ArrayList<Integer> reversedNodes = new ArrayList<>();
        Label cur = terminal;
        while (cur != null && cur.prev != null) {
            reversedEdges.add(cur.edgeFromPrev);
            if (cur.node != virtualSink) reversedNodes.add(cur.node);
            cur = cur.prev;
        }
        ArrayList<Integer> pathEdges = new ArrayList<>();
        for (int i = reversedEdges.size() - 1; i >= 0; i--) pathEdges.add(reversedEdges.get(i));
        ArrayList<Integer> nodes = new ArrayList<>();
        nodes.add(source);
        for (int i = reversedNodes.size() - 1; i >= 0; i--) nodes.add(reversedNodes.get(i));
        int targetFacilityNode = nodes.get(nodes.size() - 1);
        int targetFacilityIndex = data.getCandidatePoints().indexOf(targetFacilityNode);
        return new Path(sourceIndex, source, pathEdges, nodes, terminal.travelCost, reducedCost, terminal.time, targetFacilityNode, targetFacilityIndex);
    }

    private void buildEdges() {
        for (int i = 0; i < nodeCount; i++) for (int j = 0; j < nodeCount; j++) {
            if (i != j && data.getGraph().get(i).get(j) == 1) {
                double travelCost = data.getNodeDistance().get(i).get(j);
                double travelTime = data.getNodeTime().get(j).get(i);
                if (!Double.isFinite(travelCost) || travelCost < 0.0
                        || !Double.isFinite(travelTime) || travelTime < 0.0)
                    throw new IllegalArgumentException("Carrier search requires finite nonnegative cost/time at " + i + "->" + j);
                int idx = edges.size();
                edges.add(new Edge(i, j, false, -1));
                edgeIndex.put(key(i, j), idx);
            }
        }
        for (int c = 0; c < data.getCandidatePoints().size(); c++) {
            int facility = data.getCandidatePoints().get(c);
            int idx = edges.size();
            edges.add(new Edge(facility, virtualSink, true, c));
            edgeIndex.put(key(facility, virtualSink), idx);
        }
    }

    private void initializePathPool() {
        for (int s = 0; s < data.getStartPoints().size(); s++) {
            pathsBySource.add(new ArrayList<>());
            pathKeysBySource.add(new HashSet<>());
            ArrayList<Path> initial = new ArrayList<>();
            double[][] metrics = initialArcCostMetrics(s);
            for (int facility : data.getCandidatePoints()) {
                for (double[] metric : metrics) {
                    initial.addAll(findBestPathsToFacility(s, metric, 0.0, facility, allAllowed(), initialPathsPerSourceFacility));
                }
            }
            // An empty heuristic pool is repaired by Phase I; it is not an infeasibility proof.
            for (Path path : initial) addPath(path);
        }
    }

    private double[][] initialArcCostMetrics(int sourceIndex) {
        double[] transport = trueArcCosts();
        double[] risk = riskArcCosts(sourceIndex);
        double[] impact = impactArcCosts();
        boolean nearZeroImpact = false;
        for (int e = 0; e < edges.size(); e++) {
            if (!edges.get(e).virtual && impact[e] <= 1e-10) { nearZeroImpact = true; break; }
        }
        if (nearZeroImpact) return new double[][] {transport, risk, combine(transport, risk, 1.0, 1.0)};
        return new double[][]{
                transport,
                risk,
                impact,
                combine(transport, risk, 1.0, 1.0),
                combine(transport, impact, 1.0, 1.0),
                combine(risk, impact, 1.0, 1.0),
                combine(combine(transport, risk, 1.0, 1.0), impact, 1.0, 1.0),
                impactRiskArcCosts(sourceIndex)
        };
    }

    private double[] combine(double[] a, double[] b, double wa, double wb) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) out[i] = wa * a[i] + wb * b[i];
        return out;
    }

    private boolean[] allAllowed() {
        boolean[] allowed = new boolean[edges.size()];
        Arrays.fill(allowed, true);
        return allowed;
    }

    private boolean addPath(Path path) {
        HashSet<String> keys = pathKeysBySource.get(path.sourceIndex);
        if (keys.contains(path.key)) return false;
        keys.add(path.key);
        pathsBySource.get(path.sourceIndex).add(path);
        return true;
    }

    private double[] trueArcCosts() {
        double[] costs = new double[edges.size()];
        for (int e = 0; e < edges.size(); e++) costs[e] = edges.get(e).virtual ? 0.0 : data.getNodeDistance().get(edges.get(e).u).get(edges.get(e).v);
        return costs;
    }

    private double[] riskArcCosts(int sourceIndex) {
        double[] costs = new double[edges.size()];
        double n = data.getMinTransportTasks().get(sourceIndex);
        for (int e = 0; e < edges.size(); e++) costs[e] = n * riskMin(edges.get(e), sourceIndex);
        return costs;
    }
    private double[] impactArcCosts() {
        double[] costs = new double[edges.size()];
        for (int e = 0; e < edges.size(); e++) {
            Edge edge = edges.get(e);
            costs[e] = edge.virtual ? data.getCandidateBuildWeights().get(edge.candidateIndex)
                    : data.getDedicatedLaneCostWeight().get(edge.u).get(edge.v);
        }
        return costs;
    }

    private double[] impactRiskArcCosts(int sourceIndex) {
        double[] costs = new double[edges.size()];
        double n = data.getMinTransportTasks().get(sourceIndex);
        for (int e = 0; e < edges.size(); e++) {
            Edge edge = edges.get(e);
            costs[e] = edge.virtual ? data.getCandidateBuildWeights().get(edge.candidateIndex)
                    : data.getDedicatedLaneCostWeight().get(edge.u).get(edge.v)
                    + (data.getIncludeNominalRiskInObjective() ? n * riskMin(edge, sourceIndex) : 0.0);
        }
        return costs;
    }

    private double riskMin(Edge edge, int sourceIndex) {
        if (edge.virtual) return 0.0;
        if (data.getSourceMinRisks() != null) return data.getSourceMinRisks().get(sourceIndex).get(edge.u).get(edge.v);
        return data.getMinRisks().get(edge.u).get(edge.v);
    }
    private double riskWidth(Edge edge, int sourceIndex) {
        if (edge.virtual) return 0.0;
        if (data.getSourceRiskBuffs() != null) return data.getSourceRiskBuffs().get(sourceIndex).get(edge.u).get(edge.v);
        return data.getRiskBuffs().get(edge.u).get(edge.v);
    }
    private double pathObjectiveCoefficient(Path path) {
        double n = data.getMinTransportTasks().get(path.sourceIndex);
        double value = 0.0;
        if (data.getIncludeNominalRiskInObjective()) {
            for (int e : path.edgeIndices) value += n * riskMin(edges.get(e), path.sourceIndex);
        }
        return value;
    }
    private int totalPathCount() {
        int total = 0;
        for (ArrayList<Path> ps : pathsBySource) total += ps.size();
        return total;
    }
    private String key(int u, int v) { return u + "_" + v; }
    private String usageKey(int s, int e) { return s + "_" + e; }
    private String assignmentKey(int s, int m) { return s + "_" + m; }
    private String selectedRoadEdges(double[] zValues) {
        ArrayList<String> selected = new ArrayList<>();
        for (int e = 0; e < edges.size(); e++) {
            Edge edge = edges.get(e);
            if (!edge.virtual && zValues[e] > 0.5) selected.add(edge.u + "-" + edge.v);
        }
        return String.join(";", selected);
    }
    private String selectedRouteEdges(double[][] lambdaValues) {
        ArrayList<String> selected = new ArrayList<>();
        for (int s = 0; s < lambdaValues.length; s++) {
            for (int p = 0; p < lambdaValues[s].length && p < pathsBySource.get(s).size(); p++) {
                if (lambdaValues[s][p] <= 0.5) continue;
                Path path = pathsBySource.get(s).get(p);
                for (int e : path.edgeIndices) {
                    Edge edge = edges.get(e);
                    if (!edge.virtual) selected.add(s + ":" + edge.u + "-" + edge.v);
                }
            }
        }
        return String.join(";", selected);
    }
    private String selectedFacilityNodes(double[] zValues) {
        ArrayList<String> selected = new ArrayList<>();
        for (int e = 0; e < edges.size(); e++) {
            Edge edge = edges.get(e);
            if (edge.virtual && zValues[e] > 0.5) selected.add(Integer.toString(edge.u));
        }
        return String.join(";", selected);
    }
    private List<int[]> selectedRouteNodes(double[][] lambdaValues) {
        ArrayList<int[]> selected = new ArrayList<>();
        for (int s = 0; s < lambdaValues.length; s++) {
            int[] nodes = null;
            for (int p = 0; p < lambdaValues[s].length && p < pathsBySource.get(s).size(); p++) {
                if (lambdaValues[s][p] <= 0.5) continue;
                if (nodes != null) throw new IllegalStateException("Multiple selected paths for task " + s);
                Path path = pathsBySource.get(s).get(p);
                nodes = new int[path.nodePath.size()];
                for (int i = 0; i < nodes.length; i++) nodes[i] = path.nodePath.get(i);
            }
            if (nodes == null) throw new IllegalStateException("No selected path for task " + s);
            selected.add(nodes);
        }
        return selected;
    }
    private double elapsed(long start) { return (System.currentTimeMillis() - start) / 1000.0; }
    private String append(String a, String b) {
        if (b == null || b.isEmpty()) return a == null ? "" : a;
        if (a == null || a.isEmpty()) return b;
        return a + "; " + b;
    }
    private void close(RmpSolution solution) {
        if (solution != null && solution.cplex != null) {
            if (solution.model != null) close(solution.model);
            else solution.cplex.end();
            solution.cplex = null;
        }
    }
    private void close(IncrementalRmpModel model) {
        if (reuseSessionActive && model == persistentRmp) return; // Borrowed node lease.
        if (model != null && model.cplex != null) {
            model.cplex.end();
            model.cplex = null;
        }
    }
    private void disposePersistentRmp() {
        IncrementalRmpModel model = persistentRmp;
        persistentRmp = null;
        if (model != null && model.cplex != null) {
            model.cplex.end(); model.cplex = null;
        }
    }
}

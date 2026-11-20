import ilog.concert.IloIntVar;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;

import java.math.BigInteger;
import java.util.*;

public class CorrectedCuttingPlaneSolver {
    public static final String ALGORITHM_VERSION = "cpa_full_path_certified_nonredundant_v1";
    public static class Result {
        public String algorithmVersion = ALGORITHM_VERSION;
        public boolean solved;
        public boolean optimal;
        public double objective = Double.POSITIVE_INFINITY;
        public double lowerBound = Double.NEGATIVE_INFINITY;
        public double masterObjective = Double.POSITIVE_INFINITY;
        public double gap = Double.POSITIVE_INFINITY;
        public double absoluteOptimalityTolerance = 1e-4;
        public double carrierOptimalityTolerance = 1e-5;
        public boolean noUnusedReservationsEnforced = true;
        public boolean allOpenFacilitiesChecked;
        public boolean includeNominalRiskInObjective;
        public boolean provenInfeasible;
        public int carrierChecks;
        public int exactCarrierChecks;
        public int labelSearchTruncations;
        public int unresolvedCarrierChecks;
        public String terminationReason = "NOT_STARTED";
        public String masterStatus = "NOT_STARTED";
        public int iterations;
        public int cuts;
        public long solveTimeMillis;
        public String selectedRoadEdges = "";
        public String selectedRouteEdges = "";
        public String selectedFacilityNodes = "";
        /** Ordered physical nodes by task; a local-disposal route has one node. */
        public List<int[]> selectedRouteNodes = new ArrayList<>();
        public String message = "";
    }

    /** Independent centralized model result; no carrier-optimality flag is used. */
    public static class CentralizedResult {
        public String modelType = "SL";
        public String algorithmVersion = "centralized_cpa_arc_model_v1";
        public boolean solved;
        public boolean routesFeasible;
        public boolean optimal;
        public boolean provenInfeasible;
        public boolean includeNominalRiskInObjective;
        public boolean noUnusedReservationsEnforced = true;
        public double objective = Double.POSITIVE_INFINITY;
        public double lowerBound = Double.NEGATIVE_INFINITY;
        public double masterObjective = Double.POSITIVE_INFINITY;
        public double gap = Double.POSITIVE_INFINITY;
        public double absoluteOptimalityTolerance = 1e-4;
        public String terminationReason = "NOT_STARTED";
        public String masterStatus = "NOT_STARTED";
        public long solveTimeMillis;
        public String selectedRoadEdges = "";
        public String selectedRouteEdges = "";
        public String selectedFacilityNodes = "";
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
        final ArrayList<Integer> edgeIndices;
        final ArrayList<Integer> nodePath;
        final double travelCost;
        final String key;
        Path(int sourceIndex, ArrayList<Integer> edgeIndices, ArrayList<Integer> nodePath, double travelCost) {
            this.sourceIndex = sourceIndex;
            this.edgeIndices = edgeIndices;
            this.nodePath = nodePath;
            this.travelCost = travelCost;
            this.key = edgeIndices.toString();
        }
        int targetFacility() {
            return nodePath.get(nodePath.size() - 1);
        }
    }

    private static class Label {
        final int node;
        final BigInteger visitedMask;
        final double cost;
        final double time;
        final Label prev;
        final int edgeFromPrev;
        Label(int node, BigInteger visitedMask, double cost, double time, Label prev, int edgeFromPrev) {
            this.node = node;
            this.visitedMask = visitedMask;
            this.cost = cost;
            this.time = time;
            this.prev = prev;
            this.edgeFromPrev = edgeFromPrev;
        }
    }

    private static class CarrierCut {
        final int sourceIndex;
        final HashSet<Integer> selectedPathEdges;
        final HashSet<Integer> cheaperExtraEdges;
        CarrierCut(int sourceIndex, HashSet<Integer> selectedPathEdges, HashSet<Integer> cheaperExtraEdges) {
            this.sourceIndex = sourceIndex;
            this.selectedPathEdges = selectedPathEdges;
            this.cheaperExtraEdges = cheaperExtraEdges;
        }
    }

    private final DataCreator data;
    private final double timeLimitSeconds;
    private final int maxIterations;
    private final int maxLabels;
    private final int nodeCount;
    private final int virtualSink;
    private final ArrayList<Edge> edges = new ArrayList<>();
    private final HashMap<String, Integer> edgeIndex = new HashMap<>();
    private final ArrayList<CarrierCut> cuts = new ArrayList<>();
    private final HashSet<String> cutKeys = new HashSet<>();
    private final ArrayList<ArrayList<Integer>> outgoing = new ArrayList<>();
    private static final double EPS = 1e-7;
    private static final double BOUND_GUARD = 1e-8;
    private long startedNanos;

    public CorrectedCuttingPlaneSolver(DataCreator data, double timeLimitSeconds) {
        this(data, timeLimitSeconds, 200, 1_000_000);
    }

    public CorrectedCuttingPlaneSolver(DataCreator data, double timeLimitSeconds, int maxIterations, int maxLabels) {
        if (data == null || data.getGraph() == null) throw new IllegalArgumentException("Missing network data");
        if (!Double.isFinite(timeLimitSeconds) || timeLimitSeconds < 0 || maxIterations < 0 || maxLabels < 0)
            throw new IllegalArgumentException("Invalid solver limits");
        this.data = data;
        this.timeLimitSeconds = timeLimitSeconds;
        this.maxIterations = maxIterations;
        this.maxLabels = maxLabels;
        this.nodeCount = data.getGraph().size();
        this.virtualSink = nodeCount;
        validateInput();
        buildEdges();
    }

    public Result solve() {
        startedNanos = System.nanoTime();
        Result result = new Result();
        result.includeNominalRiskInObjective = data.getIncludeNominalRiskInObjective();
        cuts.clear();
        cutKeys.clear();
        try {
            validateInput();
            buildEdges();
            // All objective coefficients have been validated as nonnegative.
            result.lowerBound = 0.0;
            result.terminationReason = "ITERATION_LIMIT";
            for (int iter = 1; iter <= maxIterations; iter++) {
                if (remainingSeconds() <= 0.0) { result.terminationReason = "TIME_LIMIT"; break; }
                result.iterations = iter;
                SolveState state = solveMaster();
                result.masterStatus = state.status;
                if (state.infeasible) {
                    if (result.solved) throw new IllegalStateException("Master infeasibility contradicts certified incumbent");
                    result.provenInfeasible = true;
                    result.lowerBound = Double.POSITIVE_INFINITY;
                    result.terminationReason = "INFEASIBLE_CERTIFIED";
                    break;
                }
                if (Double.isFinite(state.bestBound)) result.lowerBound = Math.max(result.lowerBound, state.bestBound);
                if (!state.solved) {
                    result.terminationReason = remainingSeconds() <= 0.0 ? "TIME_LIMIT" : "MASTER_UNRESOLVED";
                    result.message = append(result.message, state.status);
                    break;
                }
                result.masterObjective = state.objective; // Diagnostic only: not a bilevel UB.
                ArrayList<Path> selected = validateCandidate(state);
                Separation separated = separateCarrierCuts(state, selected, result);
                result.cuts = cuts.size();
                if (separated.allCertified) {
                    // Re-evaluate the rounded, validated integer candidate. The dual
                    // incumbent is a conservative risk upper bound even if this LP
                    // is stopped early; only a finite feasible value is accepted.
                    double value = evaluateCandidate(state);
                    if (Double.isFinite(value) && value < result.objective) {
                        result.objective = value;
                        result.solved = true;
                        result.allOpenFacilitiesChecked = true;
                        result.selectedRoadEdges = selectedRoadEdges(state.zValues);
                        result.selectedRouteEdges = selectedRouteEdges(state.xValues);
                        result.selectedFacilityNodes = selectedFacilityNodes(state.zValues);
                        result.selectedRouteNodes = selectedRouteNodes(selected);
                    } else if (!Double.isFinite(value)) {
                        result.terminationReason = remainingSeconds() <= 0.0 ? "TIME_LIMIT" : "CANDIDATE_EVALUATION_UNRESOLVED";
                        break;
                    }
                }
                updateGap(result);
                if (result.optimal) { result.terminationReason = "OPTIMAL"; break; }
                if (!separated.allCertified && separated.added == 0) {
                    result.terminationReason = remainingSeconds() <= 0.0 ? "TIME_LIMIT" : "CARRIER_UNRESOLVED";
                    result.message = append(result.message, separated.message);
                    break;
                }
            }
            updateGap(result);
            if (result.optimal) result.terminationReason = "OPTIMAL";
            else if (!result.provenInfeasible && remainingSeconds() <= 0.0) result.terminationReason = "TIME_LIMIT";
        } catch (Exception e) {
            result.optimal = false;
            result.terminationReason = "ERROR_UNCERTIFIED";
            result.gap = Double.isFinite(result.objective) && Double.isFinite(result.lowerBound)
                    ? result.objective - result.lowerBound : Double.POSITIVE_INFINITY;
            result.message = append(result.message, e.getMessage());
        }
        result.solveTimeMillis = (System.nanoTime() - startedNanos) / 1_000_000;
        return result;
    }

    /**
     * Solve the full integer centralized formulation using a fresh CPLEX model.
     * The arc model and robust risk are shared with the CPA master, but no stored
     * carrier cuts or follower-optimality separation are applied. This method is
     * safe to call after solve(): cuts from that BL run cannot enter the SL model.
     */
    public CentralizedResult solveCentralized() {
        startedNanos = System.nanoTime();
        CentralizedResult result = new CentralizedResult();
        result.includeNominalRiskInObjective = data.getIncludeNominalRiskInObjective();
        try {
            validateInput();
            buildEdges();
            result.lowerBound = 0.0;
            if (remainingSeconds() <= 0.0) {
                result.terminationReason = "TIME_LIMIT";
            } else {
                SolveState state = solveMaster(false);
                result.masterStatus = state.status;
                if (state.infeasible) {
                    result.provenInfeasible = true;
                    result.lowerBound = Double.POSITIVE_INFINITY;
                    result.terminationReason = "INFEASIBLE_CERTIFIED";
                } else {
                    if (Double.isFinite(state.bestBound)) result.lowerBound = Math.max(0.0, state.bestBound);
                    result.terminationReason = remainingSeconds() <= 0.0 ? "TIME_LIMIT" : "MASTER_UNRESOLVED";
                    if (state.solved) {
                        result.masterObjective = state.objective;
                        ArrayList<Path> paths = validateCandidate(state);
                        result.routesFeasible = true;
                        result.selectedRoadEdges = selectedRoadEdges(state.zValues);
                        result.selectedRouteEdges = selectedRouteEdges(state.xValues);
                        result.selectedFacilityNodes = selectedFacilityNodes(state.zValues);
                        result.selectedRouteNodes = selectedRouteNodes(paths);
                        double value = evaluateCandidate(state);
                        if (Double.isFinite(value)) {
                            result.solved = true;
                            result.objective = value;
                            result.gap = value - result.lowerBound;
                            if (result.gap < -EPS)
                                throw new IllegalStateException("SL lower bound exceeds feasible upper bound: " + result.gap);
                            result.optimal = result.gap >= -EPS && result.gap <= result.absoluteOptimalityTolerance;
                            result.terminationReason = result.optimal ? "OPTIMAL"
                                    : remainingSeconds() <= 0.0 ? "TIME_LIMIT" : "MASTER_GAP_UNRESOLVED";
                        } else {
                            result.terminationReason = remainingSeconds() <= 0.0 ? "TIME_LIMIT" : "CANDIDATE_EVALUATION_UNRESOLVED";
                        }
                    }
                }
            }
        } catch (Exception e) {
            result.optimal = false;
            result.terminationReason = "ERROR_UNCERTIFIED";
            result.message = append(result.message, e.getMessage());
        }
        result.solveTimeMillis = (System.nanoTime() - startedNanos) / 1_000_000;
        return result;
    }

    private void updateGap(Result result) {
        result.optimal = false;
        result.gap = result.solved && Double.isFinite(result.objective) && Double.isFinite(result.lowerBound)
                ? result.objective - result.lowerBound : Double.POSITIVE_INFINITY;
        if (result.gap < -EPS) throw new IllegalStateException("Certified lower bound exceeds feasible upper bound: " + result.gap);
        result.optimal = result.solved && result.allOpenFacilitiesChecked
                && result.gap >= -EPS && result.gap <= result.absoluteOptimalityTolerance;
    }

    private static class SolveState {
        boolean solved;
        boolean infeasible;
        double objective = Double.POSITIVE_INFINITY;
        double bestBound = Double.NEGATIVE_INFINITY;
        String status = "UNKNOWN";
        double[] zValues;
        double[][] xValues;
    }

    private SolveState solveMaster() throws Exception {
        return solveMaster(true);
    }

    private SolveState solveMaster(boolean includeCarrierCuts) throws Exception {
        SolveState state = new SolveState();
        IloCplex cplex = null;
        try {
        cplex = new IloCplex();
        cplex.setOut(null);
        cplex.setParam(IloCplex.Param.Threads, 1);
        cplex.setParam(IloCplex.Param.MIP.Tolerances.MIPGap, 1e-9);
        cplex.setParam(IloCplex.Param.MIP.Tolerances.AbsMIPGap, 1e-6);
        cplex.setParam(IloCplex.Param.MIP.Tolerances.Integrality, 1e-9);
        cplex.setParam(IloCplex.Param.Simplex.Tolerances.Feasibility, 1e-9);
        cplex.setParam(IloCplex.Param.Simplex.Tolerances.Optimality, 1e-9);

        int sCount = data.getStartPoints().size();
        int eCount = edges.size();
        IloIntVar[] z = new IloIntVar[eCount];
        IloIntVar[][] x = new IloIntVar[sCount][eCount];
        for (int e = 0; e < eCount; e++) z[e] = cplex.boolVar("z_" + e);
        for (int s = 0; s < sCount; s++) for (int e = 0; e < eCount; e++) x[s][e] = cplex.boolVar("x_" + s + "_" + e);

        IloLinearNumExpr objective = cplex.linearNumExpr();
        IloLinearNumExpr budget = cplex.linearNumExpr();
        for (int e = 0; e < eCount; e++) {
            Edge edge = edges.get(e);
            if (edge.virtual) objective.addTerm(data.getCandidateBuildWeights().get(edge.candidateIndex), z[e]);
            else {
                objective.addTerm(data.getDedicatedLaneCostWeight().get(edge.u).get(edge.v), z[e]);
                budget.addTerm(data.getDedicatedLaneBuildCostWeight().get(edge.u).get(edge.v), z[e]);
            }
        }
        cplex.addLe(budget, data.getGovernmentRoadBudget(), "road_budget");

        for (int s = 0; s < sCount; s++) {
            int source = data.getStartPoints().get(s);
            for (int node = 0; node <= virtualSink; node++) {
                IloLinearNumExpr flow = cplex.linearNumExpr();
                IloLinearNumExpr incoming = cplex.linearNumExpr();
                IloLinearNumExpr outflow = cplex.linearNumExpr();
                for (int e = 0; e < eCount; e++) {
                    Edge edge = edges.get(e);
                    if (edge.u == node) { flow.addTerm(1.0, x[s][e]); outflow.addTerm(1.0, x[s][e]); }
                    if (edge.v == node) { flow.addTerm(-1.0, x[s][e]); incoming.addTerm(1.0, x[s][e]); }
                }
                double rhs = node == source ? 1.0 : node == virtualSink ? -1.0 : 0.0;
                cplex.addEq(flow, rhs, "flow_" + s + "_" + node);
                cplex.addLe(incoming, 1.0, "in_degree_" + s + "_" + node);
                cplex.addLe(outflow, 1.0, "out_degree_" + s + "_" + node);
            }
            // The path-based BPA uses elementary routes. Enforce that same
            // representation here, including when zero-cost physical cycles exist.
            IloNumVar[] rank = new IloNumVar[nodeCount];
            for (int node = 0; node < nodeCount; node++)
                rank[node] = cplex.numVar(0.0, node == source ? 0.0 : nodeCount - 1.0, "rank_" + s + "_" + node);
            for (int e = 0; e < eCount; e++) {
                Edge a = edges.get(e);
                if (a.virtual) continue;
                IloLinearNumExpr order = cplex.linearNumExpr();
                order.addTerm(1.0, rank[a.v]); order.addTerm(-1.0, rank[a.u]);
                order.addTerm(-nodeCount, x[s][e]);
                cplex.addGe(order, 1.0 - nodeCount, "mtz_" + s + "_" + e);
            }
            IloLinearNumExpr time = cplex.linearNumExpr();
            for (int e = 0; e < eCount; e++) {
                Edge edge = edges.get(e);
                if (!edge.virtual) time.addTerm(data.getNodeTime().get(edge.v).get(edge.u), x[s][e]);
            }
            cplex.addLe(time, data.getTimeWindow().get(s), "time_" + s);
        }
        for (int e = 0; e < eCount; e++) if (!edges.get(e).virtual) {
            IloLinearNumExpr used = cplex.linearNumExpr();
            used.addTerm(1.0, z[e]);
            for (int s = 0; s < sCount; s++) used.addTerm(-1.0, x[s][e]);
            cplex.addLe(used, 0.0, "reservation_used_" + e);
        }
        for (int s = 0; s < sCount; s++) for (int e = 0; e < eCount; e++) {
            IloLinearNumExpr use = cplex.linearNumExpr();
            use.addTerm(1.0, x[s][e]);
            use.addTerm(-1.0, z[e]);
            cplex.addLe(use, 0.0, "use_" + s + "_" + e);
        }

        addRobustRiskDual(cplex, x, objective);
        if (includeCarrierCuts) addStoredCarrierCuts(cplex, x, z);
        cplex.addMinimize(objective);
        double remaining = remainingSeconds();
        if (remaining <= 0.0) { state.status = "UNKNOWN_NO_TIME_BEFORE_MASTER_SOLVE"; return state; }
        cplex.setParam(IloCplex.Param.TimeLimit, remaining);
        state.solved = cplex.solve();
        IloCplex.Status actual = cplex.getStatus();
        state.status = actual + "/" + cplex.getCplexStatus();
        if (actual == IloCplex.Status.Infeasible) { state.infeasible = true; state.solved = false; return state; }
        if (actual == IloCplex.Status.InfeasibleOrUnbounded || actual == IloCplex.Status.Unbounded) {
            state.solved = false; return state;
        }
        try {
            double bound = cplex.getBestObjValue();
            if (Double.isFinite(bound) && Math.abs(bound) < 1e70) state.bestBound = bound;
        } catch (Exception unavailable) { state.status += "/BOUND_UNAVAILABLE"; }
        if (state.solved) {
            state.objective = cplex.getObjValue();
            if (!Double.isFinite(state.objective)) throw new IllegalStateException("Nonfinite master incumbent");
            if (state.bestBound > state.objective + EPS)
                throw new IllegalStateException("Master lower bound exceeds its incumbent objective");
            state.zValues = new double[eCount];
            state.xValues = new double[sCount][eCount];
            for (int e = 0; e < eCount; e++) state.zValues[e] = cplex.getValue(z[e]);
            for (int s = 0; s < sCount; s++) for (int e = 0; e < eCount; e++) state.xValues[s][e] = cplex.getValue(x[s][e]);
        }
        return state;
        } finally { if (cplex != null) cplex.end(); }
    }

    private void addRobustRiskDual(IloCplex cplex, IloIntVar[][] x, IloLinearNumExpr objective) throws Exception {
        int sCount = data.getStartPoints().size();
        int eCount = edges.size();
        IloNumVar[] rho = new IloNumVar[sCount];
        IloNumVar[] xi = new IloNumVar[eCount];
        IloNumVar[][] eta = new IloNumVar[sCount][eCount];
        IloNumVar[][] pi = new IloNumVar[sCount][eCount];
        for (int s = 0; s < sCount; s++) {
            rho[s] = cplex.numVar(0.0, Double.MAX_VALUE, "rho_" + s);
            objective.addTerm(1.0, rho[s]);
        }
        for (int e = 0; e < eCount; e++) {
            xi[e] = cplex.numVar(0.0, Double.MAX_VALUE, "xi_" + e);
            objective.addTerm(1.0, xi[e]);
        }
        for (int s = 0; s < sCount; s++) for (int e = 0; e < eCount; e++) {
            eta[s][e] = cplex.numVar(0.0, Double.MAX_VALUE, "eta_" + s + "_" + e);
            pi[s][e] = cplex.numVar(0.0, Double.MAX_VALUE, "pi_" + s + "_" + e);
        }
        IloNumVar thetaU = cplex.numVar(0.0, Double.MAX_VALUE, "theta_u");
        IloNumVar thetaV = cplex.numVar(0.0, Double.MAX_VALUE, "theta_v");
        objective.addTerm(data.getQuantityRiskExpectation(), thetaU);
        objective.addTerm(data.getRoadRiskExpectation(), thetaV);
        for (int s = 0; s < sCount; s++) {
            double n = data.getMinTransportTasks().get(s);
            double k = data.getTransportTaskBuffs().get(s);
            IloLinearNumExpr demand = cplex.linearNumExpr();
            demand.addTerm(1.0, rho[s]);
            demand.addTerm(1.0, thetaU);
            for (int e = 0; e < eCount; e++) {
                double r = riskMin(edges.get(e), s);
                double q = riskWidth(edges.get(e), s);
                if (data.getIncludeNominalRiskInObjective()) objective.addTerm(n * r, x[s][e]);
                demand.addTerm(-1.0, eta[s][e]);
                demand.addTerm(-k * r, x[s][e]);
                IloLinearNumExpr w = cplex.linearNumExpr();
                w.addTerm(1.0, eta[s][e]);
                w.addTerm(1.0, pi[s][e]);
                w.addTerm(-k * q, x[s][e]);
                cplex.addGe(w, 0.0, "dual_w_" + s + "_" + e);
            }
            cplex.addGe(demand, 0.0, "dual_u_" + s);
        }
        for (int e = 0; e < eCount; e++) {
            IloLinearNumExpr v = cplex.linearNumExpr();
            v.addTerm(1.0, xi[e]);
            v.addTerm(1.0, thetaV);
            for (int s = 0; s < sCount; s++) {
                double n = data.getMinTransportTasks().get(s);
                double q = riskWidth(edges.get(e), s);
                v.addTerm(-1.0, pi[s][e]);
                v.addTerm(-n * q, x[s][e]);
            }
            cplex.addGe(v, 0.0, "dual_v_" + e);
        }
    }

    private void addStoredCarrierCuts(IloCplex cplex, IloIntVar[][] x, IloIntVar[] z) throws Exception {
        for (int c = 0; c < cuts.size(); c++) {
            CarrierCut cut = cuts.get(c);
            IloLinearNumExpr expr = cplex.linearNumExpr();
            for (int e : cut.selectedPathEdges) expr.addTerm(1.0, x[cut.sourceIndex][e]);
            for (int e : cut.cheaperExtraEdges) expr.addTerm(1.0, z[e]);
            cplex.addLe(expr, cut.selectedPathEdges.size() + cut.cheaperExtraEdges.size() - 1.0, "carrier_cut_" + c);
        }
    }

    private static class Separation {
        int added;
        boolean allCertified = true;
        String message = "";
    }

    private static class SearchResult {
        Path path;
        boolean certified;
        boolean truncated;
        String status = "UNKNOWN";
    }

    private Separation separateCarrierCuts(SolveState state, ArrayList<Path> selectedPaths, Result result) {
        Separation separated = new Separation();
        boolean[] allowed = new boolean[edges.size()];
        for (int e = 0; e < edges.size(); e++) allowed[e] = state.zValues[e] > 0.5;
        for (int s = 0; s < data.getStartPoints().size(); s++) {
            result.carrierChecks++;
            Path selected = selectedPaths.get(s);
            SearchResult check = searchAllOpenFacilities(s, allowed);
            if (check.truncated) result.labelSearchTruncations++;
            if (!check.certified && remainingSeconds() > 0.0) {
                result.exactCarrierChecks++;
                check = exactCarrierCheck(s, allowed, selected.travelCost, result.carrierOptimalityTolerance);
            }
            Path carrier = check.path;
            if (carrier != null && carrier.travelCost < selected.travelCost - result.carrierOptimalityTolerance) {
                separated.allCertified = false;
                HashSet<Integer> selectedEdges = new HashSet<>(selected.edgeIndices);
                HashSet<Integer> extra = new HashSet<>();
                for (int e : carrier.edgeIndices) if (!selectedEdges.contains(e)) extra.add(e);
                if (extra.isEmpty()) throw new IllegalStateException("Strictly cheaper complete path has no extra arcs");
                String cutKey = s + ":" + new TreeSet<>(selectedEdges) + ":" + new TreeSet<>(extra);
                if (!cutKeys.add(cutKey)) throw new IllegalStateException("Previously added full-path cut remains violated");
                cuts.add(new CarrierCut(s, selectedEdges, extra)); separated.added++;
            } else if (!check.certified) {
                separated.allCertified = false;
                result.unresolvedCarrierChecks++;
                separated.message = append(separated.message, "task " + s + ": " + check.status);
            }
        }
        return separated;
    }

    private Path extractPath(int sourceIndex, double[] x) {
        if (x == null || x.length != edges.size()) throw new IllegalStateException("Missing task arc vector");
        int source = data.getStartPoints().get(sourceIndex);
        ArrayList<Integer> edgePath = new ArrayList<>();
        ArrayList<Integer> nodePath = new ArrayList<>();
        int[] nextEdges = new int[nodeCount];
        Arrays.fill(nextEdges, -1);
        int selectedCount = 0;
        for (int e = 0; e < x.length; e++) {
            requireBinary(x[e], "x[" + sourceIndex + "][" + e + "]");
            if (x[e] <= 0.5) continue;
            Edge a = edges.get(e);
            if (nextEdges[a.u] != -1) throw new IllegalStateException("Multiple task outflows at node " + a.u);
            nextEdges[a.u] = e; selectedCount++;
        }
        nodePath.add(source);
        int cur = source;
        HashSet<Integer> seen = new HashSet<>();
        double cost = 0.0, duration = 0.0;
        while (cur != virtualSink) {
            if (!seen.add(cur)) throw new IllegalStateException("Cycle in selected route");
            int nextEdge = nextEdges[cur];
            if (nextEdge < 0) throw new IllegalStateException("Selected route does not reach virtual sink");
            edgePath.add(nextEdge);
            Edge edge = edges.get(nextEdge);
            if (!edge.virtual) {
                cost += arcCost(edge); duration += arcTime(edge);
                nodePath.add(edge.v);
            }
            cur = edge.v;
        }
        if (selectedCount != edgePath.size()) throw new IllegalStateException("Disconnected selected arcs or cycles");
        if (!Double.isFinite(cost) || !Double.isFinite(duration)) throw new IllegalStateException("Nonfinite route aggregate");
        if (duration > data.getTimeWindow().get(sourceIndex) + EPS) throw new IllegalStateException("Selected route violates time window");
        return new Path(sourceIndex, edgePath, nodePath, cost);
    }

    private ArrayList<Path> validateCandidate(SolveState state) {
        if (state.zValues == null || state.zValues.length != edges.size()
                || state.xValues == null || state.xValues.length != data.getStartPoints().size())
            throw new IllegalStateException("Missing incumbent vectors");
        boolean[] used = new boolean[edges.size()];
        double budget = 0.0;
        for (int e = 0; e < edges.size(); e++) {
            requireBinary(state.zValues[e], "z[" + e + "]");
            state.zValues[e] = state.zValues[e] > 0.5 ? 1.0 : 0.0;
            Edge a = edges.get(e);
            if (!a.virtual && state.zValues[e] > 0.5) budget += data.getDedicatedLaneBuildCostWeight().get(a.u).get(a.v);
        }
        if (!Double.isFinite(budget) || budget > data.getGovernmentRoadBudget() + EPS)
            throw new IllegalStateException("Incumbent violates lane budget");
        ArrayList<Path> selected = new ArrayList<>();
        for (int s = 0; s < state.xValues.length; s++) {
            Path path = extractPath(s, state.xValues[s]); selected.add(path);
            for (int e = 0; e < edges.size(); e++) state.xValues[s][e] = state.xValues[s][e] > 0.5 ? 1.0 : 0.0;
            for (int e : path.edgeIndices) {
                if (state.zValues[e] < 0.5) throw new IllegalStateException("Route uses unavailable road/facility arc");
                used[e] = true;
            }
        }
        for (int e = 0; e < edges.size(); e++) if (!edges.get(e).virtual && state.zValues[e] > 0.5 && !used[e])
            throw new IllegalStateException("Unused reserved physical arc " + e);
        return selected;
    }

    /** Uniform-cost enumeration: the first available terminal has globally minimum
     * carrier cost over every open facility, provided the queue is not truncated. */
    private SearchResult searchAllOpenFacilities(int sourceIndex, boolean[] allowedEdges) {
        SearchResult result = new SearchResult();
        long fastSearchStarted = System.nanoTime();
        int source = data.getStartPoints().get(sourceIndex);
        double timeLimit = data.getTimeWindow().get(sourceIndex);
        PriorityQueue<Label> queue = new PriorityQueue<>(Comparator.comparingDouble(a -> a.cost));
        queue.add(new Label(source, BigInteger.ONE.shiftLeft(source), 0.0, 0.0, null, -1));
        int expanded = 0;
        while (!queue.isEmpty()) {
            if (remainingSeconds() <= 0.0) { result.status = "UNKNOWN_LABEL_TIME_LIMIT"; return result; }
            if (expanded >= Math.min(maxLabels, 10_000)) { result.truncated = true; result.status = "UNKNOWN_LABEL_LIMIT"; return result; }
            if ((System.nanoTime() - fastSearchStarted) / 1e9 >= 0.1) {
                result.truncated = true; result.status = "UNKNOWN_LABEL_PHASE_TIME_LIMIT"; return result;
            }
            Label label = queue.poll();
            expanded++;
            Integer virtual = edgeIndex.get(key(label.node, virtualSink));
            if (virtual != null && allowedEdges[virtual]) {
                Label terminal = new Label(virtualSink, label.visitedMask, label.cost, label.time, label, virtual);
                result.path = pathFromLabel(sourceIndex, source, terminal);
                result.certified = true; result.status = "CERTIFIED_LABEL_OPTIMUM"; return result;
            }
            for (int e : outgoing.get(label.node)) {
                Edge a = edges.get(e);
                if (a.virtual || !allowedEdges[e]) continue;
                int next = a.v;
                if (label.visitedMask.testBit(next)) continue;
                double nextTime = label.time + arcTime(a);
                if (nextTime > timeLimit + EPS) continue;
                double nextCost = label.cost + arcCost(a);
                if (!Double.isFinite(nextTime) || !Double.isFinite(nextCost)) throw new IllegalStateException("Nonfinite label aggregate");
                queue.add(new Label(next, label.visitedMask.setBit(next), nextCost, nextTime, label, e));
            }
        }
        // A validated selected path exists, so an empty search is not a pass.
        result.status = "UNKNOWN_NO_PATH_DESPITE_VALIDATED_INCUMBENT";
        return result;
    }

    private SearchResult exactCarrierCheck(int sourceIndex, boolean[] allowed, double selectedCost, double tolerance) {
        SearchResult result = new SearchResult();
        int[] u = new int[edges.size()], v = new int[edges.size()];
        double[] cost = new double[edges.size()], time = new double[edges.size()];
        for (int e = 0; e < edges.size(); e++) {
            Edge a = edges.get(e); u[e] = a.u; v[e] = a.v; cost[e] = arcCost(a); time[e] = arcTime(a);
        }
        BpaExactPathPricing.Result exact = BpaExactPathPricing.solve(nodeCount,
                data.getStartPoints().get(sourceIndex), u, v, cost, time, data.getTimeWindow().get(sourceIndex),
                allowed, new boolean[edges.size()], Collections.emptyList(), Math.max(0.0, remainingSeconds()));
        result.status = exact.status;
        if (exact.infeasible) { result.status = "UNKNOWN_ORACLE_INFEASIBLE_WITH_VALIDATED_ROUTE"; return result; }
        if (exact.pathEdges != null) {
            double[] x = new double[edges.size()];
            for (int e : exact.pathEdges) {
                if (e < 0 || e >= edges.size() || !allowed[e] || x[e] != 0.0)
                    throw new IllegalStateException("Invalid exact-oracle path");
                x[e] = 1.0;
            }
            result.path = extractPath(sourceIndex, x);
        }
        if (exact.lowerBound > selectedCost + EPS) {
            result.status = "UNKNOWN_ORACLE_BOUND_EXCEEDS_VALIDATED_PATH";
            return result;
        }
        // A feasible improving path suffices for a cut, but a lack of improvement
        // only becomes a certificate when a valid lower bound excludes it.
        result.certified = Double.isFinite(exact.lowerBound) && Math.abs(exact.lowerBound) < 1e70
                && exact.lowerBound - BOUND_GUARD >= selectedCost - tolerance;
        return result;
    }

    private double evaluateCandidate(SolveState state) throws Exception {
        if (remainingSeconds() <= 0.0) return Double.POSITIVE_INFINITY;
        IloCplex cplex = null;
        try {
            cplex = new IloCplex(); cplex.setOut(null); cplex.setParam(IloCplex.Param.Threads, 1);
            cplex.setParam(IloCplex.Param.Simplex.Tolerances.Feasibility, 1e-9);
            cplex.setParam(IloCplex.Param.Simplex.Tolerances.Optimality, 1e-9);
            IloIntVar[][] x = new IloIntVar[state.xValues.length][edges.size()];
            for (int s = 0; s < x.length; s++) for (int e = 0; e < edges.size(); e++) {
                int fixed = state.xValues[s][e] > 0.5 ? 1 : 0;
                x[s][e] = cplex.intVar(fixed, fixed, "fixed_x_" + s + "_" + e);
            }
            double deterministic = 0.0;
            for (int e = 0; e < edges.size(); e++) if (state.zValues[e] > 0.5) {
                Edge a = edges.get(e);
                deterministic += a.virtual ? data.getCandidateBuildWeights().get(a.candidateIndex)
                        : data.getDedicatedLaneCostWeight().get(a.u).get(a.v);
            }
            IloLinearNumExpr objective = cplex.linearNumExpr();
            objective.setConstant(deterministic); addRobustRiskDual(cplex, x, objective); cplex.addMinimize(objective);
            double remaining = remainingSeconds();
            if (remaining <= 0.0) return Double.POSITIVE_INFINITY;
            cplex.setParam(IloCplex.Param.TimeLimit, remaining);
            if (!cplex.solve()) return Double.POSITIVE_INFINITY;
            double value = cplex.getObjValue();
            if (!Double.isFinite(value) || value < -EPS) throw new IllegalStateException("Invalid candidate upper bound");
            return value;
        } finally { if (cplex != null) cplex.end(); }
    }

    private Path pathFromLabel(int sourceIndex, int source, Label terminal) {
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
        double travel = 0.0;
        for (int e : pathEdges) {
            Edge edge = edges.get(e);
            if (!edge.virtual) travel += data.getNodeDistance().get(edge.u).get(edge.v);
        }
        return new Path(sourceIndex, pathEdges, nodes, travel);
    }

    private double arcCost(Edge edge) {
        return edge.virtual ? 0.0 : data.getNodeDistance().get(edge.u).get(edge.v);
    }

    private double arcTime(Edge edge) {
        // DataCreator and the existing BPA store the duration of u->v at [v][u].
        return edge.virtual ? 0.0 : data.getNodeTime().get(edge.v).get(edge.u);
    }

    private void buildEdges() {
        edges.clear(); edgeIndex.clear(); outgoing.clear();
        for (int i = 0; i < nodeCount; i++) outgoing.add(new ArrayList<>());
        for (int i = 0; i < nodeCount; i++) for (int j = 0; j < nodeCount; j++) {
            if (i != j && data.getGraph().get(i).get(j) == 1) {
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
        for (int e = 0; e < edges.size(); e++) outgoing.get(edges.get(e).u).add(e);
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

    private String key(int u, int v) {
        return u + "_" + v;
    }

    private String selectedRoadEdges(double[] zValues) {
        ArrayList<String> selected = new ArrayList<>();
        for (int e = 0; e < edges.size(); e++) {
            Edge edge = edges.get(e);
            if (!edge.virtual && zValues[e] > 0.5) selected.add(edge.u + "-" + edge.v);
        }
        return String.join(";", selected);
    }

    private String selectedRouteEdges(double[][] xValues) {
        ArrayList<String> selected = new ArrayList<>();
        for (int s = 0; s < xValues.length; s++) {
            for (int e = 0; e < edges.size(); e++) {
                Edge edge = edges.get(e);
                if (!edge.virtual && xValues[s][e] > 0.5) selected.add(s + ":" + edge.u + "-" + edge.v);
            }
        }
        return String.join(";", selected);
    }

    private String selectedFacilityNodes(double[] zValues) {
        ArrayList<String> selected = new ArrayList<>();
        for (int e = 0; e < edges.size(); e++) if (edges.get(e).virtual && zValues[e] > 0.5)
            selected.add(Integer.toString(edges.get(e).u));
        return String.join(";", selected);
    }

    private List<int[]> selectedRouteNodes(List<Path> paths) {
        ArrayList<int[]> out = new ArrayList<>();
        for (Path path : paths) {
            int[] nodes = new int[path.nodePath.size()];
            for (int i = 0; i < nodes.length; i++) nodes[i] = path.nodePath.get(i);
            out.add(nodes);
        }
        return out;
    }

    private double remainingSeconds() {
        return timeLimitSeconds - (System.nanoTime() - startedNanos) / 1e9;
    }

    private static void requireBinary(double value, String label) {
        if (!Double.isFinite(value) || value < -EPS || value > 1.0 + EPS || Math.abs(value - Math.rint(value)) > EPS)
            throw new IllegalStateException("Nonbinary " + label + ": " + value);
    }

    private static void requireNonnegative(Double value, String label) {
        if (value == null || !Double.isFinite(value) || value < 0.0) throw new IllegalArgumentException("Invalid " + label + ": " + value);
    }

    private static void requireMatrix(List<? extends List<?>> matrix, int n, String label) {
        if (matrix == null || matrix.size() != n) throw new IllegalArgumentException("Missing/wrong-size matrix " + label);
        for (List<?> row : matrix) if (row == null || row.size() != n)
            throw new IllegalArgumentException("Missing/wrong-size row in " + label);
    }

    private void validateInput() {
        if (nodeCount < 1) throw new IllegalArgumentException("Empty physical network");
        requireMatrix(data.getGraph(), nodeCount, "graph");
        requireMatrix(data.getNodeDistance(), nodeCount, "cost");
        requireMatrix(data.getNodeTime(), nodeCount, "time[v][u]");
        requireMatrix(data.getDedicatedLaneCostWeight(), nodeCount, "traffic impact");
        requireMatrix(data.getDedicatedLaneBuildCostWeight(), nodeCount, "lane setup cost");
        if (data.getStartPoints() == null || data.getCandidatePoints() == null)
            throw new IllegalArgumentException("Missing sources/facilities");
        int tasks = data.getStartPoints().size();
        if (data.getTimeWindow() == null || data.getTimeWindow().size() != tasks
                || data.getMinTransportTasks() == null || data.getMinTransportTasks().size() != tasks
                || data.getTransportTaskBuffs() == null || data.getTransportTaskBuffs().size() != tasks)
            throw new IllegalArgumentException("Task attribute lengths differ");
        if (data.getCandidateBuildWeights() == null || data.getCandidateBuildWeights().size() != data.getCandidatePoints().size())
            throw new IllegalArgumentException("Facility attribute lengths differ");
        HashSet<Integer> facilities = new HashSet<>();
        for (int j = 0; j < data.getCandidatePoints().size(); j++) {
            Integer m = data.getCandidatePoints().get(j);
            if (m == null || m < 0 || m >= nodeCount || !facilities.add(m)) throw new IllegalArgumentException("Invalid/duplicate facility");
            requireNonnegative(data.getCandidateBuildWeights().get(j), "facility cost");
        }
        for (int s = 0; s < tasks; s++) {
            Integer source = data.getStartPoints().get(s);
            if (source == null || source < 0 || source >= nodeCount) throw new IllegalArgumentException("Invalid source");
            requireNonnegative(data.getTimeWindow().get(s), "time window");
            requireNonnegative(data.getMinTransportTasks().get(s), "nominal demand");
            requireNonnegative(data.getTransportTaskBuffs().get(s), "demand width");
        }
        requireNonnegative(data.getGovernmentRoadBudget(), "road budget");
        requireNonnegative(data.getQuantityRiskExpectation(), "demand uncertainty budget");
        requireNonnegative(data.getRoadRiskExpectation(), "risk uncertainty budget");
        if (data.getSourceMinRisks() == null) requireMatrix(data.getMinRisks(), nodeCount, "nominal risk");
        else {
            if (data.getSourceMinRisks().size() != tasks) throw new IllegalArgumentException("Task risk dimensions differ");
            for (List<? extends List<?>> matrix : data.getSourceMinRisks()) requireMatrix(matrix, nodeCount, "source risk");
        }
        if (data.getSourceRiskBuffs() == null) requireMatrix(data.getRiskBuffs(), nodeCount, "risk width");
        else {
            if (data.getSourceRiskBuffs().size() != tasks) throw new IllegalArgumentException("Task risk-width dimensions differ");
            for (List<? extends List<?>> matrix : data.getSourceRiskBuffs()) requireMatrix(matrix, nodeCount, "source risk width");
        }
        for (int u = 0; u < nodeCount; u++) for (int v = 0; v < nodeCount; v++) {
            Integer adjacency = data.getGraph().get(u).get(v);
            if (adjacency == null || (adjacency != 0 && adjacency != 1) || (u == v && adjacency != 0))
                throw new IllegalArgumentException("Invalid graph entry " + u + "," + v);
            if (adjacency == 0) continue;
            requireNonnegative(data.getNodeDistance().get(u).get(v), "arc cost " + u + "," + v);
            requireNonnegative(data.getNodeTime().get(v).get(u), "arc time " + u + "," + v);
            requireNonnegative(data.getDedicatedLaneCostWeight().get(u).get(v), "traffic impact " + u + "," + v);
            requireNonnegative(data.getDedicatedLaneBuildCostWeight().get(u).get(v), "lane setup cost " + u + "," + v);
            Edge a = new Edge(u, v, false, -1);
            for (int s = 0; s < tasks; s++) {
                double r = riskMin(a, s), q = riskWidth(a, s);
                requireNonnegative(r, "nominal arc risk"); requireNonnegative(q, "arc risk width");
                double n = data.getMinTransportTasks().get(s), k = data.getTransportTaskBuffs().get(s);
                requireNonnegative(n * r, "nominal risk product"); requireNonnegative(n * q, "risk deviation product");
                requireNonnegative(k * r, "demand deviation product"); requireNonnegative(k * q, "interaction product");
            }
        }
    }

    private String append(String a, String b) {
        if (b == null || b.isEmpty()) return a == null ? "" : a;
        if (a == null || a.isEmpty()) return b;
        return a + "; " + b;
    }
}

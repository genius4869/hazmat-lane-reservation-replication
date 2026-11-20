import ilog.concert.IloIntVar;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** Exact elementary-path pricing over physical nodes 0..n-1 and virtual sink n. */
public final class BpaExactPathPricing {
    private BpaExactPathPricing() { }

    public static final class Result {
        public int[] pathEdges;
        public double objective = Double.POSITIVE_INFINITY;
        public double lowerBound = Double.NEGATIVE_INFINITY;
        public boolean infeasible;
        public String status = "UNKNOWN";
    }

    /**
     * Costs already contain all terminal/assignment constants on the virtual arcs.
     * Exclusions must be complete elementary source-to-sink paths, including the
     * virtual terminal arc. Their input edge order need not be the traversal order.
     * A time limit may return a feasible path and/or a valid global pricing bound.
     */
    public static Result solve(int n, int source, int[] edgeU, int[] edgeV,
                               double[] edgeCost, double[] edgeTime, double timeWindow,
                               boolean[] allowed, boolean[] required,
                               List<int[]> excludedPaths, double timeSeconds) {
        Result result = new Result();
        long started = System.nanoTime();
        IloCplex cplex = null;
        try {
            validateInput(n, source, edgeU, edgeV, edgeCost, edgeTime, timeWindow, allowed, required);
            if (!Double.isFinite(timeSeconds)) throw new IllegalArgumentException("Nonfinite remaining time");
            if (timeSeconds <= 0.0) {
                result.status = "UNKNOWN_NO_TIME_REMAINING";
                return result;
            }
            List<int[]> exclusions = excludedPaths == null ? new ArrayList<>() : excludedPaths;
            for (int[] excluded : exclusions) validateCompletePath(n, source, edgeU, edgeV, excluded);

            cplex = new IloCplex();
            cplex.setOut(null);
            cplex.setParam(IloCplex.Param.Threads, 1);
            // CPLEX 22.1.0 can report an inconsistent pricing bound after root
            // repeat presolve (captured EM30 fixture, independently enumerated).
            // Keep initial presolve and all tolerances; disable only this stage.
            cplex.setParam(IloCplex.Param.Preprocessing.RepeatPresolve, 0);
            cplex.setParam(IloCplex.Param.MIP.Tolerances.MIPGap, 0.0);
            cplex.setParam(IloCplex.Param.MIP.Tolerances.AbsMIPGap, 1e-9);
            cplex.setParam(IloCplex.Param.MIP.Tolerances.Integrality, 1e-9);
            cplex.setParam(IloCplex.Param.Simplex.Tolerances.Feasibility, 1e-9);
            cplex.setParam(IloCplex.Param.Simplex.Tolerances.Optimality, 1e-9);
            int count = edgeU.length;
            IloIntVar[] x = new IloIntVar[count];
            for (int e = 0; e < count; e++) {
                x[e] = cplex.boolVar("path_x_" + e);
                if (!allowed[e]) x[e].setUB(0.0);
                if (required[e]) {
                    if (allowed[e]) {
                        x[e].setLB(1.0);
                    } else {
                        // Keep a legal variable domain and let CPLEX prove the
                        // contradictory required/forbidden condition infeasible.
                        cplex.addGe(x[e], 1.0, "required_forbidden_" + e);
                    }
                }
            }

            IloLinearNumExpr terminal = cplex.linearNumExpr();
            IloLinearNumExpr duration = cplex.linearNumExpr();
            IloLinearNumExpr objective = cplex.linearNumExpr();
            for (int e = 0; e < count; e++) {
                if (edgeV[e] == n) terminal.addTerm(1.0, x[e]);
                duration.addTerm(edgeTime[e], x[e]);
                objective.addTerm(edgeCost[e], x[e]);
            }
            cplex.addEq(terminal, 1.0, "one_virtual_terminal");
            cplex.addLe(duration, timeWindow, "path_time");

            for (int node = 0; node < n; node++) {
                IloLinearNumExpr incoming = cplex.linearNumExpr();
                IloLinearNumExpr outgoing = cplex.linearNumExpr();
                IloLinearNumExpr balance = cplex.linearNumExpr();
                for (int e = 0; e < count; e++) {
                    if (edgeU[e] == node) {
                        outgoing.addTerm(1.0, x[e]);
                        balance.addTerm(1.0, x[e]);
                    }
                    if (edgeV[e] == node) {
                        incoming.addTerm(1.0, x[e]);
                        balance.addTerm(-1.0, x[e]);
                    }
                }
                cplex.addEq(balance, node == source ? 1.0 : 0.0, "flow_" + node);
                cplex.addLe(incoming, 1.0, "in_degree_" + node);
                cplex.addLe(outgoing, 1.0, "out_degree_" + node);
            }

            IloNumVar[] rank = new IloNumVar[n];
            for (int node = 0; node < n; node++) {
                rank[node] = cplex.numVar(0.0, node == source ? 0.0 : n - 1.0, "rank_" + node);
            }
            for (int e = 0; e < count; e++) {
                if (edgeV[e] == n) continue;
                // r[v] >= r[u] + 1 - n*(1-x[e]); also excludes physical self-loops.
                IloLinearNumExpr order = cplex.linearNumExpr();
                order.addTerm(1.0, rank[edgeV[e]]);
                order.addTerm(-1.0, rank[edgeU[e]]);
                order.addTerm(-n, x[e]);
                cplex.addGe(order, 1.0 - n, "mtz_" + e);
            }
            for (int p = 0; p < exclusions.size(); p++) {
                int[] path = exclusions.get(p);
                IloLinearNumExpr noGood = cplex.linearNumExpr();
                for (int e : path) noGood.addTerm(1.0, x[e]);
                cplex.addLe(noGood, path.length - 1.0, "exclude_path_" + p);
            }
            cplex.addMinimize(objective);

            double remaining = timeSeconds - (System.nanoTime() - started) / 1e9;
            if (remaining <= 0.0) {
                result.status = "UNKNOWN_NO_TIME_REMAINING_BEFORE_SOLVE";
                return result;
            }
            cplex.setParam(IloCplex.Param.TimeLimit, remaining);
            boolean hasIncumbent = cplex.solve();
            IloCplex.Status actualStatus = cplex.getStatus();
            result.status = actualStatus.toString() + "/" + cplex.getCplexStatus();
            if (actualStatus == IloCplex.Status.Infeasible) {
                result.infeasible = true;
                result.lowerBound = Double.POSITIVE_INFINITY;
                return result;
            }

            // An ambiguous infeasible-or-unbounded status is not an infeasibility
            // certificate. Likewise, a missing bound never becomes an incumbent.
            if (actualStatus != IloCplex.Status.InfeasibleOrUnbounded
                    && actualStatus != IloCplex.Status.Unbounded) {
                try {
                    double bound = cplex.getBestObjValue();
                    if (Double.isFinite(bound) || bound == Double.NEGATIVE_INFINITY) {
                        result.lowerBound = bound;
                    } else {
                        result.status += "/UNKNOWN_BOUND_UNAVAILABLE";
                    }
                } catch (Exception boundError) {
                    result.status += "/UNKNOWN_BOUND_UNAVAILABLE:" + boundError.getClass().getSimpleName();
                }
            }

            if (hasIncumbent) {
                double[] values = cplex.getValues(x);
                int[] ordered = selectedPath(n, source, edgeU, edgeV, values);
                double pathCost = 0.0, pathTime = 0.0;
                boolean[] used = new boolean[count];
                for (int e : ordered) {
                    used[e] = true;
                    if (!allowed[e]) throw new IllegalStateException("Incumbent uses forbidden edge " + e);
                    pathCost += edgeCost[e];
                    pathTime += edgeTime[e];
                }
                for (int e = 0; e < count; e++) {
                    if (required[e] && !used[e]) throw new IllegalStateException("Incumbent omits required edge " + e);
                }
                if (!Double.isFinite(pathCost) || !Double.isFinite(pathTime)) throw new IllegalStateException("Nonfinite path value");
                if (pathTime > timeWindow + 1e-7) throw new IllegalStateException("Incumbent violates its time window");
                for (int[] excluded : exclusions) {
                    boolean allUsed = true;
                    for (int e : excluded) if (!used[e]) { allUsed = false; break; }
                    if (allUsed) throw new IllegalStateException("Incumbent violates a path exclusion");
                }
                double cplexObjective = cplex.getObjValue();
                double tolerance = 1e-7 * Math.max(1.0, Math.max(Math.abs(pathCost), Math.abs(cplexObjective)));
                if (Math.abs(pathCost - cplexObjective) > tolerance) throw new IllegalStateException("Path cost and CPLEX objective disagree");
                if (result.lowerBound > pathCost + tolerance) throw new IllegalStateException(
                        "CPLEX lower bound exceeds feasible path cost: LB=" + result.lowerBound
                        + ", pathCost=" + pathCost + ", CPLEXObj=" + cplexObjective
                        + ", tol=" + tolerance + ", status=" + result.status);
                result.pathEdges = ordered;
                result.objective = pathCost;
            }
        } catch (Exception error) {
            unknown(result, error);
        } finally {
            if (cplex != null) {
                try { cplex.end(); }
                catch (Exception cleanupError) { unknown(result, cleanupError); }
            }
        }
        return result;
    }

    private static void unknown(Result result, Exception error) {
        result.pathEdges = null;
        result.objective = Double.POSITIVE_INFINITY;
        result.lowerBound = Double.NEGATIVE_INFINITY;
        result.infeasible = false;
        result.status = "UNKNOWN:" + error.getClass().getSimpleName() + ":" + error.getMessage();
    }

    private static void validateInput(int n, int source, int[] u, int[] v, double[] cost, double[] time,
                                       double window, boolean[] allowed, boolean[] required) {
        if (n < 1 || source < 0 || source >= n) throw new IllegalArgumentException("Invalid physical node/source");
        if (u == null || v == null || cost == null || time == null || allowed == null || required == null)
            throw new IllegalArgumentException("Null pricing array");
        int count = u.length;
        if (v.length != count || cost.length != count || time.length != count || allowed.length != count || required.length != count)
            throw new IllegalArgumentException("Pricing array lengths differ");
        if (!Double.isFinite(window) || window < 0.0) throw new IllegalArgumentException("Invalid time window");
        for (int e = 0; e < count; e++) {
            if (u[e] < 0 || u[e] >= n || v[e] < 0 || v[e] > n) throw new IllegalArgumentException("Invalid edge endpoints at " + e);
            if (!Double.isFinite(cost[e]) || !Double.isFinite(time[e]) || time[e] < 0.0)
                throw new IllegalArgumentException("Invalid cost/time at edge " + e);
        }
    }

    private static int[] selectedPath(int n, int source, int[] u, int[] v, double[] values) {
        ArrayList<Integer> selected = new ArrayList<>();
        for (int e = 0; e < values.length; e++) {
            double value = values[e];
            if (!Double.isFinite(value) || value < -1e-6 || value > 1.0 + 1e-6
                    || Math.abs(value - Math.rint(value)) > 1e-6)
                throw new IllegalStateException("Nonbinary path incumbent at edge " + e);
            if (value > 0.5) selected.add(e);
        }
        int[] indices = selected.stream().mapToInt(Integer::intValue).toArray();
        return validateCompletePath(n, source, u, v, indices);
    }

    /** Reconstruct and require exact coverage, rejecting disconnected arcs/cycles. */
    private static int[] validateCompletePath(int n, int source, int[] u, int[] v, int[] indices) {
        if (indices == null || indices.length == 0 || indices.length > n)
            throw new IllegalArgumentException("A complete elementary path must include its virtual terminal edge");
        int[] outgoing = new int[n];
        Arrays.fill(outgoing, -1);
        HashSet<Integer> unique = new HashSet<>();
        for (int e : indices) {
            if (e < 0 || e >= u.length || !unique.add(e)) throw new IllegalArgumentException("Invalid/duplicate path edge");
            if (outgoing[u[e]] >= 0) throw new IllegalArgumentException("Multiple selected outgoing edges at node " + u[e]);
            outgoing[u[e]] = e;
        }
        boolean[] visited = new boolean[n];
        ArrayList<Integer> ordered = new ArrayList<>();
        int current = source;
        while (current != n) {
            if (visited[current]) throw new IllegalArgumentException("Physical cycle in path");
            visited[current] = true;
            int e = outgoing[current];
            if (e < 0) throw new IllegalArgumentException("Path does not reach virtual sink");
            ordered.add(e);
            current = v[e];
        }
        if (ordered.size() != indices.length) throw new IllegalArgumentException("Selected path contains leftover disconnected edges");
        return ordered.stream().mapToInt(Integer::intValue).toArray();
    }
}

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Public entry point for one solve or fixed-design evaluation. */
public final class Replication {
    private Replication() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 6) {
            throw new IllegalArgumentException(
                    "solve CPA|BPA|SL INPUT OUTPUT_DIR [SECONDS], or "
                    + "evaluate sequential|carrier|tie|original_base|original_small|original_fractional "
                    + "INPUT OUTPUT_DIR DESIGN_OR_DASH [SECONDS]");
        }
        boolean solve = args[0].equals("solve");
        if ((!solve && !args[0].equals("evaluate")) || (solve && args.length > 5)
                || (!solve && args.length < 5)) throw new IllegalArgumentException("Invalid command");
        String method = args[1];
        Path input = Path.of(args[2]), output = Path.of(args[3]);
        int optionalIndex = solve ? 4 : 5;
        double limit = args.length > optionalIndex ? Double.parseDouble(args[optionalIndex]) : 1800.0;
        if (!Double.isFinite(limit) || limit <= 0) throw new IllegalArgumentException("Invalid time limit");
        List<String> allowed = solve ? Arrays.asList("CPA", "BPA", "SL")
                : Arrays.asList("sequential", "carrier", "tie", "original_base", "original_small", "original_fractional");
        if (!allowed.contains(method)) throw new IllegalArgumentException("Unknown method: " + method);

        String hash = EffectiveInput.sha(input);
        DataCreator data = EffectiveInput.read(input);
        EffectiveInput.validate(data);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.createDirectory(output);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("case_id", input.getFileName().toString().replaceFirst("\\.bin\\.gz$", ""));
        record.put("input_sha256", hash);
        record.put("method", method);
        record.put("time_limit_seconds", limit);
        if (solve) {
            solve(data, method, hash, output, limit, record);
        } else {
            evaluate(data, method, hash, args[4], limit, record);
        }
        Path result = output.resolve("result.json");
        DesignChecks.writeJson(result, record);
        System.out.println("Result: " + result);
        if (solve && "FAIL".equals(record.get("verification_status"))) {
            throw new IllegalStateException("Independent verification failed. See result.json");
        }
    }

    private static void solve(DataCreator data, String method, String hash, Path output,
                              double limit, Map<String, Object> record) throws Exception {
        long start = System.nanoTime();
        Object solution;
        if ("BPA".equals(method)) {
            solution = new EnhancedBranchAndPriceSolverV4(data, 1e-6, 3, 5, 5, 300, 4000,
                    limit, 1_000_000).solve();
        } else {
            CorrectedCuttingPlaneSolver solver = new CorrectedCuttingPlaneSolver(data, limit, 200, 1_000_000);
            solution = method.equals("SL") ? solver.solveCentralized() : solver.solve();
        }
        record.put("external_solver_seconds", (System.nanoTime() - start) / 1e9);
        Map<String, Object> fields = DesignChecks.fields(solution);
        record.put("solver", fields);
        if (!Boolean.TRUE.equals(fields.get("solved"))) {
            record.put("verification_status", "NO_INCUMBENT");
            return;
        }
        double objective = (double) fields.get(method.equals("BPA") ? "bestObjective" : "objective");
        double bound = (double) fields.get(method.equals("BPA") ? "bestLowerBound" : "lowerBound");
        if (Double.isFinite(bound) && bound > objective + 1e-4)
            throw new IllegalStateException("Certified lower bound exceeds the incumbent objective");
        @SuppressWarnings("unchecked")
        List<int[]> routes = (List<int[]>) fields.get("selectedRouteNodes");
        DesignChecks.Design design = DesignChecks.design(data, (String) fields.get("selectedFacilityNodes"),
                (String) fields.get("selectedRoadEdges"), routes);
        Map<String, Object> check = DesignChecks.verify(data, design, objective, !method.equals("SL"), 600);
        record.put("verification", check);
        record.put("verification_status", check.get("status"));
        DesignChecks.saveDesign(output.resolve("design.bin"), hash, design);
        System.out.printf(Locale.ROOT, "%s %s objective=%.10f bound=%.10f verification=%s%n",
                method, fields.get("terminationReason"), objective, bound, check.get("status"));
    }

    private static void evaluate(DataCreator data, String method, String hash, String designFile,
                                 double limit, Map<String, Object> record) throws Exception {
        if (method.equals("sequential")) {
            if (!designFile.equals("-")) throw new IllegalArgumentException("Use '-' for a sequential evaluation");
            record.put("evaluation", DesignChecks.fields(UnifiedDesignEvaluator.sequential(data, 600, limit)));
            return;
        }
        Path file = Path.of(designFile);
        DesignChecks.Design design = DesignChecks.readDesign(file, hash);
        record.put("design_sha256", EffectiveInput.sha(file));
        if (method.equals("carrier")) {
            record.put("evaluation", DesignChecks.fields(UnifiedDesignEvaluator.carrierCosts(
                    data, design.open, design.reserved, 600)));
        } else if (method.equals("tie")) {
            record.put("evaluation", DesignChecks.fields(UnifiedDesignEvaluator.worstTie(
                    data, design.open, design.reserved, design.routes, 600, limit)));
        } else {
            if (!method.equals("original_base")) {
                double budget = method.equals("original_small") ? 1.0 : 0.5;
                data.setQuantityRiskExpectation(budget);
                data.setRoadRiskExpectation(budget);
            }
            record.put("gamma_u", data.getQuantityRiskExpectation());
            record.put("gamma_v", data.getRoadRiskExpectation());
            record.put("evaluation", DesignChecks.fields(UnifiedRiskEvaluator.evaluateOriginal(data, design.routes, limit)));
            record.put("conservative", DesignChecks.fields(UnifiedRiskEvaluator.evaluateConservative(data, design.routes, 60)));
            record.put("theoretical_factor", UnifiedRiskEvaluator.theoreticalFactor(data));
        }
    }
}

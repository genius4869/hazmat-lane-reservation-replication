import java.util.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Load, sample, and expand the EM benchmark. */
final class EmInstances {
    static DataCreator loadCase(Path dir) throws Exception {
        Properties meta = loadProperties(dir.resolve("metadata.properties"));
        int n = Integer.parseInt(meta.getProperty("n"));
        int sCount = Integer.parseInt(meta.getProperty("s"));
        DataCreator data = new DataCreator(n, 1L);
        data.setGraph(readIntMatrix(dir.resolve("graph.csv")));
        data.setNodeDistance(readDoubleMatrix(dir.resolve("cij.csv")));
        ArrayList<ArrayList<Double>> impact = readDoubleMatrix(UnifiedExperimentData.IMPACT);
        data.setDedicatedLaneCostWeight(impact);
        data.setDedicatedLaneBuildCostWeight(readDoubleMatrix(dir.resolve("lane_build.csv")));
        data.setNodeTime(readDoubleMatrix(dir.resolve("time.csv")));
        data.setStartPoints(readIntList(dir.resolve("sources.csv")));
        data.setCandidatePoints(readIntList(dir.resolve("candidates.csv")));
        data.setCandidateBuildWeights(readDoubleList(dir.resolve("candidate_weights.csv")));
        data.setMinTransportTasks(readDoubleList(dir.resolve("demand.csv")));
        data.setTransportTaskBuffs(readDoubleList(dir.resolve("demand_width.csv")));
        data.setTimeWindow(readDoubleList(dir.resolve("time_window.csv")));
        data.setGovernmentRoadBudget(Double.parseDouble(meta.getProperty("budget")));
        data.setQuantityRiskExpectation(Double.parseDouble(meta.getProperty("tau_u")));
        data.setRoadRiskExpectation(Double.parseDouble(meta.getProperty("tau_v")));
        data.setIncludeNominalRiskInObjective(true);
        ArrayList<ArrayList<ArrayList<Double>>> r = new ArrayList<>();
        ArrayList<ArrayList<ArrayList<Double>>> q = new ArrayList<>();
        for (int s = 0; s < sCount; s++) {
            r.add(readDoubleMatrix(dir.resolve("risk_min_s" + s + ".csv")));
            q.add(readDoubleMatrix(dir.resolve("risk_width_s" + s + ".csv")));
        }
        data.setSourceMinRisks(r);
        data.setSourceRiskBuffs(q);
        data.setMinRisks(r.get(0));
        data.setRiskBuffs(q.get(0));
        return data;
    }

    static DataCreator copyCase(DataCreator d) {
        DataCreator out = new DataCreator(d.getGraph().size(), 1L);
        out.setGraph(d.getGraph());
        out.setNodeDistance(d.getNodeDistance());
        out.setDedicatedLaneCostWeight(d.getDedicatedLaneCostWeight());
        out.setDedicatedLaneBuildCostWeight(d.getDedicatedLaneBuildCostWeight());
        out.setNodeTime(d.getNodeTime());
        out.setStartPoints(d.getStartPoints());
        out.setCandidatePoints(d.getCandidatePoints());
        out.setCandidateBuildWeights(d.getCandidateBuildWeights());
        out.setMinTransportTasks(d.getMinTransportTasks());
        out.setTransportTaskBuffs(d.getTransportTaskBuffs());
        out.setTimeWindow(d.getTimeWindow());
        out.setGovernmentRoadBudget(d.getGovernmentRoadBudget());
        out.setQuantityRiskExpectation(d.getQuantityRiskExpectation());
        out.setRoadRiskExpectation(d.getRoadRiskExpectation());
        out.setIncludeNominalRiskInObjective(d.getIncludeNominalRiskInObjective());
        out.setSourceMinRisks(d.getSourceMinRisks());
        out.setSourceRiskBuffs(d.getSourceRiskBuffs());
        out.setMinRisks(d.getMinRisks());
        out.setRiskBuffs(d.getRiskBuffs());
        return out;
    }

    static DataCreator subsetSources(DataCreator base, int sCount, long seed) {
        ArrayList<Integer> indices = new ArrayList<>();
        for (int i = 0; i < base.getStartPoints().size(); i++) indices.add(i);
        Collections.shuffle(indices, new Random(seed));
        indices = new ArrayList<>(indices.subList(0, Math.min(sCount, indices.size())));
        Collections.sort(indices);
        DataCreator data = copyCase(base);
        ArrayList<Integer> starts = new ArrayList<>();
        ArrayList<Double> demand = new ArrayList<>();
        ArrayList<Double> demandWidth = new ArrayList<>();
        ArrayList<Double> windows = new ArrayList<>();
        ArrayList<ArrayList<ArrayList<Double>>> r = new ArrayList<>();
        ArrayList<ArrayList<ArrayList<Double>>> q = new ArrayList<>();
        for (int idx : indices) {
            starts.add(base.getStartPoints().get(idx));
            demand.add(base.getMinTransportTasks().get(idx));
            demandWidth.add(base.getTransportTaskBuffs().get(idx));
            windows.add(base.getTimeWindow().get(idx));
            r.add(base.getSourceMinRisks().get(idx));
            q.add(base.getSourceRiskBuffs().get(idx));
        }
        data.setStartPoints(starts);
        data.setMinTransportTasks(demand);
        data.setTransportTaskBuffs(demandWidth);
        data.setTimeWindow(windows);
        data.setSourceMinRisks(r);
        data.setSourceRiskBuffs(q);
        data.setMinRisks(r.get(0));
        data.setRiskBuffs(q.get(0));
        data.setQuantityRiskExpectation(Math.min(data.getQuantityRiskExpectation(), starts.size()));
        return data;
    }

    static DataCreator composeEmExpandedCase(int sourceCount, int candidateCount) throws Exception {
        Path converted = UnifiedExperimentData.CONVERTED;
        DataCreator sourceBase = loadCase(converted.resolve("em_s30_m6"));
        String candidateCase = candidateCount == 6 ? "em_s12_m6" : "em_s12_m" + candidateCount;
        DataCreator candidateBase = loadCase(converted.resolve(candidateCase));
        DataCreator data = expandSourcesWithReplacement(sourceBase, sourceCount, 993000L + sourceCount * 37L + candidateCount);
        data.setCandidatePoints(candidateBase.getCandidatePoints());
        data.setCandidateBuildWeights(candidateBase.getCandidateBuildWeights());
        data.setGovernmentRoadBudget(Math.max(sourceBase.getGovernmentRoadBudget(), candidateBase.getGovernmentRoadBudget()));
        return data;
    }

    static DataCreator expandSourcesWithReplacement(DataCreator base, int sCount, long seed) {
        Random random = new Random(seed);
        DataCreator data = copyCase(base);
        ArrayList<Integer> starts = new ArrayList<>();
        ArrayList<Double> demand = new ArrayList<>();
        ArrayList<Double> demandWidth = new ArrayList<>();
        ArrayList<Double> windows = new ArrayList<>();
        ArrayList<ArrayList<ArrayList<Double>>> r = new ArrayList<>();
        ArrayList<ArrayList<ArrayList<Double>>> q = new ArrayList<>();
        int baseCount = base.getStartPoints().size();
        for (int i = 0; i < sCount; i++) {
            int idx = i < baseCount ? i : random.nextInt(baseCount);
            starts.add(base.getStartPoints().get(idx));
            demand.add(base.getMinTransportTasks().get(idx));
            demandWidth.add(base.getTransportTaskBuffs().get(idx));
            windows.add(base.getTimeWindow().get(idx));
            r.add(base.getSourceMinRisks().get(idx));
            q.add(base.getSourceRiskBuffs().get(idx));
        }
        data.setStartPoints(starts);
        data.setMinTransportTasks(demand);
        data.setTransportTaskBuffs(demandWidth);
        data.setTimeWindow(windows);
        data.setSourceMinRisks(r);
        data.setSourceRiskBuffs(q);
        data.setMinRisks(r.get(0));
        data.setRiskBuffs(q.get(0));
        data.setQuantityRiskExpectation(Math.min(data.getQuantityRiskExpectation(), starts.size()));
        return data;
    }

    static ArrayList<ArrayList<Integer>> readIntMatrix(Path path) throws Exception {
        ArrayList<ArrayList<Integer>> out = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            ArrayList<Integer> row = new ArrayList<>();
            for (String part : line.split(",")) row.add(Integer.parseInt(part.trim()));
            out.add(row);
        }
        return out;
    }
    static ArrayList<ArrayList<Double>> readDoubleMatrix(Path path) throws Exception {
        ArrayList<ArrayList<Double>> out = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            ArrayList<Double> row = new ArrayList<>();
            for (String part : line.split(",")) row.add(Double.parseDouble(part.trim()));
            out.add(row);
        }
        return out;
    }
    static List<Integer> readIntList(Path path) throws Exception {
        ArrayList<Integer> out = new ArrayList<>();
        String line = Files.readString(path, StandardCharsets.UTF_8).trim();
        if (!line.isEmpty()) for (String part : line.split(",")) out.add(Integer.parseInt(part.trim()));
        return out;
    }
    static List<Double> readDoubleList(Path path) throws Exception {
        ArrayList<Double> out = new ArrayList<>();
        String line = Files.readString(path, StandardCharsets.UTF_8).trim();
        if (!line.isEmpty()) for (String part : line.split(",")) out.add(Double.parseDouble(part.trim()));
        return out;
    }
    static Properties loadProperties(Path path) throws Exception {
        Properties props = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }
}

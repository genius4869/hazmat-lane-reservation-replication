import java.util.*;

class ExperimentDataFactory {
    static DataCreator createPaperData(
            int n,
            long seed,
            int sourceCount,
            int candidateCount,
            double density,
            double gammaUFactor,
            double gammaVFactor,
            double riskScale,
            double timeWindowFactor
    ) {
        DataCreator data = new DataCreator(n, seed);
        data.generateAllDataWithStartPoint(sourceCount, density);
        data.generateCandidatePoints(candidateCount);
        Random random = new Random(seed * 31L + 17L);
        ArrayList<ArrayList<Integer>> graph = data.getGraph();
        int edgeCount = 0;
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) if (i != j && graph.get(i).get(j) == 1) edgeCount++;
        data.setMinTransportTasks(randomList(random, sourceCount, 25.0, 120.0, true));
        data.setTransportTaskBuffs(randomList(random, sourceCount, 51.0, 56.0, false));
        overwriteRiskMatrices(data, random, riskScale);
        data.setTimeWindow(timeWindows(data, random, timeWindowFactor));
        data.setGovernmentRoadBudget(10000.0);
        data.setQuantityRiskExpectation(gammaUFactor * sourceCount);
        data.setRoadRiskExpectation(gammaVFactor * edgeCount);
        return data;
    }

    private static List<Double> randomList(Random random, int count, double min, double max, boolean integer) {
        ArrayList<Double> values = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double value = integer ? (min + random.nextInt((int) (max - min + 1))) : min + random.nextDouble() * (max - min);
            values.add(Math.round(value * 100.0) / 100.0);
        }
        return values;
    }

    private static void overwriteRiskMatrices(DataCreator data, Random random, double riskScale) {
        int n = data.getGraph().size();
        ArrayList<ArrayList<Double>> minRisks = data.getMinRisks();
        ArrayList<ArrayList<Double>> riskBuffs = data.getRiskBuffs();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j && data.getGraph().get(i).get(j) == 1) {
                    minRisks.get(i).set(j, round2(3.0 + random.nextDouble() * 7.0));
                    double q = Math.max(0.1, 10.0 + random.nextGaussian() * 4.7);
                    riskBuffs.get(i).set(j, round2(q * riskScale));
                } else {
                    minRisks.get(i).set(j, 0.0);
                    riskBuffs.get(i).set(j, 0.0);
                }
            }
        }
    }

    private static List<Double> timeWindows(DataCreator data, Random random, double timeWindowFactor) {
        ArrayList<Double> windows = new ArrayList<>();
        for (int source : data.getStartPoints()) {
            double shortest = shortestTimeToAnyCandidate(data, source);
            double factor = timeWindowFactor > 0.0 ? timeWindowFactor : 1.2 + random.nextDouble() * 0.3;
            windows.add(round2(shortest * factor));
        }
        return windows;
    }

    private static double shortestTimeToAnyCandidate(DataCreator data, int source) {
        int n = data.getGraph().size();
        double[] dist = new double[n];
        boolean[] used = new boolean[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        dist[source] = 0.0;
        for (int it = 0; it < n; it++) {
            int u = -1;
            for (int i = 0; i < n; i++) if (!used[i] && (u < 0 || dist[i] < dist[u])) u = i;
            if (u < 0 || Double.isInfinite(dist[u])) break;
            used[u] = true;
            for (int v = 0; v < n; v++) {
                if (data.getGraph().get(u).get(v) != 1) continue;
                double nd = dist[u] + data.getNodeTime().get(v).get(u);
                if (nd < dist[v]) dist[v] = nd;
            }
        }
        double best = Double.POSITIVE_INFINITY;
        for (int candidate : data.getCandidatePoints()) best = Math.min(best, dist[candidate]);
        return Double.isInfinite(best) ? 1e6 : Math.max(best, 1.0);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}


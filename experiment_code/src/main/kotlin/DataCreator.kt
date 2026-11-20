import java.util.*
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

class DataCreator(private val pointSize: Int, seed: Long = System.currentTimeMillis()) {
    /**
     * Directed adjacency matrix.
     */
    var graph: ArrayList<ArrayList<Int>>? = null

    /**
     * Carrier travel cost c_ij.
     */
    var nodeDistance: ArrayList<ArrayList<Double>>? = null

    /**
     * Travel duration T_ij. The internal solver representation stores T_ij at [j][i].
     */
    var nodeTime: ArrayList<ArrayList<Double>>? = null

    /**
     * Ordinary-traffic impact I_ij of reserving a lane.
     */
    var dedicatedLaneCostWeight: ArrayList<ArrayList<Double>>? = null

    /**
     * Reservation expenditure L_ij.
     */
    var dedicatedLaneBuildCostWeight: ArrayList<ArrayList<Double>>? = null

    /**
     * Origin of each shipment task S.
     */
    var startPoints: List<Int> = emptyList()

    /**
     * Travel time limit W_s for each task.
     */
    var timeWindow: List<Double> = emptyList()

    /**
     * Candidate disposal sites M.
     */
    var candidatePoints: List<Int> = emptyList()

    /**
     * Opening cost F_m, in candidatePoints order.
     */
    var candidateBuildWeights: List<Double> = emptyList()

    /**
     * Nominal shipment volume N_s, in startPoints order.
     * Measured in standard transport units.
     */
    var minTransportTasks: List<Double> = emptyList()

    /**
     * Shipment-volume deviation K_s, in startPoints order.
     */
    var transportTaskBuffs: List<Double> = emptyList()

    /**
     * Shared nominal road risk R_ij.
     */
    var minRisks: ArrayList<ArrayList<Double>>? = null

    /**
     * Shared road risk deviation Q_ij.
     */
    var riskBuffs: ArrayList<ArrayList<Double>>? = null

    /**
     * Optional source-dependent risk matrices for real-network instances.
     * Indexed as [sourceIndex][i][j]. When absent, minRisks/riskBuffs are used.
     */
    var sourceMinRisks: ArrayList<ArrayList<ArrayList<Double>>>? = null
    var sourceRiskBuffs: ArrayList<ArrayList<ArrayList<Double>>>? = null

    /**
     * Include the nominal risk term in the objective. All archived inputs enable it.
     */
    var includeNominalRiskInObjective: Boolean = true

    /**
     * Government road reservation budget B.
     */
    var governmentRoadBudget: Double = 0.0

    /**
     * Shipment uncertainty budget.
     */
    var quantityRiskExpectation : Double = 0.0

    /**
     * Road uncertainty budget.
     */
    var roadRiskExpectation : Double = 0.0

    private val random = Random(seed)

    // Generate all road-level matrices.
    fun generateAllMatrices(density: Double = 0.3) {
        require(density in 0.0..1.0) { "Density must be between 0 and 1" }

        // Generate the symmetric adjacency matrix.
        graph = generateSymmetricMatrix(density)

        // Generate the associated matrices.
        dedicatedLaneBuildCostWeight = generateDoubleMatrix(10.0, 100.0)
        dedicatedLaneCostWeight = generateDoubleMatrix(1.0, 5.0)
        nodeDistance = generateDoubleMatrix(1.0, 50.0).apply { makeSymmetric() }
        nodeTime = generateDoubleMatrix(0.5, 5.0)
        minRisks = generateDoubleMatrix(0.1, 1.0).apply { normalizeMinRisks() }
        riskBuffs = generateDoubleMatrix(0.01, 0.2)
    }

    fun generateAllDataWithStartPoint(
        startPointCount: Int,
        density: Double = 0.3
    ) {
        require(startPointCount in 1..pointSize) { "Origin count must be between 1 and the number of nodes" }

        // Generate the network.
        generateAllMatrices(density)

        // Generate origin attributes.
        generateStartPoints(startPointCount)
    }


    // Generate a symmetric binary adjacency matrix.
    private fun generateSymmetricMatrix(density: Double): ArrayList<ArrayList<Int>> {
        // Initialize a square matrix.
        val matrix = ArrayList<ArrayList<Int>>().apply {
            repeat(pointSize) {
                add(ArrayList<Int>().apply {
                    repeat(pointSize) { add(0) }
                })
            }
        }

        // Give each node at least one incident edge.
        for (i in 0..<pointSize) {
            // Draw a neighbor distinct from the current node.
            val forcedJ = (0..<pointSize)
                .filter { it != i }
                .random(random)
            matrix[i][forcedJ] = 1
            matrix[forcedJ][i] = 1 // Preserve symmetry.
        }

        // Add the remaining edges with the specified probability.
        for (i in 0..<pointSize) {
            for (j in i + 1..<pointSize) {
                if (matrix[i][j] == 0 && random.nextDouble() < density) {
                    matrix[i][j] = 1
                    matrix[j][i] = 1
                }
            }
        }
        return matrix
    }

    // Generate values on adjacent node pairs.
    private fun generateDoubleMatrix(min: Double, max: Double): ArrayList<ArrayList<Double>> {
        return ArrayList<ArrayList<Double>>().apply {
            repeat(pointSize) { i ->
                add(ArrayList<Double>().apply {
                    repeat(pointSize) { j ->
                        add(if (graph?.get(i)?.get(j) == 1) random.nextDouble(min, max) else 0.0)
                    }
                })
            }
        }
    }


    fun generateCandidatePoints(candidatePointCount: Int) {
        require(candidatePointCount in 1..(pointSize - startPoints.size)) {
            "Candidate count must be between 1 and the remaining node count (${pointSize - startPoints.size})"
        }

        // Collect eligible non-origin nodes with an outgoing arc.
        val availableNodes = (0 until pointSize)
            .filterNot { it in startPoints }
            .filter { node ->
                graph?.get(node)?.any { it == 1 } == true  // At least one outgoing arc.
            }
            .toMutableList()

        require(availableNodes.size >= candidatePointCount) {
            "Insufficient eligible sites (${availableNodes.size} < $candidatePointCount)"
        }

        // Sample the candidate sites.
        candidatePoints = List(candidatePointCount) {
            availableNodes.random(random).also {
                availableNodes.remove(it)  // Sample without replacement.
            }
        }

        // Weight the opening cost by out-degree.
        candidateBuildWeights = candidatePoints.map { node ->
            val outDegree = graph?.get(node)?.count { it == 1 } ?: 0
            random.nextDouble(10.0, 100.0).roundTo(2) * (1 + outDegree * 0.1)  // Sites with more outgoing arcs have higher costs.
        }
    }

    // Mirror values across the diagonal.
    private fun ArrayList<ArrayList<Double>>.makeSymmetric() {
        for (i in 0..<pointSize) {
            for (j in i + 1..<pointSize) {
                this[j][i] = this[i][j]
            }
        }
    }

    // Apply the nominal-risk floor.
    private fun ArrayList<ArrayList<Double>>.normalizeMinRisks() {
        for (i in 0..<pointSize) {
            for (j in 0..<pointSize) {
                if (this[i][j] in 0.0..0.3) this[i][j] = 0.3
            }
        }
    }

    private fun generateStartPoints(count: Int) {
        // Sample distinct origins.
        startPoints = List(pointSize) { it }.shuffled(random).take(count)

        // Compute a reference travel duration.
        val maxPathTime = calculateTimeBounds()

        // Generate task time limits.
        timeWindow = startPoints.map {
            random.nextDouble(
                maxPathTime * 0.8,  // Lower bound.
                maxPathTime * 1.2   // Upper bound.
            ).roundTo(2)
        }

        // Generate task quantities (1 to 100 standard units).
        minTransportTasks = List(count) { random.nextDouble(1.0, 100.0).roundTo(2) }

        // Generate shipment deviations (0.5 to 1.0).
        transportTaskBuffs = List(count) { random.nextDouble(0.5, 1.0).roundTo(2) }
    }

    // Compute the reference duration from road times.
    private fun calculateTimeBounds(): Double {
        requireNotNull(nodeTime) { "Generate the travel-time matrix first" }

        var maxTime = 0.0

        // Check nonzero entries.
        for (i in 0..<pointSize) {
            for (j in 0..<pointSize) {
                if (nodeTime!![i][j] > 0) {
                    maxTime = max(maxTime, nodeTime!![i][j])
                }
            }
        }

        // Scale the largest road duration by the number of nodes.
        return maxTime * pointSize  // Upper estimate of a full-length route.
    }

    // Round to a fixed number of decimal places.
    private fun Double.roundTo(decimals: Int): Double {
        val factor = 10.0.pow(decimals)
        return (this * factor).roundToInt() / factor
    }
}

# Optimization algorithms

Run commands in this directory. Supply an input from `../experiment_data/instances/inputs` and a fresh output directory for each run. Each completed optimization writes `result.json`; an incumbent also produces `design.bin`, which embeds the SHA-256 of its input.

## Build from source

The published study used 64-bit Windows, JDK 15.0.1, CPLEX 22.1.0 and Kotlin standard library 2.1.10. Install an IBM CPLEX edition licensed for the relevant instance sizes, a JDK, and a Kotlin 2.1 compiler distribution. The independent build below used Kotlin compiler 2.1.21, language/API version 2.1 and standard library 2.1.10. The historical compiler version was not recorded.

| Environment variable | Value |
| --- | --- |
| `JAVA_HOME` | JDK directory containing `bin/java` and `bin/javac` |
| `KOTLIN_HOME` | Kotlin compiler directory containing `lib/kotlin-compiler.jar` |
| `CPLEX_STUDIO_DIR` | CPLEX Studio installation containing `cplex/lib/cplex.jar` and `cplex/bin` |
| `KOTLIN_STDLIB` | Optional path to `kotlin-stdlib-2.1.10.jar` for the historical runtime. Both builds default to `KOTLIN_HOME/lib/kotlin-stdlib.jar`. |
| `CPLEX_JAR`, `CPLEX_NATIVE_DIR` | Optional overrides for the solver JAR and native library directory. Use both with a nonstandard installation. |
| `HAZMAT_HEAP` | Java heap ceiling. The default, `1024m`, matches the formal comparison runs. |

With locally installed Gradle 7.6.4:

```shell
gradle --offline build
gradle --offline run --args="solve CPA ../experiment_data/instances/inputs/R01_seed123.bin.gz runs/random-cpa 600"
```

Alternatively use Python 3.8+ and the same installed compilers. This launcher uses the Python standard library and builds both languages directly:

```shell
python solver.py build
python solver.py solve CPA ../experiment_data/instances/inputs/R01_seed123.bin.gz runs/example-random-cpa 600
python solver.py solve BPA ../experiment_data/instances/inputs/R01_seed123.bin.gz runs/example-random-bpa 600
python solver.py solve CPA ../experiment_data/instances/inputs/EM_SUB_s12_r1_m6.bin.gz runs/example-em-cpa 1800
python solver.py solve BPA ../experiment_data/instances/inputs/EM_SUB_s12_r1_m6.bin.gz runs/example-em-bpa 1800
```

The direct launcher compiles into an empty directory. To use a different location, give `--classes DIRECTORY` before each `build`, `solve` or `evaluate` command. Gradle and the direct launcher use different build directories within `build`; when using both, select a fresh `--classes` directory for the direct launcher. All build and run destinations can be external to this archive.

## Experiments

The command syntax is `solve METHOD INPUT OUTPUT_DIR [SECONDS]` or `evaluate METHOD INPUT OUTPUT_DIR DESIGN_OR_DASH [SECONDS]`. The algorithm time limit defaults to 1,800 seconds. Module 1 used 1,800 seconds, and the random comparison in Module 5 used 600 seconds. Independent objective and carrier checks have separate limits of 60 and 600 seconds, respectively. Interpret the solver termination flag and bounds together with `verification_status`. A partial check indicates missing verification evidence.

| Study calculation | Public command |
| --- | --- |
| Bilevel cutting-plane and branch-and-price designs | `solve CPA` or `solve BPA` |
| Centralized single-level comparison | `solve SL`. Evaluate `carrier` on the resulting design to obtain the minimum achievable carrier cost for each task. Compare with costs on the selected routes. |
| Worst risk among cost-minimizing follower routes on a fixed design | `evaluate tie`. The exact-arithmetic cost-tie screening is documented with the archived data. |
| Sequential infrastructure design | `evaluate sequential` with `-` for the design argument |
| Parameter sensitivity | `solve CPA` on the corresponding archived parameter variant |
| Worst risk on fixed baseline routes | `evaluate original_base`, `original_small`, or `original_fractional`. The last two set both uncertainty budgets to 1 or 0.5. Output records both conservative and original risk, bounds and the theoretical factor. |

For example, using a design generated above:

```shell
python solver.py evaluate carrier ../experiment_data/instances/inputs/EM_SUB_s12_r1_m6.bin.gz runs/em-carrier runs/example-em-cpa/design.bin 1800
python solver.py evaluate sequential ../experiment_data/instances/inputs/EM_SUB_s12_r1_m6.bin.gz runs/em-sequential - 1800
```

For the fixed-route risk study, generate a baseline design using `solve CPA` on `EM_ORIG_s12_m6__base.bin.gz`, `EM_ORIG_s18_m6__base.bin.gz` or `EM_ORIG_s24_m6__base.bin.gz`, then pass the same input and its `design.bin` to all three risk evaluations.

## Source provenance and verification

The six Java files below match the bytes recorded in the formal build manifest. The persistent restricted master problem and exact label pricing are enabled by default. The separate path MILP is used by independent carrier checks. `DataCreator.kt` retains the study's data representation and generator, with English comments and diagnostics. `EffectiveInput` and `DesignChecks` contain the input reader and independent checks extracted from the study runner. `Replication` exposes these components through file arguments.

| Frozen source | SHA-256 |
| --- | --- |
| CorrectedCuttingPlaneSolver.java | `402077eed3bee0a05e757e8298162336aa560989e49fa5488b5dcd19dcd91ffb` |
| EnhancedBranchAndPriceSolverV4.java | `d87fab1996363022642f3e0af1d6c552d1d0c11c0b6df98894351c228d936d73` |
| BpaExactLabelPricing.java | `a8dd72292481a418f53e653384142aa6aeb69fa298c91713ca0976dbe464018b` |
| BpaExactPathPricing.java | `b3c3b44bb21b34847a79dbb2966ee872e28feded393f79a74e76d81832f0b7de` |
| UnifiedRiskEvaluator.java | `5fa7a315224446e431c48bb20659268938fdd809fa59debccec7ec93f9b6c912` |
| UnifiedDesignEvaluator.java | `a061b3d2e70dc475b8bebf71a0e65fe657a9776fd3fba46c8b2aabaaeea5c7c9` |

Both builds were tested in empty external directories on Windows. Gradle 7.6.4 also ran the random CPA example offline. The following direct-build solves reproduced their published objectives within the study tolerance of `1e-4`. The independent checks covered route feasibility, reservation use, infrastructure budget, carrier optimality and the objective reconstructed from facility costs, ordinary-traffic impact and conservative risk.

| Instance | Algorithm | Recomputed objective | Lower bound | Carrier tasks verified | Termination |
| --- | --- | ---: | ---: | ---: | --- |
| R01_seed123 | CPA | 16763.455383033 | 16763.455383033 | 4 / 4 | OPTIMAL |
| R01_seed123 | BPA | 16763.455383033 | 16763.455382983 | 4 / 4 | OPTIMAL_WITHIN_TOLERANCE |
| EM_SUB_s12_r1_m6 | CPA | 142205.811432494 | 142205.811432494 | 12 / 12 | OPTIMAL |
| EM_SUB_s12_r1_m6 | BPA | 142205.811432494 | 142205.811432364 | 12 / 12 | OPTIMAL_WITHIN_TOLERANCE |

Solver elapsed times depend on hardware and runtime conditions. The archived study records provide the timings used in the paper.

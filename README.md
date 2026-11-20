# Hazardous-materials lane reservation: replication data and algorithms

This repository contains the study inputs, the observed results for eight computational experiments and the source code for the cutting-plane algorithm (CPA) and branch-and-price algorithm (BPA). Start in the repository root for all commands below.

## Find a result

Open the [paper index](experiment_data/paper_results.csv) to trace a number in the manuscript to its cases, reference files and reproduction command. The [instance manifest](experiment_data/instances/inputs_manifest.csv) connects a case ID to the exact file given to the solver. Each archived input has a SHA-256 digest. The [result files](experiment_data/results/) contain the observations themselves. Output figures and typeset tables can be generated on demand.

| Module | Experiment | What to open |
| --- | --- | --- |
| 1 | CPA and BPA on EM | [108 runs on 54 cases](experiment_data/results/module1/runs.json), with bounds, times and design decisions |
| 2 | Centralized and bilevel planning | [31 paired cases](experiment_data/results/module2/per_case.json) and [task-level carrier alternatives](experiment_data/results/module2/carrier_tasks.csv) |
| 3 | Minimum-cost path ties | [Designs, routes and path counts](experiment_data/results/module3/designs.json) for 54 EM, 210 random and 88 sensitivity designs |
| 4 | Joint and sequential planning | [54 paired designs](experiment_data/results/module4/per_case.json) and [1,446 task comparisons](experiment_data/results/module4/task_comparison.json) |
| 5 | Random network performance | [230 case outcomes](experiment_data/results/module5/per_case.json), [460 algorithm runs](experiment_data/results/module5/runs.json) and [40 algorithm-level memory outcomes](experiment_data/results/module5/memory_outcomes.json) |
| 6 | CPA sensitivity analysis | [96 distinct configurations](experiment_data/results/module6/per_case.json) and [108 points on 15 curves](experiment_data/results/module6/curves.json) |
| 7 | Fixed-route worst-case risk | [Nine evaluations](experiment_data/results/module7/per_case.json), [three fixed designs](experiment_data/results/module7/baseline_bindings.json) and [risk certificates](experiment_data/results/module7/) |
| 8 | Road reservation frequencies | [Directed decisions in 46 designs](experiment_data/results/module8/per_case_roads.json) and [counts for all 129 physical roads](experiment_data/results/module8/road_frequency.csv) |

For example, the Table 3 percentage averages the 12 EM cases with a cheaper carrier alternative. The count of 24 affected assignments uses all 300 EM tasks. For the random runtime comparison, both algorithms finished 210 cases. Both ran out of Java heap memory on the other 20 cases. Their elapsed time to failure is recorded separately from time to an optimal solution. Figure 7 counts a physical road once per design when either direction is reserved. The three sensitivity baselines each appear on five curves and count once each in the 96-case inventory.

## Check the numbers with Python

Python 3.11 can check the published inputs and reference results, then produce the four numerical tables and the statistics quoted in the text. These commands use the standard library.

```sh
python experiment_data/analysis/verify_archive.py
python experiment_data/analysis/build_tables.py --output work/tables
python experiment_data/analysis/verify_archive.py --tables work/tables
```

To evaluate the designs directly, install the analysis dependencies. Each command below reads the published input and decisions. The carrier check compares the assigned and minimum feasible costs. The ties check counts equal-cost paths. The sequential check reconstructs the benchmark, and the risk check evaluates both uncertainty models on the same routes.

```sh
python -m pip install -r experiment_data/analysis/requirements.txt
python experiment_data/analysis/evaluate_designs.py carrier
python experiment_data/analysis/evaluate_designs.py ties
python experiment_data/analysis/evaluate_designs.py sequential
python experiment_data/analysis/evaluate_designs.py risk
```

To draw the five empirical figures with the paper's settings, choose an output directory. Figures 1 and 2 illustrate the algorithm and the study network. The empirical series starts with Figure 3.

```sh
python experiment_data/analysis/build_figures.py --output work/figures
```

## Run the algorithms on an existing instance

Install a licensed copy of IBM ILOG CPLEX Optimization Studio, a JDK and the Kotlin compiler. Set `CPLEX_STUDIO_DIR`, `JAVA_HOME` and `KOTLIN_HOME` to their installation directories. Use an empty build directory and a separate, new output directory for each solve. These commands build the public sources and solve one random case and one EM case with both algorithms.

```sh
python experiment_code/solver.py build
python experiment_code/solver.py solve CPA experiment_data/instances/inputs/R01_seed123.bin.gz work/random_cpa 600
python experiment_code/solver.py solve BPA experiment_data/instances/inputs/R01_seed123.bin.gz work/random_bpa 600
python experiment_code/solver.py solve CPA experiment_data/instances/inputs/EM_SUB_s12_r1_m6.bin.gz work/em_cpa 1800
python experiment_code/solver.py solve BPA experiment_data/instances/inputs/EM_SUB_s12_r1_m6.bin.gz work/em_bpa 1800
```

The expected objectives are 16763.455383 for the random case and 142205.811432 for the EM case. Check the solver status, the certified lower bound, the incumbent objective and the independent design verification. Runtime depends on the machine. The paper reports an Intel Core i5-9300H at 2.40 GHz and 16 GB of RAM. The formal random runs used a 1,024 MiB Java heap and a 600-second limit. The EM time limit was 1,800 seconds. The study used JDK 15.0.1, CPLEX 22.1.0 and the Kotlin 2.1.10 standard library. The release was compiled with Kotlin compiler 2.1.21. See the [solver guide](experiment_code/README.md) for the build options, output fields and policy benchmarks.

## Generate the study inputs

There are 46 six-facility EM samples, 8 twenty-facility EM expansions, 230 random cases and 96 sensitivity configurations. The [instance guide](experiment_data/instances/README.md) describes the binary format, units where available, node indexing and two [complete examples in CSV](experiment_data/instances/readable_examples/).

1. For random instances, the generator takes the published network settings and seeds. For EM, start from the four preserved MATLAB base cases. They preserve the realized risks, costs and demands. Some historical scripts and seeds that produced these initial parameters are unavailable.
2. To reconstruct the EM traffic impacts, supply the ordinary-traffic demand file from the pinned public network repository, or let the script retrieve it. The baseline equilibrium and 258 single-road evaluations use the modified study network. Each evaluation removes half of one directed road's ordinary-traffic capacity.
3. Supply the resulting impact matrix to the instance builder. It checks all 380 generated inputs against the published cases and writes a comparison for each ID. Add the JDK bin directory to PATH for this step.

```sh
python -m pip install -r experiment_data/generation/requirements.txt
python experiment_data/generation/traffic_impacts.py solve --workers 4 --output work/traffic
python experiment_data/generation/rebuild_instances.py --impact work/traffic/impact.csv --output work/rebuilt
```

For an offline input rebuild, use the distributed impact matrix by omitting `--impact`. For a faster audit of the traffic calculation, use `traffic_impacts.py audit` with the pinned demand file and the published equilibrium flows. Exact commands, numerical tolerances and the per-instance verification records are in the [generation guide](experiment_data/generation/README.md). The [source register](experiment_data/sources/README.md) identifies the saved study data, upstream attribution and access terms. CPLEX is supplied by the reader under their IBM license.

The [release verification record](experiment_data/results/release_verification.json) gives the four fresh solver timings, bounds, verification outcomes and the checks against the manuscript.

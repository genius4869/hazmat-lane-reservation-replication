# Reference solutions and fixed-design evaluations

Match `case_id` to `../instances/inputs_manifest.csv`. Its `input_sha256` identifies the compressed solver input. Node, arc and task indices start at zero in the result files. Facility lists contain node IDs. An arc `[u,v]` points from `u` to `v`. A route is the sequence of visited node IDs in task order. A one-node route serves a task at its origin. CSV records from the carrier audit encode lists with semicolons and arcs as `u-v`.

An upper bound is the objective of an independently checked feasible design. A lower bound is a certified solver bound. Small numerical differences can occur within the study's absolute optimality tolerance of `1e-4`. Elapsed solver seconds include model construction and optimization. Independent post-solve checks are timed separately. Empty solver values for out-of-memory outcomes do not contribute to objective or time comparisons.

| Module | Records | Contents |
| --- | --- | --- |
| 1. EM performance | `module1/runs.json` | One row per algorithm and input, with 108 rows from the 54 paired CPA/BPA solves. Each includes bounds, measured time, objective components and the selected infrastructure and routes. `cuts` counts CPA carrier-optimality cuts. BPA's `pathCount` includes initial columns. |
| 2. Centralized and bilevel planning | `module2/per_case.json`, `module2/carrier_tasks.csv` | SL and BL objective components, bounds and infrastructure for 16 EM and 15 random cases. Each task has its selected route, cost and independently minimized cost on the reserved network. The task file contains both models. The conditional objective gaps in Table 3 use only cases with at least one cheaper SL carrier response. |
| 3. Carrier ties | `module3/designs.json` | Fixed design, selected routes, exact enumeration counts and minimum costs for each task. Coverage includes 54 EM designs, 210 completed random designs and 88 feasible sensitivity configurations. Each case has one canonical design, including 31 cases from the initial screen and 321 from its extension. The counts concern exact equality of costs calculated from the stored binary64 coefficients. |
| 4. Joint versus sequential planning | `module4/per_case.json`, `module4/task_comparison.json` | Side-by-side designs, bounds, costs and robust risks for all 54 EM instances, together with task routes and carrier checks. In the sequential benchmark each task minimizes cost on the full physical network with all candidate destinations and its original deadline. Open the destinations used by those routes and reserve the union of the traversed directed arcs. |
| 5. Random performance | `module5/runs.json`, `module5/per_case.json`, `module5/memory_outcomes.json` | All 460 algorithm outcomes for 230 seeded inputs. The 420 successful solves include design decisions and objective components. The pair file adds dimensions and comparisons. The memory file retains all 40 algorithm outcomes on the 20 instances in R21 and R23. The 210 paired optima enter Table 1. |
| 6. Sensitivity | `module6/per_case.json`, `module6/curves.json` | All 96 CPA configurations, including 88 optima with infrastructure, routes and objective components and eight certified infeasible cases. The curve records map each plotted level to its instance. Reusing each of the three baselines in five curves yields 108 points. `D0` measures traffic impact at the baseline coefficients. Baseline risk columns evaluate the plan under the common baseline uncertainty set. |
| 7. Worst-case risk | `module7/baseline_bindings.json`, `module7/per_case.json`, `module7/original_risk_certificates.json`, `module7/conservative_risk_certificates.json`, `module7/theoretical_factors.json` | Three fixed Module 6 CPA designs, each evaluated at three budget pairs. Original-risk certificates give the exact rational optimum, with the supporting demand vertices for the smaller budgets. Conservative-risk certificates give either the analytic value or primal and dual bounds. The theoretical factor covers every task and physical arc in the input. |
| 8. Road frequencies | `module8/per_case_roads.json`, `module8/topology.json`, `module8/road_frequency.csv` | The 46 six-facility BPA solutions, 129 physical roads and their per-road counts. Each design contributes at most once to a road regardless of the selected direction. The display labels in Figure 7 add one to the stored endpoints. |

## Reconstruct the paper statistics

Run the following commands from the repository root. Choose a writable output location.

```shell
python experiment_data/analysis/build_tables.py --output reproduced/tables
```

This writes `table1_random.csv`, `table2_em.csv`, `table3_carrier_response.csv` and `table4_fixed_route_risk.csv` at the paper's display precision. `runtime_summary.csv` contains arithmetic means and the shifted geometric means with a one-second shift. `prose_statistics.csv` contains the counts, percentages and component-level changes used in the text. For Table 1, the sparse density is mean out-degree and the dense density is the generator probability. Empty gap cells in Table 3 denote an empty conditional sample.

## Re-evaluate the reference decisions from solver inputs

The following commands use the Python dependencies in `../analysis/requirements.txt`. Each command compares its recomputed values against the per-instance or per-task evidence and fails on a discrepancy. Add `--case ID` to check a single case, or `--output path.csv` to save the results.

```shell
python experiment_data/analysis/evaluate_designs.py carrier
python experiment_data/analysis/evaluate_designs.py ties
python experiment_data/analysis/evaluate_designs.py sequential
python experiment_data/analysis/evaluate_designs.py risk
```

* `carrier` checks every task on each of the 31 SL and BL designs. Under SL, the planner selects routes to optimize its own objective. Under BL, each route must also minimize carrier cost. The check holds the design fixed and searches for a cheaper feasible route to any open destination. To optimize a new SL design, use the `solve SL` command in the solver guide.
* `ties` searches the reserved arcs of each design, enumerating feasible elementary paths whose cost can match or improve the selected path. Reverse shortest-path distances bound the remaining cost. The minimum and number of minimizers are calculated with exact rational arithmetic on the binary64 arc costs and travel times. All 5,164 task checks found one minimum-cost route. The risk under worst-case tie-breaking therefore equals the selected-route risk on these designs.
* `sequential` checks the benchmark routes against all physical arcs and candidate facilities. It reconstructs the infrastructure, checks the construction budget and recalculates the robust government objective for each case. The solver guide also provides a command for generating a new sequential solution.
* `risk` rebuilds the original risk, conservative risk and uniform theoretical factor from the three published inputs and fixed baseline routes. At the baseline budgets all traversed roads can deviate fully. For the two smaller budget pairs, the procedure enumerates demand vertices and optimizes the shared road budget at each vertex. For the conservative LP, SciPy/HiGHS proposes primal and dual solutions; rational bounds certify the value to an absolute tolerance of `1e-5`. The reported overestimation divides the difference between the two risk functions by the original risk.

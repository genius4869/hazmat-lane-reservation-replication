"""Recompute manuscript tables and numerical statements from per-case records."""
import argparse
import csv
import json
import math
from collections import Counter, defaultdict
from fractions import Fraction
from pathlib import Path
from statistics import mean

DATA = Path(__file__).resolve().parents[1]


def records(filename):
    return json.loads((DATA / "results" / filename).read_text(encoding="utf-8"))


def write_csv(path, rows):
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def check(condition, message):
    if not condition:
        raise ValueError(message)


def grouped(rows, key):
    groups = defaultdict(list)
    for row in rows:
        groups[row[key]].append(row)
    return groups


def average(rows, key):
    return mean(row[key] for row in rows)


class Tables:
    def __init__(self, destination):
        self.destination = destination
        self.stats = []
        self.runtime = []

    def statistic(self, module, cohort, **metrics):
        for metric, value in metrics.items():
            self.stats.append(dict(module=module, cohort=cohort, metric=metric, value=value))

    def summarize_runtime(self, module, cohort, rows):
        for algorithm in ["CPA", "BPA"]:
            times = [r[algorithm + "_seconds"] for r in rows]
            self.runtime.append(
                dict(
                    module=module,
                    cohort=cohort,
                    algorithm=algorithm,
                    instances=len(rows),
                    mean_seconds=f"{mean(times):.2f}",
                    shifted_geometric_mean_seconds=f"{math.expm1(mean(map(math.log1p,times))):.2f}",
                )
            )
        self.statistic(
            module,
            cohort,
            faster_CPA=sum(r["CPA_seconds"] < r["BPA_seconds"] for r in rows),
            faster_BPA=sum(r["BPA_seconds"] < r["CPA_seconds"] for r in rows),
        )

    def random_performance(self):
        rows = records("module5/per_case.json")
        complete = [r for r in rows if r["both_certified"]]
        missing = [r for r in rows if not r["both_certified"]]
        failures = records("module5/memory_outcomes.json")
        check(len(rows) == len({r["case_id"] for r in rows}) == 230, "Random inventory")
        check(
            all(r["CPA_status"] == r["BPA_status"] == "RECORDED_OOM" for r in missing),
            "Unexpected missing result",
        )
        expected = {(r["case_id"], a) for r in missing for a in ("CPA", "BPA")}
        check(
            {(r["case_id"], r["algorithm"]) for r in failures} == expected,
            "Memory outcome record mismatch",
        )
        outputs = []
        for name, group in sorted(grouped(complete, "group").items()):
            number = int(name[1:])
            template = group[0]
            check(
                len(group) == 10 and all(abs(r["BPA_UB"] - r["CPA_UB"]) < 1e-4 for r in group), name
            )
            if number <= 7:
                density = average(group, "arcs") / template["nodes"]
            else:
                density = (
                    0.1
                    if number <= 12
                    else 0.2
                    if number <= 16
                    else 0.3
                    if number <= 19
                    else 0.4
                    if number <= 21
                    else 0.5
                )
            outputs.append(
                dict(
                    group=name,
                    family="sparse" if number <= 7 else "dense",
                    nodes=template["nodes"],
                    tasks=template["tasks"],
                    facilities=template["facilities"],
                    density=f"{density:.2f}",
                    objective=f"{average(group,'CPA_UB'):.0f}",
                    both_optimal=f"{len(group)}/10",
                    CPA_seconds=f"{average(group,'CPA_seconds'):.2f}",
                    BPA_seconds=f"{average(group,'BPA_seconds'):.2f}",
                    root_LB=f"{average(group,'rootLowerBound'):.0f}",
                    root_columns=f"{average(group,'rootColumns'):.1f}",
                    nodes_processed=f"{average(group,'nodesProcessed'):.1f}",
                )
            )
        self.summarize_runtime(5, "paired_random", complete)
        self.statistic(
            5,
            "all_random",
            instances=len(rows),
            paired_optima=len(complete),
            OOM_instances=len(missing),
            BPA_root_optima=sum(
                r["nodesProcessed"] == 1 and r["rootVerified"] and r["rootIntegral"]
                for r in complete
            ),
            maximum_nodes=max(r["nodesProcessed"] for r in complete),
        )
        write_csv(self.destination / "table1_random.csv", outputs)

    def em_performance(self):
        cases = grouped(records("module1/runs.json"), "case_id")
        merged = []
        for case, rows in cases.items():
            pair = {r["algorithm"]: r for r in rows}
            check(
                len(rows) == 2
                and set(pair) == {"CPA", "BPA"}
                and all(r["status"] == "VERIFIED_OPTIMAL" for r in rows),
                case,
            )
            a, b = pair["CPA"], pair["BPA"]
            check(abs(a["verified_UB"] - b["verified_UB"]) < 1e-4, case + " objective mismatch")
            merged.append(
                dict(
                    case_id=case,
                    tasks=a["tasks"],
                    facilities=a["facilities"],
                    CPA_objective=a["verified_UB"],
                    BPA_objective=b["verified_UB"],
                    CPA_seconds=a["seconds"],
                    BPA_seconds=b["seconds"],
                    CPA_cuts=a["cuts"],
                    BPA_columns=b["pathCount"],
                )
            )
        outputs = []
        for facilities, cohort in sorted(grouped(merged, "facilities").items()):
            self.summarize_runtime(1, f"EM_{facilities}_facilities", cohort)
            for tasks, group in sorted(grouped(cohort, "tasks").items()):
                row = dict(
                    case_group=f"EM-{tasks}-{facilities}",
                    nodes=74,
                    tasks=tasks,
                    facilities=facilities,
                    instances=len(group),
                )
                for key, digits in dict(
                    CPA_objective=0,
                    CPA_seconds=2,
                    CPA_cuts=1,
                    BPA_objective=0,
                    BPA_seconds=2,
                    BPA_columns=1,
                ).items():
                    row[key] = f"{average(group,key):.{digits}f}"
                outputs.append(row)
        write_csv(self.destination / "table2_em.csv", outputs)

    def carrier_response(self):
        cases = records("module2/per_case.json")
        with (DATA / "results/module2/carrier_tasks.csv").open(encoding="utf-8", newline="") as f:
            tasks = grouped([r for r in csv.DictReader(f) if r["model"] == "SL"], "case_id")
        output = []
        for cohort, rows in [
            ("EM", [r for r in cases if r["family"] != "random"]),
            ("Random", [r for r in cases if r["family"] == "random"]),
            ("Overall", cases),
        ]:
            gaps = []
            percent = []
            deviations = total = 0
            for r in rows:
                checks = tasks[r["case_id"]]
                count = sum(c["exact_deviation"].lower() == "true" for c in checks)
                check(len(checks) == r["tasks"] and count == r["SL_deviating_tasks"], r["case_id"])
                deviations += count
                total += len(checks)
                if count:
                    gap = r["BL_UB"] - r["SL_UB"]
                    gaps.append(gap)
                    percent.append(100 * gap / r["SL_UB"])
            output.append(
                dict(
                    cohort=cohort,
                    instances=len(rows),
                    affected_instances=len(gaps),
                    mean_objective_gap=f"{mean(gaps):.2f}" if gaps else "",
                    mean_percent_gap=f"{mean(percent):.3f}" if gaps else "",
                    max_percent_gap=f"{max(percent):.3f}" if gaps else "",
                    cheaper_tasks=f"{deviations}/{total}",
                )
            )
        write_csv(self.destination / "table3_carrier_response.csv", output)
        screens = records("module3/designs.json")
        for family, rows in sorted(grouped(screens, "cohort").items()):
            check(
                all(
                    r["enumeration_complete"] and len(r["routes"]) == len(r["optimal_path_counts"])
                    for r in rows
                ),
                family,
            )
            self.statistic(
                3,
                family,
                designs=len(rows),
                tasks=sum(len(r["routes"]) for r in rows),
                tied_tasks=sum(n > 1 for r in rows for n in r["optimal_path_counts"]),
                tied_designs=sum(max(r["optimal_path_counts"]) > 1 for r in rows),
            )

    def risk_and_planning(self):
        rows = records("module7/per_case.json")
        original = records("module7/original_risk_certificates.json")
        conservative = records("module7/conservative_risk_certificates.json")
        factors = records("module7/theoretical_factors.json")
        output = []
        percentage_changes = []
        agreements = Counter()
        for r in rows:
            key = r["case_id"] + ":" + r["operation"]
            risk = Fraction(original[key]["exact_value"])
            c = conservative[key]
            lower, upper = Fraction(c["exact_lower_bound"]), Fraction(c["exact_upper_bound"])
            check(0 <= upper - lower < Fraction(1, 100000), key)
            percent = float(100 * (lower / risk - 1))
            check(f"{percent:.3f}" == f"{float(100*(upper/risk-1)):.3f}", key)
            factor_bound = Fraction(factors[r["case_id"]]["exact_factor_lower"])
            check(upper <= factor_bound * risk, key + " exceeds the theoretical bound")
            percentage_changes.append(float(100 * (upper / risk - 1)))
            if abs(float(upper - risk)) <= r["numerical_comparison_tolerance"]:
                agreements[r["operation"] == "original_base"] += 1
            output.append(
                dict(
                    tasks=r["tasks"],
                    gamma_u=f"{r['gamma_u']:g}",
                    gamma_v=f"{r['gamma_v']:g}",
                    original_risk=f"{float(risk):.2f}",
                    conservative_risk=f"{float(lower):.2f}",
                    overestimation_percent=f"{percent:.3f}",
                    theoretical_factor=f"{factors[r['case_id']]['factor_upper']:.4f}",
                )
            )
        write_csv(self.destination / "table4_fixed_route_risk.csv", output)
        self.statistic(
            7,
            "fixed_designs",
            designs=len(factors),
            evaluations=len(rows),
            agreements_at_baseline=agreements[True],
            agreements_at_smaller_budgets=agreements[False],
            maximum_overestimation_percent=max(percentage_changes),
            certified_factor_comparisons=len(rows),
        )
        pairs = records("module4/per_case.json")
        diffs = [r["sequential_objective"] - r["joint_objective"] for r in pairs]
        percentages = [100 * d / r["sequential_objective"] for d, r in zip(diffs, pairs)]
        self.statistic(
            4,
            "all_EM",
            instances=len(pairs),
            lower_joint_objective=sum(d > 0 for d in diffs),
            mean_objective_saving=mean(diffs),
            mean_reduction_percent=mean(percentages),
            minimum_reduction_percent=min(percentages),
            maximum_reduction_percent=max(percentages),
            mean_facility_saving=mean(
                r["sequential_facility_cost"] - r["joint_facility_cost"] for r in pairs
            ),
            mean_traffic_saving=mean(
                r["sequential_traffic_impact"] - r["joint_traffic_impact"] for r in pairs
            ),
            mean_risk_increase=mean(r["joint_risk"] - r["sequential_risk"] for r in pairs),
            risk_increases=sum(r["joint_risk"] > r["sequential_risk"] for r in pairs),
            risk_decreases=sum(r["joint_risk"] < r["sequential_risk"] for r in pairs),
        )

    def sensitivity_and_roads(self):
        cases = {r["case_id"]: r for r in records("module6/per_case.json")}
        curves = records("module6/curves.json")
        self.statistic(
            6,
            "all_sensitivity",
            configurations=len(cases),
            optimal=sum(r["verified_optimal"] for r in cases.values()),
            infeasible=sum(bool(r["certified_infeasible"]) for r in cases.values()),
            curve_points=len(curves),
        )
        for tasks, rows in grouped(curves, "tasks").items():
            by = grouped(rows, "parameter")
            for row in rows:
                case = cases[row["case_id"]]
                check(case.get("verified_UB") == row.get("verified_UB"), row["case_id"])
            budgets = sorted(
                (r for r in by["budget"] if r.get("verified_UB") is not None),
                key=lambda r: r["value"],
            )
            flat = next(
                i
                for i in range(len(budgets))
                if all(
                    r["decision_signature"] == budgets[i]["decision_signature"]
                    and abs(r["verified_UB"] - budgets[i]["verified_UB"]) < 1e-4
                    for r in budgets[i:]
                )
            )
            plateau = [r for r in by["gamma_v"] if r["value"] >= 40]
            check(
                len(set(r["decision_signature"] for r in plateau)) == 1
                and max(r["verified_UB"] for r in plateau) - min(r["verified_UB"] for r in plateau)
                < 1e-4,
                "Road uncertainty plateau",
            )
            for parameter in ("gamma_u", "gamma_v", "q"):
                ordered = sorted(by[parameter], key=lambda r: r["value"])
                check(
                    all(
                        left["verified_UB"] <= right["verified_UB"] + 1e-4
                        for left, right in zip(ordered, ordered[1:])
                    ),
                    f"Nonmonotone objective for {parameter}",
                )
            self.statistic(
                6,
                f"EM_{tasks}",
                first_feasible_budget=budgets[0]["value"],
                plateau_budget=budgets[flat]["value"],
                baseline_traffic=by["impact"][0]["D0"],
                distinct_impact_plans=len({r["decision_signature"] for r in by["impact"]}),
                distinct_Q_plans=len({r["decision_signature"] for r in by["q"]}),
                risk_bearing_arcs_at_plateau=plateau[0]["effective_deviation_arc_count"],
            )
            if tasks == 24:
                for label, param, lo, hi in [
                    ("demand", "gamma_u", 0.5, 0.75),
                    ("construction", "budget", 750, 1000),
                ]:
                    left, right = [next(r for r in by[param] if r["value"] == v) for v in (lo, hi)]
                    self.statistic(
                        6,
                        label,
                        facility_cost_before=left["facility_cost"],
                        facility_cost_after=right["facility_cost"],
                        opened_before=left["open_facility_count"],
                        opened_after=right["open_facility_count"],
                        routes_changed=sum(p != q for p, q in zip(left["routes"], right["routes"])),
                        objective_reduction_percent=100
                        * (1 - right["verified_UB"] / left["verified_UB"]),
                        baseline_risk_change_percent=100
                        * (
                            right["baseline_risk_lower_bound"] / left["baseline_risk_lower_bound"]
                            - 1
                        ),
                    )
        sols = records("module8/per_case_roads.json")
        counts = Counter()
        for row in sols:
            roads = {tuple(sorted(a)) for a in row["selected_directed_arcs"]}
            check(roads == set(map(tuple, row["selected_undirected_roads"])), row["case_id"])
            counts.update(roads)
        physical = records("module8/topology.json")["undirected_roads"]
        check(set(counts) <= set(map(tuple, physical)), "Unknown physical road")
        self.statistic(
            8,
            "six_facilities",
            plans=len(sols),
            physical_roads=len(physical),
            ever_selected=len(counts),
            never_selected=len(physical) - len(counts),
            at_least_80_percent=sum(c >= 0.8 * len(sols) for c in counts.values()),
            always_selected=sum(c == len(sols) for c in counts.values()),
            min_roads_per_plan=min(r["selected_road_count"] for r in sols),
            max_roads_per_plan=max(r["selected_road_count"] for r in sols),
        )

    def run(self):
        self.destination.mkdir(parents=True, exist_ok=True)
        self.random_performance()
        self.em_performance()
        self.carrier_response()
        self.risk_and_planning()
        self.sensitivity_and_roads()
        write_csv(self.destination / "runtime_summary.csv", self.runtime)
        write_csv(self.destination / "prose_statistics.csv", self.stats)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output",
        type=Path,
        required=True,
        help="Directory for reconstructed tables and statistics",
    )
    Tables(parser.parse_args().output).run()

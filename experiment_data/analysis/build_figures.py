"""Rebuild Figures 3-7 from published case records."""
import argparse
from collections import Counter, defaultdict
import csv
import json
import math
from pathlib import Path
from statistics import mean

import matplotlib
matplotlib.use("Agg")
import networkx as nx

from performance_figures import draw_heatmap, draw_joint_planning, draw_runtime
from sensitivity_figures import setup_style, traffic_budget_figure, uncertainty_figure


DATA = Path(__file__).resolve().parents[1]
RESULTS = DATA / "results"


def load(path):
    return json.loads((RESULTS / path).read_text(encoding="utf-8-sig"))


def check(condition, message):
    if not condition:
        raise ValueError(message)


def runtime(directory):
    cases = load("module5/per_case.json")
    complete = [case for case in cases if case["both_certified"]]
    check(len({case["case_id"] for case in cases}) == len(cases) == 230, "Random case count")
    check(Counter(case["group"] for case in cases if not case["both_certified"])
          == {"R21": 10, "R23": 10}, "Random performance exclusions")
    groups = defaultdict(list)
    for case in complete:
        groups[case["group"]].append(case)
    check(len(groups) == 21 and all(len(rows) == 10 for rows in groups.values()), "Random group sizes")
    points = [{"group": group, **{alg + "_mean_seconds": mean(row[alg + "_seconds"] for row in rows)
                                   for alg in ("CPA", "BPA")}} for group, rows in sorted(groups.items())]
    draw_runtime(points, directory)


def sensitivity(directory):
    curves = defaultdict(list)
    rows = load("module6/curves.json")
    cases = {}
    for row in rows:
        curves[row["tasks"], row["parameter"]].append(row)
        state = (row["status"], row.get("verified_UB"), row.get("D0"))
        check(cases.setdefault(row["case_id"], state) == state, "Inconsistent repeated baseline")
    check(len(rows) == 108 and len(curves) == 15 and len(cases) == 96, "Sensitivity scope")
    check(Counter(state[0] for state in cases.values()) ==
          {"CERTIFIED_OPTIMAL": 88, "CERTIFIED_INFEASIBLE": 8}, "Sensitivity statuses")
    for points in curves.values():
        points.sort(key=lambda row: row["value"])
    setup_style()
    uncertainty_figure(curves, directory)
    traffic_budget_figure(curves, directory)


def joint_planning(directory):
    groups = defaultdict(list)
    for row in load("module4/per_case.json"):
        if row["candidate_facilities"] == 6:
            check(row["same_budget_comparable"] and row["evaluator_optimal"], row["case_id"])
            groups[row["tasks"]].append(row)
    check(sum(map(len, groups.values())) == 46 and sorted(groups) == list(range(12, 31, 2)), "EM sample")
    points = [{"tasks": tasks,
               "joint_objective_mean": mean(r["joint_objective"] for r in rows),
               "sequential_objective_mean": mean(r["sequential_objective"] for r in rows),
               "mean_paired_objective_ratio_percent": mean(100 * r["joint_objective"] / r["sequential_objective"]
                                                          for r in rows)}
              for tasks, rows in sorted(groups.items())]
    draw_joint_planning(points, directory)


def road_frequency(directory):
    topology = load("module8/topology.json")
    arcs = set(map(tuple, topology["directed_arcs"]))
    roads = {tuple(sorted(arc)) for arc in arcs}
    nodes = sorted(topology["nodes"])
    check(len(arcs) == 258 and len(roads) == 129 and nodes == list(range(74)), "EM topology")
    cases = load("module8/per_case_roads.json")
    check(len({r["case_id"] for r in cases}) == len(cases) == 46, "Heatmap denominator")
    counts = Counter()
    for case in cases:
        selected = set(map(tuple, case["selected_directed_arcs"]))
        check(selected <= arcs, case["case_id"])
        counts.update({tuple(sorted(arc)) for arc in selected})
    frequency = {road: 100 * counts[road] / len(cases) for road in sorted(roads)}
    with (RESULTS / "module8/road_frequency.csv").open(encoding="utf-8-sig", newline="") as stream:
        reference = list(csv.DictReader(stream))
    check({(int(r["u"]), int(r["v"])) for r in reference} == roads, "Reference road set")
    for row in reference:
        road = int(row["u"]), int(row["v"])
        check(int(row["count"]) == counts[road] and int(row["denominator"]) == len(cases) and
              math.isclose(float(row["frequency_percent"]), frequency[road], abs_tol=1e-8),
              f"Frequency mismatch at {road}")
    graph = nx.Graph()
    graph.add_nodes_from(nodes)
    graph.add_edges_from(sorted(roads))
    draw_heatmap(graph, frequency, directory)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    for render in (runtime, sensitivity, joint_planning, road_frequency):
        with matplotlib.rc_context():
            render(args.output)
    print("Rebuilt Figures 3-7 in PNG and PDF format.")


if __name__ == "__main__":
    main()

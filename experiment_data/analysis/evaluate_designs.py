"""Re-evaluate carrier routes, exact ties, sequential plans and fixed-route risks."""
import argparse
import csv
import gzip
from collections import defaultdict
from fractions import Fraction
from hashlib import sha256
from heapq import heapify, heappop, heappush
from io import BytesIO
import json
from pathlib import Path
from struct import Struct

from fixed_route_risk import evaluate, original_exact, theoretical_factor

DATA = Path(__file__).resolve().parents[1]


def check(condition, message):
    if not condition:
        raise ValueError(message)


def records(name):
    return json.loads((DATA / "results" / name).read_text(encoding="utf-8"))


def read_instance(case, digest, arcs=None):
    """Load exact binary64 coefficients; skip unreserved arcs in large random inputs."""
    compressed = (DATA / "instances/inputs" / (case + ".bin.gz")).read_bytes()
    check(sha256(compressed).hexdigest() == digest, f"Input digest differs for {case}")
    reader = BytesIO(gzip.decompress(compressed))

    def get(fmt):
        spec = Struct(">" + fmt)
        return spec.unpack(reader.read(spec.size))

    check(reader.read(get("H")[0]) == b"HAZMAT_EFFECTIVE_INPUT_V1", case)
    nodes, task_count, facility_count, arc_count = get("4i")
    budget, gamma_u, gamma_v, nominal = get("3d?")
    facilities = [
        (node, Fraction(cost)) for node, cost in (get("id") for _ in range(facility_count))
    ]
    tasks = [
        (node, Fraction(n), Fraction(k), Fraction(w))
        for node, n, k, w in (get("i3d") for _ in range(task_count))
    ]
    (specific,) = get("?")
    pairs = task_count if specific else 1
    edges = {}
    for _ in range(arc_count):
        arc = get("2i")
        if arcs is not None and arc not in arcs:
            reader.seek(32 + 16 * pairs, 1)
            continue
        attributes = tuple(map(Fraction, get("4d")))
        risks = [tuple(map(Fraction, get("2d"))) for _ in range(pairs)]
        edges[arc] = (*attributes, risks)
    check(not reader.read(1) and (arcs is None or arcs == set(edges)), case + " arc set")
    return dict(
        n=nodes,
        s=task_count,
        m=facility_count,
        budget=Fraction(budget),
        gu=Fraction(gamma_u),
        gv=Fraction(gamma_v),
        nominal=nominal,
        fac=facilities,
        tasks=tasks,
        specific=specific,
        edges=edges,
    )


def shortest_cost_bounds(edges, opened):
    """Unconstrained reverse distances are valid lower bounds under a time limit."""
    reverse = defaultdict(list)
    for (i, j), (cost, *_) in edges.items():
        reverse[j].append((i, cost))
    dist = {v: Fraction(0) for v in opened}
    queue = [(d, v) for v, d in dist.items()]
    heapify(queue)
    while queue:
        cost, v = heappop(queue)
        if cost != dist[v]:
            continue
        for u, arc_cost in reverse[v]:
            value = cost + arc_cost
            if u not in dist or value < dist[u]:
                dist[u] = value
                heappush(queue, (value, u))
    return dist


def check_paths(data, opened, routes):
    """Enumerate simple feasible paths up to the stored route cost, including exact ties."""
    edges = data["edges"]
    dist = shortest_cost_bounds(edges, opened)
    adjacency = defaultdict(list)
    for (u, v), (cost, time, *_) in edges.items():
        adjacency[u].append((v, cost, time))
    check(len(routes) == len(data["tasks"]), "Route count differs")
    for route, (origin, _, _, deadline) in zip(routes, data["tasks"]):
        check(
            route and route[0] == origin and route[-1] in opened and len(route) == len(set(route)),
            "Invalid selected route",
        )
        attributes = [edges[e] for e in zip(route, route[1:])]
        selected = sum((a[0] for a in attributes), Fraction(0))
        elapsed = sum((a[1] for a in attributes), Fraction(0))
        check(elapsed <= deadline, "Selected route misses its deadline")
        best, count = selected, 0
        pending = [(origin, Fraction(0), Fraction(0), (origin,))]
        while pending:
            u, cost, time, visited = pending.pop()
            if u not in dist or cost + dist[u] > best:
                continue
            if u in opened:
                if cost < best:
                    best, count = cost, 0
                if cost == best:
                    count += 1
            for v, arc_cost, travel in adjacency[u]:
                if (
                    v in visited
                    or time + travel > deadline
                    or v not in dist
                    or cost + arc_cost + dist[v] > best
                ):
                    continue
                pending.append((v, cost + arc_cost, time + travel, visited + (v,)))
        check(count >= 1, "No feasible route matches the upper bound")
        yield selected, best, count


def select(rows, case):
    subset = [r for r in rows if case is None or r["case_id"] == case]
    check(bool(subset), "No record found for this case in the requested module")
    return subset


def tie_screen(case):
    output = []
    for row in select(records("module3/designs.json"), case):
        arcs = set(map(tuple, row["reserved_arcs"]))
        data = read_instance(row["case_id"], row["input_sha256"], arcs)
        counts = []
        for t, (selected, minimum, count) in enumerate(
            check_paths(data, set(row["open_facilities"]), row["routes"])
        ):
            check(
                selected == minimum and float(minimum) == row["minimum_costs"][t],
                f"Minimum cost differs for {row['case_id']} task {t}",
            )
            counts.append(count)
        check(counts == row["optimal_path_counts"], row["case_id"] + " tie counts")
        output.append(
            dict(
                case_id=row["case_id"],
                tasks=len(counts),
                tasks_with_ties=sum(n > 1 for n in counts),
            )
        )
    return output


def carrier_response(case):
    with (DATA / "results/module2/carrier_tasks.csv").open(encoding="utf-8", newline="") as stream:
        tasks = defaultdict(list)
        for row in csv.DictReader(stream):
            tasks[row["case_id"], row["model"]].append(row)
    output = []
    for row in select(records("module2/per_case.json"), case):
        for model in ("SL", "BL"):
            opened = {int(x) for x in str(row[model + "_selected_facilities"]).split(";") if x}
            arcs = {
                tuple(map(int, x.split("-")))
                for x in row[model + "_selected_roads"].split(";")
                if x
            }
            data = read_instance(row["case_id"], row["input_sha256"], arcs)
            task_rows = sorted(tasks[row["case_id"], model], key=lambda t: int(t["task"]))
            routes = [list(map(int, t["route"].split(";"))) for t in task_rows]
            expensive = 0
            for (selected, minimum, _), t in zip(check_paths(data, opened, routes), task_rows):
                check(
                    selected == Fraction(t["exact_selected_cost"])
                    and minimum == Fraction(t["exact_shortest_cost"]),
                    row["case_id"],
                )
                expensive += selected > minimum
            check(expensive == (row["SL_deviating_tasks"] if model == "SL" else 0), row["case_id"])
            output.append(
                dict(
                    case_id=row["case_id"],
                    model=model,
                    tasks=len(routes),
                    cheaper_responses=expensive,
                )
            )
    return output


def sequential(case):
    task_rows = defaultdict(list)
    for task in records("module4/task_comparison.json"):
        task_rows[task["case_id"]].append(task)
    output = []
    for row in select(records("module4/per_case.json"), case):
        cid = row["case_id"]
        data = read_instance(cid, row["input_sha256"])
        routes = [
            t["sequential_route"]
            for t in sorted(task_rows[cid], key=lambda t: t["task_zero_based"])
        ]
        facilities = dict(data["fac"])
        for selected, minimum, _ in check_paths(data, set(facilities), routes):
            check(selected == minimum, cid + " sequential path is suboptimal on the full network")
        opened = {p[-1] for p in routes}
        arcs = {a for p in routes for a in zip(p, p[1:])}
        check(
            opened == set(row["sequential_open_facilities"])
            and arcs == set(map(tuple, row["sequential_reserved_roads"])),
            cid,
        )
        build = sum(data["edges"][a][3] for a in arcs)
        facility = sum(facilities[v] for v in opened)
        traffic = sum(data["edges"][a][2] for a in arcs)
        risk = evaluate(data, routes)
        objective = float(facility + traffic) + risk["lower_bound"]
        check(
            build <= data["budget"]
            and risk["status"] == "CERTIFIED"
            and abs(objective - row["sequential_objective"]) < 1e-4,
            cid,
        )
        output.append(
            dict(
                case_id=cid, tasks=len(routes), budget_feasible=True, sequential_objective=objective
            )
        )
    return output


def fixed_risks(case):
    rows = select(records("module7/per_case.json"), case)
    designs = records("module7/baseline_bindings.json")
    output = []
    for row in rows:
        cid = row["case_id"]
        design = designs[cid]
        data = read_instance(cid, design["input_sha256"])
        factor = theoretical_factor(data)
        data.update(gu=Fraction(row["gamma_u"]), gv=Fraction(row["gamma_v"]))
        original = original_exact(data, design["routes"])
        conservative = evaluate(data, design["routes"])
        check(
            conservative["status"] == "CERTIFIED"
            and float(original["lower_bound"]) == row["original_lower"],
            cid,
        )
        check(
            abs(conservative["lower_bound"] - row["conservative_lower"]) < 1e-5
            and abs(conservative["upper_bound"] - row["conservative_upper"]) < 1e-5,
            cid,
        )
        check(abs(factor["factor_upper"] - row["theoretical_factor_upper"]) < 1e-12, cid)
        output.append(
            dict(
                case_id=cid,
                operation=row["operation"],
                original=original["lower_bound"],
                conservative_lower=conservative["lower_bound"],
                conservative_upper=conservative["upper_bound"],
                factor=factor["factor_upper"],
            )
        )
    return output


if __name__ == "__main__":
    evaluators = dict(
        ties=tie_screen, carrier=carrier_response, sequential=sequential, risk=fixed_risks
    )
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evaluation", choices=evaluators)
    parser.add_argument(
        "--case", help="Evaluate one case ID; by default evaluate the entire module"
    )
    parser.add_argument(
        "--output", type=Path, help="Optional destination for a CSV with recomputed case values"
    )
    args = parser.parse_args()
    output = evaluators[args.evaluation](args.case)
    if not output:
        parser.error("No published design matches the requested case and evaluation.")
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, list(output[0]), lineterminator="\n")
            writer.writeheader()
            writer.writerows(output)
    print(f"Verified {len(output)} {args.evaluation} records.")

"""Calculate the EM traffic impacts from the OD matrix or audit saved equilibria."""
import argparse
import csv
import hashlib
import json
import os
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path
from urllib.request import urlopen

# Small matrices perform best with one numerical-library thread per worker.
for library in ("MKL_NUM_THREADS", "OPENBLAS_NUM_THREADS", "OMP_NUM_THREADS"):
    os.environ[library] = "1"

import numpy as np
from assignment import Network, parse_od, solve_ue
from conjugate_fw import solve_conjugate

DATA = Path(__file__).resolve().parents[1] / "sources" / "traffic"
TARGET_MEAN = 9.652767539378358
GAP_LIMIT = 1e-8

def get_od(local_path, output):
    record = json.loads((DATA / "public_source.json").read_text())["files"]["EMA_trips.tntp"]
    if local_path is None:
        local_path = output / "EMA_trips.tntp"
        if not local_path.exists():
            raw = urlopen(record["url"], timeout=60).read()
            if hashlib.sha256(raw).hexdigest() != record["sha256"]:
                raise ValueError("Upstream OD file failed its pinned SHA-256 check")
            local_path.write_bytes(raw)
    if hashlib.sha256(local_path.read_bytes()).hexdigest() != record["sha256"]:
        raise ValueError("Unexpected OD file contents")
    return parse_od(local_path, 74)

def make_network(od):
    # The original import used the last complete record for each directed pair.
    roads = {}
    for line in (DATA / "EMA.txt").read_text().splitlines():
        if line.strip():
            row = list(map(float, line.replace(";", " ").split()))
            if len(row) != 10:
                raise ValueError("Expected ten fields for each road")
            roads[int(row[0]), int(row[1])] = row
    rows = np.array([roads[pair] for pair in sorted(roads)])
    with (DATA / "road_attributes.csv").open(newline="") as f:
        attrs = [[float(row[key]) for key in
                  ("tail_1based","head_1based","capacity","length","free_flow_time","alpha","beta")]
                 for row in csv.DictReader(f)]
    if rows.shape != (258, 10) or not np.array_equal(rows[:, :7], attrs):
        raise ValueError("Road import differs from the preserved 258-arc attribute table")
    return Network(74, rows[:,0].astype(int)-1, rows[:,1].astype(int)-1,
                   rows[:,2], rows[:,4], rows[:,5], rows[:,6], od)

def assignment_job(job):
    net, baseline_flow, edge = job
    cap = net.capacity.copy()
    cap[edge] *= .5
    solution = solve_ue(net, capacity=cap, start=baseline_flow, max_iter=0)
    if not solution["converged"]:
        solution = solve_ue(net, capacity=cap)
    if not solution["converged"]:
        solution = solve_conjugate(net, capacity=cap)
    if not solution["converged"]:
        raise ValueError(f"Equilibrium failed for arc {edge}")
    return solution["flow"]

def evaluate(net, flows):
    if flows.shape != (259, 258):
        raise ValueError("Missing baseline or single-arc scenario")
    diagnostics = []
    for scenario, flow in enumerate(flows):
        cap = net.capacity.copy()
        if scenario:
            cap[scenario-1] *= .5
        costs = net.times(flow, cap)
        target, _ = net.aon(costs)
        tstt = float(flow @ costs)
        gap = float(costs @ (flow - target)) / tstt
        error = float(net.balance_error(flow))
        if (not np.isfinite(flow).all() or flow.min() < -1e-8 or
                not -1e-8 <= gap <= GAP_LIMIT * (1 + 1e-6) or error > 1e-6):
            raise ValueError(f"Flow, conservation, or equilibrium check failed in scenario {scenario}")
        diagnostics.append((tstt, gap, error))
    tstt, gaps, errors = np.array(diagnostics).T
    changes = np.maximum(0., tstt[1:] - tstt[0])
    scale = TARGET_MEAN / changes.mean()
    matrix = np.zeros((74,74))
    matrix[net.u,net.v] = scale * changes
    return matrix, dict(baseline_tstt=tstt[0], maximum_relative_gap=max(gaps),
                        maximum_flow_balance_error=max(errors), scale=scale)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["audit", "solve"])
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--od", type=Path, help="Optional local copy of the pinned TNTP demand file")
    parser.add_argument("--workers", type=int, default=1)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    if any((args.output / file).exists() for file in ("traffic_verification.json", "impact.csv", "fresh_flows.npz")):
        raise FileExistsError("Choose an output directory without previous results")
    net = make_network(get_od(args.od, args.output))
    if args.operation == "audit":
        with np.load(DATA / "equilibrium_flows.npz") as reference:
            flows = reference["flows"]
    else:
        base = solve_ue(net)
        if not base["converged"]:
            raise ValueError("Baseline failed to converge")
        print("Baseline solved. Computing 258 single-road equilibria.", flush=True)
        jobs = ((net, base["flow"], edge) for edge in range(len(net.u)))
        with ProcessPoolExecutor(max_workers=args.workers) as pool:
            flows = np.stack([base["flow"], *pool.map(assignment_job, jobs)])
        np.savez_compressed(args.output / "fresh_flows.npz", flows=flows)
    matrix, metrics = evaluate(net, flows)
    # The published matrix uses 15 significant digits and CRLF on every platform.
    with (args.output / "impact.csv").open("w", newline="", encoding="ascii") as destination:
        np.savetxt(destination, matrix, delimiter=",", fmt="%.15g", newline="\r\n")
    difference = np.abs(matrix - np.loadtxt(DATA / "impact.csv", delimiter=","))
    summary = dict(operation=args.operation, equilibria=len(flows), **metrics,
                   maximum_absolute_impact_difference=float(difference.max()),
                   mean_absolute_impact_difference=float(difference[net.u,net.v].mean()))
    if args.operation == "solve":
        with np.load(DATA / "equilibrium_flows.npz") as reference:
            summary["all_flow_values_match_reference_exactly"] = bool(np.array_equal(flows, reference["flows"]))
        signature = hashlib.sha256((args.output / "impact.csv").read_bytes()).hexdigest()
        summary["serialized_matrix_sha256"] = signature
        summary["serialized_matrix_matches_reference"] = signature == hashlib.sha256((DATA / "impact.csv").read_bytes()).hexdigest()
    (args.output / "traffic_verification.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))
    if args.operation == "audit" and difference.max() > 1e-10:
        raise ValueError("Recomputed impacts disagree with the frozen scenario estimates")

if __name__ == "__main__":
    main()


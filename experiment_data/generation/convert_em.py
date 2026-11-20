"""Convert the four saved EM base cases and extend the facility list."""
import csv
from pathlib import Path

import numpy as np
import scipy.io as sio


def as_vector(value):
    return np.atleast_1d(value).astype(float).reshape(-1)


def write_matrix(path, matrix, integer=False):
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        for row in matrix:
            if integer:
                writer.writerow([str(int(round(x))) for x in row])
            else:
                writer.writerow([f"{float(x):.12g}" for x in row])


def write_vector(path, vector, integer=False):
    with open(path, "w", encoding="utf-8") as f:
        if integer:
            f.write(",".join(str(int(round(x))) for x in vector))
        else:
            f.write(",".join(f"{float(x):.12g}" for x in vector))
        f.write("\n")


def load_data(mat_path):
    mat = sio.loadmat(mat_path, squeeze_me=True, struct_as_record=False)
    return mat["Data"]


def trim_dummy(data):
    graph = np.array(data.Graph, dtype=float)
    n = 74
    destinations = set(np.atleast_1d(data.Destination).astype(int) - 1)
    if (graph.shape != (75, 75) or graph[n,n] != 0 or
            set(np.nonzero(graph[n,:n])[0]) != destinations or
            set(np.nonzero(graph[:n,n])[0]) != destinations):
        raise ValueError("The last node must be the artificial facility terminal")
    roads = graph[:n,:n] != 0
    for name in ("Cijgraph", "timegraph", "lgraph", "igraph", "R", "Q"):
        if not np.isfinite(getattr(data, name)[:n,:n][roads]).all():
            raise ValueError(f"Missing {name} on a valid directed road")
    out = {
        "graph": graph[:n, :n],
        "cij": np.array(data.Cijgraph, dtype=float)[:n, :n],
        "impact": np.array(data.igraph, dtype=float)[:n, :n],
        "lane_build": np.array(data.lgraph, dtype=float)[:n, :n],
        "time": np.array(data.timegraph, dtype=float)[:n, :n],
        "r": np.array(data.R, dtype=float)[:n, :n, :],
        "q": np.array(data.Q, dtype=float)[:n, :n, :],
        "source": as_vector(data.Source).astype(int) - 1,
        "destination": as_vector(data.Destination).astype(int) - 1,
        "weights": as_vector(data.weightgraph),
        "demand": as_vector(data.N),
        "demand_width": as_vector(data.K),
        "time_window": as_vector(data.timewindow),
        "budget": float(data.B),
        "tau_u": float(data.tau_u),
        "tau_v": float(data.tau_v),
    }
    out["graph"] = (out["graph"] != 0).astype(int)
    for key in ["cij", "impact", "lane_build", "time"]:
        matrix = out[key]
        matrix[out["graph"] == 0] = 0.0
        out[key] = matrix
    out["r"][out["graph"] == 0, :] = 0.0
    out["q"][out["graph"] == 0, :] = 0.0
    return out


def extend_candidates(case, target_m):
    current = list(case["destination"])
    if len(current) >= target_m:
        case["destination"] = np.array(current[:target_m], dtype=int)
        case["weights"] = np.array(case["weights"][:target_m], dtype=float)
        return case
    blocked = set(int(x) for x in case["source"]) | set(int(x) for x in current)
    for node in range(case["graph"].shape[0]):
        if node not in blocked and np.any(case["graph"][node] != 0):
            current.append(node)
            blocked.add(node)
            if len(current) == target_m:
                break
    if len(current) < target_m:
        raise ValueError(f"Cannot extend candidates to {target_m}")
    extra = target_m - len(case["weights"])
    case["destination"] = np.array(current, dtype=int)
    case["weights"] = np.concatenate([case["weights"], np.full(extra, 40000.0)])
    return case


def write_case(case_dir, case, source_file, note):
    case_dir.mkdir(parents=True, exist_ok=True)
    n = case["graph"].shape[0]
    arcs = int(case["graph"].sum())
    metadata = {
        "case": case_dir.name,
        "source_file": source_file,
        "note": note,
        "n": str(n),
        "arcs": str(arcs),
        "s": str(len(case["source"])),
        "m": str(len(case["destination"])),
        "budget": f"{case['budget']:.12g}",
        "tau_u": f"{case['tau_u']:.12g}",
        "tau_v": f"{case['tau_v']:.12g}",
    }
    with open(case_dir / "metadata.properties", "w", encoding="utf-8") as f:
        for k, v in metadata.items():
            f.write(f"{k}={v}\n")
    write_matrix(case_dir / "graph.csv", case["graph"], integer=True)
    write_matrix(case_dir / "cij.csv", case["cij"])
    write_matrix(case_dir / "impact.csv", case["impact"])
    write_matrix(case_dir / "lane_build.csv", case["lane_build"])
    write_matrix(case_dir / "time.csv", case["time"])
    write_vector(case_dir / "sources.csv", case["source"], integer=True)
    write_vector(case_dir / "candidates.csv", case["destination"], integer=True)
    write_vector(case_dir / "candidate_weights.csv", case["weights"])
    write_vector(case_dir / "demand.csv", case["demand"])
    write_vector(case_dir / "demand_width.csv", case["demand_width"])
    write_vector(case_dir / "time_window.csv", case["time_window"])
    for s in range(len(case["source"])):
        write_matrix(case_dir / f"risk_min_s{s}.csv", case["r"][:, :, s])
        write_matrix(case_dir / f"risk_width_s{s}.csv", case["q"][:, :, s])


def convert(source, destination):
    for count in (12,18,24,30):
        filename = f"EM_{count}6.mat"
        case = trim_dummy(load_data(source / filename))
        write_case(destination / f"em_s{count}_m6", case, filename, "saved_em_file")
        if count == 12:
            expanded = extend_candidates(dict(case), 20)
            write_case(destination / "em_s12_m20", expanded, filename, "extended_candidates")

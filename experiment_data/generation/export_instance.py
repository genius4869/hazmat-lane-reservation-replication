"""Export one frozen Java instance as readable, lossless numerical tables."""
import argparse
import csv
import gzip
import json
from pathlib import Path
import struct

def unpack(stream, format_string):
    fmt = struct.Struct(">" + format_string)
    return fmt.unpack(stream.read(fmt.size))

def export(source, destination):
    destination.mkdir(exist_ok=True, parents=True)
    tables = ["instance.json", "facilities.csv", "tasks.csv", "arcs.csv", "risks.csv"]
    if any((destination / name).exists() for name in tables):
        raise FileExistsError("Choose an export directory without previous tables")
    with gzip.open(source, "rb") as reader:
        length, = unpack(reader, "H")
        if reader.read(length) != b"HAZMAT_EFFECTIVE_INPUT_V1":
            raise ValueError("Unknown instance format")
        n, s, m, a = unpack(reader, "4i")
        budget, gu, gv, nominal = unpack(reader, "3d?")
        with (destination / "facilities.csv").open("w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow(("facility_index","node","opening_cost"))
            for idx in range(m):
                writer.writerow((idx, *unpack(reader,"id")))
        with (destination / "tasks.csv").open("w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow(("task_index","origin","nominal_demand","demand_deviation","time_window"))
            for idx in range(s):
                writer.writerow((idx, *unpack(reader,"i3d")))
        task_specific, = unpack(reader, "?")
        meta = dict(case_id=source.name.removesuffix(".bin.gz"), nodes=n, tasks=s,
                    facilities=m, arcs=a, budget=budget, gamma_u=gu, gamma_v=gv,
                    include_nominal_risk=nominal, task_specific_risk=task_specific,
                    index_base=0)
        (destination / "instance.json").write_text(json.dumps(meta, indent=2)+"\n", encoding="utf-8")
        with (destination / "arcs.csv").open("w",newline="",encoding="utf-8") as arcs, \
             (destination / "risks.csv").open("w",newline="",encoding="utf-8") as risks:
            aw, rw = csv.writer(arcs), csv.writer(risks)
            aw.writerow(("tail","head","transport_cost","travel_time","traffic_impact","reservation_cost"))
            rw.writerow(("tail","head","task_index","nominal_risk","risk_deviation"))
            for _ in range(a):
                u, v, *attributes = unpack(reader, "2i4d")
                aw.writerow((u,v,*attributes))
                for k in range(s if task_specific else 1):
                    rw.writerow((u,v,k if task_specific else "all",*unpack(reader,"2d")))
        if reader.read(1):
            raise ValueError("Trailing bytes in frozen input")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("instance", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    export(args.instance, args.output)


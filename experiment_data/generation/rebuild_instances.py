"""Build all 380 instances and compare them with the published inputs."""
import argparse
import csv
import gzip
import hashlib
import os
from pathlib import Path
import shutil
import subprocess

from convert_em import convert

HERE = Path(__file__).resolve().parent
DATA = HERE.parent
ARCHIVE = DATA.parent

def hash_file(path, *, unpack=False):
    open_file = gzip.open if unpack else open
    digest = hashlib.sha256()
    with open_file(path, "rb") as source:
        for chunk in iter(lambda: source.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()

def kotlin_tools(home):
    name = "kotlinc.bat" if os.name == "nt" else "kotlinc"
    if home is None:
        compiler = shutil.which(name)
        if not compiler:
            raise ValueError("Set KOTLIN_HOME or pass --kotlin-home for the Kotlin compiler distribution")
        home = Path(compiler).resolve().parents[1]
    return home / "bin" / name, home / "lib" / "kotlin-stdlib.jar"

def manifest(directory):
    with (directory / "inputs_manifest.csv").open(encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
    records = {row["case_id"]: row for row in rows}
    if len(rows) != 380 or len(records) != 380:
        raise ValueError("Expected 380 distinct case identifiers")
    return records

def compare(destination):
    expected = manifest(DATA / "instances")
    actual = manifest(destination)
    if expected.keys() != actual.keys():
        raise ValueError("Case inventory differs from the reference")
    rows = []
    for identifier, reference in expected.items():
        value = actual[identifier]
        expected_path = DATA / "instances" / reference["input_file"]
        generated_path = destination / value["input_file"]
        sha = hash_file(generated_path)
        reference_sha = hash_file(expected_path)
        if reference_sha != reference["input_sha256"] or sha != value["input_sha256"]:
            raise ValueError(f"Manifest checksum failure for {identifier}")
        metadata = all(value[k] == v for k,v in reference.items() if k != "input_sha256")
        packed = sha == reference_sha
        # DEFLATE encoders on other systems may differ while reproducing every input bit.
        payload = packed or hash_file(generated_path, unpack=True) == hash_file(expected_path, unpack=True)
        rows.append((identifier,metadata,packed,payload,sha))
    with (destination / "rebuild_check.csv").open("w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(("case_id","metadata_match","gzip_bytes_match","input_payload_match","generated_sha256"))
        writer.writerows(rows)
    counts = [sum(row[i] for row in rows) for i in (1,2,3)]
    print(f"{len(rows)} cases checked. Metadata matches: {counts[0]}; gzip byte matches: {counts[1]}; "
          f"uncompressed input matches: {counts[2]}.", flush=True)
    if counts[0] != len(rows) or counts[2] != len(rows):
        raise ValueError("One or more rebuilt cases differ. See rebuild_check.csv")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="A new or empty working directory")
    parser.add_argument("--kotlin-home", type=Path, default=os.environ.get("KOTLIN_HOME"))
    parser.add_argument("--stdlib", type=Path, help="Optional Kotlin standard library for the Java runtime")
    parser.add_argument("--impact", type=Path, default=DATA / "sources" / "traffic" / "impact.csv")
    args = parser.parse_args()
    dest = args.output.resolve()
    dest.mkdir(parents=True, exist_ok=True)
    if any(dest.iterdir()):
        raise FileExistsError("Choose a new or empty output directory")
    compiler, standard_library = kotlin_tools(args.kotlin_home)
    runtime_jar = args.stdlib or standard_library
    classes = dest / "classes"
    classes.mkdir()
    kt = ARCHIVE / "experiment_code" / "src" / "main" / "kotlin" / "DataCreator.kt"
    subprocess.run([str(compiler), str(kt), "-language-version","2.1","-api-version","2.1",
                    "-jvm-target","1.8","-d",str(classes)], check=True)
    classpath = os.pathsep.join(map(str, (classes, runtime_jar)))
    files = sorted((HERE / "java").glob("*.java"))
    subprocess.run(["javac","-encoding","UTF-8","-cp",classpath,"-d",str(classes), *map(str,files)], check=True)
    converted = dest / "converted"
    convert(DATA / "sources" / "hazmat", converted)
    subprocess.run(["java","-Xmx2g","-cp",classpath,"UnifiedExperimentData",str(converted),
                    str(args.impact.resolve()),str(dest)],check=True)
    compare(dest)

if __name__ == "__main__":
    main()


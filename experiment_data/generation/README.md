# Rebuild and inspect the study inputs

Run these commands from the repository root. Python 3.11.3, NumPy 1.24.3, SciPy 1.10.1 and Numba 0.57.0 were used for the release checks. The instance builder additionally needs a JDK on PATH and a Kotlin command-line compiler. Its source build was tested with JDK 15.0.1, Kotlin compiler 2.1.21, JVM target 1.8 and Kotlin runtime 2.1.10. Set KOTLIN_HOME to the Kotlin compiler distribution or pass its directory with --kotlin-home. The default runtime comes from that distribution. Use --stdlib to select a different runtime jar. No commercial solver is used to generate or check the input data.

```sh
python -m pip install -r experiment_data/generation/requirements.txt
python experiment_data/generation/rebuild_instances.py --output work/rebuilt
```

This command reads the four MAT base cases, converts their numeric fields with the original 12-significant-digit convention, enumerates the EM subsets and perturbations, and generates all random cases using the archived Kotlin and Java generators. The 20-candidate base starts with the six sites in the 12-task case, then adds eligible non-origin nodes in node order. Site setup cost for added candidates is 40000. EM task subsets use seed 880000 + 100 times the task count + replicate. Expanded instances retain the first 30 tasks and draw any further tasks with replacement using seed 993000 + 37 times the task count + 20. Random instances use seeds 123 through 132 for each configuration. All 380 files are written to the new working directory, including their full manifest and per-case comparison against the archived inputs. `rebuild_check.csv` compares the metadata, GZIP bytes and uncompressed solver payload. GZIP bytes may depend on the JDK and compression library.

The default uses the published `sources/traffic/impact.csv` for the EM coefficient matrix. To produce the matrix for yourself, first install the Python requirements. The traffic script fetches the exact OD demand file in `sources/traffic/public_source.json` and verifies its SHA-256. To work offline, pass `--od path/to/EMA_trips.tntp` to either traffic command.

```sh
python experiment_data/generation/traffic_impacts.py audit --output work/traffic_audit
python experiment_data/generation/rebuild_instances.py --impact work/traffic_audit/impact.csv --output work/rebuilt_from_flows
python experiment_data/generation/traffic_impacts.py solve --workers 4 --output work/traffic_solve
```

`audit` starts with the saved baseline and 258 saved perturbed flow vectors. It recomputes BPR travel times, flow balance, all-or-nothing shortest-path loads, the relative Frank-Wolfe gap, and all 258 impact coefficients directly from these flows and the OD input. `solve` starts from the network and OD matrix. It solves the baseline, tests this feasible flow under each capacity change, and uses pairwise Frank-Wolfe with a conjugate Frank-Wolfe fallback when needed. Both paths enforce a relative gap of 1e-8 (with a small floating-point allowance) and an absolute node-balance error of at most 1e-6. The output includes both the matrix and a numerical comparison to the paper's frozen coefficients. Equilibrium solver paths and last digits can vary with the numerical libraries. Use `audit` or the distributed impact matrix when the goal is exact input replication.

For a readable export of a published or newly generated instance, run

```sh
python experiment_data/generation/export_instance.py experiment_data/instances/inputs/R01_seed123.bin.gz work/readable_random
```

`validation/` contains the archived verification results. The 380-case release check used the matrix recalculated by `audit`. It matched all published GZIP files and manifest fields. Independently, the complete cold solve reproduced all 259 flow vectors exactly and returned the same serialized impact matrix (SHA-256 `ce9c152c098a8026dd4538e53191d0e25117674dd7d110085024d22cec43f674`). Its maximum Frank-Wolfe relative gap was 9.999951431451921e-9. Each working directory should be new or empty. The published data remain available under `experiment_data/instances`.

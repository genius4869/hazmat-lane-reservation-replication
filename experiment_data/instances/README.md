# Published instances

`inputs/` contains all 380 files read by the optimization algorithms. The manifest lists case identifiers, seeds, sampled task records, graph sizes, budgets, relative paths and compressed SHA-256 digests. The two smaller lists identify reused cases for the policy comparisons and fixed-route risk evaluation.

| Family | Instances | Construction |
| --- | ---: | --- |
| em_sample | 46 | Five samples at each even size 12 through 28, plus the full 30-task set; six candidate facilities. |
| em_expanded | 8 | Sizes 30, 36, 42, 48, 60, 80, 100 and 120, with 20 candidate facilities. Extra tasks are sampled with replacement. |
| random | 230 | 23 configurations, each with seeds 123 through 132. |
| sensitivity | 96 | 32 distinct configurations for each of the saved 12-, 18- and 24-task bases. |

`readable_examples/` contains complete CSV tables for one EM instance and one random instance. To export any other instance at the repository root, run

```sh
python experiment_data/generation/export_instance.py experiment_data/instances/inputs/EM_SUB_s30_full_m6.bin.gz work/em30
```

Node, task and facility indices in exported tables start at zero. Several tasks can share a node. Transport cost, time, impact and reservation cost are attached to the directed arc from tail to head. The risk table gives the nominal coefficient and its deviation for each task on each active arc. A task index of `all` means the coefficients are shared. Exported decimals preserve binary64 values on round-trip. In the manifest the `task_records` field identifies zero-based source records for EM cases. Repeated indices represent draws with replacement. `source_facility_overlap` counts tasks whose origin is also a facility. `unreachable_tasks` counts tasks without a graph path to a candidate site.

## Binary record layout

A GZIP member contains Java DataOutputStream records. Integers and binary64 numbers are big-endian. Each boolean occupies one byte.

1. Java writeUTF header, with a two-byte length and ASCII string `HAZMAT_EFFECTIVE_INPUT_V1`.
2. Four int32 counts for nodes, tasks, facilities and directed arcs. Three binary64 values for the construction budget, Gamma_u and Gamma_v, followed by the nominal-risk inclusion flag.
3. For every facility, int32 node followed by binary64 opening cost.
4. For every task, int32 origin followed by binary64 nominal demand, demand deviation and time window.
5. A boolean indicating whether risks are task-specific.
6. For each active arc, int32 tail and head, then binary64 transport cost, travel time, traffic impact and reservation cost. Finally a binary64 pair for nominal risk and risk deviation, repeated in task order if task-specific, or once otherwise.

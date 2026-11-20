# Source data

| Saved file in `hazmat/` | Tasks | Candidate sites | Use |
| --- | ---: | ---: | --- |
| EM_126.mat | 12 | 6 | Sensitivity base and the source of the 20-site candidate list |
| EM_186.mat | 18 | 6 | Sensitivity base |
| EM_246.mat | 24 | 6 | Sensitivity base |
| EM_306.mat | 30 | 6 | Task pool for subsets and expanded instances |

These MATLAB files are preserved study inputs. They contain the realized task demands, uncertainty widths, risks, costs, time windows and facility data. The complete historical scripts and random streams for those realizations were not preserved. The published instance-generation workflow starts from these four files. The converter verifies 74 physical nodes and a common structure for the artificial 75th terminal before removing it. Road transport costs use distance. Hazardous-materials parameters represent study inputs rather than measured operations.

## Ordinary traffic

The authors' modified list of directed roads is in `traffic/EMA.txt`. It contains 260 records and 258 distinct directed endpoint pairs. The last record for each pair supplies length, capacity, free-flow time and BPR coefficients. `road_attributes.csv` records the selected source line. The road and OD endpoint numbers start at one. The assignment uses the modified topology and the April 2012 PM demand scenario transferred from the public Eastern Massachusetts benchmark.

`traffic/public_source.json` lists the exact upstream revision, download URLs and SHA-256 digests. The traffic generator retrieves the pinned OD file or accepts an existing copy with `--od`. The upstream terms specify academic research use and require publications to identify the source. Please retain the upstream conditions and attribution when using the EM materials. [Terms of use](https://github.com/bstabler/TransportationNetworks/blob/d1639b4ef218c17928ba573e806ddf8ba5e7ae6d/README.md#license) · [EM data description and original publications](https://github.com/bstabler/TransportationNetworks/blob/d1639b4ef218c17928ba573e806ddf8ba5e7ae6d/Eastern-Massachusetts/README.md)

One baseline equilibrium and 258 single-arc scenarios are computed using the BPR function. In each scenario the ordinary-traffic capacity of the selected directed road is halved. Demand is interpreted as a representative hourly flow and travel time is in hours. Subtract the baseline total system travel-time rate from the scenario value and truncate each increment at zero. Scale the vector so that its mean over all 258 roads equals 9.652767539378358, the reference impact mean from the saved 30-task base. The resulting scale in the paper is 0.28390796785690503.

| Published file in `traffic/` | Contents |
| --- | --- |
| `impact.csv` | 74 by 74 matrix used in the baseline EM solver inputs. Inactive entries are zero. |
| `arc_impact_reference.csv` | Directed endpoints, capacities, baseline and scenario travel-time rates, increments, equilibrium gaps and final impact. |
| `equilibrium_flows.npz` | Binary64 array named `flows`. Row zero is the baseline. Row k+1 reduces arc k in sorted directed endpoint order. |

The reference flows allow readers to recompute conservation, the all-or-nothing equilibrium gap, system travel times and all impacts. A separate command solves the network and OD data from scratch. `source_checksums.csv` lists the distributed source and derived files by SHA-256.

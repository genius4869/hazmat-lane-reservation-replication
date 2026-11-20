"""Render the runtime, joint-planning and road-selection paper figures."""
import json
from pathlib import Path

import matplotlib as mpl
import matplotlib.pyplot as plt
import networkx as nx


HERE = Path(__file__).resolve().parent


def draw_runtime(groups, directory):
    width, height = 3084, 1015
    dpi = 270
    plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 10,
                         "axes.labelsize": 10, "xtick.labelsize": 9,
                         "ytick.labelsize": 10, "legend.fontsize": 10,
                         "axes.edgecolor": "#444A50", "axes.labelcolor": "#444A50",
                         "xtick.color": "#444A50", "ytick.color": "#444A50",
                         "axes.linewidth": 0.9, "figure.facecolor": "white",
                         "savefig.facecolor": "white", "mathtext.fontset": "dejavusans"})
    fig, ax = plt.subplots(figsize=(width / dpi, height / dpi), dpi=dpi)
    x = list(range(len(groups)))
    cpa = [r["CPA_mean_seconds"] for r in groups]
    bpa = [r["BPA_mean_seconds"] for r in groups]
    for values, color, marker, label in ((cpa, "#4C78A8", "o", "CPA runtime"),
                                         (bpa, "#F58518", "s", "BPA runtime")):
        ax.plot(x, values, color=color, marker=marker, markersize=5.0,
                linewidth=2.0, markeredgecolor="white", markeredgewidth=0.65,
                label=label, zorder=3)
    ax.set_yscale("log")
    ax.set_ylabel("Average runtime (s, log scale)", labelpad=9)
    ax.set_xticks(x)
    ax.set_xticklabels([f"$R_{{{int(r['group'][1:])}}}$" for r in groups],
                       rotation=45, ha="right")
    ax.set_xlim(-0.6, len(groups) - 0.4)
    ax.set_ylim(min(cpa + bpa) / 1.5, max(cpa + bpa) * 1.6)
    ax.grid(axis="y", which="major", color="#D8DCE3", linewidth=0.8, alpha=0.9)
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)
    ax.tick_params(axis="x", length=3.5, width=0.9)
    ax.tick_params(axis="y", which="both", width=0.8)
    fig.legend(loc="upper center", bbox_to_anchor=(0.5, 0.997), ncol=2,
               frameon=False, handlelength=2.1, columnspacing=2.0)
    fig.subplots_adjust(left=0.063, right=0.991, bottom=0.115, top=0.875)
    fig.savefig(directory / "figure_3_random_runtime.png", dpi=dpi, facecolor="white")
    fig.savefig(directory / "figure_3_random_runtime.pdf", facecolor="white")
    plt.close(fig)


def draw_joint_planning(rows, directory):
    # Original dimensions, panel proportions, colors, markers, line widths,
    # grids, legends and margin settings are preserved verbatim in substance.
    x=[r['tasks'] for r in rows]
    ratio=[r['mean_paired_objective_ratio_percent'] for r in rows]
    with plt.rc_context({'font.family':'sans-serif','font.sans-serif':['DejaVu Sans'],
                         'font.size':10,'svg.fonttype':'none'}):
        fig,axes=plt.subplots(2,1,figsize=(9.6,6.2),dpi=220,sharex=True,
                              gridspec_kw={'height_ratios':[2.05,1.05],'hspace':0.08})
        axes[0].plot(x,[r['sequential_objective_mean'] for r in rows],marker='s',
                     linewidth=2.0,markersize=5.5,color='#4C78A8',label='Sequential planning')
        axes[0].plot(x,[r['joint_objective_mean'] for r in rows],marker='o',
                     linewidth=2.0,markersize=5.5,color='#F58518',label='Joint planning')
        axes[0].set_ylabel('Objective value')
        axes[0].grid(alpha=0.25)
        axes[0].legend(frameon=False,ncol=2,loc='upper center',bbox_to_anchor=(0.5,1.12))
        axes[1].plot(x,ratio,marker='^',linewidth=2.1,markersize=5.8,
                     linestyle='-.',color='#59A14F',label='ObjRatio')
        ratio_min=max(0.0,min(ratio)-5.0)
        ratio_max=min(100.0,max(ratio)+5.0)
        if ratio_max-ratio_min<12.0:
            mid=(ratio_min+ratio_max)/2.0
            ratio_min=max(0.0,mid-6.0)
            ratio_max=min(100.0,mid+6.0)
        axes[1].set_ylim(ratio_min,ratio_max)
        axes[1].set_xlabel('$|S|$')
        axes[1].set_ylabel('ObjRatio (%)')
        axes[1].set_xticks(x)
        axes[1].grid(alpha=0.25)
        axes[1].legend(frameon=False,loc='upper center',bbox_to_anchor=(0.5,1.04))
        fig.subplots_adjust(left=0.10,right=0.985,top=0.875,bottom=0.12,hspace=0.10)
        fig.savefig(directory / 'figure_6_joint_planning.png')
        fig.savefig(directory / 'figure_6_joint_planning.pdf')
        plt.close(fig)


def draw_heatmap(graph, frequency, directory):
    mpl.rcParams.update({
        "font.family": "serif", "font.serif": ["Times New Roman", "DejaVu Serif"],
        "axes.unicode_minus": False,
        "pdf.fonttype": 42,
        "ps.fonttype": 42,
    })
    positions = json.loads((HERE / "node_positions.json").read_text(encoding="utf-8"))
    pos = {int(node): xy for node, xy in positions.items()}
    assert set(pos) == set(graph.nodes)
    selected_edges, selected_values, selected_widths, background_edges = [], [], [], []
    for edge in graph.edges():
        key = tuple(sorted(edge))
        value = frequency[key]
        if value > 0:
            selected_edges.append(edge)
            selected_values.append(value)
            selected_widths.append(0.9 + 3.8 * value / 100.0)
        else:
            background_edges.append(edge)

    fig, ax = plt.subplots(figsize=(12.6, 7.35), dpi=300)
    ax.set_aspect("equal")
    nx.draw_networkx_edges(graph, pos, edgelist=background_edges, ax=ax,
                          width=0.55, edge_color="#d0d5d8", alpha=0.55)
    cmap = mpl.colormaps["RdYlGn_r"]
    norm = mpl.colors.Normalize(vmin=0, vmax=100)
    nx.draw_networkx_edges(graph, pos, edgelist=selected_edges, ax=ax,
                          width=selected_widths, edge_color=selected_values,
                          edge_cmap=cmap, edge_vmin=0, edge_vmax=100, alpha=0.95)
    nx.draw_networkx_nodes(graph, pos, ax=ax, node_size=760,
                          node_color="#e8f4c9", edgecolors="#355f3a", linewidths=0.9)
    # Preserve the original NetworkX label call, including its sans-serif
    # default; the original rc setting controls the colorbar's serif font.
    nx.draw_networkx_labels(graph, pos,
                           labels={node: str(node + 1) for node in graph.nodes()},
                           ax=ax, font_size=11.4, font_weight="bold", font_color="#1f2527")
    scalar_map = mpl.cm.ScalarMappable(norm=norm, cmap=cmap)
    scalar_map.set_array(selected_values)
    cbar = fig.colorbar(scalar_map, ax=ax, fraction=0.028, pad=0.018, shrink=0.84)
    cbar.set_label("Selection frequency (%)", fontsize=11.0)
    cbar.ax.tick_params(labelsize=10.5, length=3.0)
    ax.set_axis_off()
    ax.margins(x=0.02, y=0.04)
    fig.subplots_adjust(left=0.01, right=0.965, top=0.985, bottom=0.02)
    png = directory / "figure_7_road_frequency.png"
    pdf = directory / "figure_7_road_frequency.pdf"
    for path in [png, pdf]:
        fig.savefig(path, bbox_inches="tight", pad_inches=0.04, facecolor="white")
    plt.close(fig)

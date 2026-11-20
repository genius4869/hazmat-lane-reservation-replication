"""Render the two paper sensitivity figures from certified case observations."""
import math

import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.ticker import FuncFormatter, MaxNLocator


STYLES = {12: ("#2F6B9A", "o"), 18: ("#F58518", "s"), 24: ("#59A14F", "^")}
BASELINES = {"gamma_u": 0.5, "gamma_v": 129, "q": 1, "impact": 1, "budget": 10000}


def setup_style():
    plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 10,
        "axes.edgecolor": "#333333", "axes.linewidth": 0.9,
        "figure.facecolor": "white", "savefig.facecolor": "white",
        "pdf.fonttype": 42, "ps.fonttype": 42, "mathtext.fontset": "dejavusans"})


def plot_curve(ax, rows, field: str, color: str, marker: str, small=False):
    x = [p["value"] for p in rows]
    # NaN breaks the line and never invents an objective for infeasible settings.
    y = [p[field] if p["status"] == "CERTIFIED_OPTIMAL" else math.nan for p in rows]
    return ax.plot(x, y, color=color, marker=marker,
                   markersize=3.2 if small else 5.0,
                   linewidth=1.25 if small else 2.0,
                   markeredgewidth=0.45 if small else 0.55,
                   markeredgecolor="white", zorder=3)[0]


def panel(ax, curves, parameter, field, title, xlabel, ylabel):
    values = []
    for tasks, (color, marker) in STYLES.items():
        points = curves[(tasks, parameter)]
        plot_curve(ax, points, field, color, marker)
        values.extend(p[field] for p in points if p["status"] == "CERTIFIED_OPTIMAL")
    ax.axvline(BASELINES[parameter], color="#8A8A8A", linestyle="--", linewidth=1.0, alpha=0.7)
    ax.set_title(title, fontsize=11.5, pad=8)
    ax.set_xlabel(xlabel, fontsize=10.5)
    ax.set_ylabel(ylabel, fontsize=10.5)
    ax.grid(True, axis="both", alpha=0.22, linewidth=0.7)
    ax.tick_params(axis="both", labelsize=9.5)
    ax.yaxis.set_major_formatter(FuncFormatter(lambda v, _: f"{v:,.0f}"))
    ax.yaxis.set_major_locator(MaxNLocator(5))
    lo, hi = min(values), max(values)
    pad = max((hi - lo) * 0.14, hi * 0.015)
    ax.set_ylim(lo - pad, hi + pad)
    ax.margins(x=0.04)


def legend(fig):
    handles = [Line2D([], [], color=c, marker=m, markersize=5, linewidth=2,
                      label=f"{s} tasks") for s, (c, m) in STYLES.items()]
    handles.append(Line2D([], [], color="#8A8A8A", linestyle="--", linewidth=1,
                          label="Baseline setting"))
    fig.legend(handles=handles, loc="upper center", bbox_to_anchor=(0.5, 0.995),
               ncol=4, frameon=False, fontsize=9.5, columnspacing=2.0)


def inset(ax, curves, parameter, bounds, rectangle, xticks):
    small = ax.inset_axes(rectangle)
    subset = []
    for tasks, (color, marker) in STYLES.items():
        points = [p for p in curves[(tasks, parameter)] if bounds[0] <= p["value"] <= bounds[1]]
        plot_curve(small, points, "verified_UB", color, marker, small=True)
        subset.extend(p["verified_UB"] for p in points if p["status"] == "CERTIFIED_OPTIMAL")
    span = bounds[1] - bounds[0]
    small.set_xlim(bounds[0] - 0.025 * span, bounds[1] + 0.025 * span)
    ymin, ymax = min(subset), max(subset)
    small.set_ylim(ymin - .12 * (ymax - ymin), ymax + .12 * (ymax - ymin))
    small.set_xticks(xticks)
    small.yaxis.set_major_locator(MaxNLocator(3))
    small.yaxis.set_major_formatter(FuncFormatter(lambda v, _: f"{v / 1000:.0f}k"))
    small.tick_params(axis="both", labelsize=7, pad=1.5, length=2)
    small.grid(alpha=0.17, linewidth=0.5)
    small.set_facecolor("white")
    symbol = r"\Gamma_v" if parameter == "gamma_v" else "B"
    small.set_title(rf"${bounds[0]:g}\leq {symbol}\leq {bounds[1]:g}$", fontsize=8, pad=3)
    for spine in small.spines.values():
        spine.set_linewidth(0.7)
        spine.set_color("#666666")
    return small


def save(fig, directory, basename):
    fig.savefig(directory / (basename + ".png"), dpi=260, bbox_inches="tight", facecolor="white")
    fig.savefig(directory / (basename + ".pdf"), bbox_inches="tight", facecolor="white",
                metadata={"CreationDate": None, "ModDate": None})
    plt.close(fig)


def uncertainty_figure(curves, directory):
    fig, axes = plt.subplots(1, 3, figsize=(11.8, 4.35), dpi=260)
    specs = [("gamma_u", "Demand uncertainty budget", r"$\Gamma_u/|S|$"),
             ("gamma_v", "Road uncertainty budget", r"$\Gamma_v$"),
             ("q", "Risk-deviation multiplier", r"$K_Q$")]
    for ax, (parameter, title, xlabel) in zip(axes, specs):
        panel(ax, curves, parameter, "verified_UB", title, xlabel,
              "Optimal objective value" if parameter == "gamma_u" else "")
    axes[0].set_xticks([0, .25, .5, .75, 1])
    axes[1].set_xticks([0, 40, 80, 129, 200, 258])
    axes[2].set_xticks([0, .5, 1, 1.5, 2])
    gamma_values = [p["verified_UB"] for task in STYLES for p in curves[(task, "gamma_v")]]
    gamma_low, gamma_high = min(gamma_values), max(gamma_values)
    gamma_span = gamma_high - gamma_low
    axes[1].set_ylim(gamma_low - .30 * gamma_span, gamma_high + .14 * gamma_span)
    # The inset lies below the long flat main-panel curves, preserving all full-range markers.
    inset(axes[1], curves, "gamma_v", (0, 40), [.48, .075, .49, .29], [0, 10, 20, 40])
    legend(fig)
    fig.subplots_adjust(left=.075, right=.985, bottom=.19, top=.82, wspace=.31)
    save(fig, directory, "figure_4_robust_sensitivity")


def traffic_budget_figure(curves, directory):
    fig, axes = plt.subplots(1, 2, figsize=(11.8, 4.65), dpi=260)
    panel(axes[0], curves, "impact", "D0", "Traffic-impact multiplier", r"$K_I$",
          r"Traffic impact $D_0$")
    panel(axes[1], curves, "budget", "verified_UB", "Lane-reservation budget", r"$B$",
          "Optimal objective value")
    axes[0].set_xticks([.5, .75, 1, 1.25, 1.5])
    axes[1].set_xticks([0, 2000, 4000, 6000, 8000, 10000])
    axes[1].xaxis.set_major_formatter(FuncFormatter(lambda v, _: f"{v:,.0f}"))
    inset(axes[1], curves, "budget", (0, 2000), [.43, .56, .53, .36], [0, 500, 1000, 2000])
    legend(fig)
    fig.text(.5, .012,
             "Infeasible settings: 12 tasks, B = 0, 250; 18/24 tasks, B = 0, 250, 500.",
             ha="center", va="bottom", fontsize=8.8,
             bbox={"facecolor": "#F5F5F5", "edgecolor": "none", "pad": 4})
    fig.subplots_adjust(left=.075, right=.985, bottom=.25, top=.82, wspace=.28)
    save(fig, directory, "figure_5_traffic_and_budget")

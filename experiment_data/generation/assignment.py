"""Single-commodity traffic assignment and equilibrium-gap calculations for the EM network."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re
import time

import numpy as np
from numba import njit
from scipy.optimize import brentq
from scipy.sparse import csr_matrix
from scipy.sparse.csgraph import dijkstra

TOL = 1e-8
MAX_ITER = 50_000

@dataclass
class Network:
    n: int
    u: np.ndarray
    v: np.ndarray
    capacity: np.ndarray
    free_time: np.ndarray
    alpha: np.ndarray
    beta: np.ndarray
    od: np.ndarray

    def __post_init__(self):
        self.origins = np.flatnonzero(self.od.sum(axis=1) > 0)
        self.edge_index = np.full((self.n, self.n), -1, dtype=np.int64)
        self.edge_index[self.u, self.v] = np.arange(len(self.u))

    def times(self, flow, capacity):
        return self.free_time * (1 + self.alpha * (np.maximum(flow, 0) / capacity) ** self.beta)

    def beckmann(self, flow, capacity):
        x = np.maximum(flow, 0)
        return float(np.sum(self.free_time * x * (1 + self.alpha / (self.beta + 1) * (x / capacity) ** self.beta)))

    def aon(self, costs):
        graph = csr_matrix((costs, (self.u, self.v)), shape=(self.n, self.n))
        distance, pred = dijkstra(graph, directed=True, indices=self.origins, return_predecessors=True)
        if np.any(~np.isfinite(distance[self.od[self.origins] > 0])):
            raise ValueError("Positive OD demand is disconnected")
        order = np.argsort(distance, axis=1)
        flow = load_trees(pred, order, self.od[self.origins], self.origins, self.edge_index, len(self.u))
        demanded = self.od[self.origins] > 0
        shortest_cost = float(np.sum(distance[demanded] * self.od[self.origins][demanded]))
        return flow, shortest_cost

    def balance_error(self, flow):
        actual = np.bincount(self.u, weights=flow, minlength=self.n) - np.bincount(self.v, weights=flow, minlength=self.n)
        expected = self.od.sum(axis=1) - self.od.sum(axis=0)
        return float(np.max(np.abs(actual - expected)))


@njit(cache=True)
def load_trees(pred, order, demands, origins, edge_index, m):
    flow = np.zeros(m)
    for r in range(len(origins)):
        loads = demands[r].copy()
        for j in range(order.shape[1] - 1, -1, -1):
            node = order[r, j]
            if node == origins[r] or loads[node] == 0:
                continue
            parent = pred[r, node]
            if parent < 0:
                raise ValueError("Unreachable demand in shortest-path tree")
            edge = edge_index[parent, node]
            if edge < 0:
                raise ValueError("Shortest-path predecessor is not a road")
            flow[edge] += loads[node]
            loads[parent] += loads[node]
    return flow


def exact_step(net, x, direction, capacity, upper):
    if upper <= 0:
        return 0.0
    def derivative(step):
        return float(np.dot(direction, net.times(x + step * direction, capacity)))
    if derivative(0.0) >= 0:
        return 0.0
    if derivative(upper) <= 0:
        return float(upper)
    return float(brentq(derivative, 0.0, upper, xtol=1e-14, rtol=1e-14))


def solve_ue(net, capacity=None, start=None, tolerance=TOL, max_iter=MAX_ITER):
    """Frank-Wolfe with feasible pairwise away steps and exact line search.

    Each iteration compares the ordinary FW step with a pairwise FW step and
    takes the lower Beckmann objective. The global stopping test is the ordinary
    FW relative gap, not a step-size or iterate-change surrogate.
    """
    begin = time.perf_counter()
    capacity = net.capacity.copy() if capacity is None else np.asarray(capacity, dtype=float)
    if np.any(capacity <= 0) or not np.isfinite(capacity).all():
        raise ValueError("Capacity must be finite and positive")
    x = net.aon(net.free_time)[0] if start is None else np.asarray(start, dtype=float).copy()
    if np.min(x) < -1e-9 or net.balance_error(x) > 1e-6:
        raise ValueError("Initial flow is not feasible")
    vertices = x[None, :].copy()
    weights = np.ones(1)
    history = []
    pairwise_steps = 0
    for iteration in range(max_iter + 1):
        costs = net.times(x, capacity)
        y, shortest_cost = net.aon(costs)
        tstt = float(np.dot(x, costs))
        gap = float(np.dot(costs, x - y))
        relative_gap = max(0.0, gap / max(tstt, 1e-30))
        if gap < -1e-6 * max(1.0, tstt):
            raise ArithmeticError("Negative FW gap beyond numerical precision")
        if iteration % 100 == 0 or relative_gap <= tolerance or iteration == max_iter:
            history.append(dict(iteration=iteration, relative_gap=relative_gap, tstt=tstt,
                                beckmann=net.beckmann(x, capacity), active_points=len(weights)))
        if relative_gap <= tolerance or iteration == max_iter:
            return dict(flow=x, times=costs, capacity=capacity, tstt=tstt,
                        shortest_total_cost=shortest_cost, relative_gap=relative_gap,
                        absolute_fw_gap=gap, iterations=iteration,
                        converged=relative_gap <= tolerance,
                        conservation_max_abs=net.balance_error(x),
                        elapsed_seconds=time.perf_counter() - begin,
                        pairwise_steps=pairwise_steps, history=history)
        direction_fw = y - x
        step_fw = exact_step(net, x, direction_fw, capacity, 1.0)
        trial_fw = x + step_fw * direction_fw
        away = int(np.argmax(vertices @ costs))
        direction_pair = y - vertices[away]
        step_pair = exact_step(net, x, direction_pair, capacity, float(weights[away]))
        trial_pair = x + step_pair * direction_pair
        pair = step_pair > 0 and net.beckmann(trial_pair, capacity) < net.beckmann(trial_fw, capacity)
        if pair:
            x = trial_pair
            step = step_pair
            weights[away] -= step
            pairwise_steps += 1
        else:
            x = trial_fw
            step = step_fw
            weights *= 1 - step
        if step <= 0:
            raise ArithmeticError(f"UE stalled above tolerance: relative gap {relative_gap}")
        same = np.flatnonzero(np.all(vertices == y, axis=1))
        if len(same):
            weights[same[0]] += step
        else:
            vertices = np.vstack((vertices, y))
            weights = np.append(weights, step)
        keep = weights > 1e-15
        weights, vertices = weights[keep], vertices[keep]
        weights /= weights.sum()
        if iteration % 25 == 0:
            x = weights @ vertices
    raise AssertionError("Unreachable")


def parse_od(path, n):
    text = Path(path).read_text(encoding="utf-8-sig")
    od = np.zeros((n, n))
    origin = None
    for line in text.splitlines():
        found = re.match(r"\s*Origin\s+(\d+)", line, flags=re.I)
        if found:
            origin = int(found[1]) - 1
        elif origin is not None:
            for destination, amount in re.findall(r"(\d+)\s*:\s*([-+0-9.eE]+)\s*;", line):
                od[origin, int(destination) - 1] = float(amount)
    expected = float(re.search(r"<TOTAL OD FLOW>\s*([-+0-9.eE]+)", text).group(1))
    if np.any(od < 0) or not np.isclose(od.sum(), expected, rtol=1e-10):
        raise ValueError("OD total does not match metadata")
    if np.any(np.diag(od) != 0):
        raise ValueError("Nonzero intrazonal trips require an explicit separate treatment")
    return od


def audit_ue(net, result, expected_capacity, tolerance=TOL):
    """Recompute equilibrium diagnostics from saved flows and current inputs."""
    flow = result['flow']
    if not np.isfinite(flow).all() or np.min(flow) < -1e-8:
        raise ValueError("Invalid saved UE flow")
    if not np.array_equal(result['capacity'], expected_capacity):
        raise ValueError("Saved UE capacity does not match requested scenario")
    costs = net.times(flow, expected_capacity)
    y, _ = net.aon(costs)
    tstt = float(flow @ costs)
    gap = float(costs @ (flow - y)) / max(tstt, 1e-30)
    if gap > tolerance * (1 + 1e-6) or gap < -1e-8:
        raise ValueError(f"Saved UE flow fails fresh FW gap check: {gap}")
    if net.balance_error(flow) > 1e-6 or not np.isclose(tstt, result['tstt'], rtol=1e-12, atol=1e-8):
        raise ValueError("Saved UE conservation or TSTT mismatch")
    if not np.allclose(costs, result['times'], rtol=1e-12, atol=1e-12):
        raise ValueError("Saved UE travel times do not match BPR")
    return dict(relative_gap=gap, tstt=tstt, conservation_error=net.balance_error(flow))



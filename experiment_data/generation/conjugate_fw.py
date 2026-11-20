"""Conjugate FW for the same separable Beckmann traffic assignment.

Feasible target mixing follows Boyles et al., Transportation Network Analysis,
chapter 6, equations 6.19--6.23, https://sboyles.github.io/book.pdf.
Stopping always uses the ordinary all-or-nothing FW gap.
"""
import time
import numpy as np
from assignment import exact_step


def solve_conjugate(net, capacity=None, start=None, tolerance=1e-8, max_iter=50000):
    begin = time.perf_counter()
    capacity = net.capacity.copy() if capacity is None else np.asarray(capacity)
    if not np.isfinite(capacity).all() or np.any(capacity <= 0):
        raise ValueError('Capacity must be positive and finite')
    x = net.aon(net.free_time)[0] if start is None else np.asarray(start).copy()
    if np.min(x) < -1e-9 or net.balance_error(x) > 1e-6:
        raise ValueError('Initial flow is infeasible')
    previous_target = None
    history = []
    conjugate_steps = 0
    for iteration in range(max_iter + 1):
        costs = net.times(x, capacity)
        y, shortest = net.aon(costs)
        tstt = float(x @ costs)
        absolute_gap = float(costs @ (x - y))
        gap = max(0., absolute_gap / max(tstt, 1e-30))
        if absolute_gap < -1e-6 * max(1., tstt):
            raise ArithmeticError('Negative FW gap')
        if iteration % 100 == 0 or gap <= tolerance or iteration == max_iter:
            history.append(dict(iteration=iteration, relative_gap=gap, tstt=tstt,
                                beckmann=net.beckmann(x, capacity)))
        if gap <= tolerance or iteration == max_iter:
            return dict(flow=x, times=costs, capacity=capacity, tstt=tstt,
                        shortest_total_cost=shortest, relative_gap=gap,
                        absolute_fw_gap=absolute_gap, iterations=iteration,
                        converged=gap <= tolerance, conservation_max_abs=net.balance_error(x),
                        elapsed_seconds=time.perf_counter()-begin,
                        algorithm='conjugate Frank-Wolfe', conjugate_steps=conjugate_steps,
                        history=history)
        target = y
        if previous_target is not None:
            hessian = net.free_time * net.alpha * net.beta * (np.maximum(x, 0) / capacity) ** (net.beta-1) / capacity
            old_direction = previous_target - x
            numerator = float((hessian * old_direction) @ (y - x))
            denominator = float((hessian * old_direction) @ (y - previous_target))
            alpha = np.clip(numerator / denominator, 0., 0.999) if abs(denominator) > 1e-30 else 0.
            target = alpha * previous_target + (1-alpha) * y
            if costs @ (target - x) >= 0:
                target = y
            elif alpha > 0:
                conjugate_steps += 1
        step = exact_step(net, x, target-x, capacity, 1.)
        if step <= 0:
            target = y
            step = exact_step(net, x, target-x, capacity, 1.)
        if step <= 0:
            raise ArithmeticError(f'CFW stalled above tolerance: {gap}')
        x = (1-step)*x + step*target
        previous_target = target if step < 1 - 1e-12 else None
    raise AssertionError('Unreachable')

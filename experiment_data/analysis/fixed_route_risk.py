"""Worst-case risk on fixed routes, including rational primal and dual bounds.

For interior budgets SciPy proposes a solution to the conservative LP.
Fraction arithmetic certifies the resulting bounds independently of the LP tolerance.
"""
from fractions import Fraction as F
from math import isqrt
import time


def check(condition, message):
    if not condition:
        raise ValueError(message)


def support(values, budget):
    out = F(0)
    remaining = max(F(0), budget)
    for value in sorted(values, reverse=True):
        take = min(F(1), remaining)
        out += take * value
        remaining -= take
        if remaining <= 0:
            break
    return out


def project(values, budget):
    a = [max(F(0), min(F(1), F(float(x)).limit_denominator(10**8))) for x in values]
    total = sum(a, F(0))
    if total > budget:
        a = [x * budget / total for x in a]
    return a


def risk_input(data, routes):
    arcs = sorted({e for p in routes for e in zip(p, p[1:])})
    index = {e: i for i, e in enumerate(arcs)}
    terms = []
    nominal = F(0)
    effective = set()
    for s, p in enumerate(routes):
        _, n, k, _ = data["tasks"][s]
        for e in zip(p, p[1:]):
            r, q = data["edges"][e][4][s if data["specific"] else 0]
            terms.append((s, index[e], n, k, r, q))
            nominal += n * r
            if q * (n + k) > 0:
                effective.add(e)
    return len(data["tasks"]), arcs, terms, nominal, len(effective)


def evaluate(data, routes, tolerance=F(1, 100000), time_limit=60):
    started = time.monotonic()
    s, arcs, terms, nominal, effective = risk_input(data, routes)
    a = len(arcs)
    gu = min(data["gu"], F(s))
    gv = min(data["gv"], F(a))
    uc = [F(0)] * s
    vc = [F(0)] * a
    analytic = None
    reason = None
    if gu in (0, s):
        u = F(gu == s)
        constant = F(0)
        for i, j, n, k, r, q in terms:
            constant += (n + k * u) * r
            vc[j] += (n + k * u) * q
        analytic = constant + support(vc, gv)
        reason = "DEMAND_BUDGET_ENDPOINT"
    elif gv in (0, a):
        v = F(gv == a)
        constant = F(0)
        for i, j, n, k, r, q in terms:
            constant += n * (r + q * v)
            uc[i] += k * (r + q * v)
        analytic = constant + support(uc, gu)
        reason = "ROAD_BUDGET_ENDPOINT"
    elif not any(k * q for _, _, _, k, _, q in terms):
        for i, j, n, k, r, q in terms:
            uc[i] += k * r
            vc[j] += n * q
        analytic = nominal + support(uc, gu) + support(vc, gv)
        reason = "NO_INTERACTION"
    out = dict(
        status="CERTIFIED",
        method="EXACT_FRACTION_ANALYTICAL",
        analytic_reason=reason,
        nominal_risk=float(nominal),
        exact_nominal_risk=str(nominal),
        route_arc_count=a,
        effective_deviation_arc_count=effective,
        road_budget=float(data["gv"]),
        road_budget_covers_route_arcs=data["gv"] >= a,
    )
    if analytic is not None:
        out.update(
            lower_bound=float(analytic),
            upper_bound=float(analytic),
            exact_lower_bound=str(analytic),
            exact_upper_bound=str(analytic),
            gap=0.0,
            seconds=time.monotonic() - started,
        )
        return out
    import numpy as np
    from scipy.optimize import linprog
    from scipy.sparse import coo_matrix

    for i, j, n, k, r, q in terms:
        uc[i] += k * r
        vc[j] += n * q
    active = [t for t in terms if t[3] * t[5] > 0]
    c = uc + vc + [k * q for _, _, _, k, _, q in active]
    nv = len(c)
    rows = []
    cols = []
    values = []
    for j in range(s):
        rows.append(0)
        cols.append(j)
        values.append(1.0)
    for j in range(a):
        rows.append(1)
        cols.append(s + j)
        values.append(1.0)
    for h, (i, j, *_) in enumerate(active):
        z = s + a + h
        rows.extend([2 + 2 * h, 2 + 2 * h, 3 + 2 * h, 3 + 2 * h])
        cols.extend([z, i, z, s + j])
        values.extend([1.0, -1.0, 1.0, -1.0])
    matrix = coo_matrix((values, (rows, cols)), shape=(2 + 2 * len(active), nv)).tocsr()
    rhs = [gu, gv] + [F(0)] * (2 * len(active))
    scale = max(c)
    result = linprog(
        -np.array([float(x / scale) for x in c]),
        A_ub=matrix,
        b_ub=np.array([float(x) for x in rhs]),
        bounds=(0, 1),
        method="highs",
        options={
            "time_limit": time_limit,
            "primal_feasibility_tolerance": 1e-10,
            "dual_feasibility_tolerance": 1e-10,
        },
    )
    if result.x is None or result.ineqlin.marginals is None:
        out.update(
            status="UNRESOLVED",
            method="HIGHS_WITHOUT_CERTIFICATE",
            solver_status=int(result.status),
            message=result.message,
            lower_bound=float(nominal),
            upper_bound=None,
            gap=None,
            seconds=time.monotonic() - started,
        )
        return out
    u = project(result.x[:s], gu)
    v = project(result.x[s : s + a], gv)
    lower = nominal + sum(
        (k * r * u[i] + n * q * v[j] + k * q * min(u[i], v[j]) for i, j, n, k, r, q in terms), F(0)
    )
    # For max c.z with Az<=b, 0<=z<=1: any y>=0 and
    # t_j=max(0,c_j-(A^T y)_j) gives a feasible dual (y,t).
    y = [
        max(F(0), F(float(-value)).limit_denominator(10**8) * scale)
        for value in result.ineqlin.marginals
    ]
    at = [y[0]] * s + [y[1]] * a + [F(0)] * len(active)
    for h, (i, j, *_) in enumerate(active):
        yu, yv = y[2 + 2 * h], y[3 + 2 * h]
        at[i] -= yu
        at[s + j] -= yv
        at[s + a + h] = yu + yv
    residual = [max(F(0), cj - aj) for cj, aj in zip(c, at)]
    upper = nominal + sum((bi * yi for bi, yi in zip(rhs, y)), F(0)) + sum(residual, F(0))
    assert lower <= upper, "Exact primal/dual contradiction"
    gap = upper - lower
    out.update(
        status="CERTIFIED" if gap <= tolerance else "BOUND_ONLY",
        method="HIGHS_CANDIDATES_EXACT_FRACTION_PRIMAL_DUAL",
        solver_status=int(result.status),
        message=result.message,
        lower_bound=float(lower),
        upper_bound=float(upper),
        exact_lower_bound=str(lower),
        exact_upper_bound=str(upper),
        gap=float(gap),
        primal_u=list(map(str, u)),
        primal_v=list(map(str, v)),
        dual_y=list(map(str, y)),
        dual_upper_residual=list(map(str, residual)),
        arcs=[list(e) for e in arcs],
        seconds=time.monotonic() - started,
    )
    return out


def original_exact(data, routes):
    """Budget endpoints or a single nonzero demand vertex, exact rational arithmetic."""
    s, arcs, terms, nominal, effective = risk_input(data, routes)
    a = len(arcs)
    gu = min(data["gu"], F(s))
    gv = min(data["gv"], F(a))
    if gv in (0, a):
        v = F(gv == a)
        const = sum((n * (r + q * v) for _, _, n, _, r, q in terms), F(0))
        coeff = [F(0)] * s
        for i, j, n, k, r, q in terms:
            coeff[i] += k * (r + q * v)
        exact = const + support(coeff, gu)
        evidence = dict(
            method="EXACT_FRACTION_ROAD_BUDGET_ENDPOINT",
            demand_coefficients=list(map(str, coeff)),
            constant=str(const),
            road_deviation=str(v),
        )
    elif 0 <= gu <= 1:
        au = [F(0)] * s
        bv = [F(0)] * a
        cross = {}
        for i, j, n, k, r, q in terms:
            au[i] += k * r
            bv[j] += n * q
            cross[i, j] = cross.get((i, j), F(0)) + k * q
        values = []
        vertex_evidence = []
        for task in range(s):
            vc = [bv[j] + gu * cross.get((task, j), F(0)) for j in range(a)]
            order = sorted(range(a), key=lambda j: (-vc[j], j))
            remaining = gv
            v = [F(0)] * a
            for j in order:
                v[j] = min(F(1), remaining)
                remaining -= v[j]
                if remaining <= 0:
                    break
            value = nominal + gu * au[task] + sum((v[j] * vc[j] for j in range(a)), F(0))
            values.append(value)
            vertex_evidence.append(
                dict(
                    task=task,
                    value=str(value),
                    demand_deviation=str(gu),
                    road_scenario=[
                        dict(arc=list(arcs[j]), deviation=str(z)) for j, z in enumerate(v) if z
                    ],
                )
            )
        exact = max(values, default=nominal)
        evidence = dict(
            method="EXACT_FRACTION_DEMAND_VERTEX_ENUMERATION",
            demand_vertex_count=s,
            maximizing_tasks=[i for i, x in enumerate(values) if x == exact],
            vertices=vertex_evidence,
            demand_coefficients=list(map(str, au)),
            shared_road_coefficients=list(map(str, bv)),
            arcs=[list(x) for x in arcs],
        )
    else:
        raise ValueError(
            "Unsupported exact original-risk audit budget; do not silently approximate"
        )
    evidence.update(
        status="CERTIFIED_EXACT",
        gamma_u=str(data["gu"]),
        gamma_v=str(data["gv"]),
        exact_nominal_risk=str(nominal),
        nominal_risk=float(nominal),
        route_arc_count=a,
        effective_deviation_arc_count=effective,
        exact_value=str(exact),
        lower_bound=float(exact),
        upper_bound=float(exact),
    )
    return evidence


def sqrt_interval(x, scale=10**40):
    check(x >= 0, "Negative square root argument")
    k = isqrt(x.numerator * scale * scale // x.denominator)
    lo = F(k, scale)
    return lo, lo if lo * lo == x else F(k + 1, scale)


def theoretical_factor(data):
    """Uniform factor over ALL physical task-arc pairs; square roots rationally enclosed."""
    lower = upper = F(1)
    witness = None
    interactions = 0
    min_n = min_r = None
    for task, (_, n, k, _) in enumerate(data["tasks"]):
        for arc, e in data["edges"].items():
            r, q = e[4][task if data["specific"] else 0]
            check(min(n, k, r, q) >= 0, "Negative risk parameter")
            if not k * q:
                continue
            check(n > 0 and r > 0, "Finite theory factor assumptions fail")
            interactions += 1
            min_n = n if min_n is None else min(min_n, n)
            min_r = r if min_r is None else min(min_r, r)
            delta, zeta = k / n, q / r
            sl, su = sqrt_interval((1 + delta) * (1 + zeta))
            base = 2 + delta + zeta
            cl = 1 + delta * zeta / (base + 2 * su)
            cu = 1 + delta * zeta / (base + 2 * sl)
            lower = max(lower, cl)
            if cu > upper:
                upper = cu
                witness = dict(
                    task=task,
                    arc=list(arc),
                    delta=str(delta),
                    zeta=str(zeta),
                    pair_lower=str(cl),
                    pair_upper=str(cu),
                )
    return dict(
        status="CERTIFIED_RATIONAL_ENCLOSURE",
        scope="ALL_PHYSICAL_TASK_ARC_PAIRS",
        task_arc_pairs=len(data["tasks"]) * len(data["edges"]),
        positive_interaction_pairs=interactions,
        min_N=float(min_n) if min_n is not None else None,
        min_R=float(min_r) if min_r is not None else None,
        factor_lower=float(lower),
        factor_upper=float(upper),
        exact_factor_lower=str(lower),
        exact_factor_upper=str(upper),
        factor_enclosure_width=float(upper - lower),
        maximizing_pair=witness,
    )

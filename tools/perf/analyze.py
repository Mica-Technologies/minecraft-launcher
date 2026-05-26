#!/usr/bin/env python3
"""Aggregate Mica Minecraft Launcher cold-start measurement CSVs.

Reads one or two CSV files produced by measure-cold-start.ps1 + the
in-launcher ColdStartProfiler. Prints per-phase stats (median, p90,
min, max, stdev) and, when two files are given, a comparison table
showing the delta per phase.

Usage:
    python analyze.py baseline.csv
    python analyze.py baseline.csv cds.csv

The CSV is long-format: one row per (run_id, phase) with an offset_ms
column. Phase order within a run is the insertion order in which the
launcher called ColdStartProfiler.mark().
"""

import csv
import statistics
import sys
from collections import defaultdict
from pathlib import Path


def load_runs(csv_path):
    """Returns a list of dicts keyed by run_id, each holding phase -> offset_ms.

    Phase order within a run is preserved via a parallel ordered list.
    Also returns the canonical phase order across the file (first run wins).
    """
    runs = defaultdict(dict)
    phase_order = []
    seen_phases = set()
    metadata = {}

    with open(csv_path, newline="", encoding="utf-8") as fh:
        reader = csv.DictReader(fh)
        for row in reader:
            run_id = row["run_id"]
            phase = row["phase"]
            offset_ms = int(row["offset_ms"])
            runs[run_id][phase] = offset_ms
            if phase not in seen_phases:
                phase_order.append(phase)
                seen_phases.add(phase)
            # Capture one row of metadata for the file (jdk/os/cds_enabled).
            if "jdk_version" not in metadata:
                metadata["jdk_version"] = row.get("jdk_version", "")
                metadata["os"] = row.get("os", "")
                metadata["arch"] = row.get("arch", "")
                metadata["cds_enabled"] = row.get("cds_enabled", "")

    return list(runs.values()), phase_order, metadata


def compute_inter_phase_deltas(runs, phase_order):
    """Returns dict of phase -> list of inter-phase delta_ms across runs.

    The first phase's "delta" is its absolute offset (i.e. distance from
    JVM class-load). Subsequent phases are computed as offset[i] - offset[i-1].
    """
    deltas = defaultdict(list)
    for run in runs:
        prev = 0
        for phase in phase_order:
            if phase not in run:
                continue
            d = run[phase] - prev
            deltas[phase].append(d)
            prev = run[phase]
    return deltas


def compute_totals(runs, phase_order):
    """Total cold-start time per run, taking the LAST captured phase as endpoint."""
    totals = []
    last_phase = phase_order[-1] if phase_order else None
    if not last_phase:
        return totals
    for run in runs:
        if last_phase in run:
            totals.append(run[last_phase])
    return totals


def stats_summary(samples):
    if not samples:
        return None
    sorted_s = sorted(samples)
    n = len(sorted_s)
    return {
        "n": n,
        "min": sorted_s[0],
        "max": sorted_s[-1],
        "median": statistics.median(sorted_s),
        "mean": statistics.fmean(sorted_s),
        "p90": sorted_s[max(0, int(round(n * 0.9)) - 1)] if n >= 1 else 0,
        "stdev": statistics.pstdev(sorted_s) if n > 1 else 0.0,
    }


def fmt_ms(v):
    if v is None:
        return "    -"
    return f"{v:>5.0f} ms"


def fmt_pct(delta_pct):
    if delta_pct is None:
        return "       "
    sign = "+" if delta_pct >= 0 else ""
    return f"{sign}{delta_pct:>5.1f}%"


def print_header(title):
    print()
    print(f"--- {title} ---")
    print()


def print_single_file_summary(label, runs, phase_order, deltas, totals):
    n_runs = len(runs)
    print(f"File: {label}    runs={n_runs}")
    print(f"{'Phase':<24} {'n':>4} {'median':>10} {'p90':>10} {'min':>10} {'max':>10} {'stdev':>10}")
    print("-" * 86)
    for phase in phase_order:
        s = stats_summary(deltas.get(phase, []))
        if not s:
            continue
        print(f"{phase:<24} {s['n']:>4} {s['median']:>7.0f} ms {s['p90']:>7.0f} ms "
              f"{s['min']:>7.0f} ms {s['max']:>7.0f} ms {s['stdev']:>7.1f} ms")
    print("-" * 86)
    total_stats = stats_summary(totals)
    if total_stats:
        print(f"{'TOTAL (cold->paint)':<24} {total_stats['n']:>4} "
              f"{total_stats['median']:>7.0f} ms {total_stats['p90']:>7.0f} ms "
              f"{total_stats['min']:>7.0f} ms {total_stats['max']:>7.0f} ms "
              f"{total_stats['stdev']:>7.1f} ms")


def print_comparison(a_label, b_label, a_deltas, b_deltas, a_totals, b_totals, phase_order):
    print(f"{'Phase':<24} {'baseline':>12} {'treatment':>12} {'delta':>12} {'pct':>10}")
    print("-" * 76)

    def diff_row(name, a_samples, b_samples):
        a = stats_summary(a_samples)
        b = stats_summary(b_samples)
        if not a or not b:
            return
        delta = b["median"] - a["median"]
        pct = (delta / a["median"] * 100.0) if a["median"] != 0 else 0.0
        marker = ""
        if abs(pct) >= 10:
            marker = "  *" if pct < 0 else "  !"
        print(f"{name:<24} {a['median']:>9.0f} ms {b['median']:>9.0f} ms "
              f"{delta:>+9.0f} ms {fmt_pct(pct)}{marker}")

    for phase in phase_order:
        diff_row(phase, a_deltas.get(phase, []), b_deltas.get(phase, []))

    print("-" * 76)
    diff_row("TOTAL (cold->paint)", a_totals, b_totals)
    print()
    print("Legend:  '*' = >=10% faster  '!' = >=10% slower")


def main(argv):
    if len(argv) < 2 or len(argv) > 3:
        print(__doc__)
        sys.exit(1)

    a_path = Path(argv[1])
    runs_a, order_a, meta_a = load_runs(a_path)
    deltas_a = compute_inter_phase_deltas(runs_a, order_a)
    totals_a = compute_totals(runs_a, order_a)

    print_header(f"BASELINE  ({a_path.name})")
    print(f"  jdk={meta_a.get('jdk_version','?')}  os={meta_a.get('os','?')}  "
          f"arch={meta_a.get('arch','?')}  cds={meta_a.get('cds_enabled','?')}")
    print()
    print_single_file_summary(a_path.name, runs_a, order_a, deltas_a, totals_a)

    if len(argv) == 3:
        b_path = Path(argv[2])
        runs_b, order_b, meta_b = load_runs(b_path)
        deltas_b = compute_inter_phase_deltas(runs_b, order_b)
        totals_b = compute_totals(runs_b, order_b)

        print_header(f"TREATMENT  ({b_path.name})")
        print(f"  jdk={meta_b.get('jdk_version','?')}  os={meta_b.get('os','?')}  "
              f"arch={meta_b.get('arch','?')}  cds={meta_b.get('cds_enabled','?')}")
        print()
        print_single_file_summary(b_path.name, runs_b, order_b, deltas_b, totals_b)

        # Use baseline's phase order for the comparison.
        print_header("COMPARISON  (treatment vs baseline)")
        print_comparison(a_path.name, b_path.name,
                         deltas_a, deltas_b,
                         totals_a, totals_b,
                         order_a)


if __name__ == "__main__":
    main(sys.argv)

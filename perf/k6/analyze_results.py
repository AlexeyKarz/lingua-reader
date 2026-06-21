#!/usr/bin/env python3

from __future__ import annotations

import argparse
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd


DEFAULT_INPUT = Path("perf/k6/results/comparison.csv")
DEFAULT_OUTPUT_DIR = Path("perf/k6/results/analysis")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Aggregate merged k6 benchmark CSV results and generate simple summary plots."
    )
    parser.add_argument(
        "--input",
        type=Path,
        default=DEFAULT_INPUT,
        help=f"Path to merged comparison CSV (default: {DEFAULT_INPUT})",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
        help=f"Directory for generated CSVs and plots (default: {DEFAULT_OUTPUT_DIR})",
    )
    return parser.parse_args()


def load_results(path: Path) -> pd.DataFrame:
    df = pd.read_csv(path)
    numeric_columns = [
        "vus",
        "fixture_count",
        "http_reqs_count",
        "http_req_failed_rate",
        "http_req_duration_med_ms",
        "http_req_duration_p95_ms",
        "iteration_duration_med_ms",
        "iteration_duration_p95_ms",
        "checks_rate",
    ]
    for column in numeric_columns:
        if column in df.columns:
            df[column] = pd.to_numeric(df[column], errors="coerce")
    return df


def summarize(df: pd.DataFrame) -> pd.DataFrame:
    summary = (
        df.groupby(["architecture", "provider", "mode", "vus", "duration", "fixtures"], dropna=False)
        .agg(
            runs=("run_label", "nunique"),
            http_reqs_avg=("http_reqs_count", "mean"),
            http_req_failed_rate_avg=("http_req_failed_rate", "mean"),
            http_req_duration_med_ms_median=("http_req_duration_med_ms", "median"),
            http_req_duration_med_ms_min=("http_req_duration_med_ms", "min"),
            http_req_duration_med_ms_max=("http_req_duration_med_ms", "max"),
            http_req_duration_p95_ms_median=("http_req_duration_p95_ms", "median"),
            http_req_duration_p95_ms_min=("http_req_duration_p95_ms", "min"),
            http_req_duration_p95_ms_max=("http_req_duration_p95_ms", "max"),
            iteration_duration_med_ms_median=("iteration_duration_med_ms", "median"),
            iteration_duration_p95_ms_median=("iteration_duration_p95_ms", "median"),
            checks_rate_avg=("checks_rate", "mean"),
        )
        .reset_index()
        .sort_values(["provider", "mode", "architecture", "vus", "duration"])
    )
    return summary


def write_summary(summary: pd.DataFrame, output_dir: Path) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    target = output_dir / "summary_by_mode.csv"
    summary.to_csv(target, index=False)
    return target


def plot_metric(
    summary: pd.DataFrame,
    metric: str,
    title: str,
    target: Path,
    metric_min: str | None = None,
    metric_max: str | None = None,
) -> None:
    modes = list(dict.fromkeys(summary["mode"].tolist()))
    architectures = list(dict.fromkeys(summary["architecture"].tolist()))

    n_archs = len(architectures)
    x = np.arange(len(modes))
    width = 0.8 / n_archs
    offsets = np.linspace(-(n_archs - 1) / 2, (n_archs - 1) / 2, n_archs) * width
    colors = plt.rcParams["axes.prop_cycle"].by_key()["color"]

    fig, ax = plt.subplots(figsize=(9, 5))

    for i, arch in enumerate(architectures):
        arch_rows = summary[summary["architecture"] == arch].set_index("mode")
        values = np.array([
            arch_rows.loc[m, metric] if m in arch_rows.index else np.nan
            for m in modes
        ])

        yerr = None
        if metric_min and metric_max:
            mins = np.array([
                arch_rows.loc[m, metric_min] if m in arch_rows.index else np.nan
                for m in modes
            ])
            maxs = np.array([
                arch_rows.loc[m, metric_max] if m in arch_rows.index else np.nan
                for m in modes
            ])
            yerr = [values - mins, maxs - values]  # asymmetric: [lower, upper]

        ax.bar(
            x + offsets[i],
            values,
            width,
            label=arch,
            color=colors[i % len(colors)],
            yerr=yerr,
            capsize=5,
            error_kw=dict(ecolor="black", lw=1.2, capthick=1.2),
        )

    ax.set_title(title)
    ax.set_xlabel("Scenario")
    ax.set_ylabel("Milliseconds")
    ax.set_xticks(x)
    ax.set_xticklabels(modes, rotation=0)
    ax.grid(axis="y", linestyle="--", alpha=0.35)
    ax.legend(title="Architecture")
    plt.tight_layout()
    plt.savefig(target, dpi=160)
    plt.close()


def build_report(summary: pd.DataFrame, output_dir: Path) -> None:
    for provider in summary["provider"].dropna().unique():
        provider_summary = summary[summary["provider"] == provider]
        suffix = provider.replace("/", "-")
        plot_metric(
            provider_summary,
            "http_req_duration_p95_ms_median",
            f"k6 median p95 request latency by scenario ({provider})",
            output_dir / f"http_req_p95_{suffix}.png",
            metric_min="http_req_duration_p95_ms_min",
            metric_max="http_req_duration_p95_ms_max",
        )
        plot_metric(
            provider_summary,
            "http_req_duration_med_ms_median",
            f"k6 median request latency by scenario ({provider})",
            output_dir / f"http_req_median_{suffix}.png",
            metric_min="http_req_duration_med_ms_min",
            metric_max="http_req_duration_med_ms_max",
        )


def main() -> None:
    args = parse_args()
    df = load_results(args.input)
    if df.empty:
        raise SystemExit(f"No rows found in {args.input}")
    summary = summarize(df)
    output_dir = args.output_dir
    target = write_summary(summary, output_dir)
    build_report(summary, output_dir)
    print(f"Wrote summary CSV: {target}")
    print(f"Wrote charts to: {output_dir}")


if __name__ == "__main__":
    main()

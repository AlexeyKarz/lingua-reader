#!/usr/bin/env python3

from __future__ import annotations

import argparse
from pathlib import Path

import matplotlib.pyplot as plt
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
            http_req_duration_p95_ms_median=("http_req_duration_p95_ms", "median"),
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


def plot_metric(summary: pd.DataFrame, metric: str, title: str, target: Path) -> None:
    modes = list(dict.fromkeys(summary["mode"].tolist()))
    architectures = list(dict.fromkeys(summary["architecture"].tolist()))

    pivot = summary.pivot_table(index="mode", columns="architecture", values=metric, aggfunc="first")
    pivot = pivot.reindex(index=modes, columns=architectures)

    ax = pivot.plot(kind="bar", figsize=(9, 5), rot=0)
    ax.set_title(title)
    ax.set_xlabel("Scenario")
    ax.set_ylabel("Milliseconds")
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
        )
        plot_metric(
            provider_summary,
            "http_req_duration_med_ms_median",
            f"k6 median request latency by scenario ({provider})",
            output_dir / f"http_req_median_{suffix}.png",
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

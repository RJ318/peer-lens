"""Metric catalog and value formatting shared by the charts and the table."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

Kind = Literal["currency", "percent", "ratio"]


@dataclass(frozen=True)
class Metric:
    key: str
    label: str
    kind: Kind
    description: str


METRICS: dict[str, Metric] = {
    m.key: m
    for m in (
        Metric("revenue", "Revenue", "currency", "Total revenue for the fiscal year (net revenue for banks)."),
        Metric("netIncome", "Net income", "currency", "Net income attributable to the company."),
        Metric("operatingCashFlow", "Operating cash flow", "currency", "Net cash provided by operating activities."),
        Metric("netMargin", "Net margin", "percent", "Net income divided by revenue."),
        Metric(
            "revenueGrowth",
            "Revenue growth",
            "percent",
            "Change versus the prior fiscal year, measured within one filing so restatements do not distort it.",
        ),
        Metric("cashConversion", "Cash conversion", "ratio", "Operating cash flow divided by net income."),
    )
}

DEFAULT_METRIC = "netMargin"

# Caveats shown under the charts when a metric is misleading for a sector.
SECTOR_CAVEATS: dict[tuple[str, str], str] = {
    ("banks", "operatingCashFlow"): (
        "Bank operating cash flow swings with trading and lending activity, "
        "so it says little about earnings quality for banks."
    ),
    ("banks", "cashConversion"): (
        "Cash conversion is not meaningful for banks, because their operating cash flow "
        "moves with trading and lending activity rather than with earnings."
    ),
    ("banks", "revenue"): ("Bank revenue is net revenue: interest income minus interest expense, plus fee income."),
}


def caveat_for(sector: str | None, metric: str) -> str | None:
    if sector is None:
        return None
    return SECTOR_CAVEATS.get((sector, metric))


def format_value(kind: Kind, value: float | None) -> str:
    """Format a metric value for people: $394.3B, 24.3%, 1.12x. Missing values read n/a."""
    if value is None:
        return "n/a"
    if kind == "percent":
        return f"{value * 100:.1f}%"
    if kind == "ratio":
        return f"{value:.2f}x"
    sign = "-" if value < 0 else ""
    magnitude = abs(value)
    for threshold, suffix in ((1e12, "T"), (1e9, "B"), (1e6, "M")):
        if magnitude >= threshold:
            return f"{sign}${magnitude / threshold:.1f}{suffix}"
    return f"{sign}${magnitude:,.0f}"


def format_metric(metric_key: str, value: float | None) -> str:
    return format_value(METRICS[metric_key].kind, value)

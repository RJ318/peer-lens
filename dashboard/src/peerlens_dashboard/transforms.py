"""Pure functions that turn API payloads into chart- and table-ready frames."""

from __future__ import annotations

from typing import Any

import pandas as pd

from .metrics import format_metric

COMPARISON_COLUMNS = ["ticker", "name", "fiscalYear", "periodEnd", "value", "display", "sourceUrl"]
HISTORY_COLUMNS = ["ticker", "name", "fiscalYear", "periodEnd", "value"]


def comparison_frame(rows: list[dict[str, Any]], metric: str) -> pd.DataFrame:
    """One row per company for the latest fiscal year, highest value first, missing values last."""
    records = [
        {
            "ticker": row["ticker"],
            "name": row["name"],
            "fiscalYear": row.get("fiscalYear"),
            "periodEnd": row.get("periodEnd"),
            "value": row.get(metric),
            "display": format_metric(metric, row.get(metric)),
            "sourceUrl": _source_url(row, metric),
        }
        for row in rows
    ]
    frame = pd.DataFrame.from_records(records, columns=COMPARISON_COLUMNS)
    frame["value"] = pd.to_numeric(frame["value"], errors="coerce")
    frame["fiscalYear"] = pd.to_numeric(frame["fiscalYear"], errors="coerce").astype("Int64")
    # pandas turns a missing link into NaN, which is truthy; keep it a real None so no broken link renders.
    frame["sourceUrl"] = pd.Series(
        [url if isinstance(url, str) else None for url in frame["sourceUrl"]], index=frame.index, dtype=object
    )
    return frame.sort_values("value", ascending=False, na_position="last").reset_index(drop=True)


def history_frame(companies: list[dict[str, Any]], metric: str) -> pd.DataFrame:
    """Long format (one row per company per year) with missing values dropped, oldest year first."""
    records = [
        {
            "ticker": company["ticker"],
            "name": company["name"],
            "fiscalYear": year.get("fiscalYear"),
            "periodEnd": year.get("periodEnd"),
            "value": year.get(metric),
        }
        for company in companies
        for year in company.get("years", [])
    ]
    frame = pd.DataFrame.from_records(records, columns=HISTORY_COLUMNS)
    frame["value"] = pd.to_numeric(frame["value"], errors="coerce")
    frame["fiscalYear"] = pd.to_numeric(frame["fiscalYear"], errors="coerce").astype("Int64")
    frame = frame.dropna(subset=["value"])
    return frame.sort_values(["ticker", "fiscalYear"]).reset_index(drop=True)


def _source_url(row: dict[str, Any], metric: str) -> str | None:
    """Link to the filing behind the metric. Derived metrics trace back to the revenue filing."""
    sources = row.get("sources") or {}
    source = sources.get(metric) or sources.get("revenue") or sources.get("netIncome")
    return source.get("url") if source else None

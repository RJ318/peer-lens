"""Export a snapshot of the Peer Lens API for the static demo site.

Usage (with the API running and data loaded):
    python scripts/export_site_data.py --api http://localhost:8080 --out site/data/peer-lens.json

The site is static (GitHub Pages), so it ships this snapshot instead of calling the API live.
Only the fields the site displays are kept, which keeps the file small.
"""

from __future__ import annotations

import argparse
import json
import urllib.request
from datetime import UTC, datetime
from pathlib import Path

FIELDS = ["revenue", "netIncome", "operatingCashFlow", "netMargin", "revenueGrowth", "cashConversion"]
SOURCE_KEYS = ["revenue", "netIncome", "operatingCashFlow", "revenueGrowth"]


def get(api: str, path: str):
    with urllib.request.urlopen(f"{api}{path}", timeout=30) as response:
        return json.load(response)


def source(src: dict | None) -> dict | None:
    if not src:
        return None
    return {"form": src.get("form"), "filed": src.get("filed"), "url": src.get("url"), "concept": src.get("concept")}


def export(api: str) -> dict:
    sectors = []
    for sector in get(api, "/api/sectors"):
        history = get(api, f"/api/sectors/{sector['key']}/history")
        companies = []
        for company in history["companies"]:
            years = []
            for year in company["years"]:
                row = {"fy": year["fiscalYear"], "end": year["periodEnd"]}
                row.update({field: year.get(field) for field in FIELDS})
                sources = year.get("sources") or {}
                row["src"] = {key: source(sources.get(key)) for key in SOURCE_KEYS if sources.get(key)}
                years.append(row)
            companies.append({"ticker": company["ticker"], "name": company["name"], "years": years})
        sectors.append({"key": sector["key"], "label": sector["label"], "companies": companies})
    return {
        "generatedAt": datetime.now(UTC).strftime("%Y-%m-%d"),
        "source": "SEC EDGAR XBRL company facts, via the Peer Lens API",
        "sectors": sectors,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--api", default="http://localhost:8080")
    parser.add_argument("--out", default="site/data/peer-lens.json")
    args = parser.parse_args()
    data = export(args.api.rstrip("/"))
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(data, separators=(",", ":")), encoding="utf-8")
    companies = sum(len(s["companies"]) for s in data["sectors"])
    print(f"Wrote {out} ({out.stat().st_size:,} bytes): {len(data['sectors'])} peer groups, {companies} companies")


if __name__ == "__main__":
    main()

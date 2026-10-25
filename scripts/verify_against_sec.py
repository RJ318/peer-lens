"""Independent check of the Peer Lens API against the SEC's own data, using different mechanisms than the Java code.

Usage (with the API running and data loaded):
    SEC_USER_AGENT="Your Name you@example.com" python scripts/verify_against_sec.py --api http://localhost:8080


1. Levels: for each value the API returns, look up the fact the SEC itself selected for that concept and
   period (the one carrying a "frame" label, which the SEC assigns to the latest-filed value). The Java code
   never reads the frame field, so this is an independent de-duplication.
2. Concept choice: flag any year where another configured revenue concept has a larger SEC-framed value for
   the same period, which would mean a component was picked instead of the total.
3. Growth: recompute each year's growth from the 10-K that covers the following fiscal year (or, for the
   latest year, that year's own 10-K), identifying each filing's own year as the latest period it reports.
4. Labels: fiscal years must be unique per company.
"""

import argparse
import json
import os
import sys
import time
import urllib.request
from datetime import date

parser = argparse.ArgumentParser(description="Verify Peer Lens output against SEC EDGAR")
parser.add_argument("--api", default="http://localhost:8080")
args = parser.parse_args()
API = args.api.rstrip("/")
if "@" not in os.environ.get("SEC_USER_AGENT", ""):
    sys.exit('Set SEC_USER_AGENT to your name and email, for example SEC_USER_AGENT="Jane Doe jane@example.com"')
UA = {"User-Agent": os.environ["SEC_USER_AGENT"]}
REV = ["Revenues", "RevenueFromContractWithCustomerExcludingAssessedTax", "SalesRevenueNet",
       "RevenueFromContractWithCustomerIncludingAssessedTax"]
BANK_REV = ["RevenuesNetOfInterestExpense", "Revenues"]
OTHER = {"netIncome": ["NetIncomeLoss"],
         "operatingCashFlow": ["NetCashProvidedByUsedInOperatingActivities",
                               "NetCashProvidedByUsedInOperatingActivitiesContinuingOperations"]}


def get(url, headers=None):
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers or {}), timeout=60) as r:
        return json.load(r)


def annual(facts):
    return [f for f in facts if f.get("start") and f.get("form", "").startswith("10-K")
            and 350 <= (date.fromisoformat(f["end"]) - date.fromisoformat(f["start"])).days <= 380]


problems, checked = [], {"levels": 0, "growth": 0}
for sector in get(f"{API}/api/sectors"):
    rev_concepts = BANK_REV if sector["key"] == "banks" else REV
    for company in get(f"{API}/api/sectors/{sector['key']}/history")["companies"]:
        t = company["ticker"]
        cik = get(f"{API}/api/companies/{t}")["cik"]
        gaap = get(f"https://data.sec.gov/api/xbrl/companyfacts/CIK{cik}.json", UA)["facts"]["us-gaap"]
        time.sleep(0.2)
        facts = {c: annual(gaap.get(c, {}).get("units", {}).get("USD", [])) for c in set(rev_concepts) | {
            c for cs in OTHER.values() for c in cs}}

        def framed(concept, end):
            hits = [f for f in facts.get(concept, []) if f["end"] == end and f.get("frame")]
            return hits[0]["val"] if hits else None

        labels = [y["fiscalYear"] for y in company["years"]]
        if len(labels) != len(set(labels)):
            problems.append(f"{t}: duplicate fiscal year labels {labels}")

        ends = [y["periodEnd"] for y in company["years"]]
        for i, y in enumerate(company["years"]):
            pe = y["periodEnd"]
            for metric in ("revenue", "netIncome", "operatingCashFlow"):
                src = (y["sources"] or {}).get(metric)
                if y[metric] is None or not src:
                    problems.append(f"{t} {pe}: {metric} missing")
                    continue
                expected = framed(src["concept"], pe)
                if expected is None:
                    continue  # SEC assigned no frame (rare); level not checkable this way
                checked["levels"] += 1
                if abs(expected - y[metric]) > 1:
                    problems.append(f"{t} {pe} {metric}: API {y[metric]:,} vs SEC-framed {expected:,}")
            # Concept choice: another revenue concept bigger by more than 0.5%?
            for c in rev_concepts:
                other = framed(c, pe)
                if other and y["revenue"] and other > y["revenue"] * 1.005:
                    problems.append(f"{t} {pe}: {c} = {other:,} exceeds chosen revenue {y['revenue']:,}")
            # Growth from the 10-K that covers the next fiscal year, else this year's own 10-K.
            g_src = (y["sources"] or {}).get("revenueGrowth")
            if y["revenueGrowth"] is None or not g_src:
                continue
            concept = g_src["concept"]
            by_accn = {}
            for f in facts.get(concept, []):
                by_accn.setdefault(f["accn"], {})[f["end"]] = f["val"]
            own_year = {a: max(v) for a, v in by_accn.items()}
            target = ends[i + 1] if i + 1 < len(ends) else pe
            accns = [a for a, e in own_year.items() if e == target and pe in by_accn[a]] or \
                    [a for a, e in own_year.items() if e == pe]
            prior = [e for a in accns for e in by_accn[a] if 330 <= (date.fromisoformat(pe) - date.fromisoformat(e)).days <= 400]
            if not accns or not prior:
                continue
            a = sorted(accns)[-1]
            p = [e for e in by_accn[a] if 330 <= (date.fromisoformat(pe) - date.fromisoformat(e)).days <= 400]
            if not p:
                continue
            expected_g = by_accn[a][pe] / by_accn[a][p[0]] - 1
            checked["growth"] += 1
            if abs(expected_g - y["revenueGrowth"]) > 0.00015:
                problems.append(f"{t} {pe} growth: API {y['revenueGrowth']:.4f} vs independent {expected_g:.4f} ({a})")

print(f"Checked {checked['levels']} reported values and {checked['growth']} growth rates.")
print("\nProblems:" if problems else "No problems found.")
for p in problems:
    print(" -", p)
sys.exit(1 if problems else 0)

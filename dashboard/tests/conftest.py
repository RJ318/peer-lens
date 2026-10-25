"""Shared fixtures. Companies and figures here are invented test data, not real filings."""

from __future__ import annotations

import pytest

SECTORS = [
    {"key": "technology", "label": "Technology", "companyCount": 3},
    {"key": "banks", "label": "Banks", "companyCount": 1},
]


def _row(ticker, name, fy, revenue, net_income, ocf, growth):
    margin = net_income / revenue if revenue else None
    conversion = ocf / net_income if net_income and net_income > 0 else None
    url = f"https://www.sec.gov/Archives/edgar/data/1/{ticker}{fy}/"
    return {
        "ticker": ticker,
        "name": name,
        "fiscalYear": fy,
        "periodEnd": f"{fy}-12-31",
        "revenue": revenue,
        "netIncome": net_income,
        "operatingCashFlow": ocf,
        "netMargin": margin,
        "revenueGrowth": growth,
        "cashConversion": conversion,
        "sources": {"revenue": {"form": "10-K", "url": url}},
    }


TECH_HISTORY = {
    "sector": SECTORS[0],
    "companies": [
        {
            "ticker": "AAA",
            "name": "Alpha Systems",
            "years": [
                _row("AAA", "Alpha Systems", 2024, 100e9, 20e9, 25e9, None),
                _row("AAA", "Alpha Systems", 2025, 110e9, 24e9, 26e9, 0.10),
            ],
        },
        {
            "ticker": "BBB",
            "name": "Beta Devices",
            "years": [
                _row("BBB", "Beta Devices", 2024, 50e9, -2e9, 1e9, None),
                _row("BBB", "Beta Devices", 2025, 45e9, 3e9, 4e9, -0.10),
            ],
        },
        {"ticker": "CCC", "name": "Gamma Cloud", "years": []},
    ],
}

TECH_COMPARISON = {
    "sector": SECTORS[0],
    "companies": [
        TECH_HISTORY["companies"][0]["years"][-1],
        TECH_HISTORY["companies"][1]["years"][-1],
        {"ticker": "CCC", "name": "Gamma Cloud", "fiscalYear": None, "periodEnd": None, "sources": {}},
    ],
}


class FakeClient:
    """Stands in for PeerLensClient so tests never touch the network."""

    def __init__(self, error: Exception | None = None):
        self.error = error

    def sectors(self):
        if self.error:
            raise self.error
        return SECTORS

    def comparison(self, sector):
        if self.error:
            raise self.error
        return TECH_COMPARISON

    def history(self, sector):
        if self.error:
            raise self.error
        return TECH_HISTORY


@pytest.fixture
def fake_client():
    return FakeClient()

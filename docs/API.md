# Peer Lens API

Base URL in local development: `http://localhost:8080`. All responses are JSON. Errors use
RFC 9457 problem details (`application/problem+json`).

Monetary values are in US dollars. Ratios are decimals (`0.243` means 24.3%). Any value the
filings do not support is `null` rather than zero.

## Metrics

| Field | Meaning | Source |
|---|---|---|
| `revenue` | Total revenue for the fiscal year (net revenue for banks) | SEC XBRL, sector-specific concept list |
| `netIncome` | Net income attributable to the company | `NetIncomeLoss` |
| `operatingCashFlow` | Net cash provided by operating activities | `NetCashProvidedByUsedInOperatingActivities` |
| `netMargin` | `netIncome / revenue` | Derived |
| `revenueGrowth` | Revenue change versus the prior fiscal year | Measured within the most recent single filing that reports both years, so a restatement is never compared with an unrestated figure. `null` when no filing reports both. Its filing is in `sources.revenueGrowth`. |
| `cashConversion` | `operatingCashFlow / netIncome` | Derived, `null` when net income is zero or negative |

Each reported value is the most recently filed figure for that period. Because restatements
only recast the years in the restating filing, `revenue` for an older year can be on an earlier
accounting basis than a newer year; `revenueGrowth` is not affected by this.

`fiscalYear` is the year of the period end after stepping back seven days, so a 52- or 53-week
year ending in the first days of January is labeled by the year before (a year ending
2023-01-01 is fiscal 2022), while a year ending later in January keeps that year (NVIDIA's year
ending 2026-01-25 is fiscal 2026). `periodEnd` is always returned so the exact period is never
ambiguous.

## Endpoints

### `GET /api/sectors`

```json
[{ "key": "technology", "label": "Technology", "companyCount": 5 }]
```

### `GET /api/sectors/{key}/comparison`

Each company's most recent fiscal year, one row per company.

```json
{
  "sector": { "key": "technology", "label": "Technology", "companyCount": 5 },
  "companies": [
    {
      "ticker": "EXMP",
      "name": "Example Corp",
      "fiscalYear": 2025,
      "periodEnd": "2025-12-31",
      "revenue": 1000000000,
      "netIncome": 200000000,
      "operatingCashFlow": 250000000,
      "netMargin": 0.2,
      "revenueGrowth": 0.1,
      "cashConversion": 1.25,
      "sources": {
        "revenue": {
          "concept": "RevenueFromContractWithCustomerExcludingAssessedTax",
          "form": "10-K",
          "filed": "2026-02-15",
          "accession": "0000000000-26-000001",
          "url": "https://www.sec.gov/Archives/edgar/data/0/000000000026000001/"
        }
      }
    }
  ]
}
```

(Placeholder values that show the shape only.)

### `GET /api/sectors/{key}/history`

Every stored fiscal year for every company in the sector, oldest first.

```json
{
  "sector": { "key": "technology", "label": "Technology", "companyCount": 5 },
  "companies": [
    { "ticker": "AAPL", "name": "Apple Inc.", "years": [ { "fiscalYear": 2021, "...": "same fields as a comparison row" } ] }
  ]
}
```

### `GET /api/companies?sector={key}`

Watchlist companies, optionally filtered by sector.

### `GET /api/companies/{ticker}`

One company with its full annual history.

### `POST /api/admin/ingestions`

Starts a background load from SEC EDGAR. Requires the `X-Admin-Token` header to match the
`PEERLENS_ADMIN_TOKEN` environment variable; when that variable is unset the admin endpoints
are disabled. The run is recorded before the response is sent, so it is immediately visible at
`/api/admin/ingestions/latest`. Returns `202 Accepted` with
`{"runId": 2, "status": "RUNNING", "latestRunUrl": "/api/admin/ingestions/latest"}`, or `409 Conflict` if a run is already in
progress.

### `GET /api/admin/ingestions/latest`

The most recent ingestion run with a per-company result. Same token rule as above. A run's status is
`RUNNING`, `SUCCEEDED`, `PARTIAL`, `FAILED`, or `ABANDONED` (the service stopped mid-run). Each
company result is `SUCCEEDED`, `INCOMPLETE` (loaded, but a metric had no values), or `FAILED`, with
the reason in `error`.

### `GET /actuator/health`

Liveness and database health.

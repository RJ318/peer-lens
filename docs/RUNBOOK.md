# Peer Lens runbook

How to run, operate, and troubleshoot the service.

## Configuration

| Variable | Required | Purpose |
|---|---|---|
| `SEC_USER_AGENT` | Yes | Your name and a contact email, for example `Jane Doe jane@example.com`. The SEC fair-access policy requires it on every request, and requests without it are refused. |
| `PEERLENS_ADMIN_TOKEN` | No | Enables the admin endpoints. Leave it unset to disable them. Generate one with `openssl rand -hex 24`. |
| `SPRING_DATASOURCE_URL`, `_USERNAME`, `_PASSWORD` | No | Database connection. Defaults to a local H2 file at `api/data/`. Docker Compose points it at PostgreSQL. |
| `PEERLENS_API_URL` | No | Where the dashboard finds the API. Defaults to `http://localhost:8080`. |

Secrets live in `.env`, which is git-ignored. Never commit it.

## Start

With Docker:

```bash
cp .env.example .env      # then edit SEC_USER_AGENT and PEERLENS_ADMIN_TOKEN
docker compose up --build
```

Without Docker (Java 21 and Python 3.11 or newer):

```bash
cd api && SEC_USER_AGENT="Jane Doe jane@example.com" ./mvnw spring-boot:run
cd dashboard && pip install -e . && python -m peerlens_dashboard.app
```

The API is on port 8080 and the dashboard on port 8050. On the first start with an empty
database, the API loads the watchlist from SEC EDGAR automatically. A full load makes 21 requests
(one ticker list plus one file per company) and has taken between 7 seconds and 90 seconds in
testing, depending on how quickly the SEC serves the files.

## Refresh data

Data refreshes on a schedule (`peerlens.ingest.cron`, 06:30 New York time by default). To refresh
now:

```bash
curl -X POST -H "X-Admin-Token: $PEERLENS_ADMIN_TOKEN" http://localhost:8080/api/admin/ingestions
curl -H "X-Admin-Token: $PEERLENS_ADMIN_TOKEN" http://localhost:8080/api/admin/ingestions/latest
```

A run ends as `SUCCEEDED`, `PARTIAL` (at least one company failed or was incomplete), or `FAILED`.
Each company result is `SUCCEEDED`, `INCOMPLETE` (loaded, but at least one metric had no values; the
error field names it), or `FAILED`. Each company is replaced inside its own transaction, so a company
that fails keeps its previous data.

## Check health

```bash
curl http://localhost:8080/actuator/health
```

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Run `FAILED` with "SEC_USER_AGENT is not set" | Missing or invalid User-Agent | Set `SEC_USER_AGENT` to a name and email, restart, and trigger a run. |
| Company `FAILED` with "403 Forbidden" | The SEC refused the request, usually an undeclared User-Agent | Check `SEC_USER_AGENT`. If it is set, wait ten minutes; the SEC can briefly block clients that exceed its rate limit. |
| Company `FAILED` "after 4 attempts" | SEC returned 429 or 5xx repeatedly, or the network failed | Usually transient. Trigger another run later. The client already backs off exponentially and honors `Retry-After`. |
| "is not in the SEC ticker list" | Ticker changed or was delisted | Update the ticker in `application.yml` under `peerlens.sectors`. |
| Company `INCOMPLETE` with "No annual values found for netIncome" (or another metric) | The company tags that line item with a concept not in the configured list | Look up its concepts as in the next row. If the only available concept means something different (for example `ProfitLoss`, which includes minority owners' share), consider a different company rather than mixing definitions. |
| "No annual figures found" | The company tags its figures with concepts not in the configured lists | Look up the company's concepts at `https://data.sec.gov/api/xbrl/companyfacts/CIK##########.json` and add the right one to `peerlens.concepts`, or to a sector's `revenue-concepts`. |
| Dashboard shows "Could not reach the Peer Lens API" | API down or wrong URL | Check `/actuator/health` and `PEERLENS_API_URL`. |
| A run shows `ABANDONED` | The service stopped mid-run | Nothing to clean up. Trigger a new run. |

## Change the watchlist

Edit `peerlens.sectors` in `api/src/main/resources/application.yml`. Each sector has a label, a list
of tickers, and an optional `revenue-concepts` list that overrides the default revenue concepts.
Restart the API and trigger a run.

## Reset local data

Stop the API and delete `api/data/`. With Docker, `docker compose down -v` removes the database
volume.

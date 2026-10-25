"""Peer Lens dashboard: compare annual fundamentals across a sector peer group."""

from __future__ import annotations

import os

import pandas as pd
from dash import Dash, Input, Output, State, dcc, html

from .client import PeerLensClient, PeerLensError
from .figures import comparison_figure, empty_figure, history_figure
from .metrics import DEFAULT_METRIC, METRICS, caveat_for
from .transforms import comparison_frame, history_frame

FOOTER = (
    "Data: SEC EDGAR XBRL company facts, loaded and served by the Peer Lens API. "
    "A personal project for research practice; not investment advice."
)


def create_app(client: PeerLensClient | None = None) -> Dash:
    client = client or PeerLensClient()
    app = Dash(__name__, title="Peer Lens")
    app.layout = lambda: build_layout(client)
    register_callbacks(app, client)
    return app


def build_layout(client: PeerLensClient) -> html.Div:
    try:
        sectors = client.sectors()
        status = None
    except PeerLensError as exc:
        sectors = []
        status = str(exc)

    sector_options = [{"label": s["label"], "value": s["key"]} for s in sectors]
    metric_options = [{"label": m.label, "value": m.key} for m in METRICS.values()]

    return html.Div(
        className="page",
        children=[
            html.Header(
                className="masthead",
                children=[
                    html.H1("Peer Lens"),
                    html.P("Annual fundamentals from SEC filings, compared across sector peer groups."),
                ],
            ),
            html.Div(
                className="controls",
                children=[
                    _labelled(
                        "Peer group",
                        dcc.Dropdown(
                            id="sector",
                            options=sector_options,
                            value=sector_options[0]["value"] if sector_options else None,
                            clearable=False,
                        ),
                    ),
                    _labelled(
                        "Metric",
                        dcc.Dropdown(id="metric", options=metric_options, value=DEFAULT_METRIC, clearable=False),
                    ),
                ],
            ),
            html.Div(id="status", className="status", children=status, hidden=status is None),
            html.P(id="metric-description", className="description"),
            html.P(id="caveat", className="caveat"),
            html.Section(
                className="panel",
                children=[
                    html.H2("Latest fiscal year"),
                    dcc.Loading(dcc.Graph(id="comparison-chart", config={"displayModeBar": False})),
                ],
            ),
            html.Section(
                className="panel",
                children=[
                    html.H2("Trend by fiscal year"),
                    dcc.Loading(dcc.Graph(id="history-chart", config={"displayModeBar": False})),
                ],
            ),
            html.Section(
                className="panel",
                children=[html.H2("Data"), html.Div(id="comparison-table")],
            ),
            html.Footer(FOOTER, className="footer"),
        ],
    )


def register_callbacks(app: Dash, client: PeerLensClient) -> None:
    @app.callback(
        Output("comparison-chart", "figure"),
        Output("history-chart", "figure"),
        Output("comparison-table", "children"),
        Output("metric-description", "children"),
        Output("caveat", "children"),
        Output("status", "children"),
        Output("status", "hidden"),
        Input("sector", "value"),
        Input("metric", "value"),
        State("status", "children"),
    )
    def update(sector: str | None, metric: str, current_status: str | None):
        return render(client, sector, metric, current_status)


def render(client: PeerLensClient, sector: str | None, metric: str, current_status: str | None = None):
    """Everything the page shows for one sector and metric. Kept separate from Dash so it is testable."""
    description = METRICS[metric].description
    caveat = caveat_for(sector, metric)
    if sector is None:
        message = current_status or "No peer groups are loaded yet. Run an ingestion in the API first."
        empty = empty_figure("No data yet.")
        return (empty, empty, None, description, caveat, message, False)

    try:
        comparison = client.comparison(sector)
        history = client.history(sector)
    except PeerLensError as exc:
        message = "Data unavailable."
        return (empty_figure(message), empty_figure(message), None, description, caveat, str(exc), False)

    latest = comparison_frame(comparison["companies"], metric)
    trend = history_frame(history["companies"], metric)
    tickers = [company["ticker"] for company in history["companies"]]

    missing = latest.loc[latest["value"].isna(), "ticker"].tolist()
    if missing:
        description = f"{description} Not reported for: {', '.join(missing)}."

    bar = comparison_figure(latest, metric) if latest["value"].notna().any() else empty_figure("No data yet.")
    line = history_figure(trend, metric, tickers) if not trend.empty else empty_figure("No data yet.")
    return bar, line, _table(latest, metric), description, caveat, None, True


def _table(frame, metric: str) -> html.Table:
    header = html.Thead(
        html.Tr(
            [
                html.Th("Company"),
                html.Th("Fiscal year"),
                html.Th("Period end"),
                html.Th(METRICS[metric].label, className="num"),
                html.Th("Source"),
            ]
        )
    )
    rows = [
        html.Tr(
            [
                html.Td([html.Strong(row.ticker), html.Span(f" {row.name}", className="muted")]),
                html.Td(_or_dash(row.fiscalYear)),
                html.Td(_or_dash(row.periodEnd)),
                html.Td(row.display, className="num"),
                html.Td(
                    html.A("SEC filing", href=row.sourceUrl, target="_blank", rel="noopener noreferrer")
                    if row.sourceUrl
                    else "n/a"
                ),
            ]
        )
        for row in frame.itertuples(index=False)
    ]
    return html.Table([header, html.Tbody(rows)], className="data-table")


def _or_dash(value) -> str:
    return "n/a" if pd.isna(value) else str(value)


def _labelled(label: str, control) -> html.Div:
    return html.Div(className="control", children=[html.Label(label), control])


def main() -> None:
    app = create_app()
    app.run(
        host=os.environ.get("DASH_HOST", "127.0.0.1"),
        port=int(os.environ.get("DASH_PORT", "8050")),
        debug=os.environ.get("DASH_DEBUG", "false").lower() == "true",
    )


if __name__ == "__main__":
    main()

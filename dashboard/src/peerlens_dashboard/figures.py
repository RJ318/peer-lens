"""Plotly figures. Colors follow the company, never its rank, so a metric change never repaints a line."""

from __future__ import annotations

import pandas as pd
import plotly.graph_objects as go

from .metrics import METRICS

SURFACE = "#fcfcfb"
INK = "#0b0b0b"
INK_SECONDARY = "#52514e"
INK_MUTED = "#898781"
GRID = "#e1e0d9"
BASELINE = "#c3c2b7"
FONT = 'system-ui, -apple-system, "Segoe UI", sans-serif'

# Categorical slots in fixed order (validated for color-vision deficiency on adjacent pairs).
SERIES_COLORS = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#6250d6", "#e34948"]
BAR_COLOR = SERIES_COLORS[0]

CURRENCY_SCALE = 1e9


def color_map(tickers: list[str]) -> dict[str, str]:
    """Stable color per company, assigned by watchlist order."""
    return {ticker: SERIES_COLORS[i % len(SERIES_COLORS)] for i, ticker in enumerate(tickers)}


def comparison_figure(frame: pd.DataFrame, metric_key: str) -> go.Figure:
    metric = METRICS[metric_key]
    plotted = frame.dropna(subset=["value"])
    x, axis = _scale(plotted["value"], metric.kind)
    figure = go.Figure(
        go.Bar(
            x=x,
            y=plotted["ticker"],
            orientation="h",
            text=plotted["display"],
            textposition="outside",
            cliponaxis=False,
            marker={"color": BAR_COLOR, "cornerradius": 4},
            customdata=plotted[["name", "fiscalYear"]].to_numpy(),
            hovertemplate="<b>%{customdata[0]}</b><br>FY%{customdata[1]}: %{text}<extra></extra>",
        )
    )
    _base_layout(figure, height=70 + 46 * max(len(plotted), 1))
    figure.update_xaxes(
        title_text=axis["title"],
        tickformat=axis["tickformat"],
        ticksuffix=axis["ticksuffix"],
        range=bar_range(x),
    )
    figure.update_yaxes(
        autorange="reversed", showgrid=False, ticks="", showline=False, tickfont={"color": INK_SECONDARY}
    )
    figure.update_layout(showlegend=False, bargap=0.35, margin={"t": 8})
    return figure


def bar_range(values: pd.Series) -> list[float]:
    """Axis range that always includes zero and leaves room for the value labels outside each bar end,
    so a long negative bar's label never runs into the company names on the axis."""
    low = min(0.0, float(values.min())) if len(values) else 0.0
    high = max(0.0, float(values.max())) if len(values) else 1.0
    span = (high - low) or 1.0
    # Negative labels sit between the bar and the company names, so they get more room.
    return [low - 0.3 * span if low < 0 else low, high + 0.18 * span if high > 0 else high]


def history_figure(frame: pd.DataFrame, metric_key: str, tickers: list[str]) -> go.Figure:
    # Five companies per peer group: identity comes from the legend, hover, and the data table.
    # End-of-line labels were dropped because peers with similar values made them overlap.
    metric = METRICS[metric_key]
    colors = color_map(tickers)
    figure = go.Figure()
    for ticker in tickers:
        series = frame[frame["ticker"] == ticker]
        if series.empty:
            continue
        y, axis = _scale(series["value"], metric.kind)
        figure.add_trace(
            go.Scatter(
                x=series["fiscalYear"],
                y=y,
                name=ticker,
                mode="lines+markers",
                line={"color": colors[ticker], "width": 2},
                marker={"color": colors[ticker], "size": 8, "line": {"color": SURFACE, "width": 2}},
                customdata=series[["name", "periodEnd"]].to_numpy(),
                hovertemplate=(
                    "<b>%{customdata[0]}</b><br>FY%{x} (ended %{customdata[1]})<br>"
                    + _hover_value(metric.kind)
                    + "<extra></extra>"
                ),
            )
        )
    _base_layout(figure, height=400)
    _, axis = _scale(pd.Series(dtype=float), metric.kind)
    figure.update_yaxes(title_text=axis["title"], tickformat=axis["tickformat"], ticksuffix=axis["ticksuffix"])
    figure.update_xaxes(dtick=1, tickformat="d", showgrid=False)
    figure.update_layout(
        hovermode="closest",
        legend={"orientation": "h", "yanchor": "bottom", "y": 1.02, "xanchor": "left", "x": 0},
    )
    return figure


def empty_figure(message: str) -> go.Figure:
    figure = go.Figure()
    _base_layout(figure, height=220)
    figure.update_xaxes(visible=False)
    figure.update_yaxes(visible=False)
    figure.add_annotation(text=message, showarrow=False, font={"color": INK_MUTED, "size": 14})
    return figure


def _scale(values: pd.Series, kind: str) -> tuple[pd.Series, dict[str, str]]:
    if kind == "currency":
        return values / CURRENCY_SCALE, {"title": "US$ billions", "tickformat": ",.0f", "ticksuffix": ""}
    if kind == "percent":
        return values, {"title": "", "tickformat": ".0%", "ticksuffix": ""}
    return values, {"title": "", "tickformat": ",.1f", "ticksuffix": "x"}


def _hover_value(kind: str) -> str:
    if kind == "currency":
        return "$%{y:,.1f}B"
    if kind == "percent":
        return "%{y:.1%}"
    return "%{y:.2f}x"


def _base_layout(figure: go.Figure, height: int) -> None:
    figure.update_layout(
        height=height,
        margin={"l": 8, "r": 56, "t": 40, "b": 8},
        paper_bgcolor=SURFACE,
        plot_bgcolor=SURFACE,
        font={"family": FONT, "color": INK, "size": 13},
        hoverlabel={"bgcolor": "#ffffff", "bordercolor": BASELINE, "font": {"family": FONT, "color": INK}},
    )
    figure.update_xaxes(gridcolor=GRID, zerolinecolor=BASELINE, linecolor=BASELINE, tickfont={"color": INK_MUTED})
    figure.update_yaxes(gridcolor=GRID, zerolinecolor=BASELINE, linecolor=BASELINE, tickfont={"color": INK_MUTED})

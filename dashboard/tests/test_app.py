from conftest import FakeClient

from peerlens_dashboard.app import build_layout, create_app, render
from peerlens_dashboard.client import PeerLensError


def _find(component, component_id):
    if getattr(component, "id", None) == component_id:
        return component
    children = getattr(component, "children", None)
    if children is None or isinstance(children, str):
        return None
    for child in children if isinstance(children, list) else [children]:
        found = _find(child, component_id)
        if found is not None:
            return found
    return None


def test_app_builds(fake_client):
    app = create_app(fake_client)
    assert app.title == "Peer Lens"


def test_layout_defaults_to_first_sector(fake_client):
    layout = build_layout(fake_client)
    assert _find(layout, "sector").value == "technology"
    assert _find(layout, "status").hidden is True


def test_layout_shows_error_when_api_down():
    layout = build_layout(FakeClient(error=PeerLensError("API down")))
    status = _find(layout, "status")
    assert status.children == "API down"
    assert status.hidden is False


def test_render_builds_both_charts_and_table(fake_client):
    bar, line, table, description, caveat, status, hidden = render(fake_client, "technology", "revenue")
    assert [trace.type for trace in bar.data] == ["bar"]
    assert list(bar.data[0].y) == ["AAA", "BBB"]
    assert {trace.name for trace in line.data} == {"AAA", "BBB"}
    assert "Not reported for: CCC" in description
    assert caveat is None
    assert status is None and hidden is True
    assert len(table.children[1].children) == 3


def test_table_rows_render_years_and_missing_values(fake_client):
    table = render(fake_client, "technology", "revenue")[2]
    first, _, missing = table.children[1].children
    assert first.children[1].children == "2025"
    assert [cell.children for cell in missing.children[1:]] == ["n/a", "n/a", "n/a", "n/a"]


def test_line_colors_follow_company_not_rank(fake_client):
    _, revenue_line, *_ = render(fake_client, "technology", "revenue")
    _, margin_line, *_ = render(fake_client, "technology", "netMargin")
    colors = lambda fig: {trace.name: trace.line.color for trace in fig.data}  # noqa: E731
    assert colors(revenue_line) == colors(margin_line)


def test_bar_range_leaves_room_for_negative_labels():
    import pandas as pd

    from peerlens_dashboard.figures import bar_range

    low, high = bar_range(pd.Series([-4.73, 0.41]))
    assert low < -4.73 * 1.1
    assert high > 0.41
    assert bar_range(pd.Series([10.0, 20.0]))[0] == 0.0


def test_render_reports_api_errors():
    *_, status, hidden = render(FakeClient(error=PeerLensError("timeout")), "technology", "revenue")
    assert status == "timeout"
    assert hidden is False


def test_render_keeps_layout_error_when_no_sectors(fake_client):
    *_, status, hidden = render(fake_client, None, "revenue", current_status="API down")
    assert status == "API down"
    assert hidden is False


def test_bank_caveat_is_shown(fake_client):
    *_, caveat, _, _ = render(fake_client, "banks", "cashConversion")
    assert "not meaningful" in caveat

from conftest import TECH_COMPARISON, TECH_HISTORY

from peerlens_dashboard.transforms import comparison_frame, history_frame


def test_comparison_sorted_high_to_low_with_missing_last():
    frame = comparison_frame(TECH_COMPARISON["companies"], "revenue")
    assert frame["ticker"].tolist() == ["AAA", "BBB", "CCC"]
    assert frame.loc[2, "display"] == "n/a"


def test_comparison_handles_negative_growth():
    frame = comparison_frame(TECH_COMPARISON["companies"], "revenueGrowth")
    assert frame["ticker"].tolist()[:2] == ["AAA", "BBB"]
    assert frame.loc[1, "display"] == "-10.0%"


def test_comparison_links_derived_metric_to_revenue_filing():
    frame = comparison_frame(TECH_COMPARISON["companies"], "netMargin")
    assert frame.loc[0, "sourceUrl"].endswith("AAA2025/")
    assert frame.loc[2, "sourceUrl"] is None


def test_history_is_long_format_without_missing_values():
    frame = history_frame(TECH_HISTORY["companies"], "revenueGrowth")
    # First-year growth is unknown, so only one point per company survives.
    assert frame[["ticker", "fiscalYear"]].values.tolist() == [["AAA", 2025], ["BBB", 2025]]


def test_history_ordered_by_company_then_year():
    frame = history_frame(TECH_HISTORY["companies"], "revenue")
    assert frame["fiscalYear"].tolist() == [2024, 2025, 2024, 2025]


def test_empty_inputs_produce_empty_frames():
    assert comparison_frame([], "revenue").empty
    assert history_frame([], "revenue").empty

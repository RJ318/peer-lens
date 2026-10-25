import pytest

from peerlens_dashboard.metrics import caveat_for, format_metric, format_value


@pytest.mark.parametrize(
    ("kind", "value", "expected"),
    [
        ("currency", 394_328_000_000, "$394.3B"),
        ("currency", 1_250_000_000_000, "$1.2T"),
        ("currency", -2_500_000_000, "-$2.5B"),
        ("currency", 12_500_000, "$12.5M"),
        ("currency", 950, "$950"),
        ("percent", 0.2431, "24.3%"),
        ("percent", -0.05, "-5.0%"),
        ("ratio", 1.125, "1.12x"),
        ("currency", None, "n/a"),
        ("percent", None, "n/a"),
    ],
)
def test_format_value(kind, value, expected):
    assert format_value(kind, value) == expected


def test_format_metric_uses_metric_kind():
    assert format_metric("netMargin", 0.25) == "25.0%"
    assert format_metric("revenue", 2e9) == "$2.0B"


def test_bank_cash_conversion_has_caveat():
    assert "not meaningful" in caveat_for("banks", "cashConversion")


def test_no_caveat_for_ordinary_case():
    assert caveat_for("technology", "netMargin") is None
    assert caveat_for(None, "netMargin") is None

# Data notes

What the first live loads against SEC EDGAR turned up, and how each finding shaped the code or the
watchlist.

## How the data was verified

The first check re-derived each company's latest figures from the raw SEC files, but it reused the
same selection rules as the code, so it could only confirm the code did what it was told. An
independent code review then found three data errors that check could not see (Pfizer, Kimberly-Clark,
and Johnson & Johnson, below). The final check on October 25, 2026 uses different mechanisms from the
code: it compares 324 stored values with the fact the SEC itself selected for each period (the one
carrying a `frame` label, which the code never reads), flags any year where another revenue concept
reports a larger total, and recomputes all 120 growth rates from the 10-K covering the following
year. It found no problems. The check is `scripts/verify_against_sec.py`.

## Restatements are real, and the latest filing should win

Bank of America's 10-K for fiscal 2025 recast its 2023 and 2024 revenue upward by about $4 billion
each (for example, 2024 moved from $101.9 billion as first reported to $105.9 billion). Because the
extractor keeps the most recently filed value for each period, Peer Lens shows 2024 and 2025 on the
same basis.

## Growth has to be measured within one filing

A company only recasts the years that appear in the restating filing. Kimberly-Clark's latest 10-K
restated 2023 for a divested business, while 2022 was last reported on the old basis, so the first
version computed 2023 growth as −15.0% by comparing the two. In the filing that reports both years on
the same basis, 2023 growth was +1.3%. Johnson & Johnson's spin-off of its consumer health business
had the same effect in the other direction (−4.7% shown, +13.6% actual). Growth now comes from the most
recent single filing that reports both years, and the API links to that filing.

Revenue levels can still sit on different bases across a restatement boundary (Johnson & Johnson's
2020 revenue includes the consumer business; 2021 onward does not). That is documented rather than
hidden, and it does not affect growth.

## Concept priority must not override recency

Pfizer's 10-K filed in February 2024 tagged product revenue alone with
`RevenueFromContractWithCustomerExcludingAssessedTax` and total revenue with `Revenues`. The first
version tried concepts in a fixed order and took the first one present for each year, so it reported
2021 to 2023 revenue $7.6 billion to $8.6 billion too low, and 2024 growth as +25.0% instead of +6.8%.
The extractor now takes the most recently filed value for each year whatever its concept, uses
concept priority only between values in the same filing, and lists `Revenues` first so a total beats
a component.

## Tagging gaps exist, so the year window has to be shared

U.S. Bancorp tagged total revenue for 2015 to 2018 and again from 2022, but not for 2019 to 2021,
while net income was tagged every year. The first version trimmed each metric to its own six most
recent values, which pulled in 2017 and 2018 revenue and produced eight misaligned years. The fix
keeps one window of the six most recent fiscal years across all metrics
(`AnnualFactExtractor.keepRecentYears`), with a regression test built from this exact pattern.

U.S. Bancorp was then replaced by PNC, and PNC by Goldman Sachs (below), so every company on the
watchlist has complete data for all six years.

## Not every company uses the standard net income tag

PNC does not tag `NetIncomeLoss`. It tags `ProfitLoss`, which includes the share of earnings that
belongs to minority owners, and its 2020 figure includes the one-time gain from selling its BlackRock
stake. Using it would have mixed two definitions of net income in one peer group, so PNC was replaced
by Goldman Sachs, which tags the standard concepts.

The first run reported PNC as a success even though net income was missing for every year. Ingestion
now marks a company `INCOMPLETE` when any metric has no values, and names the missing metric in the
run record.

## Banks need their own revenue definition

A bank's headline figure is net revenue: net interest income plus noninterest income. The standard
`RevenueFromContractWithCustomerExcludingAssessedTax` tag covers only a bank's fee income, so the
banks sector uses its own concept list (`RevenuesNetOfInterestExpense`, then `Revenues`). For Bank of
America and Citigroup, `Revenues` equals net interest income plus noninterest income exactly.

## Bank cash flow says little about earnings

Operating cash flow at banks swings with trading and lending balances, so cash conversion comes out
negative for four of the five banks in 2025. The numbers are correct as filed. The dashboard shows a
note explaining why the metric is not meaningful for banks rather than hiding it.

## Fiscal years

Johnson & Johnson's 52- and 53-week years sometimes end in the first days of January, and it labels
those by the prior year (the year ending January 1, 2023 is its fiscal 2022). Taking the calendar year
of the period end produced two "2023" entries. The label is now the year of the period end after
stepping back seven days, which fixes Johnson & Johnson and still labels NVIDIA's year ending
January 25, 2026 as fiscal 2026. `periodEnd` is always returned so nothing is ambiguous.

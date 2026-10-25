package io.github.rj318.peerlens.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.rj318.peerlens.service.FinancialsService;
import io.github.rj318.peerlens.web.ApiViews.CompanyDetailView;
import io.github.rj318.peerlens.web.ApiViews.CompanySummaryView;
import io.github.rj318.peerlens.web.ApiViews.ComparisonView;
import io.github.rj318.peerlens.web.ApiViews.HistoryView;
import io.github.rj318.peerlens.web.ApiViews.SectorView;

@RestController
@RequestMapping("/api")
public class FinancialsController {

    private final FinancialsService financials;

    public FinancialsController(FinancialsService financials) {
        this.financials = financials;
    }

    @GetMapping("/sectors")
    public List<SectorView> sectors() {
        return financials.sectors();
    }

    @GetMapping("/sectors/{key}/comparison")
    public ComparisonView comparison(@PathVariable String key) {
        return financials.comparison(key);
    }

    @GetMapping("/sectors/{key}/history")
    public HistoryView history(@PathVariable String key) {
        return financials.history(key);
    }

    @GetMapping("/companies")
    public List<CompanySummaryView> companies(@RequestParam(required = false) String sector) {
        return financials.companies(sector);
    }

    @GetMapping("/companies/{ticker}")
    public CompanyDetailView company(@PathVariable String ticker) {
        return financials.company(ticker);
    }
}

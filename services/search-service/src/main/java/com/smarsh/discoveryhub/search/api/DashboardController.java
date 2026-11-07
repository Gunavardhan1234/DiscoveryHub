package com.smarsh.discoveryhub.search.api;

import com.smarsh.discoveryhub.search.domain.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard counts API (FR-8.1). Returns the four top-line counts that frame
 * the tool: total messages (an ES aggregation this service owns), active cases,
 * active holds, and exports completed. Each remote count degrades to 0 if its
 * owning service is down — the dashboard never blanks entirely (NFR-2).
 *
 * <p><b>SOLID — SRP:</b> a thin HTTP shell over {@link DashboardService}; the
 * aggregation and degradation rules live in the domain, not here.
 *
 * <p><b>Design note worth defending:</b> the dashboard is served by
 * search-service rather than a dedicated BFF because search-service already
 * owns the only expensive number (the corpus count, an ES aggregation) and can
 * fan out cheap REST reads for the other three through
 * {@code RemoteCountsPort}. The alternative — the browser making four parallel
 * calls and summing them — would push cross-service orchestration and
 * failure-handling into the UI.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/counts")
    public DashboardService.DashboardCounts counts() {
        return dashboardService.counts();
    }
}

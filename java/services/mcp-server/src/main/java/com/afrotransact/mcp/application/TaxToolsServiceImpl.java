package com.afrotransact.mcp.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

import com.afrotransact.mcp.port.TaxApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
class TaxToolsServiceImpl implements TaxToolsService {

    private static final List<String> STATUSES = List.of("PENDING_REVIEW", "APPROVED", "REJECTED", "STALE");
    private static final List<String> PROVIDERS = List.of("STRIPE", "AVALARA");
    private static final int PAGE_SIZE = 25;
    private static final int SHADOW_ROWS = 10;

    private final ToolGuardService guard;
    private final TaxApi tax;
    private final Clock clock;

    @Autowired
    TaxToolsServiceImpl(ToolGuardService guard, TaxApi tax) {
        this(guard, tax, Clock.systemUTC());
    }

    TaxToolsServiceImpl(ToolGuardService guard, TaxApi tax, Clock clock) {
        this.guard = guard;
        this.tax = tax;
        this.clock = clock;
    }

    @Override
    public ToolResult reviewQueue(McpCaller caller, String status, String query, Integer page) {
        guard.require(caller.profile(), ToolCatalog.TAX_REVIEW_QUEUE);
        String wanted = status == null || status.isBlank() ? "PENDING_REVIEW" : status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(wanted)) {
            return ToolResult.of("REJECTED", "status must be one of " + String.join(", ", STATUSES));
        }
        return Results.from(tax.reviewQueue(wanted, query == null ? null : query.trim(),
                page == null ? 0 : Math.max(page, 0), PAGE_SIZE, caller.bearerToken()), "tax review queue");
    }

    @Override
    public ToolResult shadowReport(McpCaller caller, Integer days, String provider) {
        guard.require(caller.profile(), ToolCatalog.TAX_SHADOW_REPORT);
        int window = days == null ? 30 : days;
        if (window != 7 && window != 30 && window != 90) {
            return ToolResult.of("REJECTED", "days must be 7, 30 or 90");
        }
        String wanted = provider == null || provider.isBlank() ? null : provider.trim().toUpperCase(Locale.ROOT);
        if (wanted != null && !PROVIDERS.contains(wanted)) {
            return ToolResult.of("REJECTED", "provider must be STRIPE or AVALARA");
        }
        LocalDate to = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        return Results.from(tax.shadowReport(to.minusDays(window).toString(), to.toString(), wanted, SHADOW_ROWS,
                caller.bearerToken()), "tax shadow report");
    }
}

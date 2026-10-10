package com.afrotransact.mcp.application;

import java.util.Locale;

import com.afrotransact.mcp.domain.PiiMasker;
import com.afrotransact.mcp.port.ApplicationsApi;
import com.afrotransact.mcp.port.CatalogApi;
import com.afrotransact.mcp.port.Downstream;
import com.afrotransact.mcp.port.PaymentsApi;
import org.springframework.stereotype.Service;

@Service
class StaffToolsServiceImpl implements StaffToolsService {

    private final ToolGuardService guard;
    private final ApplicationsApi applications;
    private final CatalogApi catalog;
    private final PaymentsApi payments;
    private final PiiMasker masker;

    StaffToolsServiceImpl(ToolGuardService guard, ApplicationsApi applications, CatalogApi catalog,
                          PaymentsApi payments, PiiMasker masker) {
        this.guard = guard;
        this.applications = applications;
        this.catalog = catalog;
        this.payments = payments;
        this.masker = masker;
    }

    @Override
    public ToolResult applicationsQueue(McpCaller caller, String status, Integer page) {
        guard.require(caller.profile(), ToolCatalog.APPLICATIONS_QUEUE);
        String s = status == null || status.isBlank() ? "IN_REVIEW" : status.trim().toUpperCase(Locale.ROOT);
        Downstream d = applications.queue(s, page == null ? 0 : Math.max(page, 0), 25, caller.bearerToken());
        return d.ok() ? ToolResult.ok(masker.mask(d.body())) : Results.from(d, "application queue");
    }

    @Override
    public ToolResult applicationDetail(McpCaller caller, String applicationId) {
        guard.require(caller.profile(), ToolCatalog.APPLICATION_DETAIL);
        Downstream d = applications.application(applicationId == null ? "" : applicationId.trim(),
                caller.bearerToken());
        if (!d.ok()) {
            return Results.from(d, "application");
        }
        return new ToolResult("OK", masker.mask(d.body()),
                "Personal data is masked (tax ids and account numbers to the last four, dates of birth to the year).");
    }

    @Override
    public ToolResult moderationQueue(McpCaller caller, Integer page) {
        guard.require(caller.profile(), ToolCatalog.MODERATION_QUEUE);
        return Results.from(catalog.products(null, "IN_REVIEW", null, page == null ? 0 : Math.max(page, 0), 25,
                caller.bearerToken()), "moderation queue");
    }

    @Override
    public ToolResult orderPaymentSplit(McpCaller caller, String orderId) {
        guard.require(caller.profile(), ToolCatalog.ORDER_PAYMENT_SPLIT);
        return Results.from(payments.orderSplit(orderId == null ? "" : orderId.trim(), caller.bearerToken()),
                "payment split");
    }
}

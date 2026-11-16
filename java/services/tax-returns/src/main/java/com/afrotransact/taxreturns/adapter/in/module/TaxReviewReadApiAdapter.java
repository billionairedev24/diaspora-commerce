package com.afrotransact.taxreturns.adapter.in.module;

import com.afrotransact.module.EndUserActor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import com.afrotransact.taxreturns.adapter.in.web.TaxReviewEndpointViews;
import com.afrotransact.taxreturns.api.TaxReviewReadApi;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link TaxReviewReadApi}: the review queue and shadow report endpoints behind the MCP tax tools, entered AS
 * the signed-in person ({@code ModuleEntry.runAsUser}), so tax-returns' own {@code tax:read} check decides as for the HTTP request.
 */
@Component
class TaxReviewReadApiAdapter implements TaxReviewReadApi {

    private final TaxReviewEndpointViews views;
    private final ModuleEntry entry;

    TaxReviewReadApiAdapter(TaxReviewEndpointViews views, ActorResolver actors) {
        this.views = views;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public Object reviewQueue(EndUserActor user, String status, String query, int page, int size) {
        return entry.runAsUser(user, READ_SCOPE, () -> views.reviewQueue(status, query, page, size), TaxReturnsFailures::map);
    }

    @Override
    public Object shadowReport(EndUserActor user, String from, String to, String provider, int size) {
        return entry.runAsUser(user, READ_SCOPE, () -> views.shadowReport(from, to, provider, size), TaxReturnsFailures::map);
    }
}

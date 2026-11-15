package com.afrotransact.taxreturns.adapter.in.module;

import java.util.Map;

import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import com.afrotransact.taxreturns.api.ListingDecisionApi;
import com.afrotransact.taxreturns.application.SellerListingDecisionService;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of the published {@link ListingDecisionApi}: the use cases behind {@code GET /v1/tax/listing-decisions}
 * and {@code POST /v1/tax/listing-decisions/request}, entered under the calling module's principal. It adds no rule and no
 * permission of its own: the application service requires {@code tax:read} / {@code tax:classify} exactly as it does for a
 * token. It refuses to run without a caller, and when the actor in force is not that caller.
 */
@Component
class ListingDecisionApiAdapter implements ListingDecisionApi {

    private final SellerListingDecisionService decisions;
    private final ModuleEntry entry;

    ListingDecisionApiAdapter(SellerListingDecisionService decisions, ActorResolver actors) {
        this.decisions = decisions;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public DecisionState find(String atin, String sellerOrgId) {
        return entry.run(() -> state(decisions.find(atin, sellerOrgId)));
    }

    @Override
    public DecisionState request(String atin, String sellerOrgId, Map<String, Object> sellerBehaviour) {
        return entry.run(() -> state(decisions.request(atin, sellerOrgId, sellerBehaviour)));
    }

    private static DecisionState state(SellerListingDecisionService.State s) {
        return new DecisionState(s.status(), s.resolved(), s.reasons());
    }
}

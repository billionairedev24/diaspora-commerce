package com.afrotransact.taxreturns.adapter.in.module;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.taxreturns.api.TaxClassificationApi;
import com.afrotransact.taxreturns.application.ClassificationResolveService;
import com.afrotransact.taxreturns.application.ClassificationResolveService.ResolveCommand;
import com.afrotransact.taxreturns.application.ListingDecisionStatusService;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of the published {@link TaxClassificationApi}: the same two use cases the HTTP endpoints call, entered
 * under the calling module's principal. It adds no rule and no permission of its own; the application services check the
 * caller's scopes exactly as they check a token's. It refuses to run without a caller, and refuses when the actor in force is
 * not that caller, so a misconfigured application fails closed instead of running as whoever is on the thread.
 *
 * <p>Inside this class {@code Resolution} and {@code ListingStatus} are the API's records; the domain's are used through
 * {@code var}.
 */
@Component
class TaxClassificationApiAdapter implements TaxClassificationApi {

    private final ClassificationResolveService resolver;
    private final ListingDecisionStatusService listingStatus;
    private final ActorResolver actors;

    TaxClassificationApiAdapter(ClassificationResolveService resolver, ListingDecisionStatusService listingStatus,
                                ActorResolver actors) {
        this.resolver = resolver;
        this.listingStatus = listingStatus;
        this.actors = actors;
    }

    @Override
    public Resolution resolve(ResolveRequest request) {
        requireCaller();
        try {
            var r = resolver.resolve(new ResolveCommand(request.categoryCode(), request.productTypeCode(),
                    request.facts(), request.sellerBehaviour(), null, null, null));
            List<MissingFact> missing = r.missingFacts().stream()
                    .map(m -> new MissingFact(m.factId(), m.label(), m.message())).toList();
            return new Resolution(r.status().name(), r.outcome() == null ? null : r.outcome().name(),
                    r.internalTaxCode(), r.rowId(), r.rateDisplay(), missing, r.ruleVersion(), r.reason());
        } catch (NotFoundException ex) {
            throw new UnknownTaxProfileException(request.categoryCode());
        }
    }

    @Override
    public Map<String, ListingStatus> listingStatus(Collection<ListingQuery> queries) {
        requireCaller();
        var found = listingStatus.statusOf(queries.stream()
                .map(q -> new ListingDecisionStatusService.Item(q.atin().trim().toUpperCase(Locale.ROOT),
                        q.sellerOrgId()))
                .toList());
        Map<String, ListingStatus> out = new LinkedHashMap<>();
        found.forEach((atin, s) -> out.put(atin, new ListingStatus(atin, s.status().name(), s.recordStatus(),
                s.reason())));
        return out;
    }

    private void requireCaller() {
        CallerPrincipal caller = InProcessCalls.require();
        String subject = actors.current().subject();
        if (!caller.clientId().equals(subject)) {
            throw new IllegalStateException("The actor in force (" + subject + ") is not the in-process caller ("
                    + caller.clientId() + "): the ActorResolver does not honour in-process callers");
        }
    }
}

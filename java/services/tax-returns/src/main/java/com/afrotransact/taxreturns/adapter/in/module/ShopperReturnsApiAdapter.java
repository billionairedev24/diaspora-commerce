package com.afrotransact.taxreturns.adapter.in.module;

import java.util.List;

import com.afrotransact.module.EndUserActor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import com.afrotransact.taxreturns.api.ShopperReturnsApi;
import com.afrotransact.taxreturns.application.ReturnSearch;
import com.afrotransact.taxreturns.application.ReturnService;
import com.afrotransact.taxreturns.domain.returns.Rma;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link ShopperReturnsApi}: the use case behind {@code GET /v1/returns}, entered for the signed-in person with
 * no order, status or seller filter. {@link ReturnService#search} scopes a customer to their own returns from the actor in force, which
 * inside {@code runAsUser} is the person's token subject (never the calling module). The handover code is not part of the published view.
 */
@Component
class ShopperReturnsApiAdapter implements ShopperReturnsApi {

    private final ReturnService returns;
    private final ModuleEntry entry;

    ShopperReturnsApiAdapter(ReturnService returns, ActorResolver actors) {
        this.returns = returns;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public List<ReturnSummary> mine(EndUserActor user, int limit) {
        return entry.runAsUser(user, CALLER_SCOPE, () -> returns.search(new ReturnSearch(null, null, null, limit)).stream()
                .map(ShopperReturnsApiAdapter::summary).toList(), TaxReturnsFailures::map);
    }

    private static ReturnSummary summary(Rma r) {
        return new ReturnSummary(r.number(), r.orderNumber(), r.status().name(), r.method().name(), r.refundState().name(),
                r.estimateMinor(), r.currency(), r.dropOffBy(), r.createdAt(), r.lines().stream()
                .map(l -> new ReturnLine(l.title(), l.quantity(), l.reason() == null ? null : l.reason().name(),
                        l.outcome() == null ? null : l.outcome().name())).toList());
    }
}

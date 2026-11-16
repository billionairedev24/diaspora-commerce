package com.afrotransact.payments.adapter.in.module;

import com.afrotransact.module.EndUserActor;
import com.afrotransact.payments.adapter.in.web.PayoutEndpointViews;
import com.afrotransact.payments.api.PayoutReadApi;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link PayoutReadApi}: the payout read endpoints behind the MCP tools, entered AS the signed-in person
 * ({@code ModuleEntry.runAsUser}), so payments' own checks decide as for the HTTP request (members read their own organisation's
 * balance and requests, staff name one).
 */
@Component
class PayoutReadApiAdapter implements PayoutReadApi {

    private final PayoutEndpointViews views;
    private final ModuleEntry entry;

    PayoutReadApiAdapter(PayoutEndpointViews views, ActorResolver actors) {
        this.views = views;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public Object balance(EndUserActor user, String orgId) {
        return entry.runAsUser(user, READ_SCOPE, () -> views.balance(orgId), PaymentsFailures::map);
    }

    @Override
    public Object requests(EndUserActor user, String orgId, int limit) {
        return entry.runAsUser(user, READ_SCOPE, () -> views.requests(orgId, limit), PaymentsFailures::map);
    }
}

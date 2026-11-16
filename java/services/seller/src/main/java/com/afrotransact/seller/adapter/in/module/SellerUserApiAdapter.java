package com.afrotransact.seller.adapter.in.module;

import com.afrotransact.module.EndUserActor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import com.afrotransact.seller.adapter.in.web.SellerEndpointViews;
import com.afrotransact.seller.api.SellerUserApi;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link SellerUserApi}: the seller endpoints behind the MCP tools, entered AS the signed-in person
 * ({@code ModuleEntry.runAsUser}), so seller's own permission and organisation checks decide as for the HTTP request (account health of
 * the person's own organisation or, for staff, any; the review queue and the dossier need an application review permission; personal
 * data is masked unless the reviewer holds {@code applications:pii}, in which case the view is logged as over HTTP).
 */
@Component
class SellerUserApiAdapter implements SellerUserApi {

    private final SellerEndpointViews views;
    private final ModuleEntry entry;

    SellerUserApiAdapter(SellerEndpointViews views, ActorResolver actors) {
        this.views = views;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public Object accountHealth(EndUserActor user, String orgId) {
        return entry.runAsUser(user, READ_SCOPE, () -> views.accountHealth(orgId), SellerFailures::map);
    }

    @Override
    public Object applicationQueue(EndUserActor user, String status, int page, int size) {
        return entry.runAsUser(user, READ_SCOPE, () -> views.applicationQueue(status, page, size), SellerFailures::map);
    }

    @Override
    public Object application(EndUserActor user, String applicationId) {
        return entry.runAsUser(user, READ_SCOPE, () -> views.application(applicationId), SellerFailures::map);
    }
}

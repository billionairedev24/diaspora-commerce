package com.afrotransact.catalog.adapter.in.module;

import java.util.Map;
import java.util.function.Supplier;

import com.afrotransact.catalog.adapter.in.web.CatalogEndpointViews;
import com.afrotransact.catalog.api.CatalogUserApi;
import com.afrotransact.module.EndUserActor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link CatalogUserApi}: the catalog endpoints behind the MCP tools, entered AS the signed-in person
 * ({@code ModuleEntry.runAsUser}: their subject, type, organisation and permissions, never the calling module's). Catalog's use cases
 * apply their own permission, ownership and status checks exactly as for the HTTP request; the one controller-level guard among these
 * endpoints, the staff-only category request queue ({@code @StaffOrServiceOnly}), is applied here by {@code requireStaff}.
 */
@Component
class CatalogUserApiAdapter implements CatalogUserApi {

    private final CatalogEndpointViews views;
    private final ModuleEntry entry;

    CatalogUserApiAdapter(CatalogEndpointViews views, ActorResolver actors) {
        this.views = views;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public Object publicProduct(EndUserActor user, String atin) {
        return read(user, () -> views.publicProduct(atin));
    }

    @Override
    public Object publicVariant(EndUserActor user, String atin) {
        return read(user, () -> views.publicVariant(atin));
    }

    @Override
    public Object products(EndUserActor user, String orgId, String status, String query, int page, int size) {
        return read(user, () -> views.products(orgId, status, query, page, size));
    }

    @Override
    public Object product(EndUserActor user, String idOrAtin) {
        return read(user, () -> views.product(idOrAtin));
    }

    @Override
    public Object requestCategory(EndUserActor user, Map<String, Object> request) {
        return entry.runAsUser(user, WRITE_SCOPE, () -> views.requestCategory(request), CatalogFailures::map);
    }

    @Override
    public Object categoryRequests(EndUserActor user, String status) {
        return entry.runAsUser(user, READ_SCOPE, () -> {
            entry.requireStaff();
            return views.categoryRequests(status);
        }, CatalogFailures::map);
    }

    private Object read(EndUserActor user, Supplier<Object> body) {
        return entry.runAsUser(user, READ_SCOPE, body, CatalogFailures::map);
    }
}

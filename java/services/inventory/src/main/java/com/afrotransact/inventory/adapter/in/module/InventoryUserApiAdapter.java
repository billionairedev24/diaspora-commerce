package com.afrotransact.inventory.adapter.in.module;

import java.util.List;

import com.afrotransact.inventory.adapter.in.web.InventoryEndpointViews;
import com.afrotransact.inventory.api.InventoryUserApi;
import com.afrotransact.module.EndUserActor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link InventoryUserApi}: the stock endpoints behind the MCP tools, entered AS the signed-in person
 * ({@code ModuleEntry.runAsUser}), so inventory's own checks (a seller sees only its own FBA positions, positions need
 * {@code inventory:read}) decide as for the HTTP request.
 */
@Component
class InventoryUserApiAdapter implements InventoryUserApi {

    private final InventoryEndpointViews views;
    private final ModuleEntry entry;

    InventoryUserApiAdapter(InventoryEndpointViews views, ActorResolver actors) {
        this.views = views;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public Object availability(EndUserActor user, List<String> skus) {
        return entry.runAsUser(user, READ_SCOPE, () -> views.availability(skus), InventoryFailures::map);
    }

    @Override
    public Object positions(EndUserActor user, String ownerOrg, String sku, boolean lowStock) {
        return entry.runAsUser(user, READ_SCOPE, () -> views.positions(ownerOrg, sku, lowStock), InventoryFailures::map);
    }
}

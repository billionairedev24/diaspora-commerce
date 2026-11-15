package com.afrotransact.inventory.adapter.in.module;

import java.util.Optional;

import com.afrotransact.inventory.api.NodeAddressApi;
import com.afrotransact.inventory.application.NodeService;
import com.afrotransact.inventory.domain.PostalAddress;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Component;

/** The in-process entry of {@link NodeAddressApi}: {@code GET /v1/nodes/{id}/address} under the calling module's principal. */
@Component
class NodeAddressApiAdapter implements NodeAddressApi {

    private final NodeService nodes;
    private final ModuleEntry entry;

    NodeAddressApiAdapter(NodeService nodes, ActorResolver actors) {
        this.nodes = nodes;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public Optional<NodeAddress> addressOf(String nodeId) {
        return entry.run(() -> {
            try {
                PostalAddress a = nodes.address(nodeId);
                return Optional.of(new NodeAddress(a.line1(), a.line2(), a.city(), a.region(), a.postalCode(), a.country()));
            } catch (NotFoundException notFound) {
                return Optional.<NodeAddress>empty();
            }
        }, InventoryFailures::map);
    }
}

package com.afrotransact.orders.adapter.out.module;

import java.util.Optional;
import java.util.function.Supplier;

import com.afrotransact.inventory.api.NodeAddressApi;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.port.ShipFromAddresses;
import com.afrotransact.orders.port.TaxQuoteClient;
import com.afrotransact.orders.port.UpstreamFailure;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import com.afrotransact.seller.api.ShipFromAddressApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link ShipFromAddresses} through inventory's {@link NodeAddressApi} (a fulfilment centre) and seller's
 * {@link ShipFromAddressApi} (a pickup location, or the organisation's default address) inside this JVM. Best effort as over
 * HTTP: an unknown place, a refusal or a failure gives {@link Optional#empty()} and never fails the checkout. Used when both
 * edges are in-process.
 */
@Component
@ConditionalOnModuleMode(caller = "orders", callee = "inventory", value = ModuleMode.IN_PROCESS)
class InProcessShipFromAddresses implements ShipFromAddresses {

    private static final Logger LOG = LoggerFactory.getLogger(InProcessShipFromAddresses.class);

    private final ObjectProvider<NodeAddressApi> nodes;
    private final ObjectProvider<ShipFromAddressApi> sellers;
    private final ModuleUpstream upstream;

    InProcessShipFromAddresses(ObjectProvider<NodeAddressApi> nodes, ObjectProvider<ShipFromAddressApi> sellers,
                               ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(NodeAddressApi.class, true, false).length > 0,
                "orders", "inventory", "at.orders.inventory.mode");
        InProcessCalls.requireRunning(beans.getBeanNamesForType(ShipFromAddressApi.class, true, false).length > 0,
                "orders", "seller", "at.orders.seller.mode");
        this.nodes = nodes;
        this.sellers = sellers;
        this.upstream = upstream;
    }

    @Override
    public Optional<TaxQuoteClient.ShipFrom> forShipment(FulfilmentType type, String sellerOrgId, String nodeId,
                                                         String pickupLocationId) {
        if (nodeId != null && !nodeId.isBlank() && type != FulfilmentType.SELLER_FULFILLED) {
            return orEmpty(() -> upstream.call("inventory", () -> nodes.getObject().addressOf(nodeId))
                    .flatMap(a -> shipFrom(a.line1(), a.city(), a.region(), a.postalCode(), a.country(),
                            "FULFILMENT_CENTRE")), "inventory node " + nodeId);
        }
        if (pickupLocationId != null && !pickupLocationId.isBlank()) {
            return orEmpty(() -> upstream.call("seller", () -> sellers.getObject().location(pickupLocationId))
                    .flatMap(l -> shipFrom(l.line1(), l.city(), l.state(), l.postalCode(), l.country(),
                            "SELLER_LOCATION")), "seller " + pickupLocationId);
        }
        if (type == FulfilmentType.SELLER_FULFILLED && sellerOrgId != null && !sellerOrgId.isBlank()) {
            return orEmpty(() -> upstream.call("seller", () -> sellers.getObject().defaultLocation(sellerOrgId))
                    .flatMap(l -> shipFrom(l.line1(), l.city(), l.state(), l.postalCode(), l.country(),
                            "SELLER_LOCATION")), "seller " + sellerOrgId);
        }
        return Optional.empty();
    }

    private static Optional<TaxQuoteClient.ShipFrom> shipFrom(String line1, String city, String state, String postal,
                                                              String country, String source) {
        if (state == null || state.isBlank() || postal == null || postal.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new TaxQuoteClient.ShipFrom(line1, city, state, postal, country, source));
    }

    private static Optional<TaxQuoteClient.ShipFrom> orEmpty(Supplier<Optional<TaxQuoteClient.ShipFrom>> call,
                                                              String what) {
        try {
            return call.get();
        } catch (UpstreamFailure f) {
            if (f.status() != 404) {
                LOG.warn("ship-from of {} unavailable for the tax quote (HTTP {})", what, f.status());
            }
            return Optional.empty();
        }
    }
}

package com.afrotransact.orders.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.inventory.api.NodeAddressApi;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.offers.api.CheckoutOffersApi;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.port.CouponsClient;
import com.afrotransact.orders.port.PickupLocations;
import com.afrotransact.orders.port.ShipFromAddresses;
import com.afrotransact.orders.port.TaxQuoteClient;
import com.afrotransact.orders.port.UpstreamFailure;
import com.afrotransact.seller.api.ShipFromAddressApi;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** Coupons, offer reads, pickup locations and ship-from addresses in-process, with the HTTP adapters' best-effort rules. */
class InProcessOffersAndSellerEdgesTest {

    private static final CallerPrincipal ORDERS = new CallerPrincipal("orders", "orders-service",
            Set.of("offers:read", "sellers:ship-from.read"));

    private final CheckoutOffersApi offers = Mockito.mock(CheckoutOffersApi.class);
    private final ShipFromAddressApi sellers = Mockito.mock(ShipFromAddressApi.class);
    private final NodeAddressApi nodes = Mockito.mock(NodeAddressApi.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final ModuleUpstream upstream;

    InProcessOffersAndSellerEdgesTest() {
        beans.registerSingleton("offers", offers);
        beans.registerSingleton("sellers", sellers);
        beans.registerSingleton("nodes", nodes);
        beans.registerSingleton("callers", (CallerPrincipals) module -> ORDERS);
        upstream = new ModuleUpstream(beans.getBeanProvider(CallerPrincipals.class));
    }

    @Test
    void anUnusableCouponIsAQuoteButARedemptionIsA422() {
        Mockito.when(offers.validate(Mockito.any())).thenReturn(new CheckoutOffersApi.QuoteInfo(false, "EXPIRED", "expired",
                0, 0, 0, null, List.of()));
        Mockito.when(offers.redeem(Mockito.eq("o1"), Mockito.any())).thenThrow(new ModuleApiException(422,
                "offers.coupon-not-applicable", "not applicable"));
        CouponsClient client = new InProcessCouponsClient(beans.getBeanProvider(CheckoutOffersApi.class), upstream, beans);
        CouponsClient.Request request = new CouponsClient.Request("SAVE5", "c1", "o1", "USD",
                List.of(new CouponsClient.Line("offer-1", 1)), 0, false);
        assertThat(client.validate(request).rejection()).isEqualTo("EXPIRED");
        assertThatThrownBy(() -> client.redeem("o1", request)).isInstanceOfSatisfying(UpstreamFailure.class,
                f -> assertThat(f.status()).isEqualTo(422));
    }

    @Test
    void offerInventorySkuIsTrimmedAndAnUnknownOfferIsEmpty() {
        Mockito.when(offers.inventorySku("a")).thenReturn(Optional.of(" SKU-1 "));
        Mockito.when(offers.inventorySku("b")).thenReturn(Optional.empty());
        InProcessOfferInventory inventory = new InProcessOfferInventory(beans.getBeanProvider(CheckoutOffersApi.class),
                upstream, beans);
        assertThat(inventory.inventorySku("a")).contains("SKU-1");
        assertThat(inventory.inventorySku("b")).isEmpty();
        assertThat(inventory.inventorySku(" ")).isEmpty();
    }

    @Test
    void pickupLocationJoinsTheOfferAndTheSellersLocation() {
        Mockito.when(offers.shipFromLocationId("offer-1")).thenReturn(Optional.of("loc-1"));
        Mockito.when(sellers.location("loc-1")).thenReturn(Optional.of(new ShipFromAddressApi.ShipFromPlace("loc-1", "org",
                "1 Main", "Dallas", "TX", "75001", "US", true, 32.7, -96.8)));
        PickupLocations locations = new InProcessPickupLocations(beans.getBeanProvider(CheckoutOffersApi.class),
                beans.getBeanProvider(ShipFromAddressApi.class), upstream, beans);
        assertThat(locations.forOffer("offer-1")).get().isEqualTo(new PickupLocations.PickupLocation("loc-1", "org", true,
                32.7, -96.8));
        assertThat(locations.forOffer("offer-2")).isEmpty();
    }

    @Test
    void shipFromIsBestEffort() {
        Mockito.when(nodes.addressOf("FC1")).thenReturn(Optional.of(new NodeAddressApi.NodeAddress("1 Dock", null, "Newark",
                "NJ", "07101", "US")));
        Mockito.when(nodes.addressOf("FC2")).thenReturn(Optional.of(new NodeAddressApi.NodeAddress("2 Dock", null, "X",
                null, null, "US")));
        Mockito.when(sellers.defaultLocation("org")).thenThrow(new ModuleApiException(403, "auth.missing-permission", "no"));
        ShipFromAddresses addresses = new InProcessShipFromAddresses(beans.getBeanProvider(NodeAddressApi.class),
                beans.getBeanProvider(ShipFromAddressApi.class), upstream, beans);
        assertThat(addresses.forShipment(FulfilmentType.FBA_AFROTRANSACT, "org", "FC1", null)).get()
                .isEqualTo(new TaxQuoteClient.ShipFrom("1 Dock", "Newark", "NJ", "07101", "US", "FULFILMENT_CENTRE"));
        assertThat(addresses.forShipment(FulfilmentType.FBA_AFROTRANSACT, "org", "FC2", null)).isEmpty();
        assertThat(addresses.forShipment(FulfilmentType.SELLER_FULFILLED, "org", null, null)).isEmpty();
    }
}

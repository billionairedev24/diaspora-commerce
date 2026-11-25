package com.afrotransact.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.orders.domain.DefaultShippingPolicy;
import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.HandoverPolicy;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.ShippingSettings;
import com.afrotransact.orders.domain.ShippingSettingsFixtures;
import com.afrotransact.orders.port.CartClient;
import com.afrotransact.orders.port.DeliveryFeeRules;
import com.afrotransact.orders.port.DeliveryRunSlots;
import com.afrotransact.orders.port.MarketEligibility;
import com.afrotransact.orders.port.MemberBenefits;
import com.afrotransact.orders.port.PickupPoints;
import com.afrotransact.orders.port.PreorderEligibility;
import com.afrotransact.orders.port.ProductFactsClient;
import com.afrotransact.orders.port.PromiseClient;
import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.Test;

/**
 * The seller's own shipping (docs/engineering/delivery-fulfilment.md, option A) is reachable only behind the staff
 * switch, which is off by default: a shopper (or a stale page) naming it is refused in plain words, and with the switch on the
 * charge is the seller's one flat charge per package, never the AfroTransact delivery fee, and never waived by a plan.
 */
class SelfShippingCheckoutTest {

    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    private static final ShipTo TO = new ShipTo("addr-1", "Ada Obi", "+17135550142", "1 Main St", null, "Giddings",
            "TX", "78942", "US", null);

    private final AtomicReference<ShippingSettings> settings = new AtomicReference<>(
            ShippingSettingsFixtures.of(599, null, false));
    private final SellerSelfShippingService selfShipping = mock(SellerSelfShippingService.class);
    private final CheckoutActivitiesImpl activities = activities();

    private CheckoutActivitiesImpl activities() {
        CheckoutTax tax = mock(CheckoutTax.class);
        when(tax.tax(any())).thenAnswer(i -> i.getArgument(0));
        MarketEligibility markets = mock(MarketEligibility.class);
        when(markets.check(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        ProductFactsClient catalog = mock(ProductFactsClient.class);
        when(catalog.facts(any())).thenReturn(Map.of());
        PromiseClient promises = mock(PromiseClient.class);
        when(promises.days(anyString(), anyList())).thenReturn(Map.of());
        MemberBenefits benefits = mock(MemberBenefits.class);
        OrdersSettings ordersSettings = new OrdersSettings("org-retail", Duration.ofMinutes(15), Duration.ofSeconds(5),
                Duration.ofHours(1), Set.of(), Map.of(), 10, 3, "America/Chicago");
        return new CheckoutActivitiesImpl(markets, catalog, promises, tax,
                new DefaultShippingPolicy(settings::get, DefaultShippingPolicy.Rates.defaults()),
                mock(HandoverPolicy.class), null, null, null, null, null, null, null, null, null,
                mock(LocalDeliveryQuotes.class), mock(PickupPoints.class), mock(PreorderEligibility.class),
                mock(DeliveryRunSlots.class), mock(DeliveryFeeRules.class), benefits, selfShipping,
                new PreorderSettings(true, false, 100, 24, null, null), ordersSettings,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static CheckoutInput sellerPackage(String option) {
        CartClient.Item item = new CartClient.Item("ATA", "PATA", "Item", null, "org-seller", "Seller", "SELLER", "SELLER",
                2500, "USD", 3, false, "offer-1");
        return new CheckoutInput("order-1", "AT-2026-000001", "cust-1", "idem-1", "checkout-order-1", "USD", TO,
                List.of(item), List.of(new CheckoutInput.Selection("org-seller:SELLER", option, null, null, null)),
                900, NOW, null);
    }

    private void sellerOptedIn(long charge) {
        when(selfShipping.offered(anyList(), anyString())).thenReturn(List.of(
                new SellerSelfShippingService.Setting("org-seller", "USD", true, true, charge, 4)));
    }

    @Test
    void switchOffRefusesTheSellersOwnShippingEvenWhenTheSellerOptedIn() {
        sellerOptedIn(750);
        assertThatThrownBy(() -> activities.prepare(sellerPackage("seller_ships")))
                .isInstanceOf(ApplicationFailure.class)
                .extracting(e -> ((ApplicationFailure) e).getType()).isEqualTo(CheckoutCodes.SELF_SHIPPING_UNAVAILABLE);
    }

    @Test
    void switchOnButSellerNotOptedInIsRefusedToo() {
        settings.set(ShippingSettingsFixtures.withSelfShipping(599, null, true));
        when(selfShipping.offered(anyList(), anyString())).thenReturn(List.of());
        assertThatThrownBy(() -> activities.prepare(sellerPackage("seller_ships")))
                .isInstanceOf(ApplicationFailure.class).hasMessageContaining("does not ship this package");
    }

    @Test
    void switchOnChargesTheSellersOneFlatChargePerPackageNotPerItem() {
        settings.set(ShippingSettingsFixtures.withSelfShipping(599, 1000L, true));
        sellerOptedIn(750);
        CheckoutInput in = sellerPackage("seller_ships");
        CheckoutPlan plan = activities.prepare(in);
        assertThat(plan.packages()).hasSize(1);
        assertThat(plan.packages().getFirst().method()).isEqualTo(DeliveryMethod.SELLER_SELF_SHIPS);
        OrderDraft draft = activities.reserve(in, plan);
        assertThat(draft.shipments()).hasSize(1);
        assertThat(draft.shipments().getFirst().shippingMinor())
                .as("three items, one flat charge, ignoring the AfroTransact fee and its free-above threshold")
                .isEqualTo(750);
        assertThat(draft.shipments().getFirst().feeRule().source()).isEqualTo("SELLER_SELF_SHIPPING");
    }

    @Test
    void withoutASelectionASellerPackageStillHasNoDeliveryAndNothingIsGuessed() {
        settings.set(ShippingSettingsFixtures.withSelfShipping(599, null, true));
        sellerOptedIn(750);
        assertThatThrownBy(() -> activities.prepare(sellerPackage(null)))
                .isInstanceOf(ApplicationFailure.class)
                .extracting(e -> ((ApplicationFailure) e).getType()).isEqualTo(CheckoutCodes.FULFILMENT_UNAVAILABLE);
    }
}

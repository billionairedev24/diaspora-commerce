package com.afrotransact.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.orders.domain.DefaultShippingPolicy;
import com.afrotransact.orders.domain.HandoverPolicy;
import com.afrotransact.orders.domain.LocalDelivery;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.ShippingPolicy;
import com.afrotransact.orders.domain.ShippingSettings;
import com.afrotransact.orders.domain.ShippingSettingsFixtures;
import com.afrotransact.orders.domain.PreorderStage;
import com.afrotransact.orders.port.CartClient;
import com.afrotransact.orders.port.DeliveryFeeRules;
import com.afrotransact.orders.port.DeliveryRunSlots;
import com.afrotransact.orders.port.MarketEligibility;
import com.afrotransact.orders.port.MemberBenefits;
import com.afrotransact.orders.port.PickupLocations;
import com.afrotransact.orders.port.PickupPoints;
import com.afrotransact.orders.port.PreorderEligibility;
import com.afrotransact.orders.port.ProductFactsClient;
import com.afrotransact.orders.port.PromiseClient;
import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Listing rules D16 and D22: the delivery fee the shopper's estimate shows and the amount orders charges come from one
 * place. The vectors in {@code tools/checkout/shipping-fee-vectors.json} are the shopper side's numbers too (gateway-bff
 * {@code ShippingFeeContractTest}, the web and mobile mocks); here every option of every package kind is charged through
 * checkout for a range of cart values and must equal them. A pre-order and an in-stock line in one cart become separate
 * packages, each with its own fee, charged once.
 */
class ShippingFeeContractTest {

    private static final Path VECTORS = Path.of("..", "..", "..", "tools", "checkout", "shipping-fee-vectors.json");
    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    private static final ShipTo TO = new ShipTo("addr-1", "Ada Obi", "+17135550142", "1 Main St", null, "Giddings",
            "TX", "78942", "US", null);

    private final AtomicReference<ShippingSettings> settings = new AtomicReference<>();
    private final PreorderEligibility eligibility = mock(PreorderEligibility.class);
    private final LocalDeliveryQuotes localDelivery = mock(LocalDeliveryQuotes.class);
    private final PickupPoints pickupPoints = mock(PickupPoints.class);
    private final CheckoutActivitiesImpl activities = activities();

    private CheckoutActivitiesImpl activities() {
        HandoverPolicy handover = mock(HandoverPolicy.class);
        CheckoutTax tax = mock(CheckoutTax.class);
        when(tax.tax(any())).thenAnswer(i -> i.getArgument(0));
        MarketEligibility markets = mock(MarketEligibility.class);
        when(markets.check(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        ProductFactsClient catalog = mock(ProductFactsClient.class);
        when(catalog.facts(any())).thenReturn(Map.of());
        PromiseClient promises = mock(PromiseClient.class);
        when(promises.days(anyString(), anyList())).thenReturn(Map.of());
        MemberBenefits benefits = mock(MemberBenefits.class);
        when(benefits.earlyAccessHours(any())).thenReturn(0);
        OrdersSettings ordersSettings = new OrdersSettings("org-retail", Duration.ofMinutes(15),
                Duration.ofSeconds(5), Duration.ofHours(1), Set.of(), Map.of(), 10, 3, "America/Chicago");
        ShippingPolicy policy = new DefaultShippingPolicy(settings::get, DefaultShippingPolicy.Rates.defaults());
        return new CheckoutActivitiesImpl(markets, catalog, promises, tax, policy, handover, null, null, null, null,
                null, null, null, null, null, localDelivery, pickupPoints, eligibility,
                mock(DeliveryRunSlots.class), mock(DeliveryFeeRules.class), benefits,
                new PreorderSettings(true, false, 100, 24, null, null), ordersSettings,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static CartClient.Item item(String atin, String seller, String kind, String fulfilment, long price,
                                        int qty) {
        return new CartClient.Item(atin, "P" + atin, "Item " + atin, null, seller, "Seller", kind, fulfilment, price,
                "USD", qty, false, "offer-" + atin);
    }

    private static CheckoutInput input(List<CartClient.Item> items, List<CheckoutInput.Selection> selections) {
        return new CheckoutInput("order-1", "AT-2026-000001", "cust-1", "idem-1", "checkout-order-1", "USD", TO, items,
                selections, 900, NOW, null);
    }

    /** The shipping prepare plans and checkout charges for one package of {@code kind} with the given selection. */
    private long charged(String kind, long itemsMinor, String option) {
        boolean fba = "AFROTRANSACT".equals(kind);
        String seller = fba ? "org-fba" : "org-seller";
        when(pickupPoints.find("pickup-1")).thenReturn(Optional.of(fba
                ? new PickupPoints.PickupPoint("pickup-1", "PLATFORM_COUNTER", "afrotransact", "Houston counter", true)
                : new PickupPoints.PickupPoint("pickup-1", "SELLER", seller, "The store", true)));
        CheckoutInput in = input(List.of(item("ATA", seller, "SELLER", kind, itemsMinor, 1)), option == null
                ? List.of() : List.of(new CheckoutInput.Selection(seller + ":" + kind, option, null, "pickup-1",
                        null)));
        CheckoutPlan plan = activities.prepare(in);
        assertThat(plan.packages()).hasSize(1);
        CheckoutPlan.Package p = plan.packages().getFirst();
        if (fba) {
            return activities.shippingOf(p, itemsMinor, null);
        }
        OrderDraft draft = activities.reserve(in, plan);
        assertThat(draft.shipments()).hasSize(1);
        return draft.shipments().getFirst().shippingMinor();
    }

    @Test
    void everySharedVectorIsWhatCheckoutCharges() throws Exception {
        JsonNode root = JsonMapper.builder().build().readTree(Files.readString(VECTORS));
        assertThat(root.path("settings").size()).isGreaterThanOrEqualTo(4);
        for (JsonNode s : root.path("settings")) {
            settings.set(ShippingSettingsFixtures.of(s.path("flatFeeMinor").asLong(),
                    s.path("freeAboveMinor").isNumber() ? s.path("freeAboveMinor").asLong() : null, false));
            for (JsonNode c : s.path("carts")) {
                long items = c.path("itemsMinor").asLong();
                if (items <= 0) {
                    continue; // a package always holds goods; the policy itself is checked for 0 in the policy test
                }
                String what = s.path("name").asString() + " / items " + items;
                long delivery = c.path("afrotransactDeliveryMinor").asLong();
                for (String kind : List.of("AFROTRANSACT", "SELLER")) {
                    // Delivery by AfroTransact (kind AFROTRANSACT) and the seller's package delivered by AfroTransact
                    // (SELLER, carrier switch off) cost the same flat fee; no selection means delivery.
                    assertThat(charged(kind, items, "afrotransact_delivery")).as(what + " " + kind).isEqualTo(delivery);
                    assertThat(charged(kind, items, null)).as(what + " " + kind + " default").isEqualTo(delivery);
                    assertThat(charged(kind, items, "pickup")).as(what + " " + kind + " pickup")
                            .isEqualTo(c.path("pickupMinor").asLong());
                }
            }
        }
    }

    @Test
    void aSellerStoreIsCollectedAndDeliveredAtTheSameFlatFeeWhateverTheDistanceTariffQuoted() {
        settings.set(ShippingSettingsFixtures.of(599, 5000L, false));
        PickupLocations.PickupLocation store = new PickupLocations.PickupLocation("loc-1", "org-store", true, 30.0,
                -97.0);
        when(localDelivery.pickupFor(any())).thenReturn(store);
        when(localDelivery.quote(anyString(), anyString(), any(), any())).thenReturn(new LocalDelivery("loc-1", 30.0,
                -97.0, 8.0, 11.0, "ROAD_OSRM", 4200, 700, 3, 20.0));
        for (long items : new long[] {2000, 4999, 5000}) {
            CheckoutInput in = input(List.of(item("ATS", "org-store", "SELLER", LocalDeliveryQuotes.FULFILMENT, items,
                    1)), List.of());
            CheckoutPlan.Package p = activities.prepare(in).packages().getFirst();
            assertThat(p.localDelivery().customerChargeMinor()).as("items " + items)
                    .isEqualTo(items >= 5000 ? 0 : 599);
            assertThat(p.localDelivery().sellerFeeMinor()).as("the seller's pickup fee is kept").isEqualTo(700);
            assertThat(activities.shippingOf(p, items, null)).isEqualTo(items >= 5000 ? 0 : 599);
        }
    }

    @Test
    void theRetiredOptionsAreRefusedInPlainWordsAndNeverCharged() {
        settings.set(ShippingSettingsFixtures.of(599, null, false));
        for (String option : List.of("express", "carrier", "standard")) {
            assertThatThrownBy(() -> charged("AFROTRANSACT", 2000, option)).as(option)
                    .isInstanceOf(ApplicationFailure.class)
                    .hasMessageContaining("delivery options have changed");
        }
        assertThatThrownBy(() -> charged("SELLER", 2000, "quote_required"))
                .hasMessageContaining("Ask us for a quote");
    }

    @Test
    void theCarrierOptionExistsOnlyBehindTheStaffSwitch() {
        settings.set(ShippingSettingsFixtures.of(599, null, true));
        assertThat(charged("SELLER", 2000, "carrier")).as("carrier fee while the switch is on").isEqualTo(599);
        assertThat(charged("SELLER", 3500, "carrier")).isZero();
        assertThat(charged("SELLER", 2000, "afrotransact_delivery")).isEqualTo(599);
    }

    @Test
    void aPreorderAndAnInStockLineBecomeSeparatePackagesEachWithItsOwnFeeAndNothingIsDoubleCharged() {
        settings.set(ShippingSettingsFixtures.of(599, null, false));
        LocalDate deliversOn = LocalDate.parse("2026-11-20");
        when(eligibility.eligibility(anyString(), anyString(), anyString(), anyList(), anyInt())).thenReturn(Map.of(
                "ATB", new PreorderEligibility.Line("ATB", true, "win-1", "LBK-7", "Lubbock Pre-order #7",
                        PreorderStage.OPEN, deliversOn, NOW.plus(Duration.ofDays(3)), true, true, 899, 0, "USD",
                        Set.of("CARD"), "pp-1", null, null)));
        CheckoutInput in = input(List.of(item("ATA", "org-a", "SELLER", "SELLER", 2000, 1),
                item("ATB", "org-b", "SELLER", "SELLER", 1500, 2)), List.of());

        CheckoutPlan plan = activities.prepare(in);

        assertThat(plan.packages()).hasSize(2);
        CheckoutPlan.Package inStock = plan.packages().stream().filter(p -> p.preorder() == null).findFirst()
                .orElseThrow();
        CheckoutPlan.Package preorder = plan.packages().stream().filter(p -> p.preorder() != null).findFirst()
                .orElseThrow();
        assertThat(inStock.lines()).extracting(CheckoutPlan.Line::variantAtin).containsExactly("ATA");
        assertThat(preorder.lines()).extracting(CheckoutPlan.Line::variantAtin).containsExactly("ATB");
        assertThat(preorder.preorder().deliversOn()).as("the window date").isEqualTo(deliversOn);
        assertThat(preorder.preorderFeeMinor()).as("the run's own fee, not the flat fee").isEqualTo(899);

        OrderDraft draft = activities.reserve(in, plan);
        assertThat(draft.shipments()).hasSize(2);
        long inStockShipping = draft.shipments().stream().filter(s -> s.preorder() == null)
                .mapToLong(OrderDraft.DraftShipment::shippingMinor).sum();
        long preorderShipping = draft.shipments().stream().filter(s -> s.preorder() != null)
                .mapToLong(OrderDraft.DraftShipment::shippingMinor).sum();
        assertThat(inStockShipping).as("the in-stock package pays the flat fee once").isEqualTo(599);
        assertThat(preorderShipping).as("the pre-order package pays its window fee once").isEqualTo(899);
    }
}

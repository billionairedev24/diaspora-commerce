package com.afrotransact.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.orders.domain.DefaultShippingPolicy;
import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.FeeRuleSnapshot;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.HandoverPolicy;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.PreorderFulfilment;
import com.afrotransact.orders.domain.PreorderPackage;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.port.CartClient;
import com.afrotransact.orders.port.DeliveryFeeRules;
import com.afrotransact.orders.port.DeliveryRunSlots;
import com.afrotransact.orders.port.MarketEligibility;
import com.afrotransact.orders.port.PreorderEligibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ADR 0005 §3 at checkout: the staff delivery fee rule prices a standard carrier shipment (what an address beyond
 * AfroTransact's coverage pays) and a window's package; the rule applied is kept on the shipment and the order, so later
 * rule edits never change it. Express stays on the flat rates, and a checkout never fails because the rules are
 * unavailable.
 */
class CheckoutDeliveryFeeRuleTest {

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static final ShipTo TO = new ShipTo("addr-1", "Ada Obi", "+17135550142", "1 Main St", null, "Giddings",
            "TX", "78942", "US", null);

    private DeliveryFeeRules rules;
    private CheckoutActivitiesImpl activities;

    @BeforeEach
    void setUp() {
        rules = mock(DeliveryFeeRules.class);
        HandoverPolicy handover = mock(HandoverPolicy.class);
        CheckoutTax tax = mock(CheckoutTax.class);
        when(tax.tax(any())).thenAnswer(i -> i.getArgument(0));
        OrdersSettings settings = new OrdersSettings("org-retail", Duration.ofMinutes(15), Duration.ofSeconds(5),
                Duration.ofHours(1), Set.of(), Map.of(), 10, 3, "America/Chicago");
        activities = new CheckoutActivitiesImpl(mock(MarketEligibility.class), null, null, null, tax,
                new DefaultShippingPolicy(DefaultShippingPolicy.Rates.defaults()), handover, null, null, null, null,
                null, null, null, null, null, null, null, mock(PreorderEligibility.class), mock(DeliveryRunSlots.class), rules,
                new PreorderSettings(true, 100, 24, null, null), settings, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static CheckoutPlan.Line line(String atin, String seller, long price, int qty) {
        return new CheckoutPlan.Line(UUID.randomUUID().toString(), 1, atin, "P" + atin, "Item " + atin, null, qty,
                price, 0, "groceries", false, "US-TX", 3, "offer-1", 0);
    }

    private static CheckoutInput input(String atin, String seller) {
        CartClient.Item item = new CartClient.Item(atin, "P" + atin, "Item " + atin, null, seller, "Seller", "SELLER",
                "SELLER", 2000, "USD", 1, false, "offer-1");
        return new CheckoutInput("order-1", "AT-2026-000001", "cust-1", "idem-1", "checkout-order-1", "USD", TO,
                List.of(item), List.of(), 900, NOW, null);
    }

    private static CheckoutPlan.Package carrier(String level) {
        return new CheckoutPlan.Package("org-a:SELLER", "org-a", "Seller", FulfilmentType.SELLER_FULFILLED,
                DeliveryMethod.CARRIER, level, null, DeliveryPreferences.defaults(), 3,
                List.of(line("ATA", "org-a", 2000, 1)));
    }

    private static FeeRuleSnapshot carrierRule(long amount, String source) {
        return new FeeRuleSnapshot("CARRIER", UUID.randomUUID().toString(), 4, "USD", amount, source, null, null);
    }

    private OrderDraft.DraftShipment reserve(CheckoutPlan.Package p) {
        OrderDraft draft = activities.reserve(input("ATA", "org-a"), new CheckoutPlan("USD", List.of(p)));
        assertThat(draft.shipments()).hasSize(1);
        return draft.shipments().getFirst();
    }

    @Test
    void aStandardCarrierShipmentIsPricedByTheStaffRuleAndKeepsIt() {
        FeeRuleSnapshot rule = carrierRule(1250, "CARRIER_FALLBACK");
        when(rules.carrier("USD", null, 2000)).thenReturn(Optional.of(rule));

        OrderDraft.DraftShipment s = reserve(carrier("standard"));

        assertThat(s.shippingMinor()).as("the rule, not the flat $5.99").isEqualTo(1250);
        assertThat(s.feeRule()).isEqualTo(rule);
    }

    @Test
    void theRuleSnapshotReachesTheOrderAndSurvivesLaterTransitions() {
        FeeRuleSnapshot rule = carrierRule(799, "CARRIER_FALLBACK");
        when(rules.carrier("USD", null, 2000)).thenReturn(Optional.of(rule));
        OrderDraft draft = activities.reserve(input("ATA", "org-a"), new CheckoutPlan("USD",
                List.of(carrier("standard"))));

        Order order = OrderDrafts.toOrder(draft, "pay-1");

        Shipment shipment = order.shipments().getFirst();
        assertThat(shipment.feeRule()).isEqualTo(rule);
        assertThat(shipment.withCapture("cap-1", NOW).feeRule()).as("kept when captured").isEqualTo(rule);
        assertThat(shipment.withTracking("usps", "9400").feeRule()).as("kept with a label").isEqualTo(rule);
    }

    @Test
    void expressStaysOnTheFlatRateAndNeverAsksForARule() {
        OrderDraft.DraftShipment express = reserve(carrier("express"));
        assertThat(express.shippingMinor()).isEqualTo(1499);
        assertThat(express.feeRule()).isNull();
        verify(rules, never()).carrier(any(), any(), anyLong());
    }

    @Test
    void whenNoRuleAppliesTheFlatCarrierRateIsUsed() {
        when(rules.carrier(eq("USD"), any(), anyLong())).thenReturn(Optional.empty());

        OrderDraft.DraftShipment s = reserve(carrier("standard"));

        assertThat(s.shippingMinor()).isEqualTo(599);
        assertThat(s.feeRule()).isNull();
    }

    @Test
    void aWindowPackageKeepsTheDoorRuleOnEveryShipmentAndSpreadsItsFee() {
        FeeRuleSnapshot door = new FeeRuleSnapshot("AFROTRANSACT_DELIVERY", UUID.randomUUID().toString(), 3, "USD",
                899, "RULE", "10–40 mi", new BigDecimal("32"));
        PreorderPackage window = PreorderPackage.placed("win-1", "LBK-7", "Lubbock Pre-order #7",
                LocalDate.parse("2026-10-30"), NOW.plus(Duration.ofDays(3)), PreorderFulfilment.DOOR);
        CheckoutPlan.Package p = new CheckoutPlan.Package("preorder:win-1", "org-a", "Lubbock Pre-order #7",
                FulfilmentType.PREORDER, DeliveryMethod.AFROTRANSACT_DELIVERY, "preorder_door", null,
                DeliveryPreferences.defaults(), 0, List.of(line("ATA", "org-a", 2000, 1)), null, window, 899, door);

        OrderDraft.DraftShipment s = reserve(p);

        assertThat(s.shippingMinor()).isEqualTo(899);
        assertThat(s.feeRule()).isEqualTo(door);
        assertThat(s.feeRule().band()).isEqualTo("10–40 mi");
        verify(rules, never()).carrier(any(), any(), anyLong());
    }

    @Test
    void aPackageIsPricedByARuleOnlyThroughWithFeeRule() {
        FeeRuleSnapshot rule = carrierRule(300, "RULE");
        CheckoutPlan.Package p = carrier("standard");
        assertThat(p.feeRule()).isNull();
        assertThat(p.withFeeRule(rule).feeRule()).isEqualTo(rule);
        assertThat(p.withFeeRule(rule).lines()).isEqualTo(p.lines());
    }
}

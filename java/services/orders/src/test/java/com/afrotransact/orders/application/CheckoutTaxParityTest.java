package com.afrotransact.orders.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.ShippingAllocation;
import com.afrotransact.orders.port.CouponsClient;
import com.afrotransact.orders.port.TaxQuoteClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The checkout estimate and the order must ask tax-returns the same question. The same baskets go through orders'
 * {@link CheckoutTaxImpl} (after the real coupon step) here and through gateway-bff's estimate
 * ({@code checkout_tax_parity_test.go}); both must send exactly the request lines in the shared vectors and add up the
 * answers to the same tax. Same request into the same tax-returns function gives the same tax: this pins the half the
 * two services own, tax-returns' determinism is its own.
 */
class CheckoutTaxParityTest {

    /** Shared with go/services/gateway-bff (checkout_tax_parity_test.go). */
    static final Path VECTORS = Path.of("..", "..", "..", "tools", "checkout", "checkout-tax-parity.json");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void everySharedBasketSendsTaxReturnsTheSameRequestAndAddsUpToTheSameTax() throws Exception {
        JsonNode root = JSON.readTree(Files.readString(VECTORS));
        assertThat(root.path("cases").size()).isGreaterThanOrEqualTo(10);
        for (JsonNode c : root.path("cases")) {
            String name = c.path("name").asString();
            long bps = c.path("rateBps").asLong();
            Map<String, String> outcome = new LinkedHashMap<>();
            List<TaxQuoteClient.Request> sent = new ArrayList<>();
            TaxQuoteClient fakeTaxReturns = request -> {
                sent.add(request);
                return new TaxQuoteClient.Quote("RULES", request.lines().stream().map(l -> {
                    boolean taxable = "TAXABLE".equals(outcome.get(l.lineId()));
                    long base = l.unitPriceMinor() * l.quantity() - l.discountMinor() + l.shippingMinor();
                    return new TaxQuoteClient.LineTax(l.lineId(), taxable ? (base * bps + 5000) / 10000 : 0, 0, "US-TX",
                            null, "RESOLVED", outcome.get(l.lineId()), "AT.CODE", "txcd", "v1", null, "ZIP");
                }).toList());
            };

            List<OrderDraft.DraftShipment> shipments = new ArrayList<>();
            List<CouponsClient.LineDiscount> discounts = new ArrayList<>();
            int seq = 0;
            int lineNo = 0;
            for (JsonNode p : c.path("packages")) {
                String seller = p.path("sellerOrgId").isNull() ? null : p.path("sellerOrgId").asString();
                List<CheckoutPlan.Line> lines = new ArrayList<>();
                for (JsonNode l : p.path("lines")) {
                    lineNo++;
                    outcome.put(l.path("lineId").asString(), l.path("outcome").asString());
                    Map<String, Object> behaviour = new LinkedHashMap<>();
                    l.path("sellerBehaviour").properties().forEach(e -> behaviour.put(e.getKey(),
                            e.getValue().isBoolean() ? (Object) e.getValue().asBoolean() : e.getValue().asString()));
                    lines.add(new CheckoutPlan.Line(l.path("lineId").asString(), lineNo, l.path("atin").asString(),
                            "P-" + lineNo, "Item " + lineNo, null, l.path("quantity").asInt(),
                            l.path("unitPriceMinor").asLong(), 0, "groceries", false, null, 2,
                            l.path("offerId").asString(), 0, l.path("productType").asString(), null, 0, seller,
                            "Seller").withBehaviour(behaviour));
                    if (l.path("discountMinor").asLong() > 0) {
                        discounts.add(new CouponsClient.LineDiscount(l.path("offerId").asString(), seller,
                                l.path("discountMinor").asLong()));
                    }
                }
                // What orders charges the package: its fee less the membership waiver (CheckoutActivitiesImpl
                // .withMembership); the coupon's shipping discount comes off below, in order (applyCoupon).
                long shipping = Math.max(0, p.path("feeMinor").asLong() - p.path("membershipWaivedMinor").asLong());
                seq++;
                shipments.add(new OrderDraft.DraftShipment("s" + seq, seq, seller, "Seller",
                        FulfilmentType.SELLER_FULFILLED, null,
                        p.path("pickup").asBoolean() ? DeliveryMethod.PICKUP : DeliveryMethod.CARRIER, "standard", null,
                        DeliveryPreferences.defaults(), false, false, null, null, shipping, LocalDate.now(),
                        LocalDate.now(), lines));
            }
            JsonNode to = c.path("shipTo");
            OrderDraft draft = new OrderDraft("o1", "AT-2026-AAAAAA", "c1", "w1", c.path("currency").asString(),
                    new ShipTo("a", "Ada", null, "1 Main St", null, to.path("city").asString(),
                            to.path("state").asString(), to.path("postalCode").asString(),
                            to.path("country").asString(), null), false, shipments, Instant.now(), null);
            long shippingDiscount = c.path("shippingDiscountMinor").asLong();
            if (!discounts.isEmpty() || shippingDiscount > 0) {
                long total = discounts.stream().mapToLong(CouponsClient.LineDiscount::discountMinor).sum();
                draft = CheckoutActivitiesImpl.applyCoupon(draft, "CODE", new CouponsClient.Redemption("r1", "c1",
                        "CODE", "REDEEMED", total, shippingDiscount, total + shippingDiscount, "PLATFORM", null,
                        discounts));
            }

            OrderDraft taxed = new CheckoutTaxImpl(fakeTaxReturns,
                    new TaxSettings(ShippingAllocation.parse(c.path("allocation").asString()))).tax(draft);

            TaxQuoteClient.Request request = sent.getFirst();
            assertThat(request.currency()).as(name).isEqualTo(c.path("currency").asString());
            assertThat(request.country()).as(name).isEqualTo(to.path("country").asString());
            assertThat(request.state()).as(name).isEqualTo(to.path("state").asString());
            assertThat(request.postalCode()).as(name).isEqualTo(to.path("postalCode").asString());
            assertThat(request.city()).as(name).isEqualTo(to.path("city").asString());
            JsonNode expected = c.path("expected").path("requestLines");
            assertThat(request.lines()).as(name).hasSize(expected.size());
            for (int i = 0; i < expected.size(); i++) {
                assertThat(sentLine(request.lines().get(i))).as(name + " line " + i).isEqualTo(expected.get(i));
            }
            long tax = taxed.shipments().stream().flatMap(s -> s.lines().stream())
                    .mapToLong(CheckoutPlan.Line::taxMinor).sum();
            assertThat(tax).as(name + " tax").isEqualTo(c.path("expected").path("taxMinor").asLong());
        }
    }

    /** The line as it goes over the wire to tax-returns (HttpTaxQuoteClient): a seller org of null is left out. */
    private static JsonNode sentLine(TaxQuoteClient.Line l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lineId", l.lineId());
        m.put("atin", l.atin());
        if (l.sellerOrgId() != null) {
            m.put("sellerOrgId", l.sellerOrgId());
        }
        m.put("productType", l.productType());
        m.put("quantity", l.quantity());
        m.put("unitPriceMinor", l.unitPriceMinor());
        m.put("discountMinor", l.discountMinor());
        m.put("shippingMinor", l.shippingMinor());
        m.put("sellerBehaviour", l.sellerBehaviour());
        // Through text, so numbers are typed as in the vectors file (int vs long nodes differ in equals).
        return JSON.readTree(JSON.writeValueAsString(m));
    }
}

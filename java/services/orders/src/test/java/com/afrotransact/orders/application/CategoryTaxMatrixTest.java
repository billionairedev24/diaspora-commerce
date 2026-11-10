package com.afrotransact.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.orders.application.OrdersEventPayloads.LineSummary;
import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.OrderLine;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.ShippingAllocation;
import com.afrotransact.orders.port.TaxQuoteClient;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The category tax verification matrix, orders side: for each of the 105 decision rows, the answer tax-returns gives
 * (built from the row's expected outcome, internal code and Stripe code, as tax-returns' own matrix verifies) goes through the
 * checkout tax step with the seller's behaviour on the line: a definite EXEMPT or TAXABLE decision is snapshotted on the order
 * line and carried by the order-placed line event (outcome, internal code, provider code, rule version, address precision),
 * anything else stops the checkout with the plain message and nothing is defaulted. Writes
 * {@code target/category-tax-matrix/orders.json}.
 */
class CategoryTaxMatrixTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final String RULES = "tx-2026.11.09-draft.1";

    @Test
    @SuppressWarnings("unchecked")
    void everyDecisionRowIsSnapshottedOnTheOrderLineOrBlocksTheCheckout() throws IOException {
        Map<String, Object> scenarios;
        try (InputStream in = getClass().getResourceAsStream("/taxonomy/scenarios.json")) {
            scenarios = JSON.readValue(in, MAP);
        }
        List<Map<String, Object>> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Object o : (List<Object>) scenarios.get("scenarios")) {
            Map<String, Object> s = (Map<String, Object>) o;
            Map<String, Object> inputs = (Map<String, Object>) s.get("inputs");
            Map<String, Object> expect = (Map<String, Object>) s.get("expect");
            Map<String, Object> behaviour = (Map<String, Object>) inputs.get("sellerBehaviour");
            int n = ((Number) s.get("scenario")).intValue();
            String id = "scenario " + n + " (" + s.get("categoryCode") + ")";
            boolean resolved = "RESOLVED".equals(expect.get("resolveStatus"));
            List<TaxQuoteClient.Request> sent = new ArrayList<>();
            TaxQuoteClient quotes = request -> {
                sent.add(request);
                return new TaxQuoteClient.Quote("RULES", request.lines().stream().map(l -> answer(l, expect, resolved)).toList());
            };
            CheckoutTax tax = new CheckoutTaxImpl(quotes, new TaxSettings(ShippingAllocation.PROPORTIONAL));
            CheckoutPlan.Line line = new CheckoutPlan.Line("line-" + n, 1, "ATMX" + n, "PMX" + n, "Item " + n, null, 1, 4_000, 0,
                    "groceries", false, null, 2, null, 500, String.valueOf(s.get("categoryCode")), null, 0, "org_a", "Seller")
                    .withBehaviour(behaviour);
            OrderDraft draft = draft(799, line);

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("scenario", n);
            r.put("categoryCode", s.get("categoryCode"));
            r.put("expected", expect);
            boolean ok = true;
            if (resolved) {
                OrderDraft taxed = tax.tax(draft);
                OrderLine saved = OrderDrafts.toOrder(taxed, "pay-1").shipments().getFirst().lines().getFirst();
                LineSummary event = LineSummary.of(saved);
                TaxQuoteClient.Request req = sent.getFirst();
                long base = 4_000 - 500 + 799;
                long wantTax = "EXEMPT".equals(expect.get("outcome")) ? 0
                        : BigDecimal.valueOf(base).multiply(new BigDecimal("0.0825")).setScale(0, RoundingMode.HALF_UP).longValue();
                Map<String, Object> snap = new LinkedHashMap<>();
                snap.put("outcome", saved.taxDecision().outcome());
                snap.put("internalTaxCode", saved.taxDecision().internalTaxCode());
                snap.put("providerTaxCode", saved.taxDecision().providerTaxCode());
                snap.put("ruleVersion", saved.taxDecision().ruleVersion());
                snap.put("addressPrecision", saved.taxDecision().addressPrecision());
                snap.put("taxMinor", saved.taxMinor());
                snap.put("shippingAllocMinor", saved.shippingAllocMinor());
                snap.put("eventTaxOutcome", event.taxOutcome());
                snap.put("eventInternalTaxCode", event.internalTaxCode());
                snap.put("eventProviderTaxCode", event.providerTaxCode());
                snap.put("eventTaxRuleVersion", event.taxRuleVersion());
                snap.put("eventTaxAddressPrecision", event.taxAddressPrecision());
                r.put("snapshot", snap);
                ok = expect.get("outcome").equals(saved.taxDecision().outcome())
                        && expect.get("internalTaxCode").equals(saved.taxDecision().internalTaxCode())
                        && expect.get("stripeCode").equals(saved.taxDecision().providerTaxCode())
                        && RULES.equals(saved.taxDecision().ruleVersion()) && "ZIP".equals(saved.taxDecision().addressPrecision())
                        && saved.taxMinor() == wantTax && saved.shippingAllocMinor() == 799
                        && expect.get("outcome").equals(event.taxOutcome())
                        && expect.get("internalTaxCode").equals(event.internalTaxCode())
                        && expect.get("stripeCode").equals(event.providerTaxCode()) && RULES.equals(event.taxRuleVersion())
                        && "ZIP".equals(event.taxAddressPrecision())
                        && "org_a".equals(req.lines().getFirst().sellerOrgId())
                        && behaviour.equals(req.lines().getFirst().sellerBehaviour())
                        && "o1".equals(req.orderId()) && ("ATMX" + n).equals(req.lines().getFirst().atin());
                if (!ok) {
                    failures.add(id + ": snapshot " + snap + " does not match " + expect);
                }
            } else {
                try {
                    tax.tax(draft);
                    failures.add(id + ": the checkout did not stop for " + expect.get("resolveStatus"));
                    ok = false;
                } catch (OrdersRuleViolation e) {
                    boolean plain = CheckoutCodes.TAX_LINE_BLOCKED.equals(e.code()) && e.getMessage().contains("Item " + n)
                            && !e.getMessage().contains(String.valueOf(expect.get("resolveStatus")));
                    r.put("blocked", e.getMessage());
                    ok = plain;
                    if (!plain) {
                        failures.add(id + ": blocked without the plain message: " + e.getMessage());
                    }
                }
            }
            r.put("pass", ok);
            report.add(r);
        }
        assertThat(report).hasSize(105);
        Path out = Path.of("target", "category-tax-matrix");
        Files.createDirectories(out);
        Files.writeString(out.resolve("orders.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                Map.of("scenarios", report, "failures", failures)), StandardCharsets.UTF_8);
        assertThat(failures).as("matrix failures (see target/category-tax-matrix/orders.json)").isEmpty();
    }

    @Test
    void aLineTheEngineCannotDecideNeverGetsADefaultRate() {
        TaxQuoteClient.Quote none = new TaxQuoteClient.Quote("RULES", List.of());
        CheckoutTax tax = new CheckoutTaxImpl(request -> none, new TaxSettings(ShippingAllocation.PROPORTIONAL));
        CheckoutPlan.Line line = new CheckoutPlan.Line("l1", 1, "ATX", "P", "Item", null, 1, 1_000, 0, "groceries", false, null, 2,
                null, 0, "nuts-seeds", null, 0, "org_a", "Seller");

        assertThatThrownBy(() -> tax.tax(draft(0, line))).hasMessageContaining("missing some lines");
    }

    private static TaxQuoteClient.LineTax answer(TaxQuoteClient.Line l, Map<String, Object> expect, boolean resolved) {
        if (!resolved) {
            return new TaxQuoteClient.LineTax(l.lineId(), 0, 0, null, null, (String) expect.get("resolveStatus"), "BLOCKED", null,
                    null, RULES, "This item can't be bought right now.", null);
        }
        long base = l.unitPriceMinor() * l.quantity() - l.discountMinor() + l.shippingMinor();
        boolean exempt = "EXEMPT".equals(expect.get("outcome"));
        long tax = exempt ? 0 : BigDecimal.valueOf(base).multiply(new BigDecimal("0.0825")).setScale(0, RoundingMode.HALF_UP).longValue();
        long shippingTax = exempt ? 0 : BigDecimal.valueOf(l.shippingMinor()).multiply(new BigDecimal("0.0825"))
                .setScale(0, RoundingMode.HALF_UP).longValue();
        return new TaxQuoteClient.LineTax(l.lineId(), tax, shippingTax, "US-TX", exempt ? null : "US-TX-770", "RESOLVED",
                (String) expect.get("outcome"), (String) expect.get("internalTaxCode"), (String) expect.get("stripeCode"), RULES,
                null, "ZIP");
    }

    private static OrderDraft draft(long shipping, CheckoutPlan.Line... lines) {
        OrderDraft.DraftShipment s = new OrderDraft.DraftShipment("s1", 1, "org_a", "Seller", FulfilmentType.SELLER_FULFILLED, null,
                DeliveryMethod.CARRIER, "standard", null, DeliveryPreferences.defaults(), false, false, null, null, shipping,
                LocalDate.now(), LocalDate.now(), List.of(lines));
        return new OrderDraft("o1", "AT-2026-MATRIX", "c1", "w1", "USD", new ShipTo("a", "Ada", null, "1 Main St", null, "Houston",
                "TX", "77002", "US", null), false, List.of(s), Instant.now(), null);
    }
}

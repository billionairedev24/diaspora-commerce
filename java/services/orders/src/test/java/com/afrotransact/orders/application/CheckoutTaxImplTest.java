package com.afrotransact.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.afrotransact.orders.config.OrdersProperties;
import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.OrderLine;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.ShippingAllocation;
import com.afrotransact.orders.domain.TaxDecision;
import com.afrotransact.orders.port.TaxQuoteClient;
import com.afrotransact.orders.port.UpstreamFailure;
import org.junit.jupiter.api.Test;

/** Checkout tax: the stored decision per line, blocked lines, delivery allocation, snapshot, no fallback rate. */
class CheckoutTaxImplTest {

    private final List<TaxQuoteClient.Request> requests = new ArrayList<>();
    private RuntimeException failure;
    /** Answers per line id; defaults to a RESOLVED taxable line at 8.25 % of (items - discount + shipping). */
    private Function<TaxQuoteClient.Line, TaxQuoteClient.LineTax> answer = CheckoutTaxImplTest::taxable;

    private final TaxQuoteClient quotes = request -> {
        requests.add(request);
        if (failure != null) {
            throw failure;
        }
        return new TaxQuoteClient.Quote("RULES", request.lines().stream().map(answer).toList());
    };

    private static TaxQuoteClient.LineTax taxable(TaxQuoteClient.Line l) {
        long base = l.unitPriceMinor() * l.quantity() - l.discountMinor() + l.shippingMinor();
        long shippingTax = Math.round(l.shippingMinor() * 0.0825);
        return new TaxQuoteClient.LineTax(l.lineId(), Math.round(base * 0.0825), shippingTax, "US-TX", "US-TX-770",
                "RESOLVED", "TAXABLE", "AT.PANTRY.TAXABLE", "txcd_40060003", "tx-test", null, "ZIP");
    }

    private static TaxQuoteClient.LineTax exempt(TaxQuoteClient.Line l) {
        return new TaxQuoteClient.LineTax(l.lineId(), 0, 0, "US-TX", null, "RESOLVED", "EXEMPT",
                "AT.NUTS_SEEDS.PLAIN_MULTI_SERVING_2_5OZ_PLUS", "txcd_40040000", "tx-test", null, "ZIP");
    }

    private static TaxQuoteClient.LineTax undecided(TaxQuoteClient.Line l, String status) {
        return new TaxQuoteClient.LineTax(l.lineId(), 0, 0, null, null, status, "BLOCKED", null, null, "tx-test",
                "awaiting review", null);
    }

    private static CheckoutPlan.Line line(String id, int no, String title, int qty, long unit, long discount) {
        return new CheckoutPlan.Line(id, no, "ATIN-" + id, "P-" + id, title, null, qty, unit, 0, "groceries", false,
                null, 2, null, discount, "pantry", null, 0, "org", "Seller");
    }

    private static OrderDraft draft(long shipping, CheckoutPlan.Line... lines) {
        OrderDraft.DraftShipment s = new OrderDraft.DraftShipment("s1", 1, "org", "Seller",
                FulfilmentType.SELLER_FULFILLED, null, DeliveryMethod.CARRIER, "standard", null,
                DeliveryPreferences.defaults(), false, false, null, null, shipping, LocalDate.now(), LocalDate.now(),
                List.of(lines));
        return new OrderDraft("o1", "AT-2026-AAAAAA", "c1", "w1", "USD", new ShipTo("a", "Ada", null, "1 Main St",
                null, "Houston", "TX", "77002", "US", null), false, List.of(s), Instant.now(), null);
    }

    private CheckoutTax proportional() {
        return new CheckoutTaxImpl(quotes, new TaxSettings(ShippingAllocation.PROPORTIONAL));
    }

    private static CheckoutPlan.Line taxed(OrderDraft d, String lineId) {
        return d.shipments().getFirst().lines().stream().filter(l -> l.lineId().equals(lineId)).findFirst()
                .orElseThrow();
    }

    @Test
    void sendsTheVariantAtinSellerAndEachLinesShareOfTheDeliveryAndKeepsTheDecisionSnapshot() {
        OrderDraft out = proportional().tax(draft(599, line("l2", 2, "Oil", 2, 500, 0),
                line("l1", 1, "Rice", 1, 1000, 100)));
        TaxQuoteClient.Request sent = requests.getFirst();
        assertThat(sent.state()).isEqualTo("TX");
        assertThat(sent.lines()).extracting(TaxQuoteClient.Line::lineId).containsExactly("l1", "l2");
        assertThat(sent.lines()).extracting(TaxQuoteClient.Line::atin).containsExactly("ATIN-l1", "ATIN-l2");
        assertThat(sent.lines()).extracting(TaxQuoteClient.Line::sellerOrgId).containsOnly("org");
        // net item value 900 and 1000: 599 splits 284 / 315 (largest remainder), never all on the first line
        assertThat(sent.lines()).extracting(TaxQuoteClient.Line::shippingMinor).containsExactly(284L, 315L);
        assertThat(sent.lines().stream().mapToLong(TaxQuoteClient.Line::shippingMinor).sum()).isEqualTo(599);

        CheckoutPlan.Line first = taxed(out, "l1");
        assertThat(first.shippingAllocMinor()).isEqualTo(284);
        assertThat(first.taxDecision()).isEqualTo(new TaxDecision("RESOLVED", "TAXABLE", "AT.PANTRY.TAXABLE",
                "txcd_40060003", "tx-test", "RULES", "ZIP"));
        assertThat(first.localTaxJurisdiction()).isEqualTo("US-TX-770");
        assertThat(first.shippingTaxMinor()).isEqualTo(23);
    }

    @Test
    void sendsTheOrderIdAndEachLinesSellerBehaviourAndBlocksWhatTaxReturnsCannotDecide() {
        // The cookie line carries the owner's behaviour; the second line (another seller's offer) carries none, so a
        // category that needs sold_heated answers MISSING_FACTS and the checkout stops: nothing is assumed or defaulted.
        answer = l -> l.sellerBehaviour().isEmpty() ? undecided(l, "MISSING_FACTS") : taxable(l);
        CheckoutPlan.Line owner = line("l1", 1, "Cookies", 1, 1000, 0)
                .withBehaviour(Map.of("sold_heated", false, "utensils_provided", false));
        CheckoutPlan.Line other = line("l2", 2, "Cookies (other seller)", 1, 1000, 0);

        assertThatThrownBy(() -> proportional().tax(draft(0, owner, other))).isInstanceOf(OrdersRuleViolation.class)
                .hasMessageContaining("Cookies (other seller)");

        TaxQuoteClient.Request sent = requests.getFirst();
        assertThat(sent.orderId()).isEqualTo("o1");
        assertThat(sent.lines().get(0).sellerBehaviour()).containsEntry("sold_heated", false)
                .containsEntry("utensils_provided", false);
        assertThat(sent.lines().get(1).sellerBehaviour()).isEmpty();
    }

    @Test
    void theSnapshotReachesTheOrderLine() {
        OrderDraft out = proportional().tax(draft(300, line("l1", 1, "Rice", 1, 1000, 0)));
        OrderLine saved = OrderDrafts.toOrder(out, "pay-1").shipments().getFirst().lines().getFirst();
        assertThat(saved.taxDecision().internalTaxCode()).isEqualTo("AT.PANTRY.TAXABLE");
        assertThat(saved.taxDecision().provider()).isEqualTo("RULES");
        assertThat(saved.shippingAllocMinor()).isEqualTo(300);
    }

    @Test
    void aDeliveryTiedOnlyToExemptLinesIsNotTaxed() {
        answer = CheckoutTaxImplTest::exempt;
        OrderDraft out = proportional().tax(draft(499, line("l1", 1, "Egusi", 1, 2500, 0),
                line("l2", 2, "Groundnuts", 1, 1500, 0)));
        assertThat(out.shipments().getFirst().lines()).allSatisfy(l -> {
            assertThat(l.taxMinor()).isZero();
            assertThat(l.taxDecision().outcome()).isEqualTo("EXEMPT");
        });
        assertThat(out.shipments().getFirst().lines().stream().mapToLong(CheckoutPlan.Line::shippingAllocMinor).sum())
                .isEqualTo(499);
    }

    @Test
    void aMixedPackageSplitsTheDeliveryBetweenItsExemptAndTaxableLines() {
        answer = l -> l.lineId().equals("ex") ? exempt(l) : taxable(l);
        OrderDraft out = proportional().tax(draft(800, line("ex", 1, "Egusi", 1, 1000, 0),
                line("tx", 2, "Sauce pan", 1, 3000, 0)));
        assertThat(taxed(out, "ex").shippingAllocMinor()).isEqualTo(200);
        assertThat(taxed(out, "ex").taxMinor()).isZero();
        assertThat(taxed(out, "tx").shippingAllocMinor()).isEqualTo(600);
        // taxable line: (3000 + 600) x 8.25 %
        assertThat(taxed(out, "tx").taxMinor()).isEqualTo(297);
        assertThat(taxed(out, "tx").shippingTaxMinor()).isEqualTo(50);
    }

    @Test
    void firstLineIsTheRollbackToThePreviousAttribution() {
        new CheckoutTaxImpl(quotes, new TaxSettings(ShippingAllocation.FIRST_LINE)).tax(draft(599,
                line("l1", 1, "Rice", 1, 1000, 0), line("l2", 2, "Oil", 1, 1000, 0)));
        assertThat(requests.getFirst().lines()).extracting(TaxQuoteClient.Line::shippingMinor)
                .containsExactly(599L, 0L);
    }

    @Test
    void aLineWithoutADefiniteDecisionBlocksTheCheckoutAndNamesTheItemInPlainWords() {
        answer = l -> l.lineId().equals("l2") ? undecided(l, "NEEDS_APPROVAL") : taxable(l);
        assertThatThrownBy(() -> proportional().tax(draft(0, line("l1", 1, "Rice", 1, 1000, 0),
                line("l2", 2, "Gift card", 1, 2500, 0))))
                .isInstanceOfSatisfying(OrdersRuleViolation.class, e -> {
                    assertThat(e.code()).isEqualTo(CheckoutCodes.TAX_LINE_BLOCKED);
                    assertThat(e.getMessage()).contains("We can't sell Gift card yet").contains("Remove it")
                            .doesNotContain("Rice").doesNotContain("ATIN").doesNotContain("NEEDS_APPROVAL")
                            .doesNotContainIgnoringCase("tax");
                });
    }

    @Test
    void everyStatusButResolvedBlocksAndAMissingOrIncompleteDecisionIsNeverGuessed() {
        for (String status : List.of("MISSING_FACTS", "BLOCKED", "NEEDS_APPROVAL", "REFINE_CODE", "CONFLICT_OPEN")) {
            answer = l -> undecided(l, status);
            assertThatThrownBy(() -> proportional().tax(draft(0, line("l1", 1, "Rice", 1, 1000, 0))))
                    .as(status).isInstanceOf(OrdersRuleViolation.class);
        }
        // an answer without any decision details (tax-returns before the category build) is not a decision
        answer = l -> new TaxQuoteClient.LineTax(l.lineId(), 82, 0, "US-TX", null);
        assertThatThrownBy(() -> proportional().tax(draft(0, line("l1", 1, "Rice", 1, 1000, 0))))
                .isInstanceOf(OrdersRuleViolation.class);
        // RESOLVED without an outcome is not definite either
        answer = l -> new TaxQuoteClient.LineTax(l.lineId(), 82, 0, "US-TX", null, "RESOLVED", null, "AT.X", null,
                "tx-test", null, null);
        assertThatThrownBy(() -> proportional().tax(draft(0, line("l1", 1, "Rice", 1, 1000, 0))))
                .isInstanceOf(OrdersRuleViolation.class);
    }

    @Test
    void severalBlockedItemsAreListedOnceEach() {
        assertThat(CheckoutTaxImpl.blockedMessage(List.of("Gift card", "Photo print", "Gift card")))
                .isEqualTo("We can't sell these items yet: Gift card, Photo print. We're still checking its details. "
                        + "Remove them from your cart to continue, or try again later.");
    }

    @Test
    void whenTaxReturnsIsDownTheStepFailsAndNoRateIsChargedInstead() {
        failure = new UpstreamFailure("tax-returns", 503, "tax-returns.down", "down", true, List.of(), null);
        assertThatThrownBy(() -> proportional().tax(draft(599, line("l1", 1, "Rice", 1, 1000, 0))))
                .isInstanceOfSatisfying(UpstreamFailure.class, f -> assertThat(f.retryable()).isTrue());
    }

    @Test
    void aQuoteMissingALineIsRetriedNotGuessed() {
        TaxQuoteClient partial = request -> new TaxQuoteClient.Quote("RULES",
                List.of(taxable(request.lines().getFirst())));
        CheckoutTax tax = new CheckoutTaxImpl(partial, new TaxSettings(ShippingAllocation.PROPORTIONAL));
        assertThatThrownBy(() -> tax.tax(draft(0, line("l1", 1, "Rice", 1, 1000, 0), line("l2", 2, "Oil", 1, 500, 0))))
                .isInstanceOfSatisfying(UpstreamFailure.class, f -> assertThat(f.code())
                        .isEqualTo("tax-returns.incomplete-quote"));
    }

    @Test
    void redeemingCreditIsATenderTheItemsAreStillTaxedAtTheirOwnRatesAndNoGiftCardLineExists() {
        OrderDraft base = draft(0, line("l1", 1, "Egusi", 1, 1000, 0), line("l2", 2, "Sauce pan", 1, 3000, 0));
        OrderDraft paidWithCredit = new OrderDraft(base.orderId(), base.orderNumber(), base.customerId(),
                base.workflowId(), base.currency(), base.shipTo(), base.reserved(), base.shipments(),
                base.createdAt(), null, null, List.of(), false, true);
        answer = l -> l.lineId().equals("l1") ? exempt(l) : taxable(l);
        OrderDraft out = proportional().tax(paidWithCredit);
        // the quote sees the two items only: credit and gift cards are not lines, so they are never taxed
        assertThat(requests.getFirst().lines()).extracting(TaxQuoteClient.Line::lineId).containsExactly("l1", "l2");
        assertThat(out.useCredit()).isTrue();
        assertThat(taxed(out, "l1").taxMinor()).isZero();
        assertThat(taxed(out, "l2").taxMinor()).isEqualTo(248);
    }

    @Test
    void thereIsNoInterimRateTableLeftAnywhere() {
        assertThatThrownBy(() -> Class.forName("com.afrotransact.orders.adapter.out.tax.StateRateTaxCalculator"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.afrotransact.orders.port.TaxCalculator"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThat(OrdersProperties.Tax.class.getRecordComponents()).extracting(c -> c.getName())
                .containsExactly("shippingAllocation");
        assertThat(TaxSettings.class.getRecordComponents()).extracting(c -> c.getName())
                .containsExactly("shippingAllocation");
        assertThat(new TaxSettings(null).shippingAllocation()).isEqualTo(ShippingAllocation.PROPORTIONAL);
    }
}

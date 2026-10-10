package com.afrotransact.retail.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DefaultThreeWayMatcherTest {

    private static final MatchTolerance ONE_PERCENT = new MatchTolerance(BigDecimal.ONE);

    private final ThreeWayMatcher matcher = new DefaultThreeWayMatcher();
    private final Supplier supplier = Fixtures.approvedAccra();

    private PurchaseOrder sent() {
        return Fixtures.sent(Fixtures.po(supplier, List.of(
                PoLine.of(1, UUID.randomUUID(), "AT-JOL-6", "Jollof", "ACC-J", 1000, 10_000),
                PoLine.of(2, UUID.randomUUID(), "AT-SHI-12", "Shito", "ACC-S", 500, 20_000))));
    }

    private PurchaseOrder fullyReceived() {
        return sent().receive(List.of(new ReceivedQty(1, 1000, 0), new ReceivedQty(2, 500, 0)), "GRN", "x",
                Fixtures.NOW);
    }

    private MatchResult match(PurchaseOrder po, long claims, InvoiceLine... lines) {
        return matcher.match(po, "GHS", List.of(lines), claims, ONE_PERCENT);
    }

    @Test
    void poPriceTimesReceivedQuantityMatches() {
        MatchResult r = match(fullyReceived(), 0, new InvoiceLine(1, 1000, 10_000), new InvoiceLine(2, 500, 20_000));
        assertThat(r.outcome()).isEqualTo(MatchOutcome.MATCH);
        assertThat(r.expectedTotalMinor()).isEqualTo(20_000_000);
        assertThat(r.invoicedTotalMinor()).isEqualTo(20_000_000);
        assertThat(r.totalVariancePercent()).isEqualByComparingTo("0");
        assertThat(r.lines()).extracting(MatchLine::status).containsOnly(MatchLineStatus.MATCH);
        assertThat(r.reasons()).isEmpty();
    }

    @Test
    void priceWithinOnePercentStillMatches() {
        MatchResult up = match(fullyReceived(), 0, new InvoiceLine(1, 1000, 10_100));
        assertThat(up.outcome()).isEqualTo(MatchOutcome.MATCH);
        assertThat(up.lines().getFirst().priceVariancePercent()).isEqualByComparingTo("1.00");
        MatchResult down = match(fullyReceived(), 0, new InvoiceLine(1, 1000, 9_900));
        assertThat(down.outcome()).isEqualTo(MatchOutcome.MATCH);
    }

    @Test
    void priceBeyondToleranceIsAnExceptionForTheBuyer() {
        MatchResult r = match(fullyReceived(), 0, new InvoiceLine(1, 1000, 10_101), new InvoiceLine(2, 500, 20_000));
        assertThat(r.outcome()).isEqualTo(MatchOutcome.EXCEPTION);
        assertThat(r.lines().getFirst().status()).isEqualTo(MatchLineStatus.PRICE_VARIANCE);
        assertThat(r.lines().get(1).status()).isEqualTo(MatchLineStatus.MATCH);
        assertThat(r.reasons()).singleElement().asString().contains("AT-JOL-6").contains("1.01%");
    }

    @Test
    void invoiceAheadOfGoodsIsHeldForPayment() {
        PurchaseOrder partial = sent().receive(List.of(new ReceivedQty(1, 600, 0)), "GRN", "x", Fixtures.NOW);
        MatchResult r = match(partial, 0, new InvoiceLine(1, 1000, 10_000));
        assertThat(r.outcome()).isEqualTo(MatchOutcome.HOLD);
        assertThat(r.lines().getFirst().status()).isEqualTo(MatchLineStatus.AWAITING_RECEIPT);
        assertThat(r.lines().getFirst().expectedAmountMinor()).isEqualTo(6_000_000);
    }

    @Test
    void invoicingMoreThanReceivedAfterReceivingIsAnException() {
        PurchaseOrder received = sent().receive(List.of(new ReceivedQty(1, 1000, 0), new ReceivedQty(2, 450, 0),
                new ReceivedQty(2, 50, 0)), "GRN", "x", Fixtures.NOW);
        PurchaseOrder invoicedOnce = received.recordInvoiced(Map.of(2, 500), "INV-0", "fin", Fixtures.NOW);
        MatchResult r = match(invoicedOnce, 0, new InvoiceLine(2, 10, 20_000));
        assertThat(r.outcome()).isEqualTo(MatchOutcome.EXCEPTION);
        assertThat(r.lines().getFirst().status()).isEqualTo(MatchLineStatus.QTY_OVER_ORDERED);
    }

    @Test
    void shortReceiptHoldsAFullInvoiceAndOverInvoicingTheOrderIsAnException() {
        PurchaseOrder shortShipped = sent().receive(List.of(new ReceivedQty(1, 990, 0), new ReceivedQty(2, 500, 0)),
                "GRN", "x", Fixtures.NOW);
        assertThat(shortShipped.status()).isEqualTo(PoStatus.PARTIALLY_RECEIVED);
        assertThat(match(shortShipped, 0, new InvoiceLine(1, 1000, 10_000)).outcome()).isEqualTo(MatchOutcome.HOLD);
        PurchaseOrder over = fullyReceived().recordInvoiced(Map.of(1, 995), "INV-1", "fin", Fixtures.NOW);
        MatchResult r = match(over, 0, new InvoiceLine(1, 6, 10_000));
        assertThat(r.lines().getFirst().status()).isEqualTo(MatchLineStatus.QTY_OVER_ORDERED);
        assertThat(r.outcome()).isEqualTo(MatchOutcome.EXCEPTION);
    }

    @Test
    void partialInvoicesMatchAgainstUninvoicedReceipts() {
        PurchaseOrder po = fullyReceived().recordInvoiced(Map.of(1, 400), "INV-1", "fin", Fixtures.NOW);
        MatchResult r = match(po, 0, new InvoiceLine(1, 600, 10_000));
        assertThat(r.outcome()).isEqualTo(MatchOutcome.MATCH);
        assertThat(r.lines().getFirst().previouslyInvoicedQty()).isEqualTo(400);
    }

    @Test
    void linesNotOnThePoAndDuplicatesAreExceptions() {
        MatchResult notOnPo = match(fullyReceived(), 0, new InvoiceLine(9, 1, 100));
        assertThat(notOnPo.outcome()).isEqualTo(MatchOutcome.EXCEPTION);
        assertThat(notOnPo.lines().getFirst().status()).isEqualTo(MatchLineStatus.NOT_ON_PO);
        MatchResult duplicate = match(fullyReceived(), 0, new InvoiceLine(1, 500, 10_000),
                new InvoiceLine(1, 500, 10_000));
        assertThat(duplicate.outcome()).isEqualTo(MatchOutcome.EXCEPTION);
    }

    @Test
    void currencyMismatchIsAnException() {
        MatchResult r = matcher.match(fullyReceived(), "USD", List.of(new InvoiceLine(1, 1000, 10_000)), 0,
                ONE_PERCENT);
        assertThat(r.outcome()).isEqualTo(MatchOutcome.EXCEPTION);
        assertThat(r.reasons()).anyMatch(s -> s.contains("currency"));
    }

    @Test
    void openClaimsHoldPaymentEvenWhenPricesMatch() {
        MatchResult r = match(fullyReceived(), 2, new InvoiceLine(1, 1000, 10_000));
        assertThat(r.outcome()).isEqualTo(MatchOutcome.HOLD);
        assertThat(r.reasons()).anyMatch(s -> s.contains("2 open receiving claim"));
    }

    @Test
    void exceptionBeatsHold() {
        MatchResult r = match(fullyReceived(), 1, new InvoiceLine(1, 1000, 12_000));
        assertThat(r.outcome()).isEqualTo(MatchOutcome.EXCEPTION);
    }

    @Test
    void zeroToleranceRequiresExactPrices() {
        MatchResult r = matcher.match(fullyReceived(), "GHS", List.of(new InvoiceLine(1, 1000, 10_001)), 0,
                new MatchTolerance(BigDecimal.ZERO));
        assertThat(r.outcome()).isEqualTo(MatchOutcome.EXCEPTION);
    }
}

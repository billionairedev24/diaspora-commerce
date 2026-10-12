package com.afrotransact.seller.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.PlanFeeCollection;
import com.afrotransact.seller.domain.Subscription;
import com.afrotransact.seller.port.EarningsCollectionRepository;
import com.afrotransact.seller.port.EarningsCollectionRepository.Change;
import com.afrotransact.seller.port.EarningsCollectionRepository.Row;
import com.afrotransact.seller.port.EarningsWithholdingGateway;
import com.afrotransact.seller.port.GatewayException;
import com.afrotransact.seller.port.InvoiceSnapshot;
import com.afrotransact.seller.port.StripeBillingGateway;
import com.afrotransact.seller.port.StripeEvent;
import com.afrotransact.seller.port.SubscriptionRepository;
import com.afrotransact.seller.port.SubscriptionTerms;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/** S298 (seller side): plan invoices paid from earnings first, the card for the rest; releases on void / refund. */
class EarningsCollectionServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    private final EarningsWithholdingGateway payments = mock(EarningsWithholdingGateway.class);
    private final EarningsCollectionRepository collections = mock(EarningsCollectionRepository.class);
    private final SubscriptionRepository subs = mock(SubscriptionRepository.class);
    private final StripeBillingGateway stripe = mock(StripeBillingGateway.class);
    private final EarningsCollectionServiceImpl service = new EarningsCollectionServiceImpl(payments, collections,
            subs, stripe, TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void theKillSwitchOffClaimsNothing() {
        when(payments.enabled()).thenReturn(false);
        assertThat(service.onInvoiceCreated("org_1", invoice("draft", "subscription_cycle", 3900)))
                .isEqualTo("ignored");
        assertThat(service.processOpen()).isZero();
        assertThat(service.collect("in_1")).isEqualTo(EarningsCollectionService.Outcome.DONE);
        verify(collections, never()).claim(anyString(), anyString(), any(), anyLong(), anyLong(), eq(NOW));
    }

    @Test
    void onlyEarningsFirstRenewalDraftsAreClaimedOnceForWhatCreditsDoNotCover() {
        when(payments.enabled()).thenReturn(true);
        when(subs.terms("org_1")).thenReturn(terms("org_1", PlanFeeCollection.EARNINGS_FIRST, 0));
        when(subs.terms("org_2")).thenReturn(terms("org_2", PlanFeeCollection.CARD, 0));
        when(subs.terms("org_3")).thenReturn(terms("org_3", PlanFeeCollection.EARNINGS_FIRST, 1000));
        when(subs.terms("org_4")).thenReturn(terms("org_4", PlanFeeCollection.EARNINGS_FIRST, 5000));
        when(collections.claim("in_1", "org_1", "INV-1", 3900, 3900, NOW)).thenReturn(true, false);
        when(collections.claim("in_1", "org_3", "INV-1", 3900, 2900, NOW)).thenReturn(true);
        assertThat(service.onInvoiceCreated("org_1", invoice("draft", "subscription_create", 3900)))
                .as("the first invoice is charged at once").isEqualTo("ignored");
        assertThat(service.onInvoiceCreated("org_2", invoice("draft", "subscription_cycle", 3900)))
                .as("Charge my card").isEqualTo("ignored-card");
        assertThat(service.onInvoiceCreated("org_1", invoice("draft", "subscription_cycle", 3900)))
                .isEqualTo("earnings-requested");
        assertThat(service.onInvoiceCreated("org_1", invoice("draft", "subscription_cycle", 3900)))
                .isEqualTo("earnings-duplicate");
        assertThat(service.onInvoiceCreated("org_1", invoice("open", "subscription_cycle", 3900)))
                .as("already finalised").isEqualTo("ignored");
        assertThat(service.onInvoiceCreated("org_3", invoice("draft", "subscription_cycle", 3900)))
                .as("a $10 credit pays first").isEqualTo("earnings-requested");
        assertThat(service.onInvoiceCreated("org_4", invoice("draft", "subscription_cycle", 3900)))
                .isEqualTo("ignored-covered-by-credit");
    }

    @Test
    void withheldEarningsBecomeABalanceCreditWithPerInvoiceKeys() {
        when(payments.enabled()).thenReturn(true);
        when(collections.lock("in_1")).thenReturn(Optional.of(row("REQUESTED", 3900, 0)));
        when(stripe.retrieveInvoice("in_1")).thenReturn(invoice("draft", "subscription_cycle", 3900));
        when(payments.withhold("org_1", "in_1", "INV-1", 3900, "usd")).thenReturn(
                new EarningsWithholdingGateway.Withholding(true, 2500, "wh_1", "Only $25.00 was available"));
        when(subs.find("org_1")).thenReturn(Optional.of(sub("org_1")));
        when(stripe.creditBalance(eq("cus_org_1"), eq(2500L), anyString(), eq("earnings-in_1")))
                .thenReturn(new StripeBillingGateway.BalanceCredit("cbtxn_1", -2500));
        when(collections.dueReleases(NOW, "in_1", 50)).thenReturn(List.of());

        assertThat(service.collect("in_1")).isEqualTo(EarningsCollectionService.Outcome.DONE);
        verify(collections).transition("in_1", "REQUESTED", "WITHHELD",
                new Change(2500L, null, "wh_1", null, "Only $25.00 was available"), NOW);
        verify(collections).transition("in_1", "WITHHELD", "CREDITED", new Change(null, 2500L, null, "cbtxn_1",
                null), NOW);
        verify(subs).setCreditBalance("org_1", 2500);
    }

    @Test
    void declinedOrFinalisedInvoicesAreLeftToTheCard() {
        when(payments.enabled()).thenReturn(true);
        when(collections.lock("in_1")).thenReturn(Optional.of(row("REQUESTED", 3900, 0)));
        when(stripe.retrieveInvoice("in_1")).thenReturn(invoice("draft", "subscription_cycle", 3900));
        when(payments.withhold("org_1", "in_1", "INV-1", 3900, "usd")).thenReturn(
                new EarningsWithholdingGateway.Withholding(false, 0, "wh_1", "No delivered earnings"));
        assertThat(service.collect("in_1")).isEqualTo(EarningsCollectionService.Outcome.DONE);
        verify(collections).transition("in_1", "REQUESTED", "DECLINED", new Change(0L, null, "wh_1", null,
                "No delivered earnings"), NOW);
        verify(stripe, never()).creditBalance(anyString(), anyLong(), anyString(), anyString());

        when(collections.lock("in_2")).thenReturn(Optional.of(new Row("in_2", "org_1", "INV-2", 3900L, 3900, "WITHHELD",
                3900, 0, 0, "wh_2", null, null, 0, null, null, NOW, NOW)));
        when(stripe.retrieveInvoice("in_2")).thenReturn(invoice("open", "subscription_cycle", 3900));
        assertThat(service.collect("in_2")).isEqualTo(EarningsCollectionService.Outcome.DONE);
        verify(collections).transition(eq("in_2"), eq("WITHHELD"), eq("MISSED"), any(), eq(NOW));
        verify(collections).requestRelease("plan-fee-release-in_2", "in_2", 3900, "invoice finalised before the "
                + "credit", false, NOW);
    }

    @Test
    void aTransientFailureAsksForARetryAndGivesUpAfterMaxAttempts() {
        when(payments.enabled()).thenReturn(true);
        when(collections.lock("in_1")).thenReturn(Optional.of(row("REQUESTED", 3900, 0)));
        when(stripe.retrieveInvoice("in_1")).thenReturn(invoice("draft", "subscription_cycle", 3900));
        when(payments.withhold(anyString(), anyString(), any(), anyLong(), anyString()))
                .thenThrow(new GatewayException("seller.payments-unavailable", "down", false, null));
        assertThat(service.collect("in_1")).isEqualTo(EarningsCollectionService.Outcome.RETRY);
        verify(collections).failedAttempt("in_1", "down", NOW.plusSeconds(60), NOW);

        Row tired = new Row("in_1", "org_1", "INV-1", 3900L, 3900, "REQUESTED", 0, 0, 0, null, null, null,
                EarningsCollectionServiceImpl.MAX_ATTEMPTS - 1, "down", NOW, NOW, NOW);
        when(collections.lock("in_1")).thenReturn(Optional.of(tired));
        assertThat(service.collect("in_1")).isEqualTo(EarningsCollectionService.Outcome.DONE);
        verify(collections).transition(eq("in_1"), eq("REQUESTED"), eq("FAILED"), any(), eq(NOW));
        verify(collections).requestRelease("plan-fee-release-in_1", "in_1", 3900, "collection gave up", false, NOW);
    }

    @Test
    void aVoidTakesTheCreditBackAndAnOutOfBandRefundReleasesThatPart() {
        when(collections.lock("in_1")).thenReturn(Optional.of(row("CREDITED", 3900, 3900)));
        assertThat(service.onInvoiceVoided("org_1", invoice("void", "subscription_cycle", 0)))
                .isEqualTo("earnings-release-requested");
        verify(collections).requestRelease("plan-fee-release-in_1", "in_1", 3900, "invoice voided", true, NOW);

        assertThat(service.onCreditNote("org_1", new StripeEvent.CreditNote("cn_1", "in_1", "cus_org_1", 3900,
                1000))).isEqualTo("earnings-release-requested");
        verify(collections).requestRelease("plan-fee-release-cn_1", "in_1", 1000,
                "refunded out of band (credit note cn_1)", false, NOW);
        assertThat(service.onCreditNote("org_1", new StripeEvent.CreditNote("cn_2", "in_1", "cus_org_1", 3900, 0)))
                .as("refunded to the card or the balance: Stripe handles it").isEqualTo("ignored");
    }

    @Test
    void aReleaseDebitsTheBalanceOnceThenReleasesAtPayments() {
        when(collections.dueReleases(NOW, "in_1", 50)).thenReturn(List.of("plan-fee-release-in_1"));
        when(collections.lockRelease("plan-fee-release-in_1")).thenReturn(Optional.of(new EarningsCollectionRepository
                .Release("plan-fee-release-in_1", "in_1", "org_1", 3900, "invoice voided", true, null, 0)));
        when(collections.find("in_1")).thenReturn(Optional.of(row("CREDITED", 3900, 3900)));
        when(subs.find("org_1")).thenReturn(Optional.of(sub("org_1")));
        when(stripe.debitBalance(eq("cus_org_1"), eq(3900L), anyString(),
                eq("earnings-plan-fee-release-in_1"))).thenReturn(new StripeBillingGateway.BalanceCredit("cbtxn_d", 0));
        service.release("in_1");
        verify(collections).releaseDebited("plan-fee-release-in_1", "cbtxn_d", NOW);
        verify(payments).release("org_1", "in_1", 3900, "invoice voided", "plan-fee-release-in_1");
        verify(collections).addReleased("in_1", 3900, "RELEASED", NOW);
        verify(collections).releaseDone("plan-fee-release-in_1", "cbtxn_d", NOW);
    }

    private static Row row(String status, long withheld, long credited) {
        return new Row("in_1", "org_1", "INV-1", 3900L, 3900, status, withheld, credited, 0, "wh_1", null, null, 0,
                null, NOW, NOW, NOW);
    }

    private static SubscriptionTerms terms(String org, PlanFeeCollection mode, long credit) {
        return new SubscriptionTerms(org, null, null, null, null, null, null, credit, mode, "SELLER", NOW, "u1");
    }

    private static Subscription sub(String org) {
        Subscription s = Subscription.selected(org, "seller_growth", 1, Cadence.MONTHLY, null, NOW);
        return s.withStripe("cus_" + org, "sub_" + org, "si_" + org, s.status(), null, NOW, NOW, false, NOW);
    }

    private static InvoiceSnapshot invoice(String status, String reason, long amount) {
        return new InvoiceSnapshot("in_1", "cus_1", "sub_1", "INV-1", status, reason, amount, 0, "usd", NOW, NOW, null,
                null, 0, null, NOW, List.of(), null);
    }
}

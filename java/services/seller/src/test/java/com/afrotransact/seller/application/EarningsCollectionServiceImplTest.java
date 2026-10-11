package com.afrotransact.seller.application;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.afrotransact.seller.domain.Subscription;
import com.afrotransact.seller.port.EarningsCollectionRepository;
import com.afrotransact.seller.port.EarningsWithholdingGateway;
import com.afrotransact.seller.port.InvoiceSnapshot;
import com.afrotransact.seller.port.StripeBillingGateway;
import com.afrotransact.seller.port.SubscriptionRepository;
import com.afrotransact.seller.port.SubscriptionTerms;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/** S297 (seller side): plan fees collected from earnings when payments withholds them; the card is the fallback. */
class EarningsCollectionServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    private final EarningsWithholdingGateway payments = mock(EarningsWithholdingGateway.class);
    private final EarningsCollectionRepository collections = mock(EarningsCollectionRepository.class);
    private final SubscriptionRepository subs = mock(SubscriptionRepository.class);
    private final StripeBillingGateway stripe = mock(StripeBillingGateway.class);
    private final EarningsCollectionServiceImpl service = new EarningsCollectionServiceImpl(payments, collections,
            subs, stripe, null, TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void offByDefaultNothingIsClaimed() {
        when(payments.enabled()).thenReturn(false);
        assertThat(service.onInvoiceCreated("org_1", invoice("draft", "subscription_cycle", 3900)))
                .isEqualTo("ignored");
        assertThat(service.processOpen()).isZero();
        verify(collections, never()).claim(anyString(), anyString(), anyLong(), eq(NOW));
    }

    @Test
    void onlyOptedInRenewalDraftsAreClaimedOnce() {
        when(payments.enabled()).thenReturn(true);
        when(subs.terms("org_1")).thenReturn(new SubscriptionTerms("org_1", null, null, null, null, null, null, 0,
                true));
        when(subs.terms("org_2")).thenReturn(SubscriptionTerms.none("org_2"));
        when(collections.claim("in_1", "org_1", 3900, NOW)).thenReturn(true, false);
        assertThat(service.onInvoiceCreated("org_1", invoice("draft", "subscription_create", 3900)))
                .as("the first invoice is charged to the card").isEqualTo("ignored");
        assertThat(service.onInvoiceCreated("org_2", invoice("draft", "subscription_cycle", 3900)))
                .isEqualTo("ignored-not-opted-in");
        assertThat(service.onInvoiceCreated("org_1", invoice("draft", "subscription_cycle", 3900)))
                .isEqualTo("earnings-requested");
        assertThat(service.onInvoiceCreated("org_1", invoice("draft", "subscription_cycle", 3900)))
                .isEqualTo("earnings-duplicate");
        assertThat(service.onInvoiceCreated("org_1", invoice("open", "subscription_cycle", 3900)))
                .as("already finalised").isEqualTo("ignored");
    }

    @Test
    void withheldFeesBecomeABalanceCreditDeclinedOnesFallBackToTheCard() {
        when(payments.enabled()).thenReturn(true);
        when(subs.find("org_1")).thenReturn(Optional.of(sub("org_1")));
        when(subs.find("org_2")).thenReturn(Optional.of(sub("org_2")));
        when(collections.open(3, 50)).thenReturn(List.of(
                new EarningsCollectionRepository.Row("in_1", "org_1", 3900, "REQUESTED", null, null, null, NOW),
                new EarningsCollectionRepository.Row("in_2", "org_2", 3900, "REQUESTED", null, null, null, NOW)));
        when(payments.withhold("org_1", "in_1", 3900, "usd")).thenReturn(
                new EarningsWithholdingGateway.Withholding(true, "wh_1", null));
        when(payments.withhold("org_2", "in_2", 3900, "usd")).thenReturn(
                new EarningsWithholdingGateway.Withholding(false, null, "Not enough pending earnings"));
        when(stripe.creditBalance(eq("cus_org_1"), eq(3900L), anyString(), eq("earnings-in_1")))
                .thenReturn(new StripeBillingGateway.BalanceCredit("cbtxn_1", -3900));
        assertThat(service.processOpen()).isEqualTo(1);
        verify(collections).update("in_1", "WITHHELD", "wh_1", null, null, NOW);
        verify(collections).update("in_1", "CREDITED", null, "cbtxn_1", null, NOW);
        verify(subs).setCreditBalance("org_1", 3900);
        verify(collections).update("in_2", "DECLINED", null, null, "Not enough pending earnings", NOW);
        verify(stripe, never()).creditBalance(eq("cus_org_2"), anyLong(), anyString(), anyString());
    }

    private static Subscription sub(String org) {
        Subscription s = Subscription.selected(org, "seller_growth", 1, Cadence.MONTHLY, null, NOW);
        return s.withStripe("cus_" + org, "sub_" + org, "si_" + org, s.status(), null, NOW, NOW, false, NOW);
    }

    private static InvoiceSnapshot invoice(String status, String reason, long amount) {
        return new InvoiceSnapshot("in_1", "cus_1", "sub_1", null, status, reason, amount, 0, "usd", NOW, NOW, null,
                null, 0, null, NOW, List.of(), null);
    }
}

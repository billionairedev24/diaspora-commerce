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
import com.afrotransact.seller.domain.Invoice;
import com.afrotransact.seller.domain.InvoicePayment;
import com.afrotransact.seller.domain.PaymentSource;
import com.afrotransact.seller.port.EarningsCollectionRepository;
import com.afrotransact.seller.port.EarningsCollectionRepository.Change;
import com.afrotransact.seller.port.EarningsCollectionRepository.Row;
import com.afrotransact.seller.port.EarningsWithholdingGateway;
import com.afrotransact.seller.port.GatewayException;
import com.afrotransact.seller.port.InvoiceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

/** S298 (seller side): the earnings step of plan collection; the card pays what earnings do not cover. */
class EarningsCollectionServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    private final EarningsWithholdingGateway payments = mock(EarningsWithholdingGateway.class);
    private final EarningsCollectionRepository collections = mock(EarningsCollectionRepository.class);
    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final EarningsCollectionServiceImpl service = new EarningsCollectionServiceImpl(payments, collections,
            invoices, TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void theKillSwitchOffClaimsNothing() {
        when(payments.enabled()).thenReturn(false);
        assertThat(service.request(invoice("open", 3900), 3900)).isFalse();
        assertThat(service.processReleases()).isZero();
        assertThat(service.collect("inv_1")).isEqualTo(EarningsCollectionService.Outcome.DONE);
        verify(collections, never()).claim(anyString(), anyString(), any(), anyLong(), anyLong(), eq(NOW));
    }

    @Test
    void anInvoiceIsClaimedOnceForWhatCreditDidNotCover() {
        when(payments.enabled()).thenReturn(true);
        when(collections.claim("inv_1", "org_1", "AT-INV-1", 3900, 2900, NOW)).thenReturn(true, false);
        assertThat(service.request(invoice("open", 3900), 2900)).isTrue();
        assertThat(service.request(invoice("open", 3900), 2900)).as("claimed once").isFalse();
        assertThat(service.request(invoice("open", 3900), 0)).as("credit covered it").isFalse();
    }

    @Test
    void withheldEarningsBecomeAnInvoicePayment() {
        when(payments.enabled()).thenReturn(true);
        when(collections.lock("inv_1")).thenReturn(Optional.of(row("REQUESTED", 3900, 0)));
        when(invoices.findById("inv_1")).thenReturn(Optional.of(invoice("open", 3900)));
        when(payments.withhold("org_1", "inv_1", "AT-INV-1", 3900, "usd")).thenReturn(
                new EarningsWithholdingGateway.Withholding(true, 2500, "wh_1", "Only $25.00 was available"));
        when(collections.dueReleases(NOW, "inv_1", 50)).thenReturn(List.of());

        assertThat(service.collect("inv_1")).isEqualTo(EarningsCollectionService.Outcome.DONE);
        verify(collections).transition("inv_1", "REQUESTED", "WITHHELD",
                new Change(2500L, null, "wh_1", null, "Only $25.00 was available"), NOW);
        verify(collections).transition("inv_1", "WITHHELD", "CREDITED", new Change(null, 2500L, null, null, null), NOW);
        ArgumentCaptor<InvoicePayment> paid = ArgumentCaptor.forClass(InvoicePayment.class);
        verify(invoices).addPayment(paid.capture());
        assertThat(paid.getValue().source()).isEqualTo(PaymentSource.EARNINGS);
        assertThat(paid.getValue().amountCents()).isEqualTo(2500);
        assertThat(paid.getValue().status()).isEqualTo(InvoicePayment.APPLIED);
        verify(invoices).refreshPaid("inv_1", NOW);
    }

    @Test
    void declinedOrAlreadySettledInvoicesAreLeftToTheCard() {
        when(payments.enabled()).thenReturn(true);
        when(collections.lock("inv_1")).thenReturn(Optional.of(row("REQUESTED", 3900, 0)));
        when(invoices.findById("inv_1")).thenReturn(Optional.of(invoice("open", 3900)));
        when(payments.withhold("org_1", "inv_1", "AT-INV-1", 3900, "usd")).thenReturn(
                new EarningsWithholdingGateway.Withholding(false, 0, "wh_1", "No delivered earnings"));
        assertThat(service.collect("inv_1")).isEqualTo(EarningsCollectionService.Outcome.DONE);
        verify(collections).transition("inv_1", "REQUESTED", "DECLINED", new Change(0L, null, "wh_1", null,
                "No delivered earnings"), NOW);
        verify(invoices, never()).addPayment(any());

        when(collections.lock("inv_2")).thenReturn(Optional.of(new Row("inv_2", "org_1", "AT-INV-2", 3900L, 3900,
                "WITHHELD", 3900, 0, 0, "wh_2", null, null, 0, null, null, NOW, NOW)));
        when(invoices.findById("inv_2")).thenReturn(Optional.of(invoice("paid", 3900)));
        assertThat(service.collect("inv_2")).isEqualTo(EarningsCollectionService.Outcome.DONE);
        verify(collections).transition(eq("inv_2"), eq("WITHHELD"), eq("MISSED"), any(), eq(NOW));
        verify(collections).requestRelease("plan-fee-release-inv_2", "inv_2", 3900, "invoice no longer open", false,
                NOW);
    }

    @Test
    void aTransientFailureAsksForARetryAndGivesUpAfterMaxAttempts() {
        when(payments.enabled()).thenReturn(true);
        when(collections.lock("inv_1")).thenReturn(Optional.of(row("REQUESTED", 3900, 0)));
        when(invoices.findById("inv_1")).thenReturn(Optional.of(invoice("open", 3900)));
        when(payments.withhold(anyString(), anyString(), any(), anyLong(), anyString()))
                .thenThrow(new GatewayException("seller.payments-unavailable", "down", false, null));
        assertThat(service.collect("inv_1")).isEqualTo(EarningsCollectionService.Outcome.RETRY);
        verify(collections).failedAttempt("inv_1", "down", NOW.plusSeconds(60), NOW);

        Row tired = new Row("inv_1", "org_1", "AT-INV-1", 3900L, 3900, "REQUESTED", 0, 0, 0, null, null, null,
                EarningsCollectionServiceImpl.MAX_ATTEMPTS - 1, "down", NOW, NOW, NOW);
        when(collections.lock("inv_1")).thenReturn(Optional.of(tired));
        assertThat(service.collect("inv_1")).isEqualTo(EarningsCollectionService.Outcome.DONE);
        verify(collections).transition(eq("inv_1"), eq("REQUESTED"), eq("FAILED"), any(), eq(NOW));
        verify(collections).requestRelease("plan-fee-release-inv_1", "inv_1", 3900, "collection gave up", false, NOW);
    }

    @Test
    void aVoidedInvoiceReleasesWhatWasWithheld() {
        when(collections.lock("inv_1")).thenReturn(Optional.of(row("CREDITED", 3900, 3900)));
        assertThat(service.onInvoiceVoided("org_1", "inv_1")).isEqualTo("earnings-release-requested");
        verify(collections).requestRelease("plan-fee-release-inv_1", "inv_1", 3900, "invoice voided", false, NOW);
    }

    @Test
    void aReleaseGoesToPaymentsOnceWithItsOwnKey() {
        when(payments.enabled()).thenReturn(true);
        when(collections.dueReleases(NOW, "inv_1", 50)).thenReturn(List.of("plan-fee-release-inv_1"));
        when(collections.lockRelease("plan-fee-release-inv_1")).thenReturn(Optional.of(new EarningsCollectionRepository
                .Release("plan-fee-release-inv_1", "inv_1", "org_1", 3900, "invoice voided", false, null, 0)));
        when(collections.find("inv_1")).thenReturn(Optional.of(row("CREDITED", 3900, 3900)));
        service.release("inv_1");
        verify(payments).release("org_1", "inv_1", 3900, "invoice voided", "plan-fee-release-inv_1");
        verify(collections).addReleased("inv_1", 3900, "RELEASED", NOW);
        verify(collections).releaseDone("plan-fee-release-inv_1", null, NOW);
    }

    private static Row row(String status, long withheld, long credited) {
        return new Row("inv_1", "org_1", "AT-INV-1", 3900L, 3900, status, withheld, credited, 0, "wh_1", null, null,
                0, null, NOW, NOW, NOW);
    }

    private static Invoice invoice(String status, long due) {
        return new Invoice("inv_1", "org_1", "AT-INV-1", NOW, NOW.plusSeconds(86_400), due, 0, due, 0, "USD", status,
                Invoice.REASON_CYCLE, "seller_growth", 1, Cadence.MONTHLY, NOW, null, 0, null, null, NOW, List.of(),
                List.of());
    }
}

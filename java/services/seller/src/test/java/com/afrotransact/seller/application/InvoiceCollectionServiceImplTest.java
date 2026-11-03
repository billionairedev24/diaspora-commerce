package com.afrotransact.seller.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.DunningState;
import com.afrotransact.seller.domain.Invoice;
import com.afrotransact.seller.domain.InvoicePayment;
import com.afrotransact.seller.domain.Organisation;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.PaymentMethod;
import com.afrotransact.seller.domain.PaymentMethodKind;
import com.afrotransact.seller.domain.PaymentSource;
import com.afrotransact.seller.domain.PlanFeeCollection;
import com.afrotransact.seller.domain.Subscription;
import com.afrotransact.seller.domain.SubscriptionStatus;
import com.afrotransact.seller.port.BillingCustomerRepository;
import com.afrotransact.seller.port.GatewayException;
import com.afrotransact.seller.port.InvoiceRepository;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.seller.port.PaymentMethodRepository;
import com.afrotransact.seller.port.PlanPaymentsGateway;
import com.afrotransact.seller.port.SubscriptionRepository;
import com.afrotransact.seller.port.SubscriptionTerms;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

/** Platform credit first, then earnings if the seller chose that, then the saved card; retries and dunning. */
class InvoiceCollectionServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final SubscriptionRepository subs = mock(SubscriptionRepository.class);
    private final PaymentMethodRepository methods = mock(PaymentMethodRepository.class);
    private final BillingCustomerRepository customers = mock(BillingCustomerRepository.class);
    private final OrganisationRepository orgs = mock(OrganisationRepository.class);
    private final PlanPaymentsGateway gateway = mock(PlanPaymentsGateway.class);
    private final EarningsCollectionService earnings = mock(EarningsCollectionService.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final InvoiceCollectionServiceImpl service = new InvoiceCollectionServiceImpl(invoices, subs, methods,
            customers, orgs, gateway, earnings, events, TransactionOperations.withoutTransaction(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final Subscription sub = new Subscription("org_1", "seller_growth", 1, Cadence.MONTHLY,
            SubscriptionStatus.ACTIVE, PaymentMethodKind.CARD, NOW, null, NOW, NOW.plus(Duration.ofDays(30)), false,
            null, null, null, DunningState.NONE, 0, null, NOW, 2);

    InvoiceCollectionServiceImplTest() {
        when(subs.lock("org_1")).thenReturn(Optional.of(sub));
        when(subs.find("org_1")).thenReturn(Optional.of(sub));
        when(subs.update(any())).thenAnswer(i -> i.getArgument(0));
        when(customers.find("org_1")).thenReturn(Optional.of("cus_1"));
        when(methods.list("org_1")).thenReturn(List.of(card()));
        when(earnings.enabled()).thenReturn(true);
        when(subs.terms("org_1")).thenReturn(terms(0, PlanFeeCollection.CARD));
    }

    @Test
    void platformCreditPaysFirstAndCoversASmallInvoiceEntirely() {
        Invoice open = invoice(3900, 0, List.of());
        Invoice covered = invoice(3900, 3900, List.of(payment(PaymentSource.CREDIT, 3900, InvoicePayment.APPLIED)));
        when(invoices.lock("inv_1")).thenReturn(Optional.of(open), Optional.of(covered));
        when(invoices.findById("inv_1")).thenReturn(Optional.of(paidInvoice()));
        when(subs.terms("org_1")).thenReturn(terms(5000, PlanFeeCollection.CARD));

        assertThat(service.collect("inv_1")).isEqualTo(InvoiceCollectionService.Outcome.PAID);
        verify(subs).adjustCreditBalance("org_1", -3900);
        ArgumentCaptor<InvoicePayment> p = ArgumentCaptor.forClass(InvoicePayment.class);
        verify(invoices).addPayment(p.capture());
        assertThat(p.getValue().source()).isEqualTo(PaymentSource.CREDIT);
        assertThat(p.getValue().amountCents()).isEqualTo(3900);
        verify(gateway, never()).charge(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(),
                anyString());
    }

    @Test
    void creditPaysWhatItCanAndTheCardPaysTheRest() {
        Invoice open = invoice(3900, 0, List.of());
        Invoice afterCredit = invoice(3900, 1000, List.of(payment(PaymentSource.CREDIT, 1000, InvoicePayment.APPLIED)));
        when(invoices.lock("inv_1")).thenReturn(Optional.of(open), Optional.of(afterCredit), Optional.of(afterCredit));
        when(invoices.findById("inv_1")).thenReturn(Optional.of(afterCredit));
        when(subs.terms("org_1")).thenReturn(terms(1000, PlanFeeCollection.CARD));
        when(gateway.charge(eq("cus_1"), eq("pm_1"), eq(2900L), eq("USD"), eq("inv_1"), anyString(),
                eq("inv-inv_1-a0"))).thenReturn(new PlanPaymentsGateway.Charge(PlanPaymentsGateway.Status.SUCCEEDED,
                "pi_1", null, null));

        service.collect("inv_1");
        verify(subs).adjustCreditBalance("org_1", -1000);
        ArgumentCaptor<InvoicePayment> p = ArgumentCaptor.forClass(InvoicePayment.class);
        verify(invoices, times(2)).addPayment(p.capture());
        assertThat(p.getAllValues()).extracting(InvoicePayment::source).containsExactly(PaymentSource.CREDIT,
                PaymentSource.CARD);
        assertThat(p.getAllValues().get(1).amountCents()).isEqualTo(2900);
    }

    @Test
    void earningsAreOnlyAskedForWhenTheSellerChoseThem() {
        Invoice open = invoice(3900, 0, List.of());
        when(invoices.lock("inv_1")).thenReturn(Optional.of(open));
        when(invoices.findById("inv_1")).thenReturn(Optional.of(open));
        when(gateway.charge(any(), any(), anyLong(), any(), any(), any(), any())).thenReturn(
                new PlanPaymentsGateway.Charge(PlanPaymentsGateway.Status.SUCCEEDED, "pi_1", null, null));

        service.collect("inv_1");
        verify(earnings, never()).request(any(), anyLong());
        verify(earnings, never()).collect(anyString());
    }

    @Test
    void whenTheSellerChoseEarningsTheyAreTriedAfterCreditAndBeforeTheCard() {
        when(subs.terms("org_1")).thenReturn(terms(0, PlanFeeCollection.EARNINGS_FIRST));
        Invoice open = invoice(3900, 0, List.of());
        Invoice partly = invoice(3900, 2500, List.of(payment(PaymentSource.EARNINGS, 2500, InvoicePayment.APPLIED)));
        when(invoices.lock("inv_1")).thenReturn(Optional.of(open), Optional.of(open), Optional.of(partly));
        when(invoices.findById("inv_1")).thenReturn(Optional.of(partly));
        when(earnings.collect("inv_1")).thenReturn(EarningsCollectionService.Outcome.DONE);
        when(gateway.charge(eq("cus_1"), eq("pm_1"), eq(1400L), any(), any(), any(), any())).thenReturn(
                new PlanPaymentsGateway.Charge(PlanPaymentsGateway.Status.SUCCEEDED, "pi_1", null, null));

        service.collect("inv_1");
        verify(earnings).request(open, 3900);
        verify(gateway).charge(eq("cus_1"), eq("pm_1"), eq(1400L), any(), any(), any(), any());
    }

    @Test
    void whileEarningsAreStillBeingCollectedTheCardIsNotCharged() {
        when(subs.terms("org_1")).thenReturn(terms(0, PlanFeeCollection.EARNINGS_FIRST));
        Invoice open = invoice(3900, 0, List.of());
        when(invoices.lock("inv_1")).thenReturn(Optional.of(open));
        when(earnings.collect("inv_1")).thenReturn(EarningsCollectionService.Outcome.RETRY);

        assertThat(service.collect("inv_1")).isEqualTo(InvoiceCollectionService.Outcome.PENDING);
        verify(invoices).recordAttempt(eq("inv_1"), eq(0), eq(NOW.plus(InvoiceCollectionServiceImpl.EARNINGS_RETRY)),
                anyString(), eq(NOW));
        verify(gateway, never()).charge(any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void aDeclinedCardIsRetriedOnTheScheduleAndTheSellerIsPastDue() {
        Invoice open = invoice(3900, 0, List.of());
        when(invoices.lock("inv_1")).thenReturn(Optional.of(open));
        when(invoices.findById("inv_1")).thenReturn(Optional.of(open));
        when(gateway.charge(any(), any(), anyLong(), any(), any(), any(), any())).thenReturn(
                new PlanPaymentsGateway.Charge(PlanPaymentsGateway.Status.FAILED, "pi_x", "card_declined",
                        "Your card was declined."));

        assertThat(service.collect("inv_1")).isEqualTo(InvoiceCollectionService.Outcome.RETRY);
        verify(invoices).recordAttempt("inv_1", 1, NOW.plus(Duration.ofDays(1)), "Your card was declined.", NOW);
        ArgumentCaptor<Subscription> next = ArgumentCaptor.forClass(Subscription.class);
        verify(subs).update(next.capture());
        assertThat(next.getValue().status()).isEqualTo(SubscriptionStatus.PAST_DUE);
        assertThat(next.getValue().dunning()).isEqualTo(DunningState.RETRYING);
        verify(events).publish(eq(SellerEvents.SUBSCRIPTION_CHANGED), eq(SellerEvents.AGGREGATE_SUBSCRIPTION),
                eq("org_1"), any(SubscriptionEventPayload.class));
    }

    @Test
    void theLastFailureMakesTheInvoiceUncollectibleAndTheSubscriptionUnpaid() {
        Invoice open = invoiceAfterFailures(InvoiceCollectionServiceImpl.MAX_FAILURES - 1);
        when(invoices.lock("inv_1")).thenReturn(Optional.of(open));
        when(invoices.findById("inv_1")).thenReturn(Optional.of(open));
        when(gateway.charge(any(), any(), anyLong(), any(), any(), any(), any())).thenReturn(
                new PlanPaymentsGateway.Charge(PlanPaymentsGateway.Status.FAILED, "pi_x", "card_declined", "Declined"));

        assertThat(service.collect("inv_1")).isEqualTo(InvoiceCollectionService.Outcome.FAILED);
        verify(invoices).markStatus("inv_1", Invoice.UNCOLLECTIBLE, NOW);
        ArgumentCaptor<Subscription> next = ArgumentCaptor.forClass(Subscription.class);
        verify(subs).update(next.capture());
        assertThat(next.getValue().status()).isEqualTo(SubscriptionStatus.UNPAID);
        assertThat(next.getValue().dunning()).isEqualTo(DunningState.FINAL_ATTEMPT_FAILED);
    }

    @Test
    void aBankDebitInProgressStaysPendingUntilStripeReportsTheEnd() {
        Invoice open = invoice(3900, 0, List.of());
        when(invoices.lock("inv_1")).thenReturn(Optional.of(open));
        when(invoices.findById("inv_1")).thenReturn(Optional.of(open));
        when(gateway.charge(any(), any(), anyLong(), any(), any(), any(), any())).thenReturn(
                new PlanPaymentsGateway.Charge(PlanPaymentsGateway.Status.PROCESSING, "pi_ach", null, null));

        assertThat(service.collect("inv_1")).isEqualTo(InvoiceCollectionService.Outcome.PENDING);
        ArgumentCaptor<InvoicePayment> p = ArgumentCaptor.forClass(InvoicePayment.class);
        verify(invoices).addPayment(p.capture());
        assertThat(p.getValue().status()).isEqualTo(InvoicePayment.PENDING);
        assertThat(p.getValue().reference()).isEqualTo("pi_ach");
    }

    @Test
    void withoutAPaymentMethodTheInvoiceFailsWithAClearReason() {
        when(methods.list("org_1")).thenReturn(List.of());
        Invoice open = invoice(3900, 0, List.of());
        when(invoices.lock("inv_1")).thenReturn(Optional.of(open));
        when(invoices.findById("inv_1")).thenReturn(Optional.of(open));
        assertThat(service.collect("inv_1")).isEqualTo(InvoiceCollectionService.Outcome.RETRY);
        verify(invoices).recordAttempt(eq("inv_1"), eq(1), any(), contains("payment "
                + "method"), eq(NOW));
    }

    @Test
    void aPaymentSystemOutageDoesNotCountAsAFailedAttempt() {
        Invoice open = invoice(3900, 0, List.of());
        when(invoices.lock("inv_1")).thenReturn(Optional.of(open));
        when(invoices.findById("inv_1")).thenReturn(Optional.of(open));
        when(gateway.charge(any(), any(), anyLong(), any(), any(), any(), any()))
                .thenThrow(new GatewayException("seller.stripe-unavailable", "down", false, null));
        assertThat(service.collect("inv_1")).isEqualTo(InvoiceCollectionService.Outcome.RETRY);
        verify(invoices).recordAttempt(eq("inv_1"), eq(0), eq(NOW.plus(InvoiceCollectionServiceImpl.UNAVAILABLE_RETRY)),
                anyString(), eq(NOW));
    }

    @Test
    void aWaiverForgivesTheRestAndMarksTheInvoicePaid() {
        Invoice partly = invoice(3900, 1000, List.of(payment(PaymentSource.CREDIT, 1000, InvoicePayment.APPLIED)));
        when(invoices.lock("inv_1")).thenReturn(Optional.of(partly));
        service.waive("inv_1", "GOODWILL", "Outage in October", "staff_1");
        ArgumentCaptor<InvoicePayment> p = ArgumentCaptor.forClass(InvoicePayment.class);
        verify(invoices).addPayment(p.capture());
        assertThat(p.getValue().source()).isEqualTo(PaymentSource.WAIVER);
        assertThat(p.getValue().amountCents()).isEqualTo(2900);
        verify(invoices).setWaiver("inv_1", "GOODWILL", "Outage in October", "staff_1", NOW);
        verify(invoices).markPaid("inv_1", NOW);
    }

    @Test
    void aBankDebitThatSucceedsLaterSettlesTheInvoice() {
        InvoicePayment pending = payment(PaymentSource.CARD, 3900, InvoicePayment.PENDING);
        Invoice open = invoice(3900, 0, List.of(pending));
        Invoice settled = invoice(3900, 3900, List.of(payment(PaymentSource.CARD, 3900, InvoicePayment.APPLIED)));
        when(invoices.findPaymentByReference("pi_ach")).thenReturn(Optional.of(pending));
        when(invoices.lock("inv_1")).thenReturn(Optional.of(settled));
        service.onPaymentIntentResult("pi_ach", true, null, null);
        verify(invoices).setPaymentStatus(pending.id(), InvoicePayment.APPLIED, null);
        verify(invoices).markPaid("inv_1", NOW);
        assertThat(open.isOpen()).isTrue();
    }

    private static Invoice invoice(long due, long paid, List<InvoicePayment> payments) {
        return new Invoice("inv_1", "org_1", "AT-INV-1", NOW, NOW.plus(Duration.ofDays(30)), due, 0, due, paid, "USD",
                Invoice.OPEN, Invoice.REASON_CYCLE, "seller_growth", 1, Cadence.MONTHLY, NOW, null, 0, null, null, NOW,
                List.of(), payments);
    }

    private static Invoice paidInvoice() {
        return new Invoice("inv_1", "org_1", "AT-INV-1", NOW, NOW.plus(Duration.ofDays(30)), 3900, 0, 3900, 3900, "USD",
                Invoice.PAID, Invoice.REASON_CYCLE, "seller_growth", 1, Cadence.MONTHLY, NOW, NOW, 0, null, null, NOW,
                List.of(), List.of());
    }

    private static Invoice invoiceAfterFailures(int failures) {
        return new Invoice("inv_1", "org_1", "AT-INV-1", NOW, NOW.plus(Duration.ofDays(30)), 3900, 0, 3900, 0, "USD",
                Invoice.OPEN, Invoice.REASON_CYCLE, "seller_growth", 1, Cadence.MONTHLY, NOW, null, failures, null,
                "declined", NOW, List.of(), List.of());
    }

    private static InvoicePayment payment(PaymentSource source, long cents, String status) {
        return new InvoicePayment(UUID.randomUUID(), "inv_1", "org_1", source, cents, status, "ref-" + source, null,
                NOW);
    }

    private static PaymentMethod card() {
        return new PaymentMethod(UUID.randomUUID(), "org_1", "pm_1", PaymentMethodKind.CARD, "visa", "4242", 12, 2030,
                null, null, true, false, true, NOW);
    }

    private static SubscriptionTerms terms(long credit, PlanFeeCollection mode) {
        return new SubscriptionTerms("org_1", null, null, null, null, null, null, credit, mode, "SELLER", NOW, "u1",
                null);
    }

    @SuppressWarnings("unused")
    private static Organisation org() {
        return Organisation.draft("org_1", OrgKind.SELLER, "Ade's Store", NOW);
    }
}

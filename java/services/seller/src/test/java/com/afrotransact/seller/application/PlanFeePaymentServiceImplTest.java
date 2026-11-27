package com.afrotransact.seller.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.afrotransact.platform.security.Actor;
import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.Invoice;
import com.afrotransact.seller.domain.InvoicePayment;
import com.afrotransact.seller.domain.PaymentSource;
import com.afrotransact.seller.domain.PlanFeeDispute;
import com.afrotransact.seller.domain.PlanFeeRefund;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.port.BillingGrantRepository;
import com.afrotransact.seller.port.InvoiceRepository;
import com.afrotransact.seller.port.PaymentMethodRepository;
import com.afrotransact.seller.port.PlanFeeLedger;
import com.afrotransact.seller.port.PlanPaymentsGateway;
import com.afrotransact.seller.port.WebhookInbox;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Plan-fee events handed over by payments (one platform webhook): de-duplicated on the Stripe event id, then settled,
 * failed, reversed, refunded or disputed; the seller's 3-D Secure confirmation; staff refunds.
 */
class PlanFeePaymentServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private final WebhookInbox inbox = mock(WebhookInbox.class);
    private final InvoiceCollectionService collection = mock(InvoiceCollectionService.class);
    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final PaymentMethodRepository methods = mock(PaymentMethodRepository.class);
    private final PlanFeeLedger ledger = mock(PlanFeeLedger.class);
    private final BillingGrantRepository grants = mock(BillingGrantRepository.class);
    private final PlanPaymentsGateway gateway = mock(PlanPaymentsGateway.class);
    private final AccessGuard access = mock(AccessGuard.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final PlanFeePaymentServiceImpl service = new PlanFeePaymentServiceImpl(inbox, collection, invoices,
            methods, ledger, grants, gateway, access, events, TransactionOperations.withoutTransaction(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final InvoicePayment applied = new InvoicePayment(UUID.randomUUID(), "inv_1", "org_1", PaymentSource.CARD,
            3900, InvoicePayment.APPLIED, "pi_1", "Visa ••4242", NOW);

    PlanFeePaymentServiceImplTest() {
        when(inbox.firstDelivery(anyString(), anyString(), anyString(), any(), anyLong())).thenReturn(true);
        when(invoices.findPaymentByReference("pi_1")).thenReturn(Optional.of(applied));
        when(grants.reasonCodes()).thenReturn(List.of(new BillingGrantRepository.ReasonCode("GOODWILL", "Goodwill"),
                new BillingGrantRepository.ReasonCode("OTHER", "Other")));
        Actor staff = mock(Actor.class);
        when(staff.subject()).thenReturn("staff_1");
        when(access.staff(any())).thenReturn(staff);
    }

    @Test
    void aDuplicateEventChangesNothing() {
        when(inbox.firstDelivery(eq("evt_dup"), anyString(), anyString(), any(), anyLong())).thenReturn(false);
        assertThat(service.apply(event("evt_dup", "payment_intent.succeeded"))).isEqualTo("duplicate");
        verify(collection, never()).onPaymentIntentResult(anyString(), anyBoolean(), any(), any());
    }

    @Test
    void intentEventsReachCollectionAndTheOutcomeIsStored() {
        when(collection.onPaymentIntentResult("pi_1", true, null, null)).thenReturn(true);
        assertThat(service.apply(event("evt_1", "payment_intent.succeeded"))).isEqualTo("settled");
        verify(inbox).firstDelivery("evt_1", "PAYMENTS", "payment_intent.succeeded", null, 100L);
        verify(inbox).outcome("evt_1", "settled");

        when(collection.onProcessing("pi_1")).thenReturn(true);
        assertThat(service.apply(event("evt_2", "payment_intent.processing"))).isEqualTo("processing");
        when(collection.onActionRequired("pi_1")).thenReturn(true);
        assertThat(service.apply(event("evt_3", "payment_intent.requires_action"))).isEqualTo("action-required");
        assertThat(service.apply(event("evt_4", "payment_intent.payment_failed"))).isEqualTo("unknown-payment");
        verify(collection).onPaymentIntentResult("pi_1", false, "account_closed", "Closed");
    }

    @Test
    void aLostDisputeTakesThePaymentBackAndAWonOneJustCloses() {
        PlanFeeDispute open = dispute("dp_1", PlanFeeDispute.OPEN);
        when(ledger.addDispute(any())).thenReturn(true);
        when(ledger.lockDispute("dp_1")).thenReturn(Optional.of(open));
        assertThat(service.apply(dispute("evt_5", "charge.dispute.created", "dp_1", "needs_response")))
                .isEqualTo("dispute-opened");
        verify(events).publish(eq(SellerEvents.BILLING_DISPUTE_OPENED), any(), eq("org_1"), any());

        when(ledger.addDispute(any())).thenReturn(false);
        assertThat(service.apply(dispute("evt_6", "charge.dispute.funds_withdrawn", "dp_1", "needs_response")))
                .isEqualTo("dispute-funds-withdrawn");
        verify(ledger).updateDispute("dp_1", "needs_response", PlanFeeDispute.OPEN, true, null, NOW);

        assertThat(service.apply(dispute("evt_7", "charge.dispute.closed", "dp_1", "lost"))).isEqualTo("dispute-lost");
        verify(collection).onPaymentTakenBack(eq("pi_1"), eq("dispute_lost"), anyString());
        verify(ledger).updateDispute("dp_1", "lost", PlanFeeDispute.LOST, false, NOW, NOW);

        when(ledger.lockDispute("dp_2")).thenReturn(Optional.of(dispute("dp_2", PlanFeeDispute.OPEN)));
        assertThat(service.apply(dispute("evt_8", "charge.dispute.closed", "dp_2", "won"))).isEqualTo("dispute-won");
        verify(collection, times(1)).onPaymentTakenBack(anyString(), anyString(), anyString());

        when(ledger.lockDispute("dp_3")).thenReturn(Optional.of(dispute("dp_3", PlanFeeDispute.WON)));
        assertThat(service.apply(dispute("evt_9", "charge.dispute.closed", "dp_3", "won")))
                .isEqualTo("dispute-already-closed");
    }

    @Test
    void aStaffRefundIsPartialThenFullAndNeverMoreThanWasPaid() {
        when(invoices.find("org_1", "inv_1")).thenReturn(Optional.of(paidInvoice()));
        when(ledger.refundByKey(anyString())).thenReturn(Optional.empty());
        when(gateway.refund(eq("pi_1"), eq(1000L), eq("GOODWILL"), anyString()))
                .thenReturn(new PlanPaymentsGateway.RefundResult("re_1", "succeeded", 1000));
        PlanFeeRefund first = refund("re_1", 1000, PlanFeeRefund.SUCCEEDED, PlanFeeRefund.STAFF);
        when(ledger.refundByKey("plan-refund-inv_1-k1")).thenReturn(Optional.empty(), Optional.of(first));

        assertThat(service.refund("org_1", "inv_1", 1000, "GOODWILL", null, "k1").amountCents()).isEqualTo(1000);
        ArgumentCaptor<PlanFeeRefund> saved = ArgumentCaptor.forClass(PlanFeeRefund.class);
        verify(ledger).addRefund(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(PlanFeeRefund.PENDING);
        assertThat(saved.getValue().requestedBy()).isEqualTo("staff_1");
        assertThat(saved.getValue().idempotencyKey()).isEqualTo("plan-refund-inv_1-k1");
        verify(gateway).refund("pi_1", 1000, "GOODWILL", "plan-refund-inv_1-k1");
        verify(ledger).setRefund(saved.getValue().id(), PlanFeeRefund.SUCCEEDED, "re_1", NOW);
        verify(events).publish(eq(SellerEvents.BILLING_REFUNDED), any(), eq("org_1"), any());

        when(ledger.refundsOfIntent("pi_1")).thenReturn(List.of(first));
        assertThatThrownBy(() -> service.refund("org_1", "inv_1", 3000, "GOODWILL", null, "k2"))
                .isInstanceOf(SellerRuleViolation.class);
        when(gateway.refund(eq("pi_1"), eq(2900L), eq("OTHER"), anyString()))
                .thenReturn(new PlanPaymentsGateway.RefundResult("re_2", "pending", 2900));
        when(ledger.refundByKey("plan-refund-inv_1-k3")).thenReturn(Optional.empty(),
                Optional.of(refund("re_2", 2900, PlanFeeRefund.PENDING, PlanFeeRefund.STAFF)));
        assertThat(service.refund("org_1", "inv_1", 2900, "OTHER", "Duplicate charge", "k3").status())
                .isEqualTo(PlanFeeRefund.PENDING);
        assertThatThrownBy(() -> service.refund("org_1", "inv_1", 100, "OTHER", " ", "k4"))
                .isInstanceOf(SellerRuleViolation.class);
    }

    @Test
    void aRetriedRefundRequestReturnsTheFirstRefund() {
        PlanFeeRefund first = refund("re_1", 1000, PlanFeeRefund.SUCCEEDED, PlanFeeRefund.STAFF);
        when(ledger.refundByKey("plan-refund-inv_1-k1")).thenReturn(Optional.of(first));
        assertThat(service.refund("org_1", "inv_1", 1000, "GOODWILL", null, "k1")).isEqualTo(first);
        verify(gateway, never()).refund(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void chargeRefundedSettlesOurPendingRefundAndRecordsADashboardRefund() {
        PlanFeeRefund pending = refund(null, 1000, PlanFeeRefund.PENDING, PlanFeeRefund.STAFF);
        when(ledger.refundsOfIntent("pi_1")).thenReturn(List.of(pending));
        when(ledger.addRefund(any())).thenReturn(true);
        PlanFeeStripeEvent e = new PlanFeeStripeEvent("evt_r", "charge.refunded", 100, "plan_fee", "charge", "pi_1",
                "inv_1", null, "succeeded", 3900, 0, 1500, "usd", "ch_1", null, null, null, null, null, null, null, null,
                null);
        assertThat(service.apply(e)).isEqualTo("refund-reconciled");
        verify(ledger).setRefund(pending.id(), PlanFeeRefund.SUCCEEDED, null, NOW);
        ArgumentCaptor<PlanFeeRefund> outside = ArgumentCaptor.forClass(PlanFeeRefund.class);
        verify(ledger).addRefund(outside.capture());
        assertThat(outside.getValue().origin()).isEqualTo(PlanFeeRefund.DASHBOARD);
        assertThat(outside.getValue().amountCents()).isEqualTo(500);
        assertThat(outside.getValue().idempotencyKey()).isEqualTo("dashboard-ch_1-1500");
    }

    @Test
    void theSellerConfirmsAChargeWithItsClientSecret() {
        InvoicePayment waiting = new InvoicePayment(UUID.randomUUID(), "inv_1", "org_1", PaymentSource.CARD, 3900,
                InvoicePayment.ACTION_REQUIRED, "pi_3ds", "Visa ••3155", NOW);
        when(invoices.find("org_1", "inv_1")).thenReturn(Optional.of(openInvoice(List.of(waiting))));
        when(gateway.paymentStatus("pi_3ds")).thenReturn(new PlanPaymentsGateway.Charge(
                PlanPaymentsGateway.Status.REQUIRES_ACTION, "pi_3ds", "authentication_required", null,
                "pi_3ds_secret", "pm_3ds"));
        PlanFeePaymentService.PaymentConfirmation c = service.confirmPayment("org_1", "inv_1");
        assertThat(c.clientSecret()).isEqualTo("pi_3ds_secret");
        assertThat(c.paymentMethodId()).isEqualTo("pm_3ds");
        verify(access).liveOrgRole("org_1", AccessGuard.OWNER_OR_FINANCE, SellerPermissions.BILLING);
    }

    @Test
    void aVerifiedBankAccountKeepsItsMandate() {
        PlanFeeStripeEvent e = new PlanFeeStripeEvent("evt_s", "setup_intent.succeeded", 100, "seller_billing",
                "setup_intent", null, null, null, "succeeded", 0, 0, 0, null, null, null, null, null, null, null, null,
                "seti_1", "pm_ach", "mandate_1");
        assertThat(service.apply(e)).isEqualTo("payment-method-verified");
        verify(methods).markVerified("pm_ach", "mandate_1");
    }

    private static PlanFeeStripeEvent event(String id, String type) {
        return new PlanFeeStripeEvent(id, type, 100, "plan_fee", "payment_intent", "pi_1", "inv_1", null, "x", 3900,
                0, 0, "usd", null, "account_closed", "Closed", null, null, null, null, null, null, null);
    }

    private static PlanFeeStripeEvent dispute(String id, String type, String disputeId, String status) {
        return new PlanFeeStripeEvent(id, type, 100, "plan_fee", "dispute", "pi_1", null, null, null, 3900, 0, 0,
                "usd", "ch_1", null, null, null, disputeId, status, "fraudulent", null, null, null);
    }

    private static PlanFeeDispute dispute(String id, String outcome) {
        return new PlanFeeDispute(UUID.randomUUID(), id, "inv_1", "org_1", "pi_1", "ch_1", 3900, "fraudulent",
                "needs_response", outcome, false, NOW, NOW, null);
    }

    private static PlanFeeRefund refund(String stripeId, long cents, String status, String origin) {
        return new PlanFeeRefund(UUID.randomUUID(), "inv_1", "org_1", "pi_1", stripeId, cents, status, origin,
                "GOODWILL", null, "staff_1", "k-" + UUID.randomUUID(), NOW, NOW);
    }

    private Invoice paidInvoice() {
        return new Invoice("inv_1", "org_1", "AT-INV-1", NOW, NOW.plus(Duration.ofDays(30)), 3900, 0, 3900, 3900, "USD",
                Invoice.PAID, Invoice.REASON_CYCLE, "seller_growth", 1, Cadence.MONTHLY, NOW, NOW, 0, null, null, NOW,
                List.of(), List.of(applied));
    }

    private static Invoice openInvoice(List<InvoicePayment> payments) {
        return new Invoice("inv_1", "org_1", "AT-INV-1", NOW, NOW.plus(Duration.ofDays(30)), 3900, 0, 3900, 0, "USD",
                Invoice.OPEN, Invoice.REASON_CYCLE, "seller_growth", 1, Cadence.MONTHLY, NOW, null, 0, null, null, NOW,
                List.of(), payments);
    }
}

package com.afrotransact.orders.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.FulfilmentType;
import io.temporal.failure.ApplicationFailure;

/** Scriptable activities for workflow tests: records calls and fails where told to. */
class FakeCheckoutActivities implements CheckoutActivities {

    final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    volatile String failPrepareWith;
    volatile String failReserveWith;
    volatile boolean reserved = true;

    int count(String name) {
        return calls.getOrDefault(name, new AtomicInteger()).get();
    }

    private void record(String name) {
        calls.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
    }

    @Override
    public CheckoutPlan prepare(CheckoutInput input) {
        record("prepare");
        if (failPrepareWith != null) {
            throw ApplicationFailure.newNonRetryableFailure("prepare failed", failPrepareWith);
        }
        return new CheckoutPlan("USD", List.of(new CheckoutPlan.Package("org_x:AFROTRANSACT", "org_x", "Seller X",
                FulfilmentType.FBA_AFROTRANSACT, DeliveryMethod.CARRIER, "standard", null,
                DeliveryPreferences.defaults(), 3, List.of(new CheckoutPlan.Line("line-1", 1, "ATIN-1", "P-1", "Item",
                null, 2, 1250, 206, "groceries", false, "US-TX", 3)))));
    }

    @Override
    public OrderDraft reserve(CheckoutInput input, CheckoutPlan plan) {
        record("reserve");
        if (failReserveWith != null) {
            throw ApplicationFailure.newNonRetryableFailure("Not enough stock", failReserveWith,
                    "ATIN-1: requested 2, available 0");
        }
        CheckoutPlan.Package p = plan.packages().getFirst();
        return new OrderDraft(input.orderId(), input.orderNumber(), input.customerId(), input.workflowId(), "USD",
                input.shipTo(), reserved, List.of(new OrderDraft.DraftShipment("ship-1", 1, p.sellerOrgId(),
                p.sellerName(), p.fulfilmentType(), "HOU-FC", p.method(), p.serviceLevel(), null, p.preferences(),
                false, false, null, null, 599, LocalDate.now(), LocalDate.now().plusDays(3), p.lines())),
                Instant.now());
    }

    @Override
    public PaymentRef createPayment(OrderDraft draft) {
        record("createPayment");
        return new PaymentRef("pay-1", "pi_secret_1", "REQUIRES_PAYMENT_METHOD");
    }

    @Override
    public void persistPending(OrderDraft draft, PaymentRef payment) {
        record("persistPending");
    }

    @Override
    public void place(String orderId, boolean wasReserved) {
        record(wasReserved ? "place:reserved" : "place");
    }

    @Override
    public void clearCart(String customerId, List<String> variantAtins) {
        record("clearCart");
    }

    @Override
    public void releaseReservation(String orderId, String reason) {
        record("releaseReservation");
    }

    @Override
    public void cancelPayment(String orderId, String paymentId, String reason) {
        record("cancelPayment:" + paymentId);
    }

    @Override
    public void abandon(String orderId, String code, String message) {
        record("abandon:" + code);
    }
}

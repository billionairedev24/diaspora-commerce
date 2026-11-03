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
import com.afrotransact.orders.domain.PreorderFulfilment;
import com.afrotransact.orders.domain.PreorderPackage;
import com.afrotransact.orders.domain.PreorderSource;
import io.temporal.failure.ApplicationFailure;

/** Scriptable activities for workflow tests: records calls and fails where told to. */
class FakeCheckoutActivities implements CheckoutActivities {

    final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    volatile String failPrepareWith;
    volatile String failReserveWith;
    volatile boolean reserved = true;
    volatile String failCouponWith;
    /** When set, the order is on this delivery run (S261): the draft carries a run package. */
    volatile String runId;
    volatile String failRunSlotWith;

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
                null, 2, 1250, 206, "groceries", false, "US-TX", 3, "offer-1", 0)))));
    }

    @Override
    public OrderDraft reserve(CheckoutInput input, CheckoutPlan plan) {
        record("reserve");
        if (failReserveWith != null) {
            throw ApplicationFailure.newNonRetryableFailure("Not enough stock", failReserveWith,
                    "ATIN-1: requested 2, available 0");
        }
        CheckoutPlan.Package p = plan.packages().getFirst();
        OrderDraft.DraftShipment shipment = new OrderDraft.DraftShipment("ship-1", 1, p.sellerOrgId(),
                p.sellerName(), p.fulfilmentType(), "HOU-FC", p.method(), p.serviceLevel(), null, p.preferences(),
                false, false, null, null, 599, LocalDate.now(), LocalDate.now().plusDays(3), p.lines());
        if (runId != null) {
            shipment = new OrderDraft.DraftShipment("ship-1", 1, p.sellerOrgId(), p.sellerName(),
                    FulfilmentType.PREORDER, null, DeliveryMethod.AFROTRANSACT_DELIVERY, "preorder_door", null,
                    p.preferences(), false, false, null, null, 799, LocalDate.now().plusDays(5),
                    LocalDate.now().plusDays(5), p.lines(), null, PreorderPackage.placed(runId, null, "Bastrop "
                    + "delivery run", LocalDate.now().plusDays(5), Instant.now().plusSeconds(86_400),
                    PreorderFulfilment.DOOR, PreorderSource.RUN), null);
        }
        return new OrderDraft(input.orderId(), input.orderNumber(), input.customerId(), input.workflowId(), "USD",
                input.shipTo(), reserved, List.of(shipment), Instant.now(), null);
    }

    @Override
    public void validateCoupon(CheckoutInput input, CheckoutPlan plan) {
        record("validateCoupon");
        if (failCouponWith != null) {
            throw ApplicationFailure.newNonRetryableFailure("Coupon not applicable", failCouponWith, "EXPIRED");
        }
    }

    @Override
    public OrderDraft redeemCoupon(CheckoutInput input, OrderDraft draft) {
        record("redeemCoupon");
        return draft.withCoupon(draft.shipments(), new OrderDraft.CouponRef(input.couponCode(), "red-1", 250, 0,
                "PLATFORM", null));
    }

    @Override
    public void releaseCoupon(CheckoutInput input, OrderDraft draft, String redemptionId) {
        record("releaseCoupon:" + redemptionId);
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
    public String reserveRunSlot(CheckoutInput input, OrderDraft draft) {
        record("reserveRunSlot");
        if (failRunSlotWith != null) {
            throw ApplicationFailure.newNonRetryableFailure("This delivery run has closed", failRunSlotWith);
        }
        return runId;
    }

    @Override
    public void releaseRunSlot(String orderId, String runId) {
        record("releaseRunSlot:" + runId);
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

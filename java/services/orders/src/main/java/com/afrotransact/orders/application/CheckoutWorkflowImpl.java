package com.afrotransact.orders.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

/**
 * Deterministic saga orchestration; every side effect is an activity. Compensations run in reverse order and keep
 * going when one of them fails.
 */
@WorkflowImpl(taskQueues = CheckoutWorkflow.TASK_QUEUE)
class CheckoutWorkflowImpl implements CheckoutWorkflow {

    private static final Logger LOG = Workflow.getLogger(CheckoutWorkflowImpl.class);

    private final CheckoutActivities steps = Workflow.newActivityStub(CheckoutActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofMillis(500))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofSeconds(10))
                            .setMaximumAttempts(5)
                            .build())
                    .build());

    private final CheckoutActivities compensations = Workflow.newActivityStub(CheckoutActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofMinutes(1))
                            .setMaximumAttempts(20)
                            .build())
                    .build());

    /** Change id of the coupon steps: histories recorded before them replay without coupons. */
    static final String COUPONS_CHANGE = "coupons-v1";

    /** Change id of the delivery-run slot step (S261): histories recorded before it replay without it. */
    static final String RUN_SLOTS_CHANGE = "run-slots-v1";

    private CheckoutProgress progress;
    private Boolean authorized;
    private String paymentFailure;

    @Override
    public CheckoutProgress checkout(CheckoutInput input) {
        progress = CheckoutProgress.of(CheckoutProgress.Phase.VALIDATING, input.orderId(), input.orderNumber());
        boolean reservationMayExist = false;
        boolean paymentMayExist = false;
        String paymentId = null;
        boolean reserved = false;
        boolean couponMayExist = false;
        OrderDraft beforeCoupon = null;
        String redemptionId = null;
        boolean coupons = input.hasCoupon()
                && Workflow.getVersion(COUPONS_CHANGE, Workflow.DEFAULT_VERSION, 1) >= 1;
        boolean runSlots = Workflow.getVersion(RUN_SLOTS_CHANGE, Workflow.DEFAULT_VERSION, 1) >= 1;
        boolean runSlotMayExist = false;
        String runId = null;
        try {
            CheckoutPlan plan = steps.prepare(input);
            if (coupons) {
                steps.validateCoupon(input, plan);
            }

            progress = progress.at(CheckoutProgress.Phase.RESERVING);
            OrderDraft draft;
            try {
                draft = steps.reserve(input, plan);
            } catch (ActivityFailure e) {
                reservationMayExist = !CheckoutCodes.INSUFFICIENT_STOCK.equals(code(e));
                throw e;
            }
            reserved = draft.reserved();
            reservationMayExist = reserved;
            if (runSlots) {
                // S261: an order on a staff-scheduled run takes its slot before anything is charged; a run that
                // closed or filled meanwhile fails the checkout here, with the stock given back.
                try {
                    runSlotMayExist = true;
                    runId = steps.reserveRunSlot(input, draft);
                    runSlotMayExist = runId != null;
                } catch (ActivityFailure e) {
                    String failed = code(e);
                    runSlotMayExist = !CheckoutCodes.RUN_CLOSED.equals(failed) && !CheckoutCodes.RUN_FULL.equals(failed)
                            && draftRunId(draft) != null;
                    runId = draftRunId(draft);
                    throw e;
                }
            }
            if (coupons) {
                beforeCoupon = draft;
                try {
                    couponMayExist = true;
                    draft = steps.redeemCoupon(input, draft);
                    redemptionId = draft.coupon() == null ? null : draft.coupon().redemptionId();
                } catch (ActivityFailure e) {
                    couponMayExist = !CheckoutCodes.COUPON_NOT_APPLICABLE.equals(code(e));
                    throw e;
                }
            }

            progress = progress.at(CheckoutProgress.Phase.AUTHORIZING);
            PaymentRef payment;
            try {
                payment = steps.createPayment(draft);
            } catch (ActivityFailure e) {
                paymentMayExist = CheckoutCodes.UPSTREAM_UNAVAILABLE.equals(code(e));
                throw e;
            }
            paymentMayExist = true;
            paymentId = payment.paymentId();
            steps.persistPending(draft, payment);
            progress = progress.withPayment(payment).at(CheckoutProgress.Phase.AWAITING_PAYMENT);

            Duration timeout = Duration.ofSeconds(Math.max(1, input.paymentTimeoutSeconds()));
            boolean decided = Workflow.await(timeout, () -> authorized != null);
            if (!decided) {
                Undo undo = new Undo(reservationMayExist, paymentMayExist, paymentId, couponMayExist,
                        beforeCoupon, redemptionId, runSlotMayExist ? runId : null);
                return fail(input, undo, CheckoutCodes.PAYMENT_TIMEOUT,
                        "The payment was not completed within " + timeout.toMinutes() + " minutes", List.of());
            }
            if (!authorized) {
                Undo undo = new Undo(reservationMayExist, paymentMayExist, paymentId, couponMayExist,
                        beforeCoupon, redemptionId, runSlotMayExist ? runId : null);
                return fail(input, undo, CheckoutCodes.PAYMENT_FAILED,
                        paymentFailure == null ? "The payment failed" : paymentFailure, List.of());
            }

            progress = progress.at(CheckoutProgress.Phase.PLACING);
            steps.place(input.orderId(), reserved);
            progress = progress.at(CheckoutProgress.Phase.PLACED);
            try {
                steps.clearCart(input.customerId(), draft.shipments().stream()
                        .flatMap(s -> s.lines().stream()).map(CheckoutPlan.Line::variantAtin).distinct().toList());
            } catch (ActivityFailure e) {
                LOG.warn("Order {} placed but its cart could not be cleared: {}", input.orderNumber(), e.getMessage());
            }
            return progress;
        } catch (ActivityFailure e) {
            Undo undo = new Undo(reservationMayExist, paymentMayExist, paymentId, couponMayExist, beforeCoupon,
                    redemptionId, runSlotMayExist ? runId : null);
            return fail(input, undo, code(e), message(e), details(e));
        }
    }

    /** What may need undoing when the saga fails. */
    private record Undo(boolean releaseStock, boolean cancelPayment, String paymentId, boolean releaseCoupon,
                        OrderDraft beforeCoupon, String redemptionId, String releaseRunSlot) {
    }

    /** The run the draft's pre-order package was priced for, or null. */
    private static String draftRunId(OrderDraft draft) {
        return draft.shipments().stream().map(OrderDraft.DraftShipment::preorder).filter(p -> p != null && p.run())
                .map(p -> p.runId()).findFirst().orElse(null);
    }

    private CheckoutProgress fail(CheckoutInput input, Undo undo, String code, String message, List<String> details) {
        LOG.info("Checkout {} failed with {}: {}", input.orderNumber(), code, message);
        String reason = "Checkout failed: " + code;
        if (undo.cancelPayment()) {
            runCompensation(() -> compensations.cancelPayment(input.orderId(), undo.paymentId(), reason));
        }
        if (undo.releaseCoupon() && undo.beforeCoupon() != null) {
            runCompensation(() -> compensations.releaseCoupon(input, undo.beforeCoupon(), undo.redemptionId()));
        }
        if (undo.releaseRunSlot() != null) {
            runCompensation(() -> compensations.releaseRunSlot(input.orderId(), undo.releaseRunSlot()));
        }
        if (undo.releaseStock()) {
            runCompensation(() -> compensations.releaseReservation(input.orderId(), reason));
        }
        String stored = details.isEmpty() ? message : message + " (" + String.join(", ", details) + ")";
        runCompensation(() -> compensations.abandon(input.orderId(), code, stored));
        progress = progress.failed(code, message, details);
        return progress;
    }

    private static void runCompensation(Runnable compensation) {
        try {
            compensation.run();
        } catch (ActivityFailure e) {
            LOG.error("Checkout compensation failed: {}", e.getMessage());
        }
    }

    @Override
    public void paymentAuthorized() {
        if (authorized == null) {
            authorized = Boolean.TRUE;
        }
    }

    @Override
    public void paymentFailed(String reason) {
        if (authorized == null) {
            authorized = Boolean.FALSE;
            paymentFailure = reason;
        }
    }

    @Override
    public CheckoutProgress progress() {
        return progress;
    }

    private static String code(ActivityFailure e) {
        if (e.getCause() instanceof ApplicationFailure af && af.getType() != null
                && af.getType().startsWith("orders.")) {
            return af.getType();
        }
        return CheckoutCodes.UPSTREAM_UNAVAILABLE;
    }

    private static String message(ActivityFailure e) {
        if (e.getCause() instanceof ApplicationFailure af && af.getType() != null
                && af.getType().startsWith("orders.")) {
            return af.getOriginalMessage();
        }
        if (e.getCause() instanceof TimeoutFailure) {
            return "An upstream service did not answer in time";
        }
        return "An upstream service is unavailable; please try again";
    }

    private static List<String> details(ActivityFailure e) {
        List<String> out = new ArrayList<>();
        if (e.getCause() instanceof ApplicationFailure af && af.getDetails() != null && af.getDetails().getSize() > 0) {
            String joined = af.getDetails().get(0, String.class);
            if (joined != null && !joined.isBlank()) {
                out.addAll(List.of(joined.split("\n")));
            }
        }
        return out;
    }
}

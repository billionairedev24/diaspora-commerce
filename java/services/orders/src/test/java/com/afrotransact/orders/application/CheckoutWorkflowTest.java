package com.afrotransact.orders.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.orders.domain.ShipTo;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Checkout saga on Temporal's in-JVM test environment (time skipping) with scripted activities. */
class CheckoutWorkflowTest {

    private TestWorkflowEnvironment env;
    private WorkflowClient client;
    private FakeCheckoutActivities activities;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(CheckoutWorkflow.TASK_QUEUE);
        worker.registerWorkflowImplementationFactory(CheckoutWorkflow.class, CheckoutWorkflowImpl::new);
        activities = new FakeCheckoutActivities();
        worker.registerActivitiesImplementations(activities);
        env.start();
        client = env.getWorkflowClient();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    private static CheckoutInput input(String orderId) {
        return input(orderId, null);
    }

    private static CheckoutInput input(String orderId, String coupon) {
        return new CheckoutInput(orderId, "AT-2026-TESTAB", "customer-1", "key-1", "checkout-" + orderId, "USD",
                new ShipTo("addr-1", "Ada", "+17135550142", "1 Main St", null, "Houston", "TX", "77002", "US", null),
                List.of(), List.of(), 900, Instant.now(), coupon);
    }

    private CheckoutWorkflow start(CheckoutInput input) {
        CheckoutWorkflow wf = client.newWorkflowStub(CheckoutWorkflow.class, WorkflowOptions.newBuilder()
                .setWorkflowId(input.workflowId()).setTaskQueue(CheckoutWorkflow.TASK_QUEUE).build());
        WorkflowClient.start(wf::checkout, input);
        return wf;
    }

    private static void awaitPhase(CheckoutWorkflow wf, CheckoutProgress.Phase phase) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            CheckoutProgress p = wf.progress();
            if (p != null && p.phase() == phase) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("workflow never reached " + phase + " (now " + wf.progress() + ")");
    }

    private static CheckoutProgress result(CheckoutInput input, WorkflowClient client) {
        return client.newUntypedWorkflowStub(input.workflowId()).getResult(CheckoutProgress.class);
    }

    @Test
    void happyPathPlacesTheOrderAndClearsTheCart() {
        CheckoutInput in = input(UUID.randomUUID().toString());
        CheckoutWorkflow wf = start(in);
        awaitPhase(wf, CheckoutProgress.Phase.AWAITING_PAYMENT);
        assertThat(wf.progress().clientSecret()).isEqualTo("pi_secret_1");
        assertThat(activities.count("place:reserved")).isZero();

        wf.paymentAuthorized();
        CheckoutProgress done = result(in, client);

        assertThat(done.phase()).isEqualTo(CheckoutProgress.Phase.PLACED);
        assertThat(done.orderNumber()).isEqualTo("AT-2026-TESTAB");
        assertThat(activities.count("prepare")).isEqualTo(1);
        assertThat(activities.count("reserve")).isEqualTo(1);
        assertThat(activities.count("createPayment")).isEqualTo(1);
        assertThat(activities.count("persistPending")).isEqualTo(1);
        assertThat(activities.count("place:reserved")).isEqualTo(1);
        assertThat(activities.count("clearCart")).isEqualTo(1);
        assertThat(activities.count("releaseReservation")).isZero();
        assertThat(activities.count("cancelPayment:pay-1")).isZero();
        assertThat(activities.calls.keySet()).noneMatch(k -> k.startsWith("abandon"));
    }

    @Test
    void inventoryShortageReservesNothingAndNeverCreatesAPaymentIntent() {
        activities.failReserveWith = CheckoutCodes.INSUFFICIENT_STOCK;
        CheckoutInput in = input(UUID.randomUUID().toString());
        start(in);
        CheckoutProgress done = result(in, client);

        assertThat(done.phase()).isEqualTo(CheckoutProgress.Phase.FAILED);
        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.INSUFFICIENT_STOCK);
        assertThat(done.details()).containsExactly("ATIN-1: requested 2, available 0");
        assertThat(activities.count("createPayment")).isZero();
        assertThat(activities.count("persistPending")).isZero();
        assertThat(activities.count("releaseReservation")).isZero();
        assertThat(activities.calls.keySet()).noneMatch(k -> k.startsWith("cancelPayment"));
        assertThat(activities.count("abandon:" + CheckoutCodes.INSUFFICIENT_STOCK)).isEqualTo(1);
    }

    @Test
    void invalidCartStopsBeforeReserving() {
        activities.failPrepareWith = CheckoutCodes.MARKET_DISABLED;
        CheckoutInput in = input(UUID.randomUUID().toString());
        start(in);
        CheckoutProgress done = result(in, client);

        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.MARKET_DISABLED);
        assertThat(activities.count("reserve")).isZero();
        assertThat(activities.count("abandon:" + CheckoutCodes.MARKET_DISABLED)).isEqualTo(1);
    }

    @Test
    void paymentFailureReleasesTheReservationAndCancelsTheIntent() {
        CheckoutInput in = input(UUID.randomUUID().toString());
        CheckoutWorkflow wf = start(in);
        awaitPhase(wf, CheckoutProgress.Phase.AWAITING_PAYMENT);
        wf.paymentFailed("Your card was declined");
        CheckoutProgress done = result(in, client);

        assertThat(done.phase()).isEqualTo(CheckoutProgress.Phase.FAILED);
        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.PAYMENT_FAILED);
        assertThat(done.failureMessage()).isEqualTo("Your card was declined");
        assertThat(activities.count("releaseReservation")).isEqualTo(1);
        assertThat(activities.count("cancelPayment:pay-1")).isEqualTo(1);
        assertThat(activities.count("abandon:" + CheckoutCodes.PAYMENT_FAILED)).isEqualTo(1);
        assertThat(activities.calls.keySet()).noneMatch(k -> k.startsWith("place"));
    }

    @Test
    void anOrderOnADeliveryRunTakesItsSlotBeforePaymentAndGivesItBackWhenPaymentFails() {
        activities.runId = "run-1";
        CheckoutInput in = input(UUID.randomUUID().toString());
        CheckoutWorkflow wf = start(in);
        awaitPhase(wf, CheckoutProgress.Phase.AWAITING_PAYMENT);
        assertThat(activities.count("reserveRunSlot")).isEqualTo(1);
        assertThat(activities.count("releaseRunSlot:run-1")).isZero();
        wf.paymentFailed("Your card was declined");
        CheckoutProgress done = result(in, client);

        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.PAYMENT_FAILED);
        assertThat(activities.count("releaseRunSlot:run-1")).isEqualTo(1);
        assertThat(activities.count("releaseReservation")).isEqualTo(1);
        assertThat(activities.count("cancelPayment:pay-1")).isEqualTo(1);
    }

    @Test
    void anOrderOnADeliveryRunKeepsItsSlotWhenPaymentSucceeds() {
        activities.runId = "run-1";
        CheckoutInput in = input(UUID.randomUUID().toString());
        CheckoutWorkflow wf = start(in);
        awaitPhase(wf, CheckoutProgress.Phase.AWAITING_PAYMENT);
        wf.paymentAuthorized();
        CheckoutProgress done = result(in, client);

        assertThat(done.phase()).isEqualTo(CheckoutProgress.Phase.PLACED);
        assertThat(activities.count("reserveRunSlot")).isEqualTo(1);
        assertThat(activities.calls.keySet()).noneMatch(k -> k.startsWith("releaseRunSlot"));
    }

    @Test
    void aRunThatClosedFailsBeforeAnyPaymentGivesTheStockBackAndHasNoSlotToRelease() {
        activities.runId = "run-1";
        activities.failRunSlotWith = CheckoutCodes.RUN_CLOSED;
        CheckoutInput in = input(UUID.randomUUID().toString());
        start(in);
        CheckoutProgress done = result(in, client);

        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.RUN_CLOSED);
        assertThat(activities.count("createPayment")).isZero();
        assertThat(activities.count("releaseReservation")).isEqualTo(1);
        assertThat(activities.calls.keySet()).noneMatch(k -> k.startsWith("releaseRunSlot"));
        assertThat(activities.count("abandon:" + CheckoutCodes.RUN_CLOSED)).isEqualTo(1);
    }

    @Test
    void noPaymentWithinFifteenMinutesRunsEveryCompensation() {
        CheckoutInput in = input(UUID.randomUUID().toString());
        long startedAt = env.currentTimeMillis();
        start(in);
        CheckoutProgress done = result(in, client);

        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.PAYMENT_TIMEOUT);
        assertThat(env.currentTimeMillis() - startedAt).isGreaterThanOrEqualTo(Duration.ofMinutes(15).toMillis());
        assertThat(activities.count("releaseReservation")).isEqualTo(1);
        assertThat(activities.count("cancelPayment:pay-1")).isEqualTo(1);
        assertThat(activities.count("abandon:" + CheckoutCodes.PAYMENT_TIMEOUT)).isEqualTo(1);
    }

    @Test
    void ordersWithoutAfroTransactStockSkipTheReservationCompensation() {
        activities.reserved = false;
        CheckoutInput in = input(UUID.randomUUID().toString());
        CheckoutWorkflow wf = start(in);
        awaitPhase(wf, CheckoutProgress.Phase.AWAITING_PAYMENT);
        wf.paymentFailed(null);
        result(in, client);
        assertThat(activities.count("releaseReservation")).isZero();
        assertThat(activities.count("cancelPayment:pay-1")).isEqualTo(1);
    }

    @Test
    void startingTheSameCheckoutTwiceRunsOneSaga() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("temporalWorkflowClient", client);
        CheckoutWorkflows workflows = new TemporalCheckoutWorkflows(beans.getBeanProvider(WorkflowClient.class));
        CheckoutInput in = input(UUID.randomUUID().toString());

        workflows.start(in);
        workflows.start(in);
        assertThat(workflows.exists(in.workflowId())).isTrue();
        assertThat(workflows.exists("checkout-" + UUID.randomUUID())).isFalse();

        CheckoutWorkflow wf = client.newWorkflowStub(CheckoutWorkflow.class, in.workflowId());
        awaitPhase(wf, CheckoutProgress.Phase.AWAITING_PAYMENT);
        workflows.signalPayment(in.workflowId(), true, null);
        workflows.signalPayment(in.workflowId(), true, null);
        WorkflowStub stub = client.newUntypedWorkflowStub(in.workflowId());
        assertThat(stub.getResult(CheckoutProgress.class).phase()).isEqualTo(CheckoutProgress.Phase.PLACED);
        assertThat(activities.count("prepare")).isEqualTo(1);
        assertThat(activities.count("createPayment")).isEqualTo(1);

        workflows.start(in);
        workflows.signalPayment(in.workflowId(), false, "late");
        assertThat(activities.count("prepare")).isEqualTo(1);
    }

    @Test
    void couponIsValidatedRedeemedAndKeptWhenTheOrderIsPlaced() {
        CheckoutInput in = input(UUID.randomUUID().toString(), "WELCOME10");
        CheckoutWorkflow wf = start(in);
        awaitPhase(wf, CheckoutProgress.Phase.AWAITING_PAYMENT);
        wf.paymentAuthorized();
        assertThat(result(in, client).phase()).isEqualTo(CheckoutProgress.Phase.PLACED);
        assertThat(activities.count("validateCoupon")).isEqualTo(1);
        assertThat(activities.count("redeemCoupon")).isEqualTo(1);
        assertThat(activities.calls.keySet()).noneMatch(k -> k.startsWith("releaseCoupon"));
    }

    @Test
    void failedPaymentReleasesTheRedeemedCoupon() {
        CheckoutInput in = input(UUID.randomUUID().toString(), "WELCOME10");
        CheckoutWorkflow wf = start(in);
        awaitPhase(wf, CheckoutProgress.Phase.AWAITING_PAYMENT);
        wf.paymentFailed("card declined");
        assertThat(result(in, client).phase()).isEqualTo(CheckoutProgress.Phase.FAILED);
        assertThat(activities.count("releaseCoupon:red-1")).isEqualTo(1);
        assertThat(activities.count("releaseReservation")).isEqualTo(1);
    }

    @Test
    void couponThatDoesNotApplyStopsBeforeReserving() {
        activities.failCouponWith = CheckoutCodes.COUPON_NOT_APPLICABLE;
        CheckoutInput in = input(UUID.randomUUID().toString(), "OLDCODE");
        start(in);
        CheckoutProgress p = result(in, client);
        assertThat(p.phase()).isEqualTo(CheckoutProgress.Phase.FAILED);
        assertThat(p.failureCode()).isEqualTo(CheckoutCodes.COUPON_NOT_APPLICABLE);
        assertThat(activities.count("reserve")).isZero();
        assertThat(activities.count("redeemCoupon")).isZero();
        assertThat(activities.calls.keySet()).noneMatch(k -> k.startsWith("releaseCoupon"));
    }

    @Test
    void ordersWithoutACouponNeverCallOffers() {
        CheckoutInput in = input(UUID.randomUUID().toString());
        CheckoutWorkflow wf = start(in);
        awaitPhase(wf, CheckoutProgress.Phase.AWAITING_PAYMENT);
        wf.paymentAuthorized();
        result(in, client);
        assertThat(activities.count("validateCoupon") + activities.count("redeemCoupon")).isZero();
    }
}

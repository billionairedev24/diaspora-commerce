package com.afrotransact.orders.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.orders.config.LocalCheckoutFaults;
import com.afrotransact.orders.domain.ShipTo;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The local-only checkout fault injection: it fails the named saga step (before or after it ran), the saga compensates exactly as for a
 * real failure, and the switch does not exist outside the {@code local} profile or while it is off.
 */
class LocalCheckoutFaultsTest {

    private TestWorkflowEnvironment env;
    private WorkflowClient client;
    private FakeCheckoutActivities activities;

    private void start(WorkerInterceptor interceptor) {
        env = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder().setWorkerFactoryOptions(
                WorkerFactoryOptions.newBuilder().setWorkerInterceptors(interceptor).build()).build());
        Worker worker = env.newWorker(CheckoutWorkflow.TASK_QUEUE);
        worker.registerWorkflowImplementationFactory(CheckoutWorkflow.class, CheckoutWorkflowImpl::new);
        activities = new FakeCheckoutActivities();
        worker.registerActivitiesImplementations(activities, (CheckoutTotalActivities) (in, draft) -> { });
        env.start();
        client = env.getWorkflowClient();
    }

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
    }

    private CheckoutInput run() {
        CheckoutInput in = new CheckoutInput(UUID.randomUUID().toString(), "AT-2026-TESTAB", "customer-1", "key-1",
                "checkout-" + UUID.randomUUID(), "USD", new ShipTo("addr-1", "Ada", "+17135550142", "1 Main St", null,
                "Houston", "TX", "77002", "US", null), List.of(), List.of(), 900, Instant.now(), null);
        CheckoutWorkflow wf = client.newWorkflowStub(CheckoutWorkflow.class, WorkflowOptions.newBuilder()
                .setWorkflowId(in.workflowId()).setTaskQueue(CheckoutWorkflow.TASK_QUEUE).build());
        WorkflowClient.start(wf::checkout, in);
        return in;
    }

    private CheckoutProgress result(CheckoutInput in) {
        return client.newUntypedWorkflowStub(in.workflowId()).getResult(CheckoutProgress.class);
    }

    @Test
    void aRejectedPaymentStepFailsBeforeAnyIntentAndGivesTheStockBack() {
        start(LocalCheckoutFaults.injector(Set.of("CreatePayment"), Set.of(), true, Integer.MAX_VALUE));
        CheckoutProgress done = result(run());

        assertThat(done.phase()).isEqualTo(CheckoutProgress.Phase.FAILED);
        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.UPSTREAM_REJECTED);
        assertThat(activities.count("createPayment")).isZero();
        assertThat(activities.count("releaseReservation")).isEqualTo(1);
        assertThat(activities.count("abandon:" + CheckoutCodes.UPSTREAM_REJECTED)).isEqualTo(1);
    }

    @Test
    void anUnavailableReservationIsRetriedAndFailsTheCheckoutWithTheUnavailableCode() {
        start(LocalCheckoutFaults.injector(Set.of("Reserve"), Set.of(), false, Integer.MAX_VALUE));
        CheckoutProgress done = result(run());

        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.UPSTREAM_UNAVAILABLE);
        assertThat(activities.count("reserve")).isZero();
        assertThat(activities.count("createPayment")).isZero();
        assertThat(activities.count("abandon:" + CheckoutCodes.UPSTREAM_UNAVAILABLE)).isEqualTo(1);
    }

    @Test
    void aFailureAfterTheStepRanLeavesItsWorkForTheCompensationToUndo() {
        start(LocalCheckoutFaults.injector(Set.of(), Set.of("CreatePayment"), false, Integer.MAX_VALUE));
        CheckoutProgress done = result(run());

        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.UPSTREAM_UNAVAILABLE);
        assertThat(activities.count("createPayment")).isEqualTo(5);
        assertThat(activities.count("releaseReservation")).isEqualTo(1);
        assertThat(activities.calls.keySet()).anyMatch(k -> k.startsWith("cancelPayment"));
    }

    @Test
    void aLimitedNumberOfFailuresIsRetriedAwayAndTheCheckoutGoesOn() {
        start(LocalCheckoutFaults.injector(Set.of("Reserve"), Set.of(), false, 2));
        CheckoutInput in = run();
        CheckoutWorkflow wf = client.newWorkflowStub(CheckoutWorkflow.class, in.workflowId());
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline && (wf.progress() == null
                || wf.progress().phase() != CheckoutProgress.Phase.AWAITING_PAYMENT)) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertThat(wf.progress().phase()).isEqualTo(CheckoutProgress.Phase.AWAITING_PAYMENT);
        assertThat(activities.count("reserve")).isEqualTo(1);
        wf.paymentAuthorized();
        assertThat(result(in).phase()).isEqualTo(CheckoutProgress.Phase.PLACED);
    }

    @Test
    void theSwitchExistsOnlyInTheLocalProfileAndOnlyWhenTurnedOn() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(LocalCheckoutFaults.class);
        runner.withPropertyValues("spring.profiles.active=local", "at.orders.fault-injection.enabled=true",
                        "at.orders.fault-injection.fail-before=Reserve")
                .run(ctx -> assertThat(ctx).hasSingleBean(WorkerInterceptor.class));
        runner.withPropertyValues("spring.profiles.active=local")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(WorkerInterceptor.class));
        for (String profile : List.of("dev", "uat", "prod", "temporal-cloud")) {
            runner.withPropertyValues("spring.profiles.active=" + profile, "at.orders.fault-injection.enabled=true",
                            "at.orders.fault-injection.fail-before=Reserve")
                    .run(ctx -> assertThat(ctx).doesNotHaveBean(WorkerInterceptor.class));
        }
        runner.withPropertyValues("at.orders.fault-injection.enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(WorkerInterceptor.class));
    }
}

package com.afrotransact.module.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.EndUserActor;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.module.ModuleCallTimeoutException;
import com.afrotransact.platform.test.InProcessKit;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.context.ThreadLocalAccessor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The bounded wait and the bulkhead between modules: the callee runs on a thread of its own module, a hung callee fails the same way
 * as an HTTP read timeout, one saturated module does not stop another, and nothing thread-bound of the caller (transaction,
 * security context) reaches the callee while the things the callee needs (principal, person, MDC, trace) do.
 */
class BoundedModuleCallsTest {

    private static final CallerPrincipal ORDERS = new CallerPrincipal("orders", "orders-service", Set.of("inventory:reserve"));

    private final MockEnvironment environment = new MockEnvironment();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ModuleRegistry registry = new ModuleRegistry("afrotransact-core");
    private BoundedModuleCalls gate;

    @BeforeEach
    void install() {
        environment.setProperty("at.orders.inventory.timeout", "300ms");
        gate = gate(BoundedModuleCalls.snapshots(List.of()));
        InProcessCalls.install(gate);
    }

    @AfterEach
    void remove() {
        InProcessCalls.uninstall(gate);
        gate.close();
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    /** An application's own thread-local, added to the allow-list the way a {@code ThreadLocalAccessor} bean is. */
    private record MarkerAccessor(ThreadLocal<String> local) implements ThreadLocalAccessor<String> {

        @Override
        public Object key() {
            return "marker";
        }

        @Override
        public String getValue() {
            return local.get();
        }

        @Override
        public void setValue(String value) {
            local.set(value);
        }

        @Override
        public void setValue() {
            local.remove();
        }
    }

    private BoundedModuleCalls gate(ContextSnapshotFactory snapshots) {
        return new BoundedModuleCalls(new ModuleCallSettings(environment, registry), meters, snapshots);
    }

    private long count(String outcome, String callee) {
        var timer = meters.find("at.module.call").tags("caller", "orders", "callee", callee, "outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    void theCalleeRunsOnAThreadOfItsOwnModuleAsTheCaller() {
        record Seen(String thread, boolean virtual, String caller, boolean viaGate) {
        }
        Seen seen = InProcessCalls.as(ORDERS, "inventory", () -> new Seen(Thread.currentThread().getName(),
                Thread.currentThread().isVirtual(), InProcessCalls.require().clientId(), true));
        assertThat(seen.thread()).startsWith("module-call-inventory-");
        assertThat(seen.virtual()).isTrue();
        assertThat(seen.caller()).isEqualTo("orders-service");
        assertThat(InProcessCalls.current()).isEmpty();
        assertThat(count("ok", "inventory")).isEqualTo(1);
    }

    @Test
    void aTimeoutFiresInterruptsTheCalleeAndIsTheHttpReadTimeoutFailure() throws InterruptedException {
        CountDownLatch interrupted = new CountDownLatch(1);
        long started = System.nanoTime();
        assertThatThrownBy(() -> InProcessCalls.as(ORDERS, "inventory", () -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return "late";
        })).isInstanceOfSatisfying(ModuleCallTimeoutException.class, e -> {
            assertThat(e.callee()).isEqualTo("inventory");
            assertThat(e.timeout()).isEqualTo(Duration.ofMillis(300));
            // not a ModuleApiException: the adapters map it like ResourceAccessException (status 0, "<service>.unavailable")
            assertThat(e).isNotInstanceOf(ModuleApiException.class);
        });
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofMillis(250), Duration.ofSeconds(3));
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).as("callee interrupted").isTrue();
        assertThat(count("timeout", "inventory")).isEqualTo(1);
        assertThat(meters.get("at.module.call.timeouts").tags("caller", "orders", "callee", "inventory").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void aTimedOutCalleeMayStillCommit() throws InterruptedException {
        AtomicBoolean committed = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);
        assertThatThrownBy(() -> InProcessCalls.as(ORDERS, "inventory", () -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                // an interrupt that arrives after the work was done does not undo it
            }
            committed.set(true);
            done.countDown();
            return null;
        })).isInstanceOf(ModuleCallTimeoutException.class);
        // the call above was interrupted at 300 ms and still ran its commit step: same as a read timeout over HTTP
        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(committed).isTrue();
    }

    @Test
    void theBulkheadRejectsAtOnceWith503AndLeavesOtherModulesAlone() throws InterruptedException {
        environment.setProperty("at.modules.inventory.call-concurrency", "2");
        environment.setProperty("at.orders.inventory.timeout", "10s");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch busy = new CountDownLatch(2);
        List<Thread> blockers = List.of(blocker(busy, release), blocker(busy, release));
        blockers.forEach(Thread::start);
        assertThat(busy.await(2, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        assertThatThrownBy(() -> InProcessCalls.as(ORDERS, "inventory", () -> "never"))
                .isInstanceOfSatisfying(ModuleApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(503);
                    assertThat(e.code()).isEqualTo("inventory.unavailable");
                    assertThat(e.retryable()).isTrue();
                });
        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("rejected without waiting").isLessThan(Duration.ofMillis(200));
        assertThat(InProcessCalls.as(ORDERS, "payments", () -> "other module is fine")).isEqualTo("other module is fine");
        assertThat(count("rejected", "inventory")).isEqualTo(1);
        assertThat(meters.get("at.module.call.rejections").tags("callee", "inventory").counter().count()).isEqualTo(1.0);

        release.countDown();
        for (Thread t : blockers) {
            t.join(2000);
        }
        assertThat(InProcessCalls.as(ORDERS, "inventory", () -> "free again")).isEqualTo("free again");
    }

    private Thread blocker(CountDownLatch busy, CountDownLatch release) {
        return new Thread(() -> InProcessCalls.as(ORDERS, "inventory", () -> {
            busy.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
    }

    @Test
    void aTimedOutCallKeepsItsPermitUntilTheCalleeThreadEnds() throws InterruptedException {
        environment.setProperty("at.modules.inventory.call-concurrency", "1");
        CountDownLatch stuck = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        assertThatThrownBy(() -> InProcessCalls.as(ORDERS, "inventory", () -> {
            stuck.countDown();
            boolean waiting = true;
            while (waiting) {
                try {
                    finish.await(); // ignores the interrupt: a callee that cannot be stopped
                    waiting = false;
                } catch (InterruptedException e) {
                    // keep waiting
                }
            }
            return null;
        })).isInstanceOf(ModuleCallTimeoutException.class);
        assertThat(stuck.await(1, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> InProcessCalls.as(ORDERS, "inventory", () -> "x")).isInstanceOfSatisfying(ModuleApiException.class,
                e -> assertThat(e.status()).isEqualTo(503));
        finish.countDown();
        Thread.sleep(100);
        assertThat(InProcessCalls.as(ORDERS, "inventory", () -> "ok")).isEqualTo("ok");
    }

    @Test
    void aCalleeRefusalKeepsItsStatusAndCodeAndAnUnexpectedFailureKeepsItsType() {
        assertThatThrownBy(() -> InProcessCalls.as(ORDERS, "inventory", () -> {
            throw new ModuleApiException(409, "inventory.insufficient-stock", "short", List.of("A: requested 2, available 1"), null);
        })).isInstanceOfSatisfying(ModuleApiException.class, e -> {
            assertThat(e.status()).isEqualTo(409);
            assertThat(e.code()).isEqualTo("inventory.insufficient-stock");
            assertThat(e.details()).containsExactly("A: requested 2, available 1");
        });
        assertThatThrownBy(() -> InProcessCalls.as(ORDERS, "inventory", () -> {
            throw new IllegalArgumentException("boom");
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("boom");
        assertThat(count("refused", "inventory")).isEqualTo(1);
        assertThat(count("error", "inventory")).isEqualTo(1);
    }

    @Test
    void anErrorIsNotSwallowed() {
        assertThatThrownBy(() -> InProcessCalls.as(ORDERS, "inventory", () -> {
            throw new AssertionError("fatal");
        })).isInstanceOf(AssertionError.class);
    }

    @Test
    void aCallerThatIsInterruptedGivesUpWith503AndKeepsItsInterruptFlag() {
        environment.setProperty("at.orders.inventory.timeout", "10s");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean flag = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                InProcessCalls.as(ORDERS, "inventory", () -> {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            } catch (RuntimeException e) {
                thrown.set(e);
                flag.set(Thread.currentThread().isInterrupted());
            }
        });
        caller.start();
        try {
            Thread.sleep(150);
            caller.interrupt();
            caller.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(thrown.get()).isInstanceOfSatisfying(ModuleApiException.class, e -> assertThat(e.status()).isEqualTo(503));
        assertThat(flag).isTrue();
    }

    @Test
    void mdcAndAThreadLocalRegisteredForPropagationReachTheCallee() {
        ThreadLocal<String> marker = new ThreadLocal<>();
        gate = gate(BoundedModuleCalls.snapshots(List.of(new MarkerAccessor(marker))));
        InProcessCalls.install(gate);
        marker.set("trace-abc");
        MDC.put("traceId", "t-1");
        MDC.put("module", "orders");
        try {
            List<String> seen = InProcessCalls.as(ORDERS, "inventory",
                    () -> List.of(String.valueOf(marker.get()), String.valueOf(MDC.get("traceId")),
                            String.valueOf(MDC.get("module")), String.valueOf(MDC.get("callerModule"))));
            assertThat(seen).containsExactly("trace-abc", "t-1", "inventory", "orders");
            assertThat(MDC.get("module")).isEqualTo("orders");
        } finally {
            marker.remove();
        }
    }

    @Test
    void theCallersObservationIsTheCurrentOneInTheCalleeSoTheTraceContinues() {
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(context -> true);
        ObservationThreadLocalAccessor.getInstance().setObservationRegistry(observations);
        gate = gate(BoundedModuleCalls.snapshots(List.of()));
        InProcessCalls.install(gate);
        Observation caller = Observation.start("checkout", observations);
        try (Observation.Scope ignored = caller.openScope()) {
            Observation inCallee = InProcessCalls.as(ORDERS, "inventory", observations::getCurrentObservation);
            assertThat(inCallee).isSameAs(caller);
        } finally {
            caller.stop();
        }
        assertThat(observations.getCurrentObservation()).isNull();
    }

    @Test
    void theCallersTransactionAndSecurityContextDoNotLeakIntoTheCallee() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionName("caller-tx");
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("customer", "x", "ROLE_USER"));
        try {
            record Seen(boolean synchronizationActive, boolean transactionActive, String name, Object authentication) {
            }
            Seen seen = InProcessCalls.as(ORDERS, "inventory", () -> {
                Seen inside = new Seen(TransactionSynchronizationManager.isSynchronizationActive(),
                        TransactionSynchronizationManager.isActualTransactionActive(),
                        TransactionSynchronizationManager.getCurrentTransactionName(),
                        SecurityContextHolder.getContext().getAuthentication());
                // the callee's own transaction is its own: it can start and finish one without touching the caller's
                TransactionSynchronizationManager.initSynchronization();
                TransactionSynchronizationManager.setActualTransactionActive(true);
                TransactionSynchronizationManager.setActualTransactionActive(false);
                TransactionSynchronizationManager.clearSynchronization();
                return inside;
            });
            assertThat(seen.synchronizationActive()).isFalse();
            assertThat(seen.transactionActive()).isFalse();
            assertThat(seen.name()).isNull();
            assertThat(seen.authentication()).isNull();
            // and the caller's transaction is exactly as it was
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionName()).isEqualTo("caller-tx");
        } finally {
            TransactionSynchronizationManager.setCurrentTransactionName(null);
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void thePersonTravelsOnlyWhenTheCalleeEntryBindsIt() {
        EndUserActor person = InProcessKit.user("user-1", "pt", "CUSTOMER");
        assertThat(InProcessCalls.as(ORDERS, "identity", () -> InProcessCalls.currentUser().isPresent())).isFalse();
        assertThat(InProcessCalls.as(ORDERS, "identity",
                () -> InProcessCalls.withUser(person, () -> InProcessCalls.currentUser().orElseThrow().userId()))).isEqualTo("user-1");
        assertThat(InProcessCalls.currentUser()).isEmpty();
    }

    @Test
    void aNestedCallFromTheCalleeHopsAgainUnderItsOwnPrincipal() {
        CallerPrincipal payments = new CallerPrincipal("payments", "payments-service", Set.of("sellers:membership.read"));
        List<String> chain = InProcessCalls.as(ORDERS, "payments", () -> {
            String first = Thread.currentThread().getName();
            String second = InProcessCalls.as(payments, "seller",
                    () -> Thread.currentThread().getName() + "/" + InProcessCalls.require().module());
            return List.of(first, second, InProcessCalls.require().module());
        });
        assertThat(chain.get(0)).startsWith("module-call-payments-");
        assertThat(chain.get(1)).startsWith("module-call-seller-").endsWith("/payments");
        assertThat(chain.get(2)).isEqualTo("orders");
    }

    @Test
    void aPlainHopIsCheapEnoughToBoundEveryCall() {
        for (int i = 0; i < 2000; i++) {
            InProcessCalls.as(ORDERS, "inventory", () -> 1);
        }
        int n = 20_000;
        long started = System.nanoTime();
        for (int i = 0; i < n; i++) {
            InProcessCalls.as(ORDERS, "inventory", () -> 1);
        }
        long perCallMicros = (System.nanoTime() - started) / n / 1000;
        LoggerFactory.getLogger(BoundedModuleCallsTest.class).info("MEASURED bounded in-process hop: {} us per call (mean over {})", perCallMicros, n);
        assertThat(perCallMicros).as("a hop must stay far below any real call (ms)").isLessThan(500);
    }

    @Test
    void withoutAGateTheCallRunsOnTheCallersThread() {
        InProcessCalls.uninstall(gate);
        String thread = InProcessCalls.as(ORDERS, "inventory", () -> Thread.currentThread().getName());
        assertThat(thread).isEqualTo(Thread.currentThread().getName());
    }
}

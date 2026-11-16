package com.afrotransact.module.core;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.module.ModuleCallGate;
import com.afrotransact.module.ModuleCallTimeoutException;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The bounded wait and the bulkhead between modules (ADR 0011, "a hung dependency still fails the same way").
 *
 * <p><b>Where the callee runs.</b> On a virtual thread of its own module's lane, never on the caller's thread. Java 25 no longer pins a
 * virtual thread's carrier inside {@code synchronized} (JEP 491), the callee code here blocks on JDBC (the PostgreSQL driver and Hikari
 * use {@code j.u.c} locks), on Temporal and on Kafka clients, and nothing in the module code calls native code that parks, so a slow
 * callee costs a parked virtual thread, not a platform thread. The lane is a semaphore sized per callee module: at most that many calls
 * run in that module at once; the next is rejected at once with the status an HTTP 503 gets ({@code <callee>.unavailable}, retryable),
 * so one slow module cannot take the others' threads (its callers wait at most their timeout, never queue).
 *
 * <p><b>What crosses the thread hop.</b> The calling thread's MDC and its {@link ContextSnapshot} (the Micrometer observation, hence
 * the trace and span, plus whatever else registered a thread-local accessor); the caller principal and the end-user actor are bound
 * by {@link InProcessCalls} inside the callee thread. <b>What does not:</b> the caller's transaction and its connection (thread-bound
 * state: the callee's service opens its own transaction on its own schema, a cross-module call never joins the caller's), the
 * {@code SecurityContext} and the request attributes (the actor in force is the principal, or the person, handed over explicitly).
 *
 * <p><b>On timeout</b> the caller stops waiting and the callee thread is interrupted; the permit returns when the callee thread ends.
 * A callee that had already reached commit stays committed (the same as an HTTP read timeout); the caller sees
 * {@link ModuleCallTimeoutException} and must treat the outcome as unknown, as it does over HTTP.
 *
 * <p>Metrics: {@code at_module_call_seconds{caller,callee,outcome}} (outcome {@code ok}, {@code refused} for a status the callee
 * answered, {@code error}, {@code timeout}, {@code rejected}), {@code at_module_call_timeouts_total{caller,callee}},
 * {@code at_module_call_rejections_total{caller,callee}} and {@code at_module_call_active{callee}}.
 */
public final class BoundedModuleCalls implements ModuleCallGate, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(BoundedModuleCalls.class);

    private final ModuleCallSettings settings;
    private final MeterRegistry meters;
    private final ContextSnapshotFactory snapshots;
    private final Map<String, Lane> lanes = new ConcurrentHashMap<>();

    /**
     * The context handed to the callee, from an allow-list of thread-local accessors: the Micrometer observation (hence the trace and
     * span) and any {@link ThreadLocalAccessor} bean an application adds. Not the registry's global list: Spring Security registers its
     * SecurityContext there, and that is the caller's request authentication. The callee's actor is the principal or the person handed
     * over explicitly, so the caller's authentication must not be readable from the callee thread at all.
     */
    static ContextSnapshotFactory snapshots(List<ThreadLocalAccessor<?>> extra) {
        ContextRegistry registry = new ContextRegistry().registerThreadLocalAccessor(ObservationThreadLocalAccessor.getInstance());
        extra.forEach(registry::registerThreadLocalAccessor);
        return ContextSnapshotFactory.builder().contextRegistry(registry).build();
    }

    BoundedModuleCalls(ModuleCallSettings settings, MeterRegistry meters, ContextSnapshotFactory snapshots) {
        this.settings = settings;
        this.meters = meters;
        this.snapshots = snapshots;
    }

    /** One callee module's executor and bulkhead. */
    private static final class Lane {
        final Semaphore permits;
        final ExecutorService executor;
        final AtomicInteger active = new AtomicInteger();

        Lane(String callee, int concurrency) {
            this.permits = new Semaphore(concurrency);
            this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("module-call-" + callee + "-", 0).factory());
        }
    }

    @Override
    public <T> T execute(String caller, String callee, String operation, Supplier<T> call) {
        Lane lane = lane(callee);
        Duration timeout = settings.timeout(caller, callee, operation);
        long started = System.nanoTime();
        if (!lane.permits.tryAcquire()) {
            return rejected(caller, callee, started);
        }
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        ContextSnapshot snapshot = snapshots.captureAll();
        AtomicBoolean entered = new AtomicBoolean();
        Callable<T> task = () -> {
            if (!entered.compareAndSet(false, true)) {
                throw new CancellationException("cancelled before it started");
            }
            lane.active.incrementAndGet();
            try (ContextSnapshot.Scope ignored = snapshot.setThreadLocals()) {
                MDC.setContextMap(mdc == null ? Map.of() : mdc);
                MDC.put(ModuleMdcInterceptor.KEY, callee);
                MDC.put("callerModule", caller);
                return call.get();
            } finally {
                MDC.clear();
                lane.active.decrementAndGet();
                lane.permits.release();
            }
        };
        Future<T> future;
        try {
            future = lane.executor.submit(task);
        } catch (RejectedExecutionException e) {
            lane.permits.release();
            return rejected(caller, callee, started);
        }
        try {
            T result = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            record(caller, callee, "ok", started);
            return result;
        } catch (TimeoutException e) {
            cancel(future, entered, lane);
            record(caller, callee, "timeout", started);
            meters.counter("at.module.call.timeouts", "caller", caller, "callee", callee).increment();
            LOG.warn("Module '{}' did not answer '{}' within {}", callee, caller, timeout);
            throw new ModuleCallTimeoutException(caller, callee, timeout);
        } catch (InterruptedException e) {
            cancel(future, entered, lane);
            Thread.currentThread().interrupt();
            record(caller, callee, "error", started);
            throw new ModuleApiException(503, callee + ".unavailable", "The call to " + callee + " was interrupted");
        } catch (ExecutionException e) {
            throw failed(caller, callee, started, e.getCause());
        }
    }

    /** Interrupts the callee; a task cancelled before it started never runs its own release, so the permit is returned here. */
    private static void cancel(Future<?> future, AtomicBoolean entered, Lane lane) {
        future.cancel(true);
        if (entered.compareAndSet(false, true)) {
            lane.permits.release();
        }
    }

    private <T> T rejected(String caller, String callee, long started) {
        record(caller, callee, "rejected", started);
        meters.counter("at.module.call.rejections", "caller", caller, "callee", callee).increment();
        LOG.warn("Module '{}' is at its call limit of {}: '{}' is refused", callee, settings.concurrency(callee), caller);
        throw new ModuleApiException(503, callee + ".unavailable", callee + " is busy: too many calls in flight");
    }

    private RuntimeException failed(String caller, String callee, long started, Throwable cause) {
        if (cause instanceof Error error) {
            record(caller, callee, "error", started);
            throw error;
        }
        record(caller, callee, cause instanceof ModuleApiException ? "refused" : "error", started);
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException("In-process call to " + callee + " failed", cause);
    }

    private void record(String caller, String callee, String outcome, long started) {
        Timer.builder("at.module.call").description("In-process module calls: caller waiting time by outcome")
                .tags("caller", caller, "callee", callee, "outcome", outcome)
                .register(meters).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }

    private Lane lane(String callee) {
        return lanes.computeIfAbsent(callee, name -> {
            Lane lane = new Lane(name, settings.concurrency(name));
            Gauge.builder("at.module.call.active", lane.active, AtomicInteger::get)
                    .description("In-process calls running in the callee module now").tag("callee", name).register(meters);
            return lane;
        });
    }

    @Override
    public void close() {
        InProcessCalls.uninstall(this);
        lanes.values().forEach(lane -> lane.executor.shutdownNow());
    }
}

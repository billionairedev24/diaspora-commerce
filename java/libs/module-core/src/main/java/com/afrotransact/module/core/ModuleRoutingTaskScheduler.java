package com.afrotransact.module.core;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

/**
 * One scheduler thread pool per module (plus one for everything else), so that a slow or stuck job of one module can never
 * delay another module's jobs, in particular the outbox relays that carry checkout's events. Jobs are routed by the bean that
 * owns the {@code @Scheduled} method; threads are named {@code sched-<module>-<n>}.
 */
final class ModuleRoutingTaskScheduler implements TaskScheduler, DisposableBean {

    private final ModuleRegistry modules;
    private final Map<String, ThreadPoolTaskScheduler> pools = new LinkedHashMap<>();

    ModuleRoutingTaskScheduler(ModuleRegistry modules, int corePoolSize) {
        this.modules = modules;
        pools.put(ModuleRegistry.CORE, pool(ModuleRegistry.CORE, corePoolSize));
        for (ModuleDefinition module : modules.modules()) {
            pools.put(module.name(), pool(module.name(), modules.properties(module.name()).scheduler().poolSize()));
        }
    }

    private static ThreadPoolTaskScheduler pool(String module, int size) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, size));
        scheduler.setThreadNamePrefix("sched-" + module + "-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(20);
        scheduler.initialize();
        return scheduler;
    }

    private TaskScheduler poolFor(Runnable task) {
        ThreadPoolTaskScheduler pool = pools.get(modules.nameOf(ownerOf(task)));
        return pool != null ? pool : pools.get(ModuleRegistry.CORE);
    }

    /**
     * The bean that owns the {@code @Scheduled} method. Spring wraps the method runnable (in Spring 7 in a private
     * {@code Task$OutcomeTrackingRunnable}), so wrappers are opened until the method runnable is found; a runnable that is not
     * one is attributed to itself, which puts it on the core pool.
     */
    private static Object ownerOf(Runnable task) {
        Runnable current = task;
        for (int depth = 0; depth < 5; depth++) {
            if (current instanceof ScheduledMethodRunnable method) {
                return method.getTarget();
            }
            Runnable inner = wrapped(current);
            if (inner == null) {
                break;
            }
            current = inner;
        }
        return task;
    }

    private static Runnable wrapped(Runnable wrapper) {
        for (Class<?> type = wrapper.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Runnable.class.isAssignableFrom(field.getType()) && !Modifier.isStatic(field.getModifiers())) {
                    try {
                        field.setAccessible(true);
                        return (Runnable) field.get(wrapper);
                    } catch (ReflectiveOperationException | RuntimeException ex) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public Clock getClock() {
        return Clock.systemDefaultZone();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
        return poolFor(task).schedule(task, trigger);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
        return poolFor(task).schedule(task, startTime);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
        return poolFor(task).scheduleAtFixedRate(task, startTime, period);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
        return poolFor(task).scheduleAtFixedRate(task, period);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
        return poolFor(task).scheduleWithFixedDelay(task, startTime, delay);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
        return poolFor(task).scheduleWithFixedDelay(task, delay);
    }

    @Override
    public void destroy() {
        pools.values().forEach(ThreadPoolTaskScheduler::shutdown);
    }
}

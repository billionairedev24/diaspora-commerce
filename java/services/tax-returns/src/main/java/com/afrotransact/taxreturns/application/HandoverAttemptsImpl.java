package com.afrotransact.taxreturns.application;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.stereotype.Component;

/** At most {@value #MAX_FAILURES} wrong codes per RMA in {@code WINDOW}; then the RMA is locked until the oldest ages out. */
@Component
class HandoverAttemptsImpl implements HandoverAttempts {

    static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_KEYS = 50_000;

    private final Clock clock;
    private final ConcurrentMap<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    HandoverAttemptsImpl(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void requireOpen(String rmaKey) {
        Deque<Long> times = failures.get(rmaKey);
        if (times == null) {
            return;
        }
        long from = clock.millis() - WINDOW.toMillis();
        synchronized (times) {
            prune(times, from);
            if (times.size() >= MAX_FAILURES) {
                throw new ForbiddenActionException("returns.handover-locked",
                        "Too many wrong handover codes. Try again in a few minutes or ask support.");
            }
        }
    }

    @Override
    public void failed(String rmaKey) {
        long now = clock.millis();
        if (failures.size() > MAX_KEYS) {
            long from = now - WINDOW.toMillis();
            failures.values().removeIf(q -> {
                synchronized (q) {
                    prune(q, from);
                    return q.isEmpty();
                }
            });
        }
        Deque<Long> times = failures.computeIfAbsent(rmaKey, k -> new ArrayDeque<>());
        synchronized (times) {
            prune(times, now - WINDOW.toMillis());
            times.addLast(now);
        }
    }

    @Override
    public void succeeded(String rmaKey) {
        failures.remove(rmaKey);
    }

    private static void prune(Deque<Long> times, long from) {
        while (!times.isEmpty() && times.peekFirst() < from) {
            times.pollFirst();
        }
    }
}

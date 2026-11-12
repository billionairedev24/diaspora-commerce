package com.afrotransact.ai.adapter.out.ratelimit;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.afrotransact.ai.port.CallRateLimiter;
import org.springframework.stereotype.Component;

/**
 * Sliding-window limiter kept in memory of this instance. With several replicas each enforces the limit on its own
 * (the daily cost caps in the usage ledger are shared and remain the hard ceiling).
 */
@Component
class InMemoryCallRateLimiter implements CallRateLimiter {

    private static final long WINDOW_MILLIS = 60_000;
    private static final int SWEEP_ABOVE = 10_000;

    private final Clock clock;
    private final Map<String, Deque<Long>> calls = new ConcurrentHashMap<>();

    InMemoryCallRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public long acquire(String key, int limitPerMinute) {
        if (limitPerMinute <= 0) {
            return 0;
        }
        long now = clock.millis();
        if (calls.size() > SWEEP_ABOVE) {
            sweep(now);
        }
        Deque<Long> window = calls.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MILLIS) {
                window.pollFirst();
            }
            if (window.size() >= limitPerMinute) {
                long waitMillis = WINDOW_MILLIS - (now - window.peekFirst());
                return Math.max(1, (waitMillis + 999) / 1000);
            }
            window.addLast(now);
            return 0;
        }
    }

    private void sweep(long now) {
        Iterator<Map.Entry<String, Deque<Long>>> it = calls.entrySet().iterator();
        while (it.hasNext()) {
            Deque<Long> window = it.next().getValue();
            synchronized (window) {
                if (window.isEmpty() || now - window.peekLast() >= WINDOW_MILLIS) {
                    it.remove();
                }
            }
        }
    }
}

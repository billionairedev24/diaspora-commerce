package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

@Component
class SearchRateLimiterImpl implements SearchRateLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);
    private static final int MAX_ORGS = 50_000;

    private final Clock clock;
    private final ConcurrentMap<String, Deque<Long>> seen = new ConcurrentHashMap<>();

    SearchRateLimiterImpl(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean tryAcquire(String orgId, int limit) {
        long now = clock.millis();
        long from = now - WINDOW.toMillis();
        if (seen.size() > MAX_ORGS) {
            seen.values().removeIf(q -> {
                synchronized (q) {
                    return q.isEmpty() || q.peekLast() < from;
                }
            });
        }
        Deque<Long> times = seen.computeIfAbsent(orgId, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst() < from) {
                times.pollFirst();
            }
            if (times.size() >= limit) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }
}

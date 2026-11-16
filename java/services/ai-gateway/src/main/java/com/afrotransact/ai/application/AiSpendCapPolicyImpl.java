package com.afrotransact.ai.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import com.afrotransact.ai.domain.SpendCapSettings;
import com.afrotransact.ai.port.SpendCapStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class AiSpendCapPolicyImpl implements AiSpendCapPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(AiSpendCapPolicyImpl.class);

    private final SpendCapStore store;
    private final SpendCapSettings settings;
    private final Clock clock;
    private volatile Cached cached;

    AiSpendCapPolicyImpl(SpendCapStore store, SpendCapSettings settings, Clock clock) {
        this.store = store;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public Cap current() {
        Instant now = Instant.now(clock);
        Cached c = cached;
        if (c != null && now.isBefore(c.expires())) {
            return c.cap();
        }
        try {
            Cap fresh = toCap(store.current());
            cached = new Cached(fresh, now.plus(settings.cacheTtl()));
            return fresh;
        } catch (RuntimeException e) {
            // Never fail open: keep what we last knew (or the default) and look again on the next call.
            LOG.warn("AI spend cap could not be read, keeping the last known value: {}", e.toString());
            return c != null ? c.cap() : toCap(Optional.empty());
        }
    }

    @Override
    public void invalidate() {
        cached = null;
    }

    private Cap toCap(Optional<SpendCapStore.Saved> saved) {
        if (saved.isEmpty()) {
            return new Cap(Math.min(settings.defaultMicros(), settings.ceilingMicros()), Source.DEFAULT,
                    settings.defaultMicros(), settings.ceilingMicros(), 0, null, null, null);
        }
        SpendCapStore.Saved s = saved.get();
        // A ceiling lowered after a value was saved still wins: the saved value is clamped, not trusted.
        return new Cap(Math.min(s.capMicros(), settings.ceilingMicros()), Source.SAVED, settings.defaultMicros(),
                settings.ceilingMicros(), s.version(), s.changedBy(), s.changedAt(), s.reason());
    }

    private record Cached(Cap cap, Instant expires) {
    }
}

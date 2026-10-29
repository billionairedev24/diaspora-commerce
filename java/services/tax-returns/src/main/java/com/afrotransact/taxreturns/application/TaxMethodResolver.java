package com.afrotransact.taxreturns.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.tax.TaxMethod;
import com.afrotransact.taxreturns.port.TaxMethodSource;
import com.afrotransact.taxreturns.port.TaxSettingsRepository;
import org.springframework.stereotype.Component;

/**
 * The tax method in force: what staff chose, else the configured default. Read from the database at most every few
 * seconds, so a change made on another replica is followed quickly and a quote costs no query.
 */
@Component
public class TaxMethodResolver implements TaxMethodSource {

    static final Duration TTL = Duration.ofSeconds(10);

    private final TaxSettingsRepository settings;
    private final TaxReturnsProperties props;
    private final Clock clock;
    private volatile Cached cached;

    TaxMethodResolver(TaxSettingsRepository settings, TaxReturnsProperties props, Clock clock) {
        this.settings = settings;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public TaxMethod method() {
        Cached c = cached;
        Instant now = clock.instant();
        if (c == null || now.isAfter(c.expires)) {
            TaxMethod chosen = settings.method().orElseGet(this::configuredDefault);
            c = new Cached(chosen, now.plus(TTL));
            cached = c;
        }
        return c.method;
    }

    /** Forgets the remembered method (after a change on this replica). */
    void refresh() {
        cached = null;
    }

    private TaxMethod configuredDefault() {
        return props.tax().provider() == TaxReturnsProperties.TaxProvider.STRIPE_TAX ? TaxMethod.STRIPE_TAX
                : TaxMethod.RULES;
    }

    private record Cached(TaxMethod method, Instant expires) {
    }
}

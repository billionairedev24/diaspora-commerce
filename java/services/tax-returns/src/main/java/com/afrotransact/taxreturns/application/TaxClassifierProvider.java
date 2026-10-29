package com.afrotransact.taxreturns.application;

import java.time.Duration;
import java.time.Instant;

import com.afrotransact.taxreturns.domain.tax.TaxClassifier;
import com.afrotransact.taxreturns.port.TaxClassificationRepository;
import org.springframework.stereotype.Component;

/** The current {@link TaxClassifier}, rebuilt from the database at most every 30 seconds and right after a rule change. */
@Component
class TaxClassifierProvider {

    private static final Duration MAX_AGE = Duration.ofSeconds(30);

    private final TaxClassificationRepository repository;
    private volatile TaxClassifier current;
    private volatile Instant builtAt = Instant.MIN;

    TaxClassifierProvider(TaxClassificationRepository repository) {
        this.repository = repository;
    }

    TaxClassifier current() {
        TaxClassifier c = current;
        if (c == null || builtAt.plus(MAX_AGE).isBefore(Instant.now())) {
            c = new TaxClassifier(repository.codes(), repository.rules());
            current = c;
            builtAt = Instant.now();
        }
        return c;
    }

    void invalidate() {
        current = null;
    }
}

package com.afrotransact.taxreturns.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.afrotransact.taxreturns.domain.profile.ReviewSettings;
import com.afrotransact.taxreturns.domain.profile.ReviewSettingsChange;
import com.afrotransact.taxreturns.port.TaxReviewSettingsRepository;
import com.afrotransact.taxreturns.port.TaxSettingsRepository;

/** A field-backed {@link TaxReviewSettingsRepository}: mode ALL until a test changes it. */
final class InMemoryReviewSettings implements TaxReviewSettingsRepository {

    private ReviewSettings stored;
    private TaxSettingsRepository.Stamp stamp;
    private final List<ReviewSettingsChange> history = new ArrayList<>();

    @Override
    public ReviewSettings current() {
        return stored == null ? ReviewSettings.defaults() : stored;
    }

    @Override
    public Optional<TaxSettingsRepository.Stamp> stamp() {
        return Optional.ofNullable(stamp);
    }

    @Override
    public void change(ReviewSettings to, String reason, String by, Instant at) {
        history.addFirst(new ReviewSettingsChange(stored, to, reason, by, at));
        stored = to;
        stamp = new TaxSettingsRepository.Stamp(by, at);
    }

    @Override
    public List<ReviewSettingsChange> history(int limit) {
        return history.stream().limit(limit).toList();
    }
}

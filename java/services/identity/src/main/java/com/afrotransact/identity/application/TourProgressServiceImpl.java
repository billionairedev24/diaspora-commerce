package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.TourProgress;
import com.afrotransact.identity.domain.TourProgress.Status;
import com.afrotransact.identity.domain.TourProgressRules;
import com.afrotransact.identity.port.TourMetrics;
import com.afrotransact.identity.port.TourProgressRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TourProgressServiceImpl implements TourProgressService {

    private final CurrentUser current;
    private final TourProgressRepository repository;
    private final TourMetrics metrics;
    private final Clock clock;
    private final boolean autoStart;

    TourProgressServiceImpl(CurrentUser current, TourProgressRepository repository, TourMetrics metrics, Clock clock,
                            @Value("${at.identity.tours.auto-start:true}") boolean autoStart) {
        this.current = current;
        this.repository = repository;
        this.metrics = metrics;
        this.clock = clock;
        this.autoStart = autoStart;
    }

    @Override
    @Transactional(readOnly = true)
    public TourProgressSnapshot snapshot() {
        return snapshot(current.id());
    }

    @Override
    @Transactional
    public TourProgressView save(SaveTourProgressCommand c) {
        UUID user = current.id();
        validate(c);
        return view(apply(user, c, Instant.now(clock)));
    }

    @Override
    @Transactional
    public TourProgressSnapshot merge(List<SaveTourProgressCommand> commands) {
        UUID user = current.id();
        if (commands.size() > TourProgressRules.MAX_IMPORT) {
            throw new IdentityRuleViolation("identity.invalid-tour-progress",
                    "At most " + TourProgressRules.MAX_IMPORT + " tours can be merged at once",
                    Map.of("items", "At most " + TourProgressRules.MAX_IMPORT + " tours"));
        }
        Instant now = Instant.now(clock);
        for (SaveTourProgressCommand c : commands) {
            validate(c);
        }
        for (SaveTourProgressCommand c : commands) {
            // A merge only fills gaps and moves to a newer version: what the server already holds for a version stands.
            TourProgress stored = repository.find(user, c.tourId()).orElse(null);
            if (stored == null || c.contentVersion() > stored.contentVersion()) {
                apply(user, c, now);
            }
        }
        return snapshot(user);
    }

    @Override
    @Transactional
    public void reset(String tourId) {
        if (!TourProgressRules.validId(tourId)) {
            throw new IdentityRuleViolation("identity.invalid-tour-progress", "Not a tour id",
                    Map.of("tourId", "Use lower-case words joined by - or ."));
        }
        if (repository.delete(current.id(), tourId)) {
            metrics.event("reset");
        }
    }

    @Override
    @Transactional
    public void resetAll() {
        if (repository.deleteAll(current.id()) > 0) {
            metrics.event("reset");
        }
    }

    private TourProgress apply(UUID user, SaveTourProgressCommand c, Instant now) {
        TourProgress stored = repository.find(user, c.tourId()).orElse(null);
        if (!TourProgressRules.replaces(stored, c.contentVersion(), c.status())) {
            return stored;
        }
        if (stored == null && repository.count(user) >= TourProgressRules.MAX_TOURS) {
            throw new IdentityRuleViolation("identity.tour-progress-limit", "Too many tours are saved for this account",
                    Map.of("tourId", "At most " + TourProgressRules.MAX_TOURS + " tours"));
        }
        TourProgress next = new TourProgress(user, c.tourId(), c.contentVersion(), c.status(), c.lastStep(), now);
        repository.upsert(next);
        boolean changed = stored == null || stored.contentVersion() != next.contentVersion()
                || stored.status() != next.status();
        if (changed) {
            count(stored, next);
        }
        return next;
    }

    /** Counts a start when a tour first begins (or a new version begins) and its finish; a same-status rewrite counts nothing. */
    private void count(TourProgress before, TourProgress after) {
        boolean newVersion = before == null || before.contentVersion() != after.contentVersion();
        if (after.status() == Status.IN_PROGRESS && newVersion) {
            metrics.event("started");
        } else if (after.status() == Status.COMPLETED) {
            metrics.event("completed");
        } else if (after.status() == Status.DISMISSED) {
            metrics.event("dismissed");
        }
    }

    private TourProgressSnapshot snapshot(UUID user) {
        return new TourProgressSnapshot(repository.findAll(user).stream().map(TourProgressServiceImpl::view).toList(),
                autoStart);
    }

    private static TourProgressView view(TourProgress p) {
        return new TourProgressView(p.tourId(), p.contentVersion(), p.status(), p.lastStep(), p.updatedAt());
    }

    private static void validate(SaveTourProgressCommand c) {
        Map<String, String> errors = new LinkedHashMap<>(TourProgressRules.validate(c.tourId(), c.contentVersion(),
                c.status(), c.lastStep()));
        if (!errors.isEmpty()) {
            throw new IdentityRuleViolation("identity.invalid-tour-progress", "Tour progress is not valid", errors);
        }
    }
}

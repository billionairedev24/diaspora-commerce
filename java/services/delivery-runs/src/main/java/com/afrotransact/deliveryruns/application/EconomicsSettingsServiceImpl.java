package com.afrotransact.deliveryruns.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.EconomicsChange;
import com.afrotransact.deliveryruns.domain.EconomicsRevision;
import com.afrotransact.deliveryruns.domain.EconomicsSettings;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.port.EconomicsSettingsRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class EconomicsSettingsServiceImpl implements EconomicsSettingsService {

    private static final int MAX_CHANGES = 200;

    private final EconomicsSettingsRepository repository;
    private final EconomicsBook book;
    private final PermissionChecker permissions;
    private final Clock clock;

    EconomicsSettingsServiceImpl(EconomicsSettingsRepository repository, EconomicsBook book,
                                 PermissionChecker permissions, Clock clock) {
        this.repository = repository;
        this.book = book;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public EconomicsRevision current() {
        permissions.require(DeliveryRunsPermissions.READ);
        return repository.find().orElseGet(() -> EconomicsRevision.defaults(book.defaults()));
    }

    @Override
    @Transactional
    public EconomicsRevision replace(EconomicsSettingsCommand c, Long expectedVersion) {
        Actor actor = permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE);
        String reason = c.reason() == null ? "" : c.reason().trim();
        if (reason.isEmpty()) {
            throw new RuleViolation("delivery-runs.reason-required", "Say why: the reason is logged with your name");
        }
        if (reason.length() > DeliveryFeeServiceImpl.MAX_REASON) {
            throw new RuleViolation("delivery-runs.invalid-reason", "The reason is at most "
                    + DeliveryFeeServiceImpl.MAX_REASON + " characters");
        }
        EconomicsSettings numbers = EconomicsSettings.checked(c.driverCostPerHourMinor(), c.driverCostPerMileMinor(),
                c.collectionMinutes(), c.perStopMinutes(), c.pickupPointStopMinutes(), c.handoverMinutesPerOrder(),
                c.roundTrip());
        Optional<EconomicsRevision> stored = repository.lock();
        long seen = stored.map(EconomicsRevision::version).orElse(0L);
        if (expectedVersion != null && expectedVersion != seen) {
            throw new VersionMismatchException("The economics settings changed since version " + expectedVersion
                    + " (now " + seen + "); reload and retry", seen);
        }
        if (stored.isPresent() && stored.get().settings().equals(numbers)) {
            return stored.get();
        }
        Instant now = Instant.now(clock);
        EconomicsSettings before = stored.map(EconomicsRevision::settings).orElse(book.defaults());
        EconomicsRevision next;
        if (stored.isPresent()) {
            next = stored.get().next(numbers, actor.subject(), reason, now);
            repository.update(next);
        } else {
            next = EconomicsRevision.defaults(before).next(numbers, actor.subject(), reason, now);
            repository.insert(next);
        }
        repository.log(new EconomicsChange(UUID.randomUUID(), next.version(), before, numbers, actor.subject(), reason,
                now));
        book.forget();
        return next;
    }

    @Override
    @Transactional(readOnly = true)
    public List<EconomicsChange> changes(int limit) {
        permissions.require(DeliveryRunsPermissions.READ);
        return repository.changes(Math.min(Math.max(limit, 1), MAX_CHANGES));
    }
}

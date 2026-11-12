package com.afrotransact.offers.application;

import java.time.Clock;
import java.time.Instant;

import com.afrotransact.offers.domain.OfferPolicy;
import com.afrotransact.offers.domain.OfferRuleViolation;
import com.afrotransact.offers.port.OfferSettingsRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OfferSettingsServiceImpl implements OfferSettingsService {

    private final OfferSettingsRepository repository;
    private final OfferPolicy defaults;
    private final PermissionChecker permissions;
    private final Clock clock;

    OfferSettingsServiceImpl(OfferSettingsRepository repository, OfferPolicy defaults, PermissionChecker permissions,
                             Clock clock) {
        this.repository = repository;
        this.defaults = defaults;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public OfferPolicy current() {
        return repository.find().orElse(defaults);
    }

    @Override
    @Transactional(readOnly = true)
    public OfferPolicy view() {
        staff();
        return current();
    }

    @Override
    @Transactional
    public OfferPolicy update(OfferPolicy next) {
        Actor actor = staff();
        var problems = next.problems();
        if (!problems.isEmpty()) {
            throw new OfferRuleViolation("The settings are not valid", problems);
        }
        return repository.save(next.savedBy(actor.subject(), Instant.now(clock)));
    }

    private Actor staff() {
        Actor actor = permissions.require(OffersPermissions.WRITE);
        if (!Actors.isStaff(actor)) {
            throw new ForbiddenActionException("offers.staff-only", "Only staff change the offer settings");
        }
        return actor;
    }
}

package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;

import com.afrotransact.catalog.domain.CatalogPolicy;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.StaleVersionException;
import com.afrotransact.catalog.port.CatalogSettingsRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CatalogSettingsServiceImpl implements CatalogSettingsService {

    private final CatalogSettingsRepository repository;
    private final CatalogPolicy defaults;
    private final PermissionChecker permissions;
    private final Clock clock;

    CatalogSettingsServiceImpl(CatalogSettingsRepository repository, CatalogPolicy defaults,
                               PermissionChecker permissions, Clock clock) {
        this.repository = repository;
        this.defaults = defaults;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public CatalogPolicy current() {
        return repository.find().orElse(defaults);
    }

    @Override
    @Transactional(readOnly = true)
    public CatalogPolicy view() {
        permissions.require(CatalogPermissions.MODERATE);
        return current();
    }

    @Override
    @Transactional
    public CatalogPolicy update(CatalogPolicy next) {
        Actor actor = permissions.require(CatalogPermissions.MODERATE);
        var problems = next.problems();
        if (!problems.isEmpty()) {
            throw new CatalogRuleViolation("The settings are not valid", problems);
        }
        try {
            return repository.save(next.savedBy(actor.subject(), Instant.now(clock)));
        } catch (StaleVersionException ex) {
            throw new ConflictException("catalog.concurrent-update",
                    "The settings were changed by someone else; reload and try again");
        }
    }
}

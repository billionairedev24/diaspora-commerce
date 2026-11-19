package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.StaleVersionException;
import com.afrotransact.catalog.domain.pictures.PictureOverrides;
import com.afrotransact.catalog.domain.pictures.PictureProfile;
import com.afrotransact.catalog.domain.pictures.PictureRules;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.catalog.port.PictureRulesRepository;
import org.springframework.stereotype.Service;

@Service
class PictureRulesServiceImpl implements PictureRulesService {

    static final Duration CACHE_TTL = Duration.ofSeconds(10);
    static final int MIN_REASON = 3;
    static final int MAX_REASON = 300;
    private static final int HISTORY = 20;

    private final PictureRulesRepository repository;
    private final PictureRules defaults;
    private final PermissionChecker permissions;
    private final Clock clock;

    private record Cached(PictureRules rules, Instant at) {
    }

    private volatile Cached cached;

    PictureRulesServiceImpl(PictureRulesRepository repository, PictureRules environmentDefault,
                            PermissionChecker permissions, Clock clock) {
        this.repository = repository;
        this.defaults = environmentDefault;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Override
    public PictureRules current() {
        Cached c = cached;
        Instant now = Instant.now(clock);
        if (c != null && Duration.between(c.at(), now).compareTo(CACHE_TTL) < 0) {
            return c.rules();
        }
        PictureRules fresh = repository.find().orElse(defaults);
        cached = new Cached(fresh, now);
        return fresh;
    }

    @Override
    public View view() {
        boolean canChange = canManage();
        if (!canChange) {
            permissions.require(CatalogPermissions.MODERATE);
        }
        cached = null;
        List<PictureRules> profiles = Arrays.stream(PictureProfile.values()).map(PictureRules::defaults).toList();
        return new View(current(), canChange, repository.history(HISTORY), profiles, defaults.profile());
    }

    @Override
    public PictureRules update(PictureProfile profile, PictureOverrides overrides, String reason, long expectedVersion) {
        Actor actor = permissions.require(CatalogPermissions.PICTURE_RULES_MANAGE);
        Map<String, String> problems = new LinkedHashMap<>();
        PictureRules next = PictureRules.resolve(profile, overrides);
        problems.putAll(next.knobs().problems());
        String why = reason == null ? "" : reason.strip();
        if (why.length() < MIN_REASON || why.length() > MAX_REASON) {
            problems.put("reason", "Say why, 3 to 300 characters: it is logged with your name");
        }
        if (!problems.isEmpty()) {
            throw new CatalogRuleViolation("The picture rules are not valid", problems);
        }
        try {
            PictureRules saved = repository.save(next.savedBy(actor.subject(), Instant.now(clock), expectedVersion + 1),
                    expectedVersion, why);
            cached = null;
            return saved;
        } catch (StaleVersionException ex) {
            throw new ConflictException("catalog.concurrent-update",
                    "The picture rules were changed by someone else; reload and try again");
        }
    }

    private boolean canManage() {
        try {
            permissions.require(CatalogPermissions.PICTURE_RULES_MANAGE);
            return true;
        } catch (ForbiddenActionException ex) {
            return false;
        }
    }
}

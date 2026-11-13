package com.afrotransact.content.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.content.domain.ContentRuleViolation;
import com.afrotransact.content.domain.home.HomeSettings;
import com.afrotransact.content.domain.home.HomeSettingsDiff;
import com.afrotransact.content.domain.home.HomeSettingsDiff.Change;
import com.afrotransact.content.port.HomeSettingsRepository;
import com.afrotransact.content.port.HomeSettingsRepository.Stored;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class HomeSettingsServiceImpl implements HomeSettingsService {

    private final HomeSettingsRepository repository;
    private final ContentAccess access;
    private final AuditTrail audit;
    private final Clock clock;

    HomeSettingsServiceImpl(HomeSettingsRepository repository, ContentAccess access, AuditTrail audit, Clock clock) {
        this.repository = repository;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public HomeSettings current() {
        return repository.latest().map(s -> s.settings().withDefaultsFilled()).orElseGet(HomeSettings::defaults);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Stored> versions() {
        access.requireAny(ContentPermissions.HOME_VIEW, ContentPermissions.HOME_EDIT);
        return repository.history();
    }

    @Override
    @Transactional
    public HomeSettings save(int expectedVersion, HomeSettings proposed, String reason) {
        Actor actor = access.require(ContentPermissions.HOME_EDIT);
        requireReason(reason);
        HomeSettings current = current();
        if (current.version() != expectedVersion) {
            throw new ConflictException("content.home-settings-stale",
                    "The home settings were changed by someone else (now version " + current.version() + "). Reload and try again.");
        }
        HomeSettings next = proposed.withDefaultsFilled();
        next.validate();
        List<Change> changes = HomeSettingsDiff.between(current, next);
        if (changes.isEmpty()) {
            return current;
        }
        return store(actor, current, next, reason.trim(), null, changes, "home-settings.updated");
    }

    @Override
    @Transactional
    public HomeSettings revert(int toVersion, String reason) {
        Actor actor = access.require(ContentPermissions.HOME_EDIT);
        requireReason(reason);
        Stored target = repository.find(toVersion)
                .orElseThrow(() -> new NotFoundException("content.home-settings-version-not-found", "No such version"));
        HomeSettings current = current();
        HomeSettings next = target.settings().withDefaultsFilled();
        List<Change> changes = HomeSettingsDiff.between(current, next);
        if (changes.isEmpty()) {
            return current;
        }
        return store(actor, current, next, reason.trim(), toVersion, changes, "home-settings.reverted");
    }

    private HomeSettings store(Actor actor, HomeSettings current, HomeSettings next, String reason, Integer revertOf,
                               List<Change> changes, String action) {
        Instant now = Instant.now(clock);
        int version = current.version() + 1;
        HomeSettings saved = new HomeSettings(version, next.dynamic(), next.modules(), next.caps(), next.departmentLabels(),
                now, actor.subject());
        List<Map<String, Object>> maps = HomeSettingsDiff.asMaps(changes);
        if (!repository.insert(new Stored(saved, reason, revertOf, maps, now, actor.subject()))) {
            throw new ConflictException("content.home-settings-stale", "Someone else saved first. Reload and try again.");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason);
        detail.put("fromVersion", current.version());
        detail.put("toVersion", version);
        detail.put("revertOf", revertOf);
        detail.put("changes", maps);
        audit.record(actor.subject(), action, "home-settings", String.valueOf(version), detail);
        return saved;
    }

    static void requireReason(String reason) {
        if (reason == null || reason.trim().length() < 3 || reason.trim().length() > 300) {
            throw new ContentRuleViolation("content.reason-required", "Say why you are changing this",
                    Map.of("reason", "Say why you are changing this (3 to 300 characters)"));
        }
    }
}

package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.identity.domain.IdentityProviderSetting;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.IdentityProviderSettingsRepository;
import com.afrotransact.identity.port.SocialProviderCatalog;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class IdentityProviderServiceImpl implements IdentityProviderService {

    private final IdentityProviderSettingsRepository settings;
    private final SocialProviderCatalog catalog;
    private final PermissionChecker permissions;
    private final CurrentUser current;
    private final AccessChangeLog changeLog;
    private final Clock clock;

    IdentityProviderServiceImpl(IdentityProviderSettingsRepository settings, SocialProviderCatalog catalog,
                                PermissionChecker permissions, CurrentUser current, AccessChangeLog changeLog,
                                Clock clock) {
        this.settings = settings;
        this.catalog = catalog;
        this.permissions = permissions;
        this.current = current;
        this.changeLog = changeLog;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<IdentityProviderSetting> enabledProviders() {
        Set<String> configured = catalog.configuredProviders();
        return settings.all().stream()
                .filter(s -> s.enabled() && configured.contains(s.id()))
                .sorted(Comparator.comparingInt(IdentityProviderSetting::position))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEnabled(String providerId) {
        return catalog.configuredProviders().contains(providerId)
                && settings.find(providerId).map(IdentityProviderSetting::enabled).orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProviderAdminView> all() {
        Actor actor = current.actor();
        if (!actor.can(IdentityPermissions.ACCESS_READ) && !actor.can(IdentityPermissions.ACCESS_ADMIN)) {
            throw new ForbiddenActionException("auth.missing-permission",
                    "Missing permission " + IdentityPermissions.ACCESS_READ);
        }
        Set<String> configured = catalog.configuredProviders();
        return settings.all().stream()
                .sorted(Comparator.comparingInt(IdentityProviderSetting::position))
                .map(s -> new ProviderAdminView(s, configured.contains(s.id())))
                .toList();
    }

    @Override
    @Transactional
    public ProviderAdminView setEnabled(String providerId, boolean enabled) {
        Actor actor = permissions.require(IdentityPermissions.ACCESS_ADMIN);
        IdentityProviderSetting setting = settings.find(providerId).orElseThrow(() -> new NotFoundException(
                "identity.provider-not-found", "Unknown identity provider " + providerId));
        boolean configured = catalog.configuredProviders().contains(providerId);
        if (enabled && !configured) {
            throw new IdentityRuleViolation("identity.provider-not-configured",
                    "Add the " + setting.displayName() + " client id and secret before enabling it",
                    Map.of("enabled", "Not configured in this environment"));
        }
        IdentityProviderSetting updated = setting.withEnabled(enabled, Instant.now(clock), actor.subject());
        settings.save(updated);
        changeLog.append(actor.subject(), enabled ? "provider.enabled" : "provider.disabled", null, null, providerId,
                null);
        return new ProviderAdminView(updated, configured);
    }
}

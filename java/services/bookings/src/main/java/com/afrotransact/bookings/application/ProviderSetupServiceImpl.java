package com.afrotransact.bookings.application;

import java.time.Clock;
import java.time.Instant;

import com.afrotransact.bookings.domain.BookingRules;
import com.afrotransact.bookings.domain.CancellationPolicy;
import com.afrotransact.bookings.domain.CustomPolicySettings;
import com.afrotransact.bookings.domain.PolicyKind;
import com.afrotransact.bookings.domain.Provider;
import com.afrotransact.bookings.port.ProviderRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ProviderSetupServiceImpl implements ProviderSetupService {

    private final ProviderRepository providers;
    private final BookingsAccess access;
    private final Clock clock;

    ProviderSetupServiceImpl(ProviderRepository providers, BookingsAccess access, Clock clock) {
        this.providers = providers;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Provider get(String orgId) {
        access.provider(orgId, BookingsPermissions.READ);
        return require(orgId);
    }

    @Override
    @Transactional
    public Provider saveSettings(String orgId, SettingsCommand cmd) {
        access.providerWide(orgId, BookingsPermissions.AVAILABILITY_WRITE);
        Instant now = Instant.now(clock);
        BookingRules rules = cmd.rules() == null ? BookingRules.defaults() : cmd.rules();
        return providers.lock(orgId)
                .map(p -> providers.update(p.withSettings(cmd.displayName(), cmd.timeZone(), cmd.location(),
                        cmd.rules() == null ? p.rules() : rules, now)))
                .orElseGet(() -> providers.insert(Provider.create(orgId, cmd.displayName(), cmd.timeZone(),
                        cmd.currency(), cmd.location(), rules, now)));
    }

    @Override
    @Transactional
    public Provider savePolicy(String orgId, PolicyKind kind, CustomPolicySettings custom) {
        access.providerWide(orgId, BookingsPermissions.SERVICES_WRITE);
        CancellationPolicy policy = kind == PolicyKind.CUSTOM
                ? CancellationPolicy.custom(custom == null ? CustomPolicySettings.defaults() : custom)
                : CancellationPolicy.preset(kind);
        Provider p = providers.lock(orgId).orElseThrow(() -> notFound(orgId));
        return providers.update(p.withPolicy(policy, Instant.now(clock)));
    }

    private Provider require(String orgId) {
        return providers.find(orgId).orElseThrow(() -> notFound(orgId));
    }

    static NotFoundException notFound(String orgId) {
        return new NotFoundException("bookings.provider-not-found", "Provider " + orgId + " has no booking setup");
    }
}

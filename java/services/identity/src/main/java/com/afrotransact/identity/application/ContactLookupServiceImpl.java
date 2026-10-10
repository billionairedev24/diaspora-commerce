package com.afrotransact.identity.application;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.afrotransact.identity.domain.NotificationPreferences;
import com.afrotransact.identity.domain.PostalAddress;
import com.afrotransact.identity.domain.PushApp;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.AddressRepository;
import com.afrotransact.identity.port.NotificationPreferencesRepository;
import com.afrotransact.identity.port.OrganizationRepository;
import com.afrotransact.identity.port.PhoneVerificationRepository;
import com.afrotransact.identity.port.PushDeviceRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ContactLookupServiceImpl implements ContactLookupService {

    private final CurrentUser current;
    private final UserAccountRepository users;
    private final NotificationPreferencesRepository preferences;
    private final PushDeviceRepository devices;
    private final PhoneVerificationRepository phoneVerifications;
    private final AddressRepository addresses;
    private final OrganizationRepository orgs;

    ContactLookupServiceImpl(CurrentUser current, UserAccountRepository users,
                             NotificationPreferencesRepository preferences, PushDeviceRepository devices,
                             PhoneVerificationRepository phoneVerifications, AddressRepository addresses,
                             OrganizationRepository orgs) {
        this.current = current;
        this.users = users;
        this.preferences = preferences;
        this.devices = devices;
        this.phoneVerifications = phoneVerifications;
        this.addresses = addresses;
        this.orgs = orgs;
    }

    @Override
    @Transactional(readOnly = true)
    public ContactView contact(UUID userId, PushApp app) {
        requireServiceLookup();
        UserAccount u = users.findById(userId).orElseThrow(() -> new NotFoundException("identity.account-not-found",
                "Account not found"));
        NotificationPreferences p = preferences.find(userId).orElseGet(() -> NotificationPreferences.defaults(userId));
        List<ContactView.PushTarget> tokens = devices.findByUser(userId).stream()
                .filter(d -> app == null || d.app() == app)
                .map(d -> new ContactView.PushTarget(d.token(), d.provider().wire(), lower(d.app().name()),
                        lower(d.platform().name())))
                .toList();
        boolean phoneVerified = u.phone() != null
                && phoneVerifications.verifiedPhone(userId).map(u.phone()::equals).orElse(false);
        return new ContactView(u.id().toString(), u.firstName(), u.email(), u.emailVerified(), u.phone(),
                phoneVerified, p.locale(), p.timeZone(), market(u), tokens,
                new ContactView.ChannelPreferences(p.email(), p.sms(), p.push()), p.marketingOptIn());
    }

    @Override
    @Transactional(readOnly = true)
    public OrgOwnersView orgOwners(String orgId) {
        requireServiceLookup();
        orgs.findById(orgId).orElseThrow(() -> new NotFoundException("identity.org-not-found",
                "Organisation not found"));
        return new OrgOwnersView(orgId, orgs.ownerUserIds(orgId).stream().map(UUID::toString).toList());
    }

    private String market(UserAccount u) {
        String fromAddress = addresses.findByUser(u.id()).stream()
                .max(Comparator.comparing(PostalAddress::isDefault).thenComparing(PostalAddress::updatedAt))
                .map(PostalAddress::country).orElse(null);
        if (fromAddress != null) {
            return fromAddress;
        }
        return u.activeOrgId() == null ? null : orgs.findById(u.activeOrgId()).map(o -> o.market()).orElse(null);
    }

    private void requireServiceLookup() {
        Actor actor = current.actor();
        if (actor.type() != PrincipalType.SERVICE || !actor.can(IdentityPermissions.USERS_READ)) {
            throw new ForbiddenActionException("identity.service-lookup-only",
                    "Contact lookups need a service token with users:read");
        }
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}

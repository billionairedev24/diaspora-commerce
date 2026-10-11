package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.seller.domain.OrgStatus;
import com.afrotransact.seller.domain.Organisation;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.port.OrganisationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrgProvisioningServiceImpl implements OrgProvisioningService {

    private static final Pattern ID = Pattern.compile("org_[a-z0-9_]{3,60}");
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

    private final OrganisationRepository orgs;
    private final AccessGuard access;
    private final DomainEventPublisher events;
    private final SellerSettings settings;
    private final Clock clock;
    private final boolean enabled;

    OrgProvisioningServiceImpl(OrganisationRepository orgs, AccessGuard access, DomainEventPublisher events,
                               SellerSettings settings, Clock clock,
                               @Value("${at.seller.provisioning-enabled:false}") boolean enabled) {
        this.orgs = orgs;
        this.access = access;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Override
    @Transactional
    public Provisioned provision(ProvisionCommand cmd) {
        if (!enabled) {
            throw new NotFoundException("seller.provisioning-off", "Organisation provisioning is not enabled here");
        }
        access.staff(PROVISION);
        validate(cmd);
        String id = cmd.id() == null || cmd.id().isBlank() ? Ids.org() : cmd.id().trim();
        Optional<Organisation> existing = orgs.findById(id);
        if (existing.isPresent()) {
            return new Provisioned(new OrgView(existing.get(), orgs.members(id)), false);
        }
        Instant now = Instant.now(clock);
        Organisation org = orgs.insert(Organisation.draft(id, cmd.kind(), cmd.displayName().trim(), now)
                .withProfile(cmd.legalName().trim(), cmd.displayName().trim(),
                        cmd.country().trim().toUpperCase(Locale.ROOT), now)
                .withOffering(cmd.offering(), now)
                .withStatus(OrgStatus.APPROVED, now));
        OrgEventPayload payload = OrgEventPayload.of(org, List.of(), now);
        events.publish(SellerEvents.ORG_APPROVED, SellerEvents.AGGREGATE_ORG, org.id(), payload);
        events.publish(SellerEvents.ORG_UPDATED, SellerEvents.AGGREGATE_ORG, org.id(), payload);
        return new Provisioned(new OrgView(org, List.of()), true);
    }

    private void validate(ProvisionCommand cmd) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (cmd.id() != null && !cmd.id().isBlank()) {
            if (!ID.matcher(cmd.id().trim()).matches()) {
                errors.put("id", "org_ followed by 3 to 60 lowercase letters, digits or underscores");
            } else if (settings.isRetail(cmd.id().trim())) {
                errors.put("id", "AfroTransact Retail is not a marketplace organisation");
            }
        }
        if (cmd.kind() == null) {
            errors.put("kind", "SELLER or PROVIDER");
        }
        if (cmd.offering() == null) {
            errors.put("offering", "PRODUCTS, SERVICES or BOTH");
        } else if (cmd.kind() != null && !consistent(cmd)) {
            errors.put("offering", "Does not match the organisation kind");
        }
        requireName(cmd.legalName(), "legalName", errors);
        requireName(cmd.displayName(), "displayName", errors);
        if (cmd.country() == null || !COUNTRY.matcher(cmd.country().trim().toUpperCase(Locale.ROOT)).matches()) {
            errors.put("country", "ISO 3166-1 alpha-2 code");
        }
        if (!errors.isEmpty()) {
            throw new SellerRuleViolation("seller.invalid-input", "The organisation is not valid", errors);
        }
    }

    private static boolean consistent(ProvisionCommand cmd) {
        return switch (cmd.kind()) {
            case SELLER -> cmd.offering().includesProducts();
            case PROVIDER -> cmd.offering().includesServices();
        };
    }

    private static void requireName(String value, String field, Map<String, String> errors) {
        String v = value == null ? "" : value.trim();
        if (v.length() < 2 || v.length() > 80) {
            errors.put(field, "2 to 80 characters");
        }
    }
}

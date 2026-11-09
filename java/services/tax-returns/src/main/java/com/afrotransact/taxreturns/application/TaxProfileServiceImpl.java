package com.afrotransact.taxreturns.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.taxreturns.domain.profile.ProfileStatus;
import com.afrotransact.taxreturns.domain.profile.TaxProfile;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.port.TaxProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TaxProfileServiceImpl implements TaxProfileService {

    private static final int MIN_REASON = 3;

    private final TaxProfileRepository profiles;
    private final ProfileCatalog catalog;
    private final ClassificationEvents events;
    private final Access access;
    private final Clock clock;

    TaxProfileServiceImpl(TaxProfileRepository profiles, ProfileCatalog catalog, ClassificationEvents events,
                          Access access, Clock clock) {
        this.profiles = profiles;
        this.catalog = catalog;
        this.events = events;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProfileView> list(String department, ProfileStatus status, String q) {
        access.staff(TaxReturnsPermissions.TAX_READ);
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        String version = catalog.current().ruleVersion();
        return catalog.current().ordered().stream()
                .filter(p -> department == null || department.isBlank() || p.departmentCode().equals(department))
                .filter(p -> status == null || p.status() == status)
                .filter(p -> needle.isEmpty() || p.name().toLowerCase(Locale.ROOT).contains(needle)
                        || p.categoryCode().contains(needle))
                .map(p -> new ProfileView(p, null, version)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ProfileView get(String categoryCode) {
        access.staff(TaxReturnsPermissions.TAX_READ);
        return view(categoryCode);
    }

    @Override
    @Transactional
    public ProfileView changeStatus(String categoryCode, StatusChange c) {
        Actor actor = access.staffAny(TaxReturnsPermissions.TAX_CLASSIFY_ADMIN, TaxReturnsPermissions.TAX_CONFIGURE);
        TaxProfile profile = profiles.find(categoryCode).orElseThrow(() -> new NotFoundException(
                "tax.profile-not-found", "No tax profile for category " + categoryCode));
        if (c.status() == null || c.reason() == null || c.reason().trim().length() < MIN_REASON) {
            throw invalid("reason", "Say why the profile status changes (at least " + MIN_REASON + " characters)");
        }
        ProfileStatus from = profile.status();
        if (from == c.status()) {
            return view(categoryCode);
        }
        boolean lifting = c.status() == ProfileStatus.ACTIVE && from.needsSignOff();
        if (from == ProfileStatus.CONFLICT_OPEN && c.status() == ProfileStatus.ACTIVE
                && !profile.conflictingCodes().isEmpty()) {
            throw new ReturnRuleViolation("tax.profile-conflict-unresolved",
                    "The conflicting codes (" + String.join(" and ", profile.conflictingCodes())
                            + ") are still in the rules. Decide the open decision, change the dataset and ship it as a "
                            + "migration; then lift the gate.", Map.of("status", "Conflict is unresolved"));
        }
        if (lifting && (c.approvedBy() == null || c.approvedBy().isBlank())) {
            throw new ReturnRuleViolation("tax.profile-approver-required",
                    "Lifting " + from + " needs the name of the tax professional who approved it",
                    Map.of("approvedBy", "Required"));
        }
        Instant now = clock.instant();
        String by = Access.describe(actor);
        String version = profiles.newRuleVersion("Profile " + categoryCode + ": " + from + " to " + c.status() + " ("
                + c.reason().trim() + ")", by, now);
        profiles.updateStatus(categoryCode, c.status(), by, now);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", from.name());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", c.status().name());
        profiles.audit(new TaxProfileRepository.AuditRow("PROFILE_STATUS", categoryCode, null, null, now, by,
                c.reason().trim(), c.approvedBy(), c.decisionRef(), version, before, after));
        events.profileStatus(categoryCode, c.status().name(), from.name(), version, c.reason().trim(), by, now);
        catalog.refresh();
        return view(categoryCode);
    }

    private ProfileView view(String categoryCode) {
        TaxProfile p = profiles.find(categoryCode).orElseThrow(() -> new NotFoundException("tax.profile-not-found",
                "No tax profile for category " + categoryCode));
        return new ProfileView(p, profiles.stamp(categoryCode).orElse(null), profiles.currentRuleVersion());
    }

    private static ReturnRuleViolation invalid(String field, String message) {
        return new ReturnRuleViolation("tax.invalid-profile-change", message, Map.of(field, message));
    }
}

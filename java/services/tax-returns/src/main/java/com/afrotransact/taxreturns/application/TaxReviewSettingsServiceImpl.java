package com.afrotransact.taxreturns.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.taxreturns.domain.profile.ProfileStatus;
import com.afrotransact.taxreturns.domain.profile.ReviewMode;
import com.afrotransact.taxreturns.domain.profile.ReviewSettings;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.port.TaxReviewSettingsRepository;
import com.afrotransact.taxreturns.port.TaxSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TaxReviewSettingsServiceImpl implements TaxReviewSettingsService {

    private static final int HISTORY = 20;
    private static final int MIN_REASON = 10;

    private final TaxReviewSettingsRepository settings;
    private final ProfileCatalog catalog;
    private final Access access;
    private final Clock clock;

    TaxReviewSettingsServiceImpl(TaxReviewSettingsRepository settings, ProfileCatalog catalog, Access access,
                                 Clock clock) {
        this.settings = settings;
        this.catalog = catalog;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ReviewSettingsView current() {
        access.staff(TaxReturnsPermissions.TAX_READ);
        return view();
    }

    @Override
    @Transactional
    public ReviewSettingsView change(ReviewMode mode, List<String> alwaysReview, Integer auditPercent, String reason) {
        Actor actor = access.staff(TaxReturnsPermissions.TAX_CLASSIFY_ADMIN);
        if (mode == null) {
            throw new ReturnRuleViolation("tax.review-mode-required", "Choose how tax review works (ALL or RISK_BASED)",
                    Map.of("mode", "Required"));
        }
        if (reason == null || reason.trim().length() < MIN_REASON) {
            throw new ReturnRuleViolation("tax.reason-required", "Say why the tax review settings change (at least "
                    + MIN_REASON + " characters)", Map.of("reason", "Required"));
        }
        ReviewSettings current = settings.current();
        int percent = auditPercent == null ? current.auditPercent() : auditPercent;
        if (percent < 0 || percent > ReviewSettings.MAX_AUDIT_PERCENT) {
            throw new ReturnRuleViolation("tax.invalid-audit-percent", "The audit sample is a percentage from 0 to "
                    + ReviewSettings.MAX_AUDIT_PERCENT, Map.of("auditPercent", "Between 0 and 100"));
        }
        List<String> categories = alwaysReview == null ? current.alwaysReview()
                : alwaysReview.stream().filter(c -> c != null && !c.isBlank()).map(String::trim).toList();
        List<String> unknown = new ArrayList<>();
        Map<String, ?> known = catalog.current().profiles();
        for (String c : categories) {
            if (!known.containsKey(c)) {
                unknown.add(c);
            }
        }
        if (!unknown.isEmpty()) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("alwaysReview", "Unknown categories: " + String.join(", ", unknown));
            throw new ReturnRuleViolation("tax.unknown-category", "These categories have no tax profile: "
                    + String.join(", ", unknown), fields);
        }
        ReviewSettings next = new ReviewSettings(mode, categories, percent);
        if (!next.equals(current)) {
            settings.change(next, reason.trim(), actor.subject(), Instant.now(clock));
        }
        return view();
    }

    private ReviewSettingsView view() {
        TaxSettingsRepository.Stamp stamp = settings.stamp().orElse(null);
        return new ReviewSettingsView(settings.current(), List.of(ProfileStatus.NEEDS_APPROVAL.name(),
                ProfileStatus.REFINE_CODE.name(), ProfileStatus.CONFLICT_OPEN.name(),
                ProfileStatus.BLOCKED_BUNDLE.name()), stamp == null ? null : stamp.by(),
                stamp == null ? null : stamp.at(), settings.history(HISTORY));
    }
}

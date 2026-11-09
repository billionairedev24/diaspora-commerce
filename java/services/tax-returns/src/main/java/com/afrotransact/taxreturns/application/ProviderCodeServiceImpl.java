package com.afrotransact.taxreturns.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.taxreturns.domain.profile.Outcome;
import com.afrotransact.taxreturns.domain.profile.ProviderCode;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.port.ProviderCodeRepository;
import com.afrotransact.taxreturns.port.TaxProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ProviderCodeServiceImpl implements ProviderCodeService {

    private static final Pattern STRIPE_CODE = Pattern.compile("txcd_\\d{8}");
    private static final Pattern AVALARA_CODE = Pattern.compile("[A-Za-z0-9._-]{1,25}");
    private static final int MIN_REASON = 3;

    private final ProviderCodeRepository codes;
    private final TaxProfileRepository profiles;
    private final ProfileCatalog catalog;
    private final ClassificationEvents events;
    private final Access access;
    private final Clock clock;

    ProviderCodeServiceImpl(ProviderCodeRepository codes, TaxProfileRepository profiles, ProfileCatalog catalog,
                            ClassificationEvents events, Access access, Clock clock) {
        this.codes = codes;
        this.profiles = profiles;
        this.catalog = catalog;
        this.events = events;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProviderCode> list(TaxProviderId provider, String categoryCode, boolean unmappedOnly) {
        access.staff(TaxReturnsPermissions.TAX_READ);
        return codes.list(provider, categoryCode, unmappedOnly);
    }

    @Override
    @Transactional
    public ProviderCode update(String internalTaxCode, Update u) {
        Actor actor = access.staffAny(TaxReturnsPermissions.TAX_CLASSIFY_ADMIN, TaxReturnsPermissions.TAX_CONFIGURE);
        if (u.provider() == null || u.reason() == null || u.reason().trim().length() < MIN_REASON) {
            throw invalid("reason", "Say why the mapping changes (at least " + MIN_REASON + " characters)");
        }
        ProviderCode current = codes.find(internalTaxCode, u.provider()).orElseThrow(() -> new NotFoundException(
                "tax.outcome-code-not-found", "Unknown internal tax code " + internalTaxCode));
        if (current.outcome() == Outcome.BLOCKED) {
            throw invalid("internalTaxCode", internalTaxCode + " is a blocked outcome (an unitemised bundle) and stays "
                    + "unmapped on purpose");
        }
        String code = u.providerCode() == null || u.providerCode().isBlank() ? null : u.providerCode().trim();
        if (code != null && !(u.provider() == TaxProviderId.STRIPE ? STRIPE_CODE : AVALARA_CODE).matcher(code).matches()) {
            throw invalid("providerCode", u.provider() == TaxProviderId.STRIPE
                    ? "A Stripe tax code looks like txcd_40010002" : "An Avalara tax code is up to 25 letters, digits, "
                    + "dots, dashes or underscores");
        }
        if (Boolean.TRUE.equals(u.verified()) && code == null) {
            throw invalid("verified", "There is no code to verify");
        }
        boolean codeChanged = !Objects.equals(code, current.providerCode());
        boolean verified = code != null && (u.verified() != null ? u.verified() : !codeChanged && current.verified());
        Instant now = clock.instant();
        String by = Access.describe(actor);
        ProviderCode next = new ProviderCode(current.internalTaxCode(), current.categoryCode(), current.outcome(),
                current.provider(), code, code != null, verified, u.reason().trim(), current.mapVersion() + 1, by, now);
        if (Objects.equals(code, current.providerCode()) && verified == current.verified()) {
            return current;
        }
        String version = profiles.newRuleVersion("Provider code map " + u.provider() + " " + internalTaxCode + ": "
                + u.reason().trim(), by, now);
        codes.update(next);
        profiles.audit(new TaxProfileRepository.AuditRow("PROVIDER_CODE_MAP", current.categoryCode(), internalTaxCode,
                u.provider().name(), now, by, u.reason().trim(), null, null, version, snapshot(current), snapshot(next)));
        events.providerCode(current.categoryCode(), internalTaxCode, u.provider(), version, u.reason().trim(), by, now);
        catalog.refresh();
        return next;
    }

    private static Map<String, Object> snapshot(ProviderCode c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("providerCode", c.providerCode());
        m.put("verified", c.verified());
        m.put("mapVersion", c.mapVersion());
        return m;
    }

    private static ReturnRuleViolation invalid(String field, String message) {
        return new ReturnRuleViolation("tax.invalid-provider-code", message, Map.of(field, message));
    }
}

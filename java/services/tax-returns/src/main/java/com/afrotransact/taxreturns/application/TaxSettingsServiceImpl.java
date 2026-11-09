package com.afrotransact.taxreturns.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.tax.TaxMethod;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.port.ProviderCodeRepository;
import com.afrotransact.taxreturns.port.TaxSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TaxSettingsServiceImpl implements TaxSettingsService {

    private static final int HISTORY = 20;
    private static final int MIN_REASON = 10;

    private final TaxSettingsRepository settings;
    private final TaxMethodResolver resolver;
    private final ProviderCodeRepository codes;
    private final TaxReturnsProperties props;
    private final Access access;
    private final Clock clock;

    TaxSettingsServiceImpl(TaxSettingsRepository settings, TaxMethodResolver resolver, ProviderCodeRepository codes,
                           TaxReturnsProperties props, Access access, Clock clock) {
        this.settings = settings;
        this.resolver = resolver;
        this.codes = codes;
        this.props = props;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public TaxSettings current() {
        access.staff(TaxReturnsPermissions.TAX_READ);
        return view();
    }

    @Override
    @Transactional
    public TaxSettings change(TaxMethod method, TaxProviderId shadowProvider, String reason) {
        Actor actor = access.staff(TaxReturnsPermissions.TAX_CONFIGURE);
        if (reason == null || reason.trim().length() < MIN_REASON) {
            throw new ReturnRuleViolation("tax.reason-required",
                    "Say why the tax method changes (at least " + MIN_REASON + " characters)", Map.of("reason", "Required"));
        }
        TaxProviderId chosen = method.provider();
        if (chosen != null) {
            requireConfigured(chosen, "method");
            if (codes.countMapped(chosen) == 0) {
                throw new ReturnRuleViolation("tax.provider-not-mapped", chosen + " has no mapped tax codes yet. Fill the "
                        + "provider code map and run SHADOW mode first (docs/runbooks/tax-provider-cutover.md).",
                        Map.of("method", "No codes mapped"));
            }
        }
        if (shadowProvider != null) {
            requireConfigured(shadowProvider, "shadowProvider");
            if (shadowProvider == chosen) {
                throw new ReturnRuleViolation("tax.shadow-same-as-method", shadowProvider + " is already the method; "
                        + "SHADOW compares another provider with the charging method",
                        Map.of("shadowProvider", "Same as the method"));
            }
        }
        Instant now = Instant.now(clock);
        if (method != resolver.method()) {
            settings.changeMethod(method, reason.trim(), actor.subject(), now);
        }
        Optional<TaxProviderId> currentShadow = settings.shadowProvider();
        if (!Optional.ofNullable(shadowProvider).equals(currentShadow)) {
            settings.changeShadowProvider(shadowProvider, reason.trim(), actor.subject(), now);
        }
        resolver.refresh();
        return view();
    }

    private void requireConfigured(TaxProviderId provider, String field) {
        if (!available(provider)) {
            String code = provider == TaxProviderId.STRIPE ? "tax.stripe-not-set-up" : "tax.avalara-not-set-up";
            String how = provider == TaxProviderId.STRIPE
                    ? "Stripe Tax cannot be used until its key is set and Stripe Tax is set up (registrations) in the "
                            + "Stripe dashboard"
                    : "Avalara cannot be used until AVALARA_ACCOUNT_ID, AVALARA_LICENSE_KEY and AVALARA_COMPANY_CODE are set";
            throw new ReturnRuleViolation(code, how + "; our own rules keep working meanwhile",
                    Map.of(field, "Not available"));
        }
    }

    private boolean available(TaxProviderId provider) {
        return switch (provider) {
            case STRIPE -> stripeAvailable();
            case AVALARA -> props.tax().avalara().configured();
        };
    }

    private boolean stripeAvailable() {
        String key = props.tax().stripe().secretKey();
        return key != null && !key.isBlank();
    }

    private TaxSettings view() {
        TaxSettingsRepository.Stamp stamp = settings.methodStamp().orElse(null);
        return new TaxSettings(resolver.method(), configuredDefault(), stripeAvailable(),
                props.tax().avalara().configured(), settings.shadowProvider().orElse(null),
                stamp == null ? null : stamp.by(), stamp == null ? null : stamp.at(), settings.history(HISTORY));
    }

    private TaxMethod configuredDefault() {
        return switch (props.tax().provider()) {
            case STRIPE_TAX -> TaxMethod.STRIPE_TAX;
            case AVALARA -> TaxMethod.AVALARA;
            default -> TaxMethod.RULES;
        };
    }
}

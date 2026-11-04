package com.afrotransact.taxreturns.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.tax.TaxMethod;
import com.afrotransact.taxreturns.port.TaxSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TaxSettingsServiceImpl implements TaxSettingsService {

    private static final int HISTORY = 20;
    private static final int MIN_REASON = 10;

    private final TaxSettingsRepository settings;
    private final TaxMethodResolver resolver;
    private final TaxReturnsProperties props;
    private final Access access;
    private final Clock clock;

    TaxSettingsServiceImpl(TaxSettingsRepository settings, TaxMethodResolver resolver, TaxReturnsProperties props,
                           Access access, Clock clock) {
        this.settings = settings;
        this.resolver = resolver;
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
    public TaxSettings change(TaxMethod method, String reason) {
        Actor actor = access.staff(TaxReturnsPermissions.TAX_CONFIGURE);
        if (reason == null || reason.trim().length() < MIN_REASON) {
            throw new ReturnRuleViolation("tax.reason-required",
                    "Say why the tax method changes (at least " + MIN_REASON + " characters)", Map.of("reason", "Required"));
        }
        if (method == TaxMethod.STRIPE_TAX && !stripeAvailable()) {
            throw new ReturnRuleViolation("tax.stripe-not-set-up",
                    "Stripe Tax cannot be used until its key is set and Stripe Tax is set up (registrations) in the "
                            + "Stripe dashboard; our own rules keep working meanwhile", Map.of("method", "Not available"));
        }
        if (method == resolver.method()) {
            return view();
        }
        settings.changeMethod(method, reason.trim(), actor.subject(), Instant.now(clock));
        resolver.refresh();
        return view();
    }

    private boolean stripeAvailable() {
        String key = props.tax().stripe().secretKey();
        return key != null && !key.isBlank();
    }

    private TaxSettings view() {
        TaxSettingsRepository.Stamp stamp = settings.methodStamp().orElse(null);
        return new TaxSettings(resolver.method(), configuredDefault(), stripeAvailable(),
                stamp == null ? null : stamp.by(), stamp == null ? null : stamp.at(), settings.history(HISTORY));
    }

    private TaxMethod configuredDefault() {
        return props.tax().provider() == TaxReturnsProperties.TaxProvider.STRIPE_TAX ? TaxMethod.STRIPE_TAX
                : TaxMethod.RULES;
    }
}

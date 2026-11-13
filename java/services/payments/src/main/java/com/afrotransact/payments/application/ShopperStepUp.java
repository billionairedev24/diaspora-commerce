package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Duration;

import com.afrotransact.platform.security.SignInFreshness;

/**
 * Step-up for a shopper managing saved payment methods (F17: the 92-day shopper session stays, the sensitive action
 * asks for a recent sign-in). Same rule as identity's step-up (S291, the token's {@code auth_time}); stale: 403
 * {@code auth.step-up-required} and the client starts a fresh sign-in ({@code /v1/auth/login?prompt=login}).
 * {@code at.payments.shopper-step-up.enabled} (SHOPPER_STEP_UP_ENABLED) and {@code max-age} (SHOPPER_STEP_UP_MAX_AGE,
 * default 30 minutes). Typing a new card into Stripe's own field is not gated here, only adding, defaulting, removing
 * and the ephemeral key that PaymentSheet manages the wallet with.
 */
public class ShopperStepUp {

    private final boolean enabled;
    private final Duration maxAge;
    private final Clock clock;

    public ShopperStepUp(boolean enabled, Duration maxAge, Clock clock) {
        this.enabled = enabled;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    /** Passes when switched off or the current token signed in within the window; otherwise 403. */
    void require(String what) {
        if (enabled) {
            SignInFreshness.require(maxAge, clock, what);
        }
    }
}

package com.afrotransact.seller.domain;

import java.util.Locale;
import java.util.Set;

/** Default rules: see {@link LocalTestStripePolicy}. Modes and profile names are compared case-insensitively. */
public class DefaultLocalTestStripePolicy implements LocalTestStripePolicy {

    private static final Set<String> SHARED = Set.of("dev", "uat", "prod");
    private static final String LOCAL = "local";
    private static final String PLACEHOLDER_KEY_PREFIX = "sk_test_replace";

    @Override
    public boolean wantsLocalTest(String mode, String secretKey) {
        String m = mode == null || mode.isBlank() ? STRIPE : mode.trim().toLowerCase(Locale.ROOT);
        return switch (m) {
            case STRIPE -> false;
            case LOCAL_TEST -> true;
            case AUTO -> secretKey == null || secretKey.isBlank()
                    || secretKey.trim().startsWith(PLACEHOLDER_KEY_PREFIX);
            default -> throw new IllegalArgumentException("at.seller.stripe.mode must be stripe, local-test or auto, "
                    + "not " + mode);
        };
    }

    @Override
    public boolean localTestAllowed(Set<String> activeProfiles) {
        Set<String> p = activeProfiles == null ? Set.of()
                : Set.copyOf(activeProfiles.stream().map(x -> x.trim().toLowerCase(Locale.ROOT)).toList());
        return p.contains(LOCAL) && p.stream().noneMatch(SHARED::contains);
    }
}

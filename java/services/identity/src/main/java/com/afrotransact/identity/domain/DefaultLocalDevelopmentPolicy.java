package com.afrotransact.identity.domain;

import java.util.Locale;
import java.util.Set;

/** Default rules: see {@link LocalDevelopmentPolicy}. Profile names are compared case-insensitively. */
public class DefaultLocalDevelopmentPolicy implements LocalDevelopmentPolicy {

    private static final Set<String> PRODUCTION_LIKE = Set.of("uat", "prod");
    private static final String LOCAL = "local";
    private static final String DEV = "dev";
    private static final String OUTBOX = "outbox";

    @Override
    public boolean testUsersAllowed(Set<String> activeProfiles, boolean devOptIn) {
        Set<String> p = normalise(activeProfiles);
        if (p.stream().anyMatch(PRODUCTION_LIKE::contains)) {
            return false;
        }
        return p.contains(LOCAL) || p.contains(DEV) && devOptIn;
    }

    @Override
    public boolean verificationSenderAllowed(String sender, Set<String> activeProfiles) {
        String s = sender == null ? OUTBOX : sender.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || OUTBOX.equals(s)) {
            return true;
        }
        Set<String> p = normalise(activeProfiles);
        return p.contains(LOCAL) && p.stream().noneMatch(x -> PRODUCTION_LIKE.contains(x) || DEV.equals(x));
    }

    @Override
    public boolean isTestUserEmail(String email) {
        if (email == null) {
            return false;
        }
        String e = email.trim().toLowerCase(Locale.ROOT);
        int at = e.lastIndexOf('@');
        return at > 0 && e.substring(at + 1).equals(TEST_USER_DOMAIN);
    }

    private static Set<String> normalise(Set<String> profiles) {
        return profiles == null ? Set.of()
                : Set.copyOf(profiles.stream().map(x -> x.trim().toLowerCase(Locale.ROOT)).toList());
    }
}

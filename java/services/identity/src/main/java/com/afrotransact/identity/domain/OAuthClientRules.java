package com.afrotransact.identity.domain;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** What an admin may register: validated field by field, reported as a 422 with one message per field. */
public final class OAuthClientRules {

    public static final Set<String> GRANT_TYPES = Set.of("authorization_code", "refresh_token", "client_credentials");
    private static final Pattern CLIENT_ID = Pattern.compile("^[a-z][a-z0-9._-]{2,63}$");
    private static final Pattern SCOPE = Pattern.compile("^[A-Za-z0-9:._/-]{1,100}$");
    private static final Duration MAX_ACCESS = Duration.ofHours(1);
    private static final Duration MAX_REFRESH = Duration.ofDays(90);

    private OAuthClientRules() {
    }

    /** @param clientId null when editing an existing client (it cannot change) */
    public static void validate(String clientId, String name, boolean confidential, List<String> grantTypes,
                                List<String> scopes, List<String> redirectUris, List<String> postLogoutRedirectUris,
                                Duration accessTtl, Duration refreshTtl) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (clientId != null && !CLIENT_ID.matcher(clientId).matches()) {
            errors.put("clientId",
                    "Use 3 to 64 lower-case letters, digits, dots, dashes or underscores, starting with a letter");
        }
        if (name == null || name.isBlank() || name.length() > 120) {
            errors.put("name", "Give the client a name of up to 120 characters");
        }
        if (grantTypes.isEmpty() || !GRANT_TYPES.containsAll(grantTypes)) {
            errors.put("grantTypes", "Choose from " + String.join(", ", GRANT_TYPES.stream().sorted().toList()));
        } else {
            if (!confidential && grantTypes.contains("client_credentials")) {
                errors.put("grantTypes", "A public client cannot use client_credentials");
            }
            if (grantTypes.contains("refresh_token") && !grantTypes.contains("authorization_code")) {
                errors.put("grantTypes", "refresh_token needs authorization_code");
            }
            if (grantTypes.contains("authorization_code") && redirectUris.isEmpty()) {
                errors.put("redirectUris", "authorization_code needs at least one redirect URI");
            }
        }
        if (scopes.stream().anyMatch(s -> !SCOPE.matcher(s).matches())) {
            errors.put("scopes", "Scopes are words without spaces");
        }
        redirectUris.stream().filter(u -> !validRedirect(u)).findFirst().ifPresent(
                u -> errors.put("redirectUris", "Not a valid absolute URI without a fragment or wildcard: " + shorten(u)));
        postLogoutRedirectUris.stream().filter(u -> !validRedirect(u)).findFirst().ifPresent(
                u -> errors.put("postLogoutRedirectUris", "Not a valid absolute URI without a fragment or wildcard: "
                        + shorten(u)));
        if (accessTtl.isNegative() || accessTtl.isZero() || accessTtl.compareTo(MAX_ACCESS) > 0) {
            errors.put("accessTokenTtlMinutes", "Between 1 and 60 minutes");
        }
        if (refreshTtl.isNegative() || refreshTtl.isZero() || refreshTtl.compareTo(MAX_REFRESH) > 0) {
            errors.put("refreshTokenTtlMinutes", "Between 1 minute and 90 days");
        }
        if (!errors.isEmpty()) {
            throw new IdentityRuleViolation("identity.invalid-client", "Check the highlighted fields", errors);
        }
    }

    private static boolean validRedirect(String uri) {
        try {
            URI u = URI.create(uri);
            return u.isAbsolute() && u.getFragment() == null && !uri.contains("*")
                    && !"javascript".equalsIgnoreCase(u.getScheme());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String shorten(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}

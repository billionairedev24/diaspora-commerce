package com.afrotransact.module;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** The only {@link EndUserActor}: package-private, built by {@link EndUserActor#fromAuthentication}. */
record VerifiedEndUser(String userId, String principalType, Optional<String> organisationId, Set<String> organisationIds,
                       Set<String> roles, Set<String> scopes) implements EndUserActor {

    /** People who may switch to shopping; services, partner apps and AI agents keep their type. */
    private static final Set<String> PEOPLE = Set.of("CUSTOMER", "SELLER_MEMBER", "PROVIDER_MEMBER", "DRIVER", "STAFF");

    VerifiedEndUser {
        organisationId = organisationId == null ? Optional.empty() : organisationId;
        organisationIds = Set.copyOf(organisationIds);
        roles = Set.copyOf(roles);
        scopes = Set.copyOf(scopes);
    }

    @Override
    public EndUserActor asCustomer() {
        if (!PEOPLE.contains(principalType)) {
            return this;
        }
        Set<String> kept = roles.stream().filter(r -> r.startsWith("account.")).collect(Collectors.toSet());
        Set<String> permissions = "STAFF".equals(principalType)
                ? scopes.stream().filter("mcp:shopper"::equals).collect(Collectors.toSet()) : scopes;
        return new VerifiedEndUser(userId, "CUSTOMER", Optional.empty(), Set.of(), kept, permissions);
    }
}

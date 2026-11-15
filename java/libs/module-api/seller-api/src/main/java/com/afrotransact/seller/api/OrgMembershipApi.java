package com.afrotransact.seller.api;

import java.util.Optional;

/**
 * Published API of the seller module: the live member list, for modules that must not trust a token role on money actions
 * (payments). The same answer as {@code GET /v1/orgs/{orgId}/members/{userId}/role}, entered under the calling module's
 * principal; the caller needs the scope {@code sellers:membership.read} exactly as the HTTP client does.
 */
public interface OrgMembershipApi {

    /** The user's role key in the organisation (for example {@code seller.owner}); empty when the user is not a member. */
    Optional<String> roleOf(String orgId, String userId);
}

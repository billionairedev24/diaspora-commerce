package com.afrotransact.seller.application;

import java.util.Optional;

/**
 * Live membership lookup for other services that have no members table of their own (payments: payout requests).
 * Callers are service clients holding {@link #MEMBERSHIP_READ} only; the answer is the member list as it is now, never
 * a token claim.
 */
public interface OrgMembershipService {

    /** Permission (client-credentials scope) to read one member's role in one organisation. */
    String MEMBERSHIP_READ = "sellers:membership.read";

    /** The member's role key (for example {@code seller.owner}); empty when the user is not a member of the organisation. */
    Optional<String> roleOf(String orgId, String userId);
}

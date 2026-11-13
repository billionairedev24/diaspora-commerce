package com.afrotransact.payments.port;

import java.util.Optional;

/**
 * Live organisation membership, owned by the seller service (payments has no members table). Used to close the
 * access-token window on money actions: a removed or downgraded member must not request a payout for up to five
 * minutes. Answers are the seller's member list as it is now (a cache of at most a few seconds).
 */
public interface OrgMembershipDirectory {

    /** False when live checks are switched off (the local profile only); callers then trust the token roles. */
    boolean enabled();

    /**
     * The user's role key in the organisation (for example {@code seller.owner}); empty when the user is not a member.
     *
     * @throws OrgMembershipUnavailableException when the seller service cannot answer (the caller must refuse: fail
     *                                           closed)
     */
    Optional<String> roleOf(String orgId, String userId);
}

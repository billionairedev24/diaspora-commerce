package com.afrotransact.identity.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.OrgInvitation;

/** Organisation invitations (only token hashes are stored). */
public interface OrgInvitationRepository {

    void insert(OrgInvitation invitation);

    /** Writes status, acceptance and revocation fields. */
    void update(OrgInvitation invitation);

    Optional<OrgInvitation> findById(UUID id);

    Optional<OrgInvitation> findByTokenHash(String tokenHash);

    /** Stored-as-pending invitation for the email in the organisation (it may have expired). */
    Optional<OrgInvitation> findPending(String orgId, String email);

    /** Pending, unexpired invitations of the organisation, newest first. */
    List<OrgInvitation> pending(String orgId, Instant now);
}

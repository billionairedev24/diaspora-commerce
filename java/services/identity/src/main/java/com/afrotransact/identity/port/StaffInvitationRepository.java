package com.afrotransact.identity.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.StaffInvitation;

/** Persistence of staff invitations (tokens are stored hashed only). */
public interface StaffInvitationRepository {

    void insert(StaffInvitation invitation);

    Optional<StaffInvitation> findById(UUID id);

    Optional<StaffInvitation> findByTokenHash(String tokenHash);

    Optional<StaffInvitation> findPendingByEmail(String email);

    /** Writes a new token hash, expiry and send count (resend); only while the invitation is still PENDING. */
    boolean reissue(StaffInvitation invitation);

    /** PENDING to REVOKED; false when it was no longer pending. */
    boolean revoke(StaffInvitation invitation);

    /** Claims a PENDING invitation for acceptance (single use); false when somebody else already did or it expired. */
    boolean claim(UUID id, UUID userId, Instant now);

    /** Invitations still open (pending, or expired without being used), newest first. */
    List<StaffInvitation> open(int limit, int offset);

    long countOpen();
}

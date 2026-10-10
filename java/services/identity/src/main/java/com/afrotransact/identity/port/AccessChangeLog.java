package com.afrotransact.identity.port;

import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.domain.AccessChange;

/** Append-only audit trail of access changes (the table rejects UPDATE and DELETE). */
public interface AccessChangeLog {

    void append(String actorId, String action, UUID targetUserId, String targetRole, String subject, String detail);

    List<AccessChange> recent(UUID targetUserId, int limit, int offset);
}

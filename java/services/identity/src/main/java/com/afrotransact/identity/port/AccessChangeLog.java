package com.afrotransact.identity.port;

import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.domain.AccessChange;
import com.afrotransact.identity.domain.AccessLogFilter;

/** Append-only audit trail of access changes (the table rejects UPDATE and DELETE). */
public interface AccessChangeLog {

    void append(String actorId, String action, UUID targetUserId, String targetRole, String subject, String detail);

    List<AccessChange> recent(UUID targetUserId, int limit, int offset);

    /** Newest first, narrowed by person, role and/or permission. */
    List<AccessChange> search(AccessLogFilter filter, int limit, int offset);

    /** Entries matching the filter (for server-driven paging). */
    long count(AccessLogFilter filter);
}

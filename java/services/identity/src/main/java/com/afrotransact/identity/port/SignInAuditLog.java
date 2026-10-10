package com.afrotransact.identity.port;

import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.domain.SignInAuditEntry;

/** Append-only sign-in audit trail (the table rejects UPDATE and DELETE). */
public interface SignInAuditLog {

    void append(SignInAuditEntry entry);

    List<SignInAuditEntry> recent(UUID userId, int limit);
}

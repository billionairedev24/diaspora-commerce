package com.afrotransact.content.application;

import java.util.List;

import com.afrotransact.content.port.AuditLog.Entry;

/** Reads the content audit trail. */
public interface AuditQueryService {

    List<Entry> find(String entityType, String entityId, int limit);
}

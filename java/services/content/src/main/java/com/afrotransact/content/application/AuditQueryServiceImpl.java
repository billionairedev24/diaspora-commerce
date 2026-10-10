package com.afrotransact.content.application;

import java.util.List;

import com.afrotransact.content.port.AuditLog;
import com.afrotransact.content.port.AuditLog.Entry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AuditQueryServiceImpl implements AuditQueryService {

    private final AuditLog log;
    private final ContentAccess access;

    AuditQueryServiceImpl(AuditLog log, ContentAccess access) {
        this.log = log;
        this.access = access;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Entry> find(String entityType, String entityId, int limit) {
        access.require(ContentPermissions.AUDIT_VIEW);
        return log.find(entityType, entityId, Math.max(1, Math.min(limit, 500)));
    }
}

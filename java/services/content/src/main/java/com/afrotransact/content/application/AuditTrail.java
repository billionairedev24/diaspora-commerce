package com.afrotransact.content.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import com.afrotransact.content.port.AuditLog;
import org.springframework.stereotype.Component;

/** Writes audit entries in the caller's transaction. */
@Component
public class AuditTrail {

    private final AuditLog log;
    private final Clock clock;

    AuditTrail(AuditLog log, Clock clock) {
        this.log = log;
        this.clock = clock;
    }

    public void record(String actor, String action, String entityType, String entityId, Map<String, Object> detail) {
        log.append(new AuditLog.Entry(Instant.now(clock), actor, action, entityType, entityId, detail));
    }
}

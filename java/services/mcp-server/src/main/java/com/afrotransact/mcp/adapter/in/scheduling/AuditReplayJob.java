package com.afrotransact.mcp.adapter.in.scheduling;

import com.afrotransact.mcp.application.AuditRecorderService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Drains the audit spool back to Kafka on a fixed delay (every replica tries; the spool lease lets one win). */
@Component
class AuditReplayJob {

    private final AuditRecorderService audit;

    AuditReplayJob(AuditRecorderService audit) {
        this.audit = audit;
    }

    @Scheduled(fixedDelayString = "${at.mcp.audit.replay-interval:5s}",
            initialDelayString = "${at.mcp.audit.replay-interval:5s}")
    void replay() {
        audit.replaySpool();
    }
}

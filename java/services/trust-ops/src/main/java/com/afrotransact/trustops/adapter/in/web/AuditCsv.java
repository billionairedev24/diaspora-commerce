package com.afrotransact.trustops.adapter.in.web;

import java.util.List;

import com.afrotransact.trustops.domain.AuditDraft;
import com.afrotransact.trustops.domain.AuditEntry;
import com.afrotransact.trustops.domain.AuditFacts;

/** RFC 4180 CSV rows of audit entries; cells starting with = + - @ are prefixed with ' (spreadsheet injection). */
final class AuditCsv {

    static final String HEADER = "seq,occurred_at,recorded_at,actor_id,actor_type,action,entity_type,entity_id,"
            + "org_id,summary,event_type,source_topic,source_event_id,prev_hash,hash\r\n";

    private AuditCsv() {
    }

    static String row(AuditEntry e) {
        AuditDraft d = e.draft();
        AuditFacts f = d.facts();
        List<String> cells = List.of(Long.toString(e.seq()), d.occurredAt().toString(), d.recordedAt().toString(),
                text(f.actorId()), text(f.actorType()), text(f.action()), text(f.entityType()), text(f.entityId()),
                text(f.orgId()), text(f.summary()), text(d.eventType()), text(d.sourceTopic()),
                d.sourceEventId() == null ? "" : d.sourceEventId().toString(), e.prevHash(), e.hash());
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(cell(cells.get(i)));
        }
        return b.append("\r\n").toString();
    }

    static String cell(String value) {
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }
}

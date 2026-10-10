package com.afrotransact.trustops.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.afrotransact.platform.events.InboxDeduplicator;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.trustops.domain.AuditClassifier;
import com.afrotransact.trustops.domain.AuditDraft;
import com.afrotransact.trustops.domain.AuditEntry;
import com.afrotransact.trustops.domain.AuditFacts;
import com.afrotransact.trustops.domain.AuditHashChain;
import com.afrotransact.trustops.domain.ChainVerification;
import com.afrotransact.trustops.port.AuditLogRepository;
import com.afrotransact.trustops.port.AuditQuery;
import com.afrotransact.trustops.port.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AuditLogServiceImpl implements AuditLogService {

    private static final int VERIFY_PAGE = 1000;

    private final AuditLogRepository log;
    private final AuditClassifier classifier;
    private final AuditHashChain chain;
    private final AuditTrail trail;
    private final InboxDeduplicator inbox;
    private final PermissionChecker permissions;
    private final TrustOpsSettings settings;
    private final Clock clock;

    AuditLogServiceImpl(AuditLogRepository log, AuditClassifier classifier, AuditHashChain chain, AuditTrail trail,
                        InboxDeduplicator inbox, PermissionChecker permissions, TrustOpsSettings settings,
                        Clock clock) {
        this.log = log;
        this.classifier = classifier;
        this.chain = chain;
        this.trail = trail;
        this.inbox = inbox;
        this.permissions = permissions;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional
    public boolean ingest(IncomingEvent e) {
        if (!settings.auditEventTypes().contains(e.type())
                || !inbox.firstDelivery(TrustOpsEvents.CONSUMER_AUDIT, e.eventId())
                || log.existsBySourceEvent(e.eventId())) {
            return false;
        }
        Optional<AuditFacts> facts = classifier.classify(e.type(), e.payload(), e.key());
        if (facts.isEmpty()) {
            return false;
        }
        Instant now = Instant.now(clock);
        trail.append(new AuditDraft(UUID.randomUUID(), e.eventId(), e.topic(), e.type(), facts.get(),
                e.payloadJson(), e.occurredAt(), now));
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditEntry> search(AuditQuery query, int page, int size) {
        permissions.require(TrustOpsPermissions.AUDIT_READ);
        return log.search(query, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public AuditEntry get(long seq) {
        permissions.require(TrustOpsPermissions.AUDIT_READ);
        return log.findBySeq(seq).orElseThrow(() -> new NotFoundException("trustops.audit-entry-not-found",
                "Audit entry " + seq + " not found"));
    }

    @Override
    @Transactional(readOnly = true)
    public int export(AuditQuery query, Consumer<AuditEntry> sink) {
        permissions.require(TrustOpsPermissions.AUDIT_READ);
        return log.forEach(query, settings.exportMaxRows(), sink);
    }

    @Override
    @Transactional(readOnly = true)
    public ChainVerification verify(Long fromSeq, Long toSeq) {
        permissions.require(TrustOpsPermissions.AUDIT_READ);
        long from = fromSeq == null || fromSeq < 1 ? 1 : fromSeq;
        long to = toSeq == null ? log.last().map(AuditEntry::seq).orElse(0L) : toSeq;
        String prev = from == 1 ? AuditHashChain.GENESIS : log.findBySeq(from - 1).map(AuditEntry::hash)
                .orElse(null);
        if (prev == null) {
            return ChainVerification.broken(0, from - 1, "Anchor entry " + (from - 1) + " is missing", from - 1,
                    AuditHashChain.GENESIS);
        }
        long checked = 0;
        long next = from;
        while (next <= to) {
            List<AuditEntry> page = log.range(next, to, VERIFY_PAGE);
            if (page.isEmpty()) {
                return ChainVerification.broken(checked, next, "Entry " + next + " is missing", next - 1, prev);
            }
            ChainVerification v = chain.verify(page, next, prev);
            if (!v.valid()) {
                return ChainVerification.broken(checked + v.checked(), v.brokenAtSeq(), v.problem(), v.lastSeq(),
                        v.lastHash());
            }
            checked += v.checked();
            next = v.lastSeq() + 1;
            prev = v.lastHash();
        }
        return ChainVerification.ok(checked, next - 1, prev);
    }
}

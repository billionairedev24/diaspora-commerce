package com.afrotransact.content.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.content.domain.ContentStateConflict;
import com.afrotransact.content.domain.privacy.PrivacyRequest;
import com.afrotransact.content.domain.privacy.PrivacyRequestStatus;
import com.afrotransact.content.port.PrivacyRequestRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PrivacyRequestServiceImpl implements PrivacyRequestService {

    private static final int LIST_LIMIT = 500;
    /** Callers whose subject is a person's account (staff, services and agents are not requesters). */
    private static final Set<PrincipalType> ACCOUNT_HOLDERS = Set.of(PrincipalType.CUSTOMER,
            PrincipalType.SELLER_MEMBER, PrincipalType.PROVIDER_MEMBER, PrincipalType.DRIVER);

    private final PrivacyRequestRepository requests;
    private final ContentAccess access;
    private final AuditTrail audit;
    private final Clock clock;

    PrivacyRequestServiceImpl(PrivacyRequestRepository requests, ContentAccess access, AuditTrail audit, Clock clock) {
        this.requests = requests;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PrivacyRequest submit(PrivacyRequest.Submission submission) {
        String account = access.optional().filter(a -> ACCOUNT_HOLDERS.contains(a.type())).map(Actor::subject)
                .orElse(null);
        // Validate before taking a reference number so rejected forms do not burn sequence values needlessly.
        PrivacyRequest.submit(UUID.randomUUID(), 0, submission, account, Instant.now(clock));
        PrivacyRequest request = PrivacyRequest.submit(UUID.randomUUID(), requests.nextSequence(), submission, account,
                Instant.now(clock));
        requests.insert(request);
        audit.record(account == null ? "visitor" : account, "privacy-request.received", "privacy-request",
                request.reference(), Map.of("type", request.type().name(), "state", request.stateCode()));
        return request;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PrivacyRequest> list(PrivacyRequestStatus status) {
        access.requireAny(ContentPermissions.LEGAL_VIEW, ContentPermissions.LEGAL_EDIT);
        return requests.list(status, LIST_LIMIT);
    }

    @Override
    @Transactional(readOnly = true)
    public PrivacyRequest get(String reference) {
        access.requireAny(ContentPermissions.LEGAL_VIEW, ContentPermissions.LEGAL_EDIT);
        return find(reference);
    }

    @Override
    @Transactional
    public PrivacyRequest resolve(String reference, PrivacyRequestStatus outcome, String note) {
        Actor actor = access.require(ContentPermissions.LEGAL_EDIT);
        PrivacyRequest resolved = find(reference).resolve(outcome, note, actor.subject(), Instant.now(clock));
        if (!requests.update(resolved)) {
            throw new ContentStateConflict("content.privacy-request-changed",
                    "Request " + reference + " was changed by someone else; reload it");
        }
        audit.record(actor.subject(), "privacy-request." + resolved.status().name().toLowerCase(Locale.ROOT),
                "privacy-request", resolved.reference(), Map.of("type", resolved.type().name()));
        return resolved;
    }

    private PrivacyRequest find(String reference) {
        String ref = reference == null ? "" : reference.trim().toUpperCase(Locale.ROOT);
        return requests.findByReference(ref).orElseThrow(() -> new NotFoundException(
                "content.privacy-request-not-found", "No privacy request " + reference));
    }
}

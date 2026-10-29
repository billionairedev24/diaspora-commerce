package com.afrotransact.content.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.content.domain.ContentRuleViolation;
import com.afrotransact.content.domain.legal.LegalDocumentType;
import com.afrotransact.content.domain.legal.LegalDraft;
import com.afrotransact.content.domain.legal.LegalVersion;
import com.afrotransact.content.domain.legal.SemanticVersion;
import com.afrotransact.content.port.LegalDraftRepository;
import com.afrotransact.content.port.LegalRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LegalAuthoringServiceImpl implements LegalAuthoringService {

    private static final String ENTITY = "legal";
    private static final int MIN_REASON = 5;
    private static final int MAX_REASON = 500;

    private final LegalRepository legal;
    private final LegalDraftRepository drafts;
    private final LegalService published;
    private final ContentAccess access;
    private final AuditTrail audit;
    private final Clock clock;

    LegalAuthoringServiceImpl(LegalRepository legal, LegalDraftRepository drafts, LegalService published,
                              ContentAccess access, AuditTrail audit, Clock clock) {
        this.legal = legal;
        this.drafts = drafts;
        this.published = published;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LegalDraft> draft(LegalDocumentType type) {
        access.requireAny(ContentPermissions.LEGAL_VIEW, ContentPermissions.LEGAL_EDIT);
        return drafts.find(type);
    }

    @Override
    @Transactional
    public LegalDraft save(LegalDocumentType type, LegalDraft.Edit edit, Long expectedRowVersion) {
        Actor actor = access.require(ContentPermissions.LEGAL_EDIT);
        Instant now = Instant.now(clock);
        Optional<LegalDraft> existing = drafts.find(type);
        if (existing.isEmpty()) {
            if (expectedRowVersion != null) {
                throw stale(type);
            }
            LegalDraft started = LegalDraft.startFrom(currentOf(type), actor.subject(), now).edited(edit, actor.subject(), now);
            if (!drafts.insert(started)) {
                throw stale(type);
            }
            audit.record(actor.subject(), "legal.draft-saved", ENTITY, type.key(), detail(started));
            return started;
        }
        if (expectedRowVersion == null || expectedRowVersion != existing.get().rowVersion()) {
            throw stale(type);
        }
        LegalDraft next = existing.get().edited(edit, actor.subject(), now);
        if (!drafts.update(next, expectedRowVersion)) {
            throw stale(type);
        }
        audit.record(actor.subject(), "legal.draft-saved", ENTITY, type.key(), detail(next));
        return next;
    }

    @Override
    @Transactional
    public LegalDraft restore(LegalDocumentType type, String version) {
        Actor actor = access.require(ContentPermissions.LEGAL_EDIT);
        if (drafts.find(type).isPresent()) {
            throw new ConflictException("content.legal-draft-exists",
                    "There is already a draft of " + type.title() + ". Publish or discard it before restoring a version.");
        }
        LegalVersion old = legal.version(type, SemanticVersion.parse(version)).orElseThrow(() -> new NotFoundException(
                "content.legal-version-not-found", type.title() + " has no version " + version));
        LegalDraft restored = LegalDraft.restoring(currentOf(type), old, actor.subject(), Instant.now(clock));
        if (!drafts.insert(restored)) {
            throw stale(type);
        }
        Map<String, Object> detail = detail(restored);
        detail.put("restores", old.version().toString());
        audit.record(actor.subject(), "legal.draft-restored", ENTITY, type.key(), detail);
        return restored;
    }

    @Override
    @Transactional
    public void discard(LegalDocumentType type) {
        Actor actor = access.require(ContentPermissions.LEGAL_EDIT);
        LegalDraft draft = drafts.find(type).orElseThrow(() -> noDraft(type));
        drafts.delete(type);
        audit.record(actor.subject(), "legal.draft-discarded", ENTITY, type.key(),
                Map.of("version", draft.version().toString(), "lastEditedBy", draft.updatedBy()));
    }

    @Override
    @Transactional
    public LegalVersion publish(LegalDocumentType type, String reason, long expectedRowVersion) {
        Actor actor = access.require(ContentPermissions.LEGAL_EDIT);
        LegalDraft draft = drafts.find(type).orElseThrow(() -> noDraft(type));
        if (draft.rowVersion() != expectedRowVersion) {
            throw stale(type);
        }
        if (draft.editedBy(actor.subject())) {
            throw new ForbiddenActionException("content.second-approver-required",
                    "A second person must publish this: you saved the last change to the draft.");
        }
        String why = reason == null ? "" : reason.strip();
        Map<String, String> errors = new HashMap<>(draft.publicationProblems());
        if (why.length() < MIN_REASON || why.length() > MAX_REASON) {
            errors.put("reason", "Say why you are publishing (" + MIN_REASON + " to " + MAX_REASON + " characters)");
        }
        ContentRuleViolation.throwIfAny("content.legal-draft-not-publishable", "The draft cannot be published yet", errors);
        LegalVersion current = currentOf(type);
        if (!current.version().equals(draft.baseVersion())) {
            throw new ConflictException("content.legal-draft-outdated", "Version " + current.version()
                    + " was published after this draft was started. Restore the version you want as a new draft.");
        }
        LegalVersion created = published.raise(type, draft.toNewVersion());
        drafts.delete(type);
        audit.record(actor.subject(), "legal.draft-published", ENTITY, type.key(), Map.of("reason", why,
                "from", current.version().toString(), "to", created.version().toString(),
                "authoredBy", draft.createdBy(), "lastEditedBy", draft.updatedBy(), "approvedBy", actor.subject()));
        return created;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LegalVersion> history(LegalDocumentType type) {
        access.requireAny(ContentPermissions.LEGAL_VIEW, ContentPermissions.LEGAL_EDIT);
        return legal.versions(type);
    }

    @Override
    @Transactional(readOnly = true)
    public LegalVersion source(LegalDocumentType type, String version) {
        access.requireAny(ContentPermissions.LEGAL_VIEW, ContentPermissions.LEGAL_EDIT);
        return legal.version(type, SemanticVersion.parse(version)).orElseThrow(() -> new NotFoundException(
                "content.legal-version-not-found", type.title() + " has no version " + version));
    }

    private LegalVersion currentOf(LegalDocumentType type) {
        return legal.current(type).orElseThrow(() -> new NotFoundException("content.legal-not-found",
                type.title() + " has not been published"));
    }

    private static Map<String, Object> detail(LegalDraft d) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("version", d.version().toString());
        detail.put("effectiveDate", String.valueOf(d.effectiveDate()));
        detail.put("draftBanner", d.draftBanner());
        detail.put("locales", d.locales());
        detail.put("needsReview", List.copyOf(d.needsReview()));
        return detail;
    }

    private static ConflictException stale(LegalDocumentType type) {
        return new ConflictException("content.stale-legal-draft", "Someone changed the draft of " + type.title()
                + " in the meantime. Reload it and apply your change again.");
    }

    private static NotFoundException noDraft(LegalDocumentType type) {
        return new NotFoundException("content.legal-draft-not-found", "There is no draft of " + type.title());
    }
}

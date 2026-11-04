package com.afrotransact.content.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.content.domain.ContentRuleViolation;
import com.afrotransact.content.domain.help.HelpArticle;
import com.afrotransact.content.domain.help.HelpDraft;
import com.afrotransact.content.domain.help.HelpRevision;
import com.afrotransact.content.port.HelpArticleRepository;
import com.afrotransact.content.port.HelpDraftRepository;
import com.afrotransact.content.port.HelpRevisionRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class HelpAuthoringServiceImpl implements HelpAuthoringService {

    private static final String ENTITY = "help-article";
    private static final int MIN_REASON = 5;
    private static final int MAX_REASON = 500;

    private final HelpArticleRepository articles;
    private final HelpDraftRepository drafts;
    private final HelpRevisionRepository revisions;
    private final ContentAccess access;
    private final AuditTrail audit;
    private final Clock clock;

    HelpAuthoringServiceImpl(HelpArticleRepository articles, HelpDraftRepository drafts,
                             HelpRevisionRepository revisions, ContentAccess access, AuditTrail audit, Clock clock) {
        this.articles = articles;
        this.drafts = drafts;
        this.revisions = revisions;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<HelpDraft> draft(String slug) {
        access.requireAny(ContentPermissions.SITE_VIEW, ContentPermissions.SITE_EDIT);
        return drafts.find(slug);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> slugsWithDraft() {
        access.requireAny(ContentPermissions.SITE_VIEW, ContentPermissions.SITE_EDIT);
        return drafts.slugsWithDraft();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Integer> latestRevisions() {
        access.requireAny(ContentPermissions.SITE_VIEW, ContentPermissions.SITE_EDIT);
        return revisions.latestRevisions();
    }

    @Override
    @Transactional
    public HelpDraft save(String slug, HelpDraft.Edit edit, Long expectedRowVersion) {
        Actor actor = access.require(ContentPermissions.SITE_EDIT);
        Instant now = Instant.now(clock);
        Optional<HelpDraft> existing = drafts.find(slug);
        if (existing.isEmpty()) {
            if (expectedRowVersion != null) {
                throw stale(slug);
            }
            HelpDraft started = HelpDraft.startFrom(liveOf(slug), actor.subject(), now).edited(edit, actor.subject(), now);
            if (!drafts.insert(started)) {
                throw stale(slug);
            }
            audit.record(actor.subject(), "help-article.draft-saved", ENTITY, slug, Map.of("title", started.title()));
            return started;
        }
        if (expectedRowVersion == null || expectedRowVersion != existing.get().rowVersion()) {
            throw stale(slug);
        }
        HelpDraft next = existing.get().edited(edit, actor.subject(), now);
        if (!drafts.update(next, expectedRowVersion)) {
            throw stale(slug);
        }
        audit.record(actor.subject(), "help-article.draft-saved", ENTITY, slug, Map.of("title", next.title()));
        return next;
    }

    @Override
    @Transactional
    public void discard(String slug) {
        Actor actor = access.require(ContentPermissions.SITE_EDIT);
        HelpDraft draft = drafts.find(slug).orElseThrow(() -> noDraft(slug));
        drafts.delete(slug);
        audit.record(actor.subject(), "help-article.draft-discarded", ENTITY, slug,
                Map.of("lastEditedBy", draft.updatedBy()));
    }

    @Override
    @Transactional
    public HelpArticle publish(String slug, String reason, long expectedRowVersion) {
        Actor actor = access.require(ContentPermissions.SITE_EDIT);
        HelpDraft draft = drafts.find(slug).orElseThrow(() -> noDraft(slug));
        if (draft.rowVersion() != expectedRowVersion) {
            throw stale(slug);
        }
        String why = requireReason(reason);
        HelpArticle live = liveOf(slug);
        if (live.version() != draft.baseVersion()) {
            throw new ConflictException("content.help-draft-outdated", "This page was changed after the draft was "
                    + "started. Discard the draft, or copy your text and start again from the live page.");
        }
        HelpArticle next = apply(live, draft.asArticle(live.status(), live.version() + 1, actor.subject()), draft.changeSummary(),
                why, actor);
        drafts.delete(slug);
        audit.record(actor.subject(), "help-article.draft-published", ENTITY, slug, Map.of("reason", why,
                "authoredBy", draft.createdBy(), "lastEditedBy", draft.updatedBy(), "version", next.version()));
        return next;
    }

    @Override
    @Transactional(readOnly = true)
    public List<HelpRevision> revisions(String slug) {
        access.requireAny(ContentPermissions.SITE_VIEW, ContentPermissions.SITE_EDIT);
        liveOf(slug);
        return revisions.list(slug);
    }

    @Override
    @Transactional(readOnly = true)
    public HelpRevision revision(String slug, int revision) {
        access.requireAny(ContentPermissions.SITE_VIEW, ContentPermissions.SITE_EDIT);
        return revisions.find(slug, revision).orElseThrow(() -> new NotFoundException(
                "content.help-revision-not-found", "No revision " + revision + " of " + slug));
    }

    @Override
    @Transactional
    public HelpArticle restore(String slug, int revision, String reason) {
        Actor actor = access.require(ContentPermissions.SITE_EDIT);
        HelpRevision old = revisions.find(slug, revision).orElseThrow(() -> new NotFoundException(
                "content.help-revision-not-found", "No revision " + revision + " of " + slug));
        if (drafts.find(slug).isPresent()) {
            throw new ConflictException("content.help-draft-exists",
                    "There is a draft of this page. Publish or discard it before restoring a revision.");
        }
        String why = requireReason(reason);
        HelpArticle live = liveOf(slug);
        HelpArticle next = apply(live, old.asArticle(live.status(), live.version() + 1, actor.subject(), Instant.now(clock)),
                "Restores revision " + revision, why, actor);
        audit.record(actor.subject(), "help-article.restored", ENTITY, slug,
                Map.of("reason", why, "restores", revision, "version", next.version()));
        return next;
    }

    /** Stores the new live text and its revision. */
    private HelpArticle apply(HelpArticle live, HelpArticle candidate, String changeSummary, String reason, Actor actor) {
        Instant now = Instant.now(clock);
        HelpArticle next = new HelpArticle(live.slug(), candidate.topic(), candidate.title(), candidate.summary(),
                candidate.sections(), live.status(), candidate.sortOrder(), now, live.version() + 1, actor.subject());
        next.validate();
        if (!articles.update(next, live.version())) {
            throw new ConflictException("content.stale-help-article",
                    "Someone changed this article in the meantime. Reload it and apply your change again.");
        }
        revisions.append(HelpRevision.of(next, revisions.nextRevision(live.slug()), changeSummary, reason,
                actor.subject(), now));
        return next;
    }

    private static String requireReason(String reason) {
        String why = reason == null ? "" : reason.strip();
        if (why.length() < MIN_REASON || why.length() > MAX_REASON) {
            throw new ContentRuleViolation("content.reason-required", "Say why you are publishing", Map.of("reason",
                    "Say why you are publishing (" + MIN_REASON + " to " + MAX_REASON + " characters)"));
        }
        return why;
    }

    private HelpArticle liveOf(String slug) {
        return articles.find(slug).orElseThrow(() -> new NotFoundException("content.help-article-not-found",
                "No help article " + slug));
    }

    private static ConflictException stale(String slug) {
        return new ConflictException("content.stale-help-draft", "Someone changed the draft of " + slug
                + " in the meantime. Reload it and apply your change again.");
    }

    private static NotFoundException noDraft(String slug) {
        return new NotFoundException("content.help-draft-not-found", "There is no draft of " + slug);
    }
}

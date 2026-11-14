package com.afrotransact.seller.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.BusinessRuleException;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.seller.domain.OrgStatus;
import com.afrotransact.seller.domain.Organisation;
import com.afrotransact.seller.domain.StorePage;
import com.afrotransact.seller.domain.StorePageAuditEntry;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.seller.port.StorePageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class StorePageServiceImpl implements StorePageService {

    private static final String STAFF_PERMISSION = "seller:business-page";
    private static final int AUDIT_LIMIT = 50;

    private final StorePageRepository pages;
    private final OrganisationRepository orgs;
    private final AccessGuard guard;
    private final Clock clock;

    StorePageServiceImpl(StorePageRepository pages, OrganisationRepository orgs, AccessGuard guard, Clock clock) {
        this.pages = pages;
        this.orgs = orgs;
        this.guard = guard;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public StorePageView get(String orgId) {
        guard.orgRole(orgId, BusinessBrandingServiceImpl.READERS, STAFF_PERMISSION);
        return view(orgId, pages.find(orgId).orElse(null));
    }

    @Override
    @Transactional
    public StorePageView saveDraft(String orgId, String draftJson, long expectedVersion) {
        Actor actor = guard.liveOrgRole(orgId, BusinessBrandingServiceImpl.EDITORS, STAFF_PERMISSION);
        requireDocument(draftJson);
        Instant now = Instant.now(clock);
        Optional<StorePage> current = pages.find(orgId);
        if (current.isEmpty()) {
            if (expectedVersion != 0 || !pages.insert(orgId, draftJson, now, actor.subject())) {
                throw stale();
            }
            pages.audit(orgId, "DRAFT_SAVED", actor.subject(), 1, now);
            return view(orgId, pages.find(orgId).orElseThrow());
        }
        if (!pages.updateDraft(orgId, draftJson, expectedVersion, now, actor.subject())) {
            throw stale();
        }
        pages.audit(orgId, "DRAFT_SAVED", actor.subject(), expectedVersion + 1, now);
        return view(orgId, pages.find(orgId).orElseThrow());
    }

    @Override
    @Transactional
    public StorePageView publish(String orgId, long expectedVersion) {
        Actor actor = guard.liveOrgRole(orgId, BusinessBrandingServiceImpl.EDITORS, STAFF_PERMISSION);
        Organisation org = org(orgId);
        if (org.status() != OrgStatus.APPROVED) {
            throw new BusinessRuleException("seller.store-not-approved",
                    "The page can be published once the business is approved.");
        }
        if (pages.find(orgId).isEmpty() || !pages.publish(orgId, expectedVersion, Instant.now(clock), actor.subject())) {
            throw stale();
        }
        pages.audit(orgId, "PUBLISHED", actor.subject(), expectedVersion, Instant.now(clock));
        return view(orgId, pages.find(orgId).orElseThrow());
    }

    @Override
    @Transactional
    public StorePageView unpublish(String orgId) {
        Actor actor = guard.liveOrgRole(orgId, BusinessBrandingServiceImpl.EDITORS, STAFF_PERMISSION);
        StorePage page = pages.find(orgId).orElseThrow(
                () -> new NotFoundException("seller.store-page-not-found", "No storefront settings for " + orgId));
        if (page.isPublished()) {
            Instant now = Instant.now(clock);
            pages.unpublish(orgId, now, actor.subject());
            pages.audit(orgId, "UNPUBLISHED", actor.subject(), page.publishedVersion() == null ? page.version()
                    : page.publishedVersion(), now);
        }
        return view(orgId, pages.find(orgId).orElseThrow());
    }

    @Override
    @Transactional(readOnly = true)
    public List<StorePageAuditEntry> audit(String orgId) {
        guard.orgRole(orgId, BusinessBrandingServiceImpl.READERS, STAFF_PERMISSION);
        return pages.audit(orgId, AUDIT_LIMIT);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StorePage> publishedFor(String orgId) {
        Optional<Organisation> org = orgs.findById(orgId);
        if (org.isEmpty() || org.get().status() != OrgStatus.APPROVED) {
            return Optional.empty();
        }
        return pages.find(orgId).filter(StorePage::isPublished);
    }

    private StorePageView view(String orgId, StorePage page) {
        boolean approved = org(orgId).status() == OrgStatus.APPROVED;
        return new StorePageView(page, approved, true);
    }

    private Organisation org(String orgId) {
        return orgs.findById(orgId).orElseThrow(
                () -> new NotFoundException("seller.org-not-found", "Organisation " + orgId + " not found"));
    }

    private static void requireDocument(String json) {
        String trimmed = json == null ? "" : json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")
                || trimmed.getBytes(StandardCharsets.UTF_8).length > StorePage.MAX_JSON_BYTES) {
            throw new BusinessRuleException("seller.invalid-store-page", "The storefront settings are not valid.");
        }
    }

    private static ConflictException stale() {
        return new ConflictException("seller.store-page-stale",
                "The storefront was changed by someone else. Reload and try again.");
    }
}

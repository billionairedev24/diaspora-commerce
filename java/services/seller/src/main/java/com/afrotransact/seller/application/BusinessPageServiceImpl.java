package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import com.afrotransact.seller.domain.BusinessPage;
import com.afrotransact.seller.domain.BusinessSection;
import com.afrotransact.seller.domain.OrgStatus;
import com.afrotransact.seller.domain.Organisation;
import com.afrotransact.seller.port.ApplicationRepository;
import com.afrotransact.seller.port.BusinessPageRepository;
import com.afrotransact.seller.port.OrganisationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BusinessPageServiceImpl implements BusinessPageService {

    private static final int MAX_ATTEMPTS = 50;

    private final BusinessPageRepository pages;
    private final OrganisationRepository orgs;
    private final ApplicationRepository apps;
    private final String publicWebUrl;
    private final Clock clock;

    BusinessPageServiceImpl(BusinessPageRepository pages, OrganisationRepository orgs, ApplicationRepository apps,
                            @Value("${at.seller.public-web-url:http://localhost:3000}") String publicWebUrl,
                            Clock clock) {
        this.pages = pages;
        this.orgs = orgs;
        this.apps = apps;
        this.publicWebUrl = publicWebUrl.replaceAll("/+$", "");
        this.clock = clock;
    }

    @Override
    @Transactional
    public String websiteFor(String orgId, BusinessSection business) {
        if (business != null && !business.needsPage()) {
            return business.website().trim();
        }
        BusinessPage page = pages.findByOrg(orgId).orElseGet(() -> create(orgId, business));
        return publicWebUrl + "/b/" + page.slug();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PublicBusinessPage> find(String slug) {
        return pages.findBySlug(slug).flatMap(page -> {
            Optional<Organisation> org = orgs.findById(page.orgId());
            if (org.isEmpty() || org.get().status() == OrgStatus.REJECTED || org.get().status() == OrgStatus.SUSPENDED) {
                return Optional.empty();
            }
            BusinessSection b = apps.findByOrg(page.orgId()).map(a -> a.data().business()).orElse(null);
            BusinessSection.Branding branding = b == null ? null : b.branding();
            String name = b != null && b.storeName() != null && !b.storeName().isBlank() ? b.storeName()
                    : org.get().displayName();
            return Optional.of(new PublicBusinessPage(page.orgId(), page.slug(), org.get().kind().name(), name,
                    branding == null ? null : branding.description(), branding == null ? null : branding.brandColor(),
                    branding == null ? null : branding.logoUrl(), b == null ? null : b.contactEmail(),
                    org.get().status() == OrgStatus.APPROVED));
        });
    }

    private BusinessPage create(String orgId, BusinessSection business) {
        String name = business != null && business.storeName() != null ? business.storeName()
                : orgs.findById(orgId).map(Organisation::displayName).orElse(null);
        String base = BusinessPage.slugOf(name);
        Instant now = Instant.now(clock);
        for (int i = 1; i <= MAX_ATTEMPTS; i++) {
            String slug = i == 1 ? base : base + "-" + i;
            BusinessPage page = new BusinessPage(orgId, slug, now);
            if (pages.insert(page)) {
                return page;
            }
            // Another organisation took the slug, or this one already has a page (a concurrent request).
            Optional<BusinessPage> mine = pages.findByOrg(orgId);
            if (mine.isPresent()) {
                return mine.get();
            }
        }
        throw new IllegalStateException("No free address for the page of " + orgId);
    }
}

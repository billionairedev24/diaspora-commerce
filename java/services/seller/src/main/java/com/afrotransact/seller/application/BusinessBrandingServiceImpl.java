package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.seller.domain.Application;
import com.afrotransact.seller.domain.BrandingRules;
import com.afrotransact.seller.domain.BusinessPage;
import com.afrotransact.seller.domain.BusinessSection;
import com.afrotransact.seller.domain.OrgStatus;
import com.afrotransact.seller.domain.Organisation;
import com.afrotransact.seller.port.ApplicationRepository;
import com.afrotransact.seller.port.BusinessPageRepository;
import com.afrotransact.seller.port.OrganisationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BusinessBrandingServiceImpl implements BusinessBrandingService {

    private static final String STAFF_PERMISSION = "seller:business-page";
    static final Set<String> READERS = Set.of("owner", "admin", "fulfilment", "catalog", "finance", "support", "ads");
    static final Set<String> EDITORS = Set.of("owner", "admin");

    private final ApplicationRepository apps;
    private final BusinessPageRepository pages;
    private final OrganisationRepository orgs;
    private final AccessGuard guard;
    private final Clock clock;

    BusinessBrandingServiceImpl(ApplicationRepository apps, BusinessPageRepository pages, OrganisationRepository orgs,
                                AccessGuard guard, Clock clock) {
        this.apps = apps;
        this.pages = pages;
        this.orgs = orgs;
        this.guard = guard;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PublicBusinessPage get(String orgId) {
        guard.orgRole(orgId, READERS, STAFF_PERMISSION);
        return view(orgId, apps.findByOrg(orgId).orElse(null));
    }

    @Override
    @Transactional
    public PublicBusinessPage update(String orgId, BusinessSection.Branding branding) {
        guard.orgRole(orgId, EDITORS, STAFF_PERMISSION);
        BusinessSection.Branding clean = BrandingRules.clean(branding);
        Application app = apps.lockById(apps.findByOrg(orgId).map(Application::id).orElseThrow(
                () -> new NotFoundException("seller.application-not-found", "No business details for " + orgId)))
                .orElseThrow();
        return view(orgId, apps.update(app.withBranding(clean, Instant.now(clock))));
    }

    private PublicBusinessPage view(String orgId, Application app) {
        Organisation org = orgs.findById(orgId).orElseThrow(
                () -> new NotFoundException("seller.org-not-found", "Organisation " + orgId + " not found"));
        BusinessSection b = app == null ? null : app.data().business();
        BusinessSection.Branding branding = b == null ? null : b.branding();
        String name = b != null && b.storeName() != null && !b.storeName().isBlank() ? b.storeName()
                : org.displayName();
        return new PublicBusinessPage(orgId, pages.findByOrg(orgId).map(BusinessPage::slug).orElse(null),
                org.kind().name(), name, branding == null ? null : branding.description(),
                branding == null ? null : branding.brandColor(), branding == null ? null : branding.logoUrl(),
                b == null ? null : b.contactEmail(), org.status() == OrgStatus.APPROVED);
    }
}

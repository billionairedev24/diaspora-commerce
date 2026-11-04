package com.afrotransact.seller.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.seller.domain.Application;
import com.afrotransact.seller.domain.ApplicationData;
import com.afrotransact.seller.domain.ApplicationStatus;
import com.afrotransact.seller.domain.BusinessPage;
import com.afrotransact.seller.domain.BusinessSection;
import com.afrotransact.seller.domain.EntityType;
import com.afrotransact.seller.domain.Offering;
import com.afrotransact.seller.domain.OnboardingStep;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.OrgStatus;
import com.afrotransact.seller.domain.Organisation;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.port.ApplicationRepository;
import com.afrotransact.seller.port.BusinessPageRepository;
import com.afrotransact.seller.port.OrganisationRepository;
import org.junit.jupiter.api.Test;

/** The page settings: logo, colour and description change at any time, and are checked. */
class BusinessBrandingServiceImplTest {

    private final ApplicationRepository apps = mock(ApplicationRepository.class);
    private final BusinessPageRepository pages = mock(BusinessPageRepository.class);
    private final OrganisationRepository orgs = mock(OrganisationRepository.class);
    private final AccessGuard guard = mock(AccessGuard.class);
    private final BusinessBrandingServiceImpl service = new BusinessBrandingServiceImpl(apps, pages, orgs, guard,
            Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC));
    private final Instant now = Instant.parse("2026-10-01T00:00:00Z");

    private Application approvedApplication() {
        BusinessSection business = new BusinessSection(Offering.PRODUCTS, EntityType.SOLE_PROPRIETOR, "US",
                "Ade's Kitchen", "ade@example.com", null, null);
        Application started = Application.start("app_1", "APP-1", "org_1", "usr_1", OrgKind.SELLER,
                ApplicationData.empty().withBusiness(business), Set.of(), now);
        return new Application(started.id(), started.reference(), started.orgId(), started.applicantUserId(),
                started.kind(), ApplicationStatus.APPROVED, OnboardingStep.REVIEW, started.data(), Set.of(), null,
                null, null, null, null, null, null, 1, now, now, now, 3);
    }

    private void anApprovedBusiness() {
        Application app = approvedApplication();
        when(apps.findByOrg("org_1")).thenReturn(Optional.of(app));
        when(apps.lockById("app_1")).thenReturn(Optional.of(app));
        when(apps.update(any())).thenAnswer(i -> i.getArgument(0));
        when(orgs.findById("org_1")).thenReturn(Optional.of(mock(Organisation.class)));
        when(pages.findByOrg("org_1")).thenReturn(Optional.of(new BusinessPage("org_1", "ades-kitchen", now)));
    }

    @Test
    void anApprovedBusinessCanChangeItsLogoAndColour() {
        anApprovedBusiness();
        Organisation org = mock(Organisation.class);
        when(org.kind()).thenReturn(OrgKind.SELLER);
        when(org.status()).thenReturn(OrgStatus.APPROVED);
        when(orgs.findById("org_1")).thenReturn(Optional.of(org));

        PublicBusinessPage page = service.update("org_1",
                new BusinessSection.Branding(" Jollof and more ", "#1A7F4B", "https://img.afrotransact.dev/a1.webp"));

        assertThat(page.logoUrl()).isEqualTo("https://img.afrotransact.dev/a1.webp");
        assertThat(page.brandColor()).isEqualTo("#1a7f4b");
        assertThat(page.description()).isEqualTo("Jollof and more");
        assertThat(page.name()).isEqualTo("Ade's Kitchen");
        assertThat(page.slug()).isEqualTo("ades-kitchen");
        assertThat(page.approved()).isTrue();
    }

    @Test
    void blankValuesClearThePage() {
        anApprovedBusiness();
        Organisation org = mock(Organisation.class);
        when(org.kind()).thenReturn(OrgKind.SELLER);
        when(orgs.findById("org_1")).thenReturn(Optional.of(org));
        PublicBusinessPage page = service.update("org_1", new BusinessSection.Branding("", " ", ""));
        assertThat(page.logoUrl()).isNull();
        assertThat(page.brandColor()).isNull();
        assertThat(page.description()).isNull();
    }

    @Test
    void aBadColourOrALogoThatIsNotHttpsIsRefusedWithFieldErrors() {
        anApprovedBusiness();
        assertThatThrownBy(() -> service.update("org_1", new BusinessSection.Branding(null, "green", "http://x/y.png")))
                .isInstanceOfSatisfying(SellerRuleViolation.class, e -> {
                    assertThat(e.code()).isEqualTo("seller.invalid-branding");
                    assertThat(e.fieldErrors()).containsKeys("brandColor", "logoUrl");
                });
        verify(apps, never()).update(any());
    }
}

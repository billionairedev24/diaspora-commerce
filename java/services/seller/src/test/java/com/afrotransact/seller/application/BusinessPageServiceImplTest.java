package com.afrotransact.seller.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import com.afrotransact.seller.domain.BusinessPage;
import com.afrotransact.seller.domain.BusinessSection;
import com.afrotransact.seller.domain.EntityType;
import com.afrotransact.seller.domain.Offering;
import com.afrotransact.seller.port.ApplicationRepository;
import com.afrotransact.seller.port.BusinessPageRepository;
import com.afrotransact.seller.port.OrganisationRepository;
import org.junit.jupiter.api.Test;

/** A business with a website gives Stripe that website; one without gets a plain page whose address Stripe is given. */
class BusinessPageServiceImplTest {

    private final BusinessPageRepository pages = mock(BusinessPageRepository.class);
    private final OrganisationRepository orgs = mock(OrganisationRepository.class);
    private final ApplicationRepository apps = mock(ApplicationRepository.class);
    private final BusinessPageServiceImpl service = new BusinessPageServiceImpl(pages, orgs, apps,
            "https://afrotransact.com/", Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC));

    private static BusinessSection business(String name, String website) {
        return new BusinessSection(Offering.PRODUCTS, EntityType.SOLE_PROPRIETOR, "US", name, null, null, website);
    }

    @Test
    void aBusinessWithItsOwnWebsiteGetsNoPage() {
        assertThat(service.websiteFor("org_1", business("Ade's Kitchen", " https://ade.example.com "))).isEqualTo(
                "https://ade.example.com");
        verify(pages, never()).insert(any());
    }

    @Test
    void aBusinessWithoutOneGetsAPageAndItsAddressGoesToStripe() {
        when(pages.findByOrg("org_1")).thenReturn(Optional.empty());
        when(pages.insert(any())).thenReturn(true);
        assertThat(service.websiteFor("org_1", business("Ade's Kitchen", null))).isEqualTo(
                "https://afrotransact.com/b/ades-kitchen");
    }

    @Test
    void aTakenAddressGetsANumber() {
        when(pages.findByOrg("org_1")).thenReturn(Optional.empty());
        when(pages.insert(new BusinessPage("org_1", "ades-kitchen", Instant.parse("2026-10-18T12:00:00Z")))).thenReturn(
                false);
        when(pages.insert(new BusinessPage("org_1", "ades-kitchen-2", Instant.parse("2026-10-18T12:00:00Z")))).thenReturn(
                true);
        assertThat(service.websiteFor("org_1", business("Ade's Kitchen", ""))).isEqualTo(
                "https://afrotransact.com/b/ades-kitchen-2");
    }

    @Test
    void theSamePageIsReusedOnTheNextCall() {
        when(pages.findByOrg("org_1")).thenReturn(Optional.of(new BusinessPage("org_1", "ades-kitchen",
                Instant.parse("2026-10-01T00:00:00Z"))));
        assertThat(service.websiteFor("org_1", business("Another name", null))).isEqualTo(
                "https://afrotransact.com/b/ades-kitchen");
        verify(pages, never()).insert(any());
    }
}

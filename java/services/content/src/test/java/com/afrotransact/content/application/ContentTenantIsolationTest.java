package com.afrotransact.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.afrotransact.content.domain.ads.Campaign;
import com.afrotransact.content.domain.ads.CampaignContent;
import com.afrotransact.content.port.AdCampaignRepository;
import com.afrotransact.content.port.AdStatsRepository;
import com.afrotransact.content.port.PrivacyRequestRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.test.tenant.CrossTenant;
import com.afrotransact.platform.test.tenant.TenantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cross-tenant proof for content (tenant-isolation-matrix.txt). The only tenant-owned content here is a seller's ad
 * campaigns (seller storefront and brand pages live in the seller service); everything else is platform-edited and
 * guarded by content:* permissions that no seller or provider role holds.
 */
class ContentTenantIsolationTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final String ADS = "ads:manage";

    private final TenantFixture tenants = new TenantFixture();
    private final AdCampaignRepository campaigns = mock(AdCampaignRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private Campaign campaignOfA;

    private AdCampaignServiceImpl service() {
        return new AdCampaignServiceImpl(campaigns, mock(AdStatsRepository.class),
                new ContentAccess(tenants.permissions(), tenants.resolver()), mock(AuditTrail.class),
                mock(DomainEventPublisher.class), clock);
    }

    private CampaignContent content() {
        return new CampaignContent("Egusi always-on", "Mama Tee", "us", 40, 200L, 1000, "usd", List.of("egusi"),
                List.of("Groceries"), List.of("AT1EGU0001"), NOW.minusSeconds(60), null);
    }

    @BeforeEach
    void data() {
        campaignOfA = Campaign.create("cmp_a", TenantFixture.ORG_A, content(), "user-a", NOW);
        when(campaigns.findById("cmp_a")).thenReturn(Optional.of(campaignOfA));
        when(campaigns.find(any(), any(), anyInt())).thenReturn(List.of(campaignOfA));
    }

    @Test
    void advertiserOfBCannotReadReportEditOrStopCampaignsOfA() {
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_B, ADS));
        CrossTenant.assertDenied(() -> service().get("cmp_a"));
        CrossTenant.assertDenied(() -> service().report("cmp_a", null, null));
        CrossTenant.assertDenied(() -> service().update("cmp_a", content(), campaignOfA.version()));
        CrossTenant.assertDenied(() -> service().pause("cmp_a"));
        CrossTenant.assertDenied(() -> service().resume("cmp_a"));
        CrossTenant.assertDenied(() -> service().end("cmp_a"));
        verify(campaigns, never()).update(any());
    }

    @Test
    void advertiserOfBCannotCreateCampaignsInTheNameOfA() {
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_B, ADS));
        CrossTenant.assertDenied(() -> service().create(TenantFixture.ORG_A, content()));
        verify(campaigns, never()).insert(any());
    }

    @Test
    void campaignListIsPinnedToTheTokenOrg() {
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_B, ADS));
        CrossTenant.assertDenied(() -> service().list(TenantFixture.ORG_A, null));
        service().list(null, null);
        verify(campaigns).find(TenantFixture.ORG_B, null, 500);
    }

    @Test
    void memberWithoutAdsManageSeesNothingEvenInTheirOwnOrg() {
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A, "orders:read"));
        CrossTenant.assertDenied(() -> service().get("cmp_a"));
        CrossTenant.assertDenied(() -> service().list(null, null));
    }

    @Test
    void ownerMayReadOwnCampaign() {
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A, ADS));
        assertThat(service().get("cmp_a").id()).isEqualTo("cmp_a");
    }

    @Test
    void privacyRequestsAreStaffOnly() {
        PrivacyRequestServiceImpl privacy = new PrivacyRequestServiceImpl(mock(PrivacyRequestRepository.class),
                new ContentAccess(tenants.permissions(), tenants.resolver()), mock(AuditTrail.class), clock);
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_B, ADS, "support:respond"));
        CrossTenant.assertDenied(() -> privacy.list(null));
        CrossTenant.assertDenied(() -> privacy.get("PR-1"));
        CrossTenant.assertDenied(() -> privacy.resolve("PR-1", null, "x"));
        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_1));
        CrossTenant.assertDenied(() -> privacy.get("PR-1"));
    }
}

package com.afrotransact.trustops.application;

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
import java.util.Set;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.tenant.CrossTenant;
import com.afrotransact.platform.test.tenant.TenantFixture;
import com.afrotransact.trustops.domain.Claim;
import com.afrotransact.trustops.domain.ClaimDraft;
import com.afrotransact.trustops.domain.ClaimType;
import com.afrotransact.trustops.domain.DefaultSlaPolicy;
import com.afrotransact.trustops.domain.SlaPolicy;
import com.afrotransact.trustops.domain.SlaTargets;
import com.afrotransact.trustops.domain.SupportCase;
import com.afrotransact.trustops.port.ClaimRepository;
import com.afrotransact.trustops.port.OrdersClient;
import com.afrotransact.trustops.port.PaymentsClient;
import com.afrotransact.trustops.port.SupportCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cross-tenant proof for trust-ops (tenant-isolation-matrix.txt). A seller member holds {@code support:respond}
 * (seller.owner/admin/support), the same permission that gates the platform support queue, so permission alone is not
 * enough there; claims are party-checked against the token org claim and the claim's own seller org.
 */
class TrustOpsTenantIsolationTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final String SELLER_PERMS = "support:respond";

    private final TenantFixture tenants = new TenantFixture();
    private final SlaPolicy sla = new DefaultSlaPolicy(SlaTargets.defaults());
    private final ClaimRepository claims = mock(ClaimRepository.class);
    private final SupportCaseRepository cases = mock(SupportCaseRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private Claim claimOfA;
    private SupportCase customerCase;

    private ClaimServiceImpl claimService() {
        return new ClaimServiceImpl(claims, mock(PaymentsClient.class), sla, tenants.permissions(), tenants.resolver(),
                mock(DomainEventPublisher.class), mock(AuditTrail.class), clock, mock(OrdersClient.class),
                new ClaimSettings(true));
    }

    private SupportCaseServiceImpl supportService() {
        return new SupportCaseServiceImpl(cases, sla, tenants.permissions(), null, mock(DomainEventPublisher.class),
                clock);
    }

    @BeforeEach
    void data() {
        claimOfA = Claim.open(UUID.randomUUID(), "AZ-1", new ClaimDraft(ClaimType.ITEM_NOT_RECEIVED, "ord-1",
                "AT-1", "shp-1", TenantFixture.ORG_A, TenantFixture.CUSTOMER_1, 4_999, "usd",
                "Tracking says delivered but nothing arrived", List.of()), NOW, sla);
        when(claims.findById(claimOfA.id())).thenReturn(Optional.of(claimOfA));
        customerCase = SupportCase.fromHandoff(UUID.randomUUID(), "SC-1", UUID.randomUUID(), "sess-1",
                TenantFixture.CUSTOMER_1, "refund", "Customer asked about a refund", NOW, sla);
        when(cases.findById(customerCase.id())).thenReturn(Optional.of(customerCase));
    }

    // ---- ORG_OWNED: claims ----------------------------------------------------------------------------------------

    @Test
    void sellerBCannotReadHistoryRespondToOrAddEvidenceOnSellerAClaim() {
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_B, SELLER_PERMS));
        CrossTenant.assertDenied(() -> claimService().get(claimOfA.id()));
        CrossTenant.assertDenied(() -> claimService().history(claimOfA.id()));
        CrossTenant.assertDenied(() -> claimService().addEvidence(claimOfA.id(), "asset_x", "note"));
        CrossTenant.assertDenied(() -> claimService().sellerRespond(claimOfA.id(), "we shipped it", List.of()));
        verify(claims, never()).update(any());
    }

    @Test
    void sellerAMayRespondAndTheClaimantMayRead() {
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A, SELLER_PERMS));
        assertThat(claimService().get(claimOfA.id())).isNotNull();
        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_1));
        assertThat(claimService().get(claimOfA.id())).isNotNull();
    }

    @Test
    void anotherCustomerCannotReadOrWithdrawSomeoneElsesClaim() {
        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_2));
        CrossTenant.assertDenied(() -> claimService().get(claimOfA.id()));
        CrossTenant.assertDenied(() -> claimService().history(claimOfA.id()));
        CrossTenant.assertDenied(() -> claimService().addEvidence(claimOfA.id(), "asset_x", "note"));
        CrossTenant.assertDenied(() -> claimService().withdraw(claimOfA.id()));
        verify(claims, never()).update(any());
    }

    @Test
    void staffOnlyClaimActionsRefuseSellerMembers() {
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A, SELLER_PERMS, "disputes:manage"));
        CrossTenant.assertDenied(() -> claimService().escalate(claimOfA.id(), "why"));
        CrossTenant.assertDenied(() -> claimService().queue(null, null, null, 0, 20));
    }

    // ---- ORG_SCOPED_LIST: claims by seller / mine ------------------------------------------------------------------

    @Test
    void sellerClaimListIsForTheTokenOrgOnly() {
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_B, SELLER_PERMS));
        CrossTenant.assertDenied(() -> claimService().forSeller(TenantFixture.ORG_A, null, 0, 20));
        verify(claims, never()).search(any(), anyInt(), anyInt());
    }

    // ---- STAFF_ONLY despite a permission sellers also hold: support cases ------------------------------------------

    @Test
    void sellerMembersCannotReadOrWorkThePlatformSupportQueue() {
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_B, SELLER_PERMS));
        CrossTenant.assertDenied(() -> supportService().queue(null, 0, 20));
        CrossTenant.assertDenied(() -> supportService().get(customerCase.id()));
        CrossTenant.assertDenied(() -> supportService().assign(customerCase.id(), null));
        CrossTenant.assertDenied(() -> supportService().resolve(customerCase.id(), "done"));
        verify(cases, never()).search(any(), anyInt(), anyInt());
        verify(cases, never()).update(any());
    }

    @Test
    void providerMembersCannotReadTheSupportQueueEither() {
        tenants.as(new Actor("prov", PrincipalType.PROVIDER_MEMBER, Optional.of(TenantFixture.ORG_B),
                Set.of("provider.owner"), Set.of(SELLER_PERMS)));
        CrossTenant.assertDenied(() -> supportService().get(customerCase.id()));
    }

    @Test
    void staffStillWorkTheSupportQueue() {
        tenants.as(TenantFixture.staff(SELLER_PERMS));
        assertThat(supportService().get(customerCase.id())).isNotNull();
    }
}

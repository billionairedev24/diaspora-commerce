package com.afrotransact.seller.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.tenant.CrossTenant;
import com.afrotransact.platform.test.tenant.TenantFixture;
import com.afrotransact.seller.domain.OrgMember;
import com.afrotransact.seller.domain.ShipFromAddress;
import com.afrotransact.seller.port.AdSpendSource;
import com.afrotransact.seller.port.AiUsageSource;
import com.afrotransact.seller.port.ApplicationRepository;
import com.afrotransact.seller.port.BusinessPageRepository;
import com.afrotransact.seller.port.ConnectAccountRepository;
import com.afrotransact.seller.port.DeliveryZones;
import com.afrotransact.seller.port.Geocoder;
import com.afrotransact.seller.port.OrgSuspensionRepository;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.seller.port.PlanRepository;
import com.afrotransact.seller.port.ShipFromAddressRepository;
import com.afrotransact.seller.port.StorageUsageSource;
import com.afrotransact.seller.port.SubscriptionRepository;
import com.afrotransact.seller.port.UsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Cross-tenant tests for the seller use cases that take an organisation id: the real AccessGuard and RBAC checkers
 * (production wiring, no OpenFGA) in front of the real services, with mocked ports. Named by
 * tenant-isolation-matrix.txt.
 */
class SellerTenantIsolationTest {

    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");

    private final TenantFixture fx = new TenantFixture();
    private final OrganisationRepository orgs = mock(OrganisationRepository.class);
    private final ShipFromAddressRepository addresses = mock(ShipFromAddressRepository.class);
    private final SubscriptionRepository subs = mock(SubscriptionRepository.class);
    private final ConnectAccountRepository accounts = mock(ConnectAccountRepository.class);
    private final ApplicationRepository apps = mock(ApplicationRepository.class);

    private AccessGuard guard;
    private OrgService orgService;
    private ShipFromAddressService shipFrom;
    private BusinessBrandingService branding;
    private EntitlementsService entitlements;
    private PlanService plans;
    private ComplianceService compliance;
    private SellerEnforcementService enforcement;
    private UsageMeteringService usage;
    private UUID addressOfB;

    @BeforeEach
    void wire() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        TransactionOperations tx = TransactionOperations.withoutTransaction();
        guard = new AccessGuard(fx.permissions(), fx.resolver(), orgs);
        SellerSettings settings = mock(SellerSettings.class);
        orgService = new OrgServiceImpl(orgs, guard, mock(DomainEventPublisher.class), clock);
        shipFrom = new ShipFromAddressServiceImpl(addresses, orgs, guard, mock(Geocoder.class),
                mock(DeliveryZones.class), mock(DomainEventPublisher.class), settings, clock);
        branding = new BusinessBrandingServiceImpl(apps, mock(BusinessPageRepository.class), orgs, guard, clock);
        entitlements = new EntitlementsServiceImpl(subs, mock(PlanRepository.class), guard, settings);
        plans = new PlanServiceImpl(mock(PlanRepository.class), subs, orgs, mock(TrialService.class), guard, clock);
        compliance = new ComplianceServiceImpl(accounts, orgs, mock(ComplianceSync.class), guard,
                mock(DomainEventPublisher.class), tx, clock);
        enforcement = new SellerEnforcementServiceImpl(orgs, mock(OrgSuspensionRepository.class), accounts, guard,
                mock(DomainEventPublisher.class), settings, clock);
        usage = new UsageMeteringServiceImpl(mock(UsageRepository.class), subs, mock(StorageUsageSource.class),
                mock(AdSpendSource.class), mock(AiUsageSource.class), guard, tx, settings, clock);

        addressOfB = UUID.randomUUID();
        when(addresses.findById(addressOfB)).thenReturn(Optional.of(new ShipFromAddress(addressOfB,
                TenantFixture.ORG_B, "Main", "Bee", null, "+17135550142", "1 Main St", null, "Houston", "TX",
                "77001", "US", true, NOW, NOW, 0)));
    }

    @Test
    void orgScopedRoutesRefuseAnotherOrganisationsOwner() {
        String b = TenantFixture.ORG_B;
        fx.as(TenantFixture.sellerOf(TenantFixture.ORG_A, "billing:read"));
        CrossTenant.assertDenied(() -> orgService.get(b));
        CrossTenant.assertDenied(() -> orgService.rename(b, "Hijacked"));
        CrossTenant.assertDenied(() -> shipFrom.list(b));
        CrossTenant.assertDenied(() -> shipFrom.create(b, null));
        CrossTenant.assertDenied(() -> shipFrom.update(b, addressOfB, null));
        CrossTenant.assertDenied(() -> shipFrom.makeDefault(b, addressOfB));
        CrossTenant.assertDenied(() -> shipFrom.delete(b, addressOfB));
        CrossTenant.assertDenied(() -> shipFrom.setPickup(b, addressOfB, null));
        CrossTenant.assertDenied(() -> shipFrom.location(addressOfB));
        CrossTenant.assertDenied(() -> branding.get(b));
        CrossTenant.assertDenied(() -> branding.update(b, null));
        CrossTenant.assertDenied(() -> entitlements.forOrg(b));
        CrossTenant.assertDenied(() -> plans.listFor(b));
        CrossTenant.assertDenied(() -> compliance.accountHealth(b));
        CrossTenant.assertDenied(() -> usage.statement(b, LocalDate.now(), LocalDate.now()));
        verify(addresses, never()).insert(any());
        verify(addresses, never()).update(any());
        verify(addresses, never()).delete(any(), any());
        verify(addresses, never()).makeDefault(any(), any());
        verify(orgs, never()).update(any());
    }

    @Test
    void foreignAddressIdInsideOwnOrgPathIsNotFound() {
        fx.as(TenantFixture.sellerOf(TenantFixture.ORG_A));
        CrossTenant.assertDenied(() -> shipFrom.update(TenantFixture.ORG_A, addressOfB, null));
        CrossTenant.assertDenied(() -> shipFrom.makeDefault(TenantFixture.ORG_A, addressOfB));
        CrossTenant.assertDenied(() -> shipFrom.delete(TenantFixture.ORG_A, addressOfB));
        CrossTenant.assertDenied(() -> shipFrom.setPickup(TenantFixture.ORG_A, addressOfB, null));
        verify(addresses, never()).delete(any(), any());
    }

    @Test
    void ownerReachesOwnOrgAndStaffNeedsThePermission() {
        fx.as(TenantFixture.sellerOf(TenantFixture.ORG_B));
        assertThat(shipFrom.location(addressOfB).orgId()).isEqualTo(TenantFixture.ORG_B);
        fx.as(TenantFixture.staff());
        CrossTenant.assertDenied(() -> shipFrom.location(addressOfB));
    }

    @Test
    void staffOnlyEnforcementAndComplianceRefuseOrgOwners() {
        fx.as(TenantFixture.sellerOf(TenantFixture.ORG_B, "sellers:enforce", "billing:read", "compliance:remind"));
        CrossTenant.assertDenied(() -> enforcement.status(TenantFixture.ORG_B));
        CrossTenant.assertDenied(() -> enforcement.reinstate(TenantFixture.ORG_B, "x"));
        CrossTenant.assertDenied(() -> compliance.account("acct_1"));
        CrossTenant.assertDenied(() -> compliance.pausePayouts("acct_1", "x"));
        CrossTenant.assertDenied(() -> usage.runs());
    }

    @Test
    void roleSuffixIsRequiredNotJustMembershipAndBlankOrgIsRefused() {
        fx.as(new Actor("u-support", PrincipalType.SELLER_MEMBER, Optional.of(TenantFixture.ORG_A),
                Set.of("seller.support"), Set.of()));
        CrossTenant.assertDenied(() -> guard.orgRole(TenantFixture.ORG_A, AccessGuard.OWNER, null));
        fx.as(new Actor("u-x", PrincipalType.SELLER_MEMBER, Optional.empty(), Set.of("seller.owner"), Set.of()));
        CrossTenant.assertDenied(() -> guard.orgRole(TenantFixture.ORG_A, AccessGuard.OWNER, null));
        CrossTenant.assertDenied(() -> guard.orgRole("", AccessGuard.OWNER, null));
    }

    @Test
    void memberListMembershipIsPerOrganisation() {
        Actor user = new Actor("u-1", PrincipalType.CUSTOMER, Optional.empty(), Set.of("customer"), Set.of());
        when(orgs.findMember(TenantFixture.ORG_A, "u-1")).thenReturn(Optional.of(new OrgMember(TenantFixture.ORG_A,
                "u-1", "seller.owner", NOW, NOW)));
        fx.as(user);
        assertThat(guard.orgRole(TenantFixture.ORG_A, AccessGuard.OWNER, null)).isSameAs(user);
        CrossTenant.assertDenied(() -> guard.orgRole(TenantFixture.ORG_B, AccessGuard.OWNER, null));
    }

    @Test
    void sensitiveActionsRecheckTheMemberListNotTheTokenRole() {
        Actor tokenOwner = TenantFixture.sellerOf(TenantFixture.ORG_A);
        fx.as(tokenOwner);
        when(orgs.findMember(TenantFixture.ORG_A, tokenOwner.subject())).thenReturn(Optional.empty());
        // removed from the member list but the 5-minute token still says owner: refused at once
        CrossTenant.assertDenied(() -> guard.liveOrgRole(TenantFixture.ORG_A, AccessGuard.OWNER, null));
        CrossTenant.assertDenied(() -> guard.liveOrgRoleOrStaff(TenantFixture.ORG_A, AccessGuard.OWNER_OR_FINANCE,
                "billing:read"));
        CrossTenant.assertDenied(() -> orgService.rename(TenantFixture.ORG_A, "Renamed"));
        CrossTenant.assertDenied(() -> branding.update(TenantFixture.ORG_A, null));
        // downgraded to a role without the required suffix
        when(orgs.findMember(TenantFixture.ORG_A, tokenOwner.subject())).thenReturn(Optional.of(new OrgMember(
                TenantFixture.ORG_A, tokenOwner.subject(), "seller.support", NOW, NOW)));
        CrossTenant.assertDenied(() -> guard.liveOrgRole(TenantFixture.ORG_A, AccessGuard.OWNER, null));
        // still a member with the role: allowed
        when(orgs.findMember(TenantFixture.ORG_A, tokenOwner.subject())).thenReturn(Optional.of(new OrgMember(
                TenantFixture.ORG_A, tokenOwner.subject(), "seller.owner", NOW, NOW)));
        assertThat(guard.liveOrgRole(TenantFixture.ORG_A, AccessGuard.OWNER, null)).isSameAs(tokenOwner);
    }

    @Test
    void billingReasonCodesAreStaffOnly() {
        BillingAdjustmentService adjustments = new BillingAdjustmentServiceImpl(null, null, null, null, guard, null,
                null, null, null);
        fx.as(TenantFixture.sellerOf(TenantFixture.ORG_A, "billing:read"));
        CrossTenant.assertDenied(adjustments::reasonCodes);
    }
}

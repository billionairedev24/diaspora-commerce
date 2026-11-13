package com.afrotransact.deliveryruns.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.application.RunPickLists.PickList;
import com.afrotransact.deliveryruns.domain.AttributionChannel;
import com.afrotransact.deliveryruns.domain.Capacity;
import com.afrotransact.deliveryruns.domain.CutoffPolicy;
import com.afrotransact.deliveryruns.domain.DeliveryWindow;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.LocationDetails;
import com.afrotransact.deliveryruns.domain.PreorderFulfilment;
import com.afrotransact.deliveryruns.domain.PreorderLine;
import com.afrotransact.deliveryruns.domain.PreorderOrder;
import com.afrotransact.deliveryruns.domain.PreorderOrderStatus;
import com.afrotransact.deliveryruns.domain.PreorderPackage;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunOrder;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.domain.RunTerms;
import com.afrotransact.deliveryruns.port.LocationRepository;
import com.afrotransact.deliveryruns.port.PreorderOrderRepository;
import com.afrotransact.deliveryruns.port.RunOrderRepository;
import com.afrotransact.deliveryruns.port.RunRepository;
import com.afrotransact.platform.test.tenant.CrossTenant;
import com.afrotransact.platform.test.tenant.TenantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * GET /v1/seller/run-pickups and /v1/seller/run-pickups/{runId}: one run carries lines of two sellers; seller A
 * (token org claim) sees only org-a lines, an orgId naming org-b is refused, a blank orgId never means "all".
 */
class RunPickupTenantIsolationTest {

    private static final Instant NOW = Instant.parse("2026-10-22T15:00:00Z");
    private static final UUID WINDOW = UUID.randomUUID();
    private static final Run RUN = Run.confirmedFromWindow(UUID.randomUUID(), UUID.randomUUID(), new RunTerms(
            new DeliveryWindow(Instant.parse("2026-10-24T14:00:00Z"), Instant.parse("2026-10-25T02:00:00Z")),
            Instant.parse("2026-10-21T01:00:00Z"), null, Instant.parse("2026-10-23T14:00:00Z"), 799, "USD", 1,
            Capacity.UNLIMITED, DropMode.DOORSTEP, null, CutoffPolicy.CANCEL, 1), null, 3, 3, NOW);

    private final TenantFixture tenants = new TenantFixture();
    private RunPickListServiceImpl service;
    private final RunRepository runs = mock(RunRepository.class);
    private final RunOrderRepository runOrders = mock(RunOrderRepository.class);
    private final PreorderOrderRepository preorders = mock(PreorderOrderRepository.class);
    private final LocationRepository locations = mock(LocationRepository.class);

    @BeforeEach
    void setUp() {
        Location location = mock(Location.class);
        LocationDetails details = mock(LocationDetails.class);
        when(location.name()).thenReturn("Bastrop");
        when(location.details()).thenReturn(details);
        when(details.state()).thenReturn("TX");
        when(details.timeZone()).thenReturn(ZoneId.of("America/Chicago"));
        when(locations.findById(any())).thenReturn(Optional.of(location));
        when(runs.find(any(), any(), anyInt())).thenReturn(List.of(RUN));
        when(runs.findById(RUN.id())).thenReturn(Optional.of(RUN));
        PreorderOrder o1 = order("o1", "LBK-1", new PreorderLine("EGU", "Egusi", TenantFixture.ORG_A, "Seller A", 2),
                new PreorderLine("PAL", "Palm oil", TenantFixture.ORG_B, "Seller B", 1));
        when(runOrders.findByRun(any(), any())).thenReturn(List.of(slot("o1")));
        when(preorders.findByIds(any())).thenReturn(List.of(o1));
        PickListAccess access = new PickListAccess(tenants.resolver(), tenants.permissions(), tenants.access());
        service = new RunPickListServiceImpl(runs, runOrders, preorders, locations, access,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void sellerSeesOnlyItsOwnPickListAndLines() {
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A, "orders:read"));

        List<PickList> lists = service.forSeller(null);

        assertThat(lists).hasSize(1);
        CrossTenant.assertOnlyOwnedBy(lists, PickList::sellerOrgId, TenantFixture.ORG_A);
        assertThat(lists.get(0).items()).extracting(RunPickLists.Item::atin).containsExactly("EGU");
        assertThat(lists.get(0).bags()).flatExtracting(RunPickLists.OrderBag::lines)
                .extracting(RunPickLists.Item::atin).containsExactly("EGU");
    }

    @Test
    void blankOrgParamMeansOwnOrgNeverAll() {
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A, "orders:read"));

        assertThat(service.forSeller("  ")).extracting(PickList::sellerOrgId).containsOnly(TenantFixture.ORG_A);
    }

    @Test
    void sellerCannotNameAnotherOrganisation() {
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A, "orders:read"));

        CrossTenant.assertDenied(() -> service.forSeller(TenantFixture.ORG_B));
        CrossTenant.assertDenied(() -> service.forSeller(TenantFixture.ORG_B, RUN.id()));
    }

    @Test
    void sellerOnlyReachesItsOwnLinesOfARun() {
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A, "orders:read"));
        assertThat(service.forSeller(null, RUN.id()).sellerOrgId()).isEqualTo(TenantFixture.ORG_A);

        // a run with nothing of org-a on it is a 404, not org-b's list
        PreorderOrder onlyB = order("o1", "LBK-1", new PreorderLine("PAL", "Palm oil", TenantFixture.ORG_B, "B", 1));
        when(preorders.findByIds(any())).thenReturn(List.of(onlyB));
        CrossTenant.assertDenied(() -> service.forSeller(null, RUN.id()));
    }

    @Test
    void membersWithoutOrgOrPermissionAreRefused() {
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A));
        CrossTenant.assertDenied(() -> service.forSeller(null));
        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_1, "orders:read"));
        CrossTenant.assertDenied(() -> service.forSeller(null));
        CrossTenant.assertDenied(() -> service.forSeller(TenantFixture.ORG_A, RUN.id()));
    }

    @Test
    void staffMustNameTheOrganisationAndOnlyGetsThat() {
        tenants.as(TenantFixture.staff("delivery-runs:read"));
        assertThatThrownBy(() -> service.forSeller(null)).isInstanceOf(RuleViolation.class);
        assertThat(service.forSeller(TenantFixture.ORG_B)).extracting(PickList::sellerOrgId)
                .containsOnly(TenantFixture.ORG_B);
    }

    @Test
    void everySellersListsOfARunAreOperatorsOnly() {
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A, "orders:read"));
        CrossTenant.assertDenied(() -> service.forRun(RUN.id()));
        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_1));
        CrossTenant.assertDenied(() -> service.forRun(RUN.id()));
        tenants.as(TenantFixture.staff("delivery-runs:read"));
        assertThat(service.forRun(RUN.id())).hasSize(2);
    }

    private static PreorderOrder order(String id, String number, PreorderLine... lines) {
        return new PreorderOrder(id, number, WINDOW, "cust-" + id, "Ada", "+1 806 555 0101", "ada@example.com",
                PreorderOrder.Address.NONE, null, NOW, AttributionChannel.SITE_DIRECT, null,
                List.of(new PreorderPackage("ship-" + id, TenantFixture.ORG_A, 2000, 0, List.of(lines), false)),
                PreorderFulfilment.DOOR, "USD", PreorderOrderStatus.PAID, null, NOW, NOW, 0);
    }

    private static RunOrder slot(String orderId) {
        return RunOrder.reserve(UUID.randomUUID(), RUN.id(), orderId, "cust-" + orderId, 1, null, NOW)
                .confirm(null, NOW);
    }
}

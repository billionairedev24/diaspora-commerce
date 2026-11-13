package com.afrotransact.fulfilment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.afrotransact.fulfilment.domain.AccessSource;
import com.afrotransact.fulfilment.domain.DeliveryRoute;
import com.afrotransact.fulfilment.domain.DriverAccess;
import com.afrotransact.fulfilment.domain.DriverAccessStatus;
import com.afrotransact.fulfilment.domain.RoutePlanner;
import com.afrotransact.fulfilment.port.CentreRepository;
import com.afrotransact.fulfilment.port.DeliveryShipmentRepository;
import com.afrotransact.fulfilment.port.DispatchFeed;
import com.afrotransact.fulfilment.port.DriverAccessRepository;
import com.afrotransact.fulfilment.port.DriverEventLog;
import com.afrotransact.fulfilment.port.DriverPositionRepository;
import com.afrotransact.fulfilment.port.GeoClient;
import com.afrotransact.fulfilment.port.IdIssuer;
import com.afrotransact.fulfilment.port.InspectionRepository;
import com.afrotransact.fulfilment.port.OrdersCommandQueue;
import com.afrotransact.fulfilment.port.ReturnPickupRepository;
import com.afrotransact.fulfilment.port.RouteRepository;
import com.afrotransact.fulfilment.port.SosRepository;
import com.afrotransact.fulfilment.port.TaskRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.events.InboxDeduplicator;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.test.tenant.CrossTenant;
import com.afrotransact.platform.test.tenant.TenantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Driver-self and staff-only isolation of fulfilment with the production RBAC checkers: driver B never reaches
 * driver A's routes or event outcomes, and org members and shoppers never reach dispatch or the fulfilment centre.
 */
class FulfilmentTenantIsolationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC);

    private final TenantFixture tenants = new TenantFixture();
    private final RouteRepository routes = mock(RouteRepository.class);
    private final DriverAccessRepository drivers = mock(DriverAccessRepository.class);
    private FulfilmentAccess access;
    private DriverAccessServiceImpl driverAccess;

    @BeforeEach
    void setUp() {
        access = new FulfilmentAccess(tenants.permissions(), tenants.resolver());
        driverAccess = new DriverAccessServiceImpl(access, drivers, routes, mock(InboxDeduplicator.class),
                mock(DomainEventPublisher.class), mock(DispatchFeed.class), CLOCK);
        for (String id : List.of("drv-a", "drv-b")) {
            when(drivers.find(id)).thenReturn(Optional.of(new DriverAccess(id, DriverAccessStatus.ACTIVE,
                    Instant.parse("2026-01-01T00:00:00Z"), "granted", AccessSource.TOKEN)));
        }
    }

    // ----------------------------------------------------------------- DRIVER_SELF

    @Test
    void aDriverListsOnlyRoutesAssignedToTheTokenSubject() {
        when(routes.openForDriver(any())).thenReturn(List.of());
        DriverQueryServiceImpl queries = new DriverQueryServiceImpl(driverAccess, routes);
        tenants.as(TenantFixture.driver("drv-b", "delivery:operate"));

        queries.myRoutes(null);

        verify(routes).openForDriver("drv-b");
        verify(routes, never()).openForDriver("drv-a");
    }

    @Test
    void driverBCannotReadDriverAsRouteAndLearnsNothingAboutItsExistence() {
        DeliveryRoute route = mock(DeliveryRoute.class);
        when(route.driverId()).thenReturn("drv-a");
        when(routes.find("route-a")).thenReturn(Optional.of(route));
        DriverQueryServiceImpl queries = new DriverQueryServiceImpl(driverAccess, routes);
        tenants.as(TenantFixture.driver("drv-b", "delivery:operate"));

        CrossTenant.assertDenied(() -> queries.myRoute(null, "route-a"));
        CrossTenant.assertDenied(() -> queries.myRoute(null, "route-missing"));
    }

    @Test
    void aReplayedEventIdOfAnotherDriverIsNeverAnsweredWithTheirStoredOutcome() {
        DriverEventLog log = mock(DriverEventLog.class);
        DriverEventLog.Entry theirs = new DriverEventLog.Entry("evt-1", "dev-a", 1, "drv-a", "stop.arrived", "route-a",
                "stop-1", Instant.now(), Instant.now(), "REJECTED", "fulfilment.label-unknown",
                "Label 123 is not on route route-a", "{}");
        when(log.find("evt-1")).thenReturn(Optional.of(theirs));
        DriverEventApplierImpl applier = new DriverEventApplierImpl(log, null, routes,
                mock(DeliveryShipmentRepository.class), mock(InspectionRepository.class), mock(SosRepository.class),
                null, null, null, null, mock(OrdersRelayService.class), mock(DomainEventPublisher.class),
                mock(DispatchFeed.class), null, CLOCK, mock(PlatformTransactionManager.class),
                mock(ReturnPickupRepository.class), "UTC");
        DriverEvent event = mock(DriverEvent.class);
        when(event.eventId()).thenReturn("evt-1");
        DriverContext driverB = new DriverContext("drv-b", drivers.find("drv-b").orElseThrow(), false);

        EventResult result = applier.apply(driverB, "dev-b", event, null);

        assertThat(result.message()).doesNotContain("route-a");
        assertThat(result.code()).isNotEqualTo("fulfilment.label-unknown");
        verify(log, never()).record(any());
    }

    @Test
    void aLocationPingIsStoredForTheTokenSubjectOnly() {
        DriverPositionRepository positions = mock(DriverPositionRepository.class);
        DriverLocationServiceImpl locations = new DriverLocationServiceImpl(driverAccess, positions, CLOCK);
        tenants.as(TenantFixture.driver("drv-b", "delivery:operate"));

        locations.ping(new LocationPing("dev", 29.7, -95.3, null, null), null);

        verify(positions).upsertIfNewer(argThat(p -> "drv-b".equals(p.driverId())));
    }

    // ----------------------------------------------------------------- STAFF_ONLY

    @Test
    void dispatchFulfilmentCentreAndReturnPickupsRefuseOrgMembersShoppersAndDriversWithoutPermission() {
        DispatchServiceImpl dispatch = new DispatchServiceImpl(access, routes, mock(DeliveryShipmentRepository.class),
                mock(CentreRepository.class), null, mock(RoutePlanner.class), mock(GeoClient.class),
                mock(IdIssuer.class), mock(SosRepository.class), mock(InspectionRepository.class),
                mock(DriverEventLog.class), mock(OrdersCommandQueue.class), driverAccess,
                mock(DomainEventPublisher.class), mock(DispatchFeed.class), CLOCK, mock(ReturnPickupRepository.class));
        CentreServiceImpl centres = new CentreServiceImpl(access, mock(CentreRepository.class));
        TaskServiceImpl tasks = new TaskServiceImpl(access, mock(TaskRepository.class), mock(CentreRepository.class),
                mock(DeliveryShipmentRepository.class), mock(IdIssuer.class), mock(OrdersRelayService.class),
                mock(DomainEventPublisher.class), CLOCK);
        ReturnPickupServiceImpl returns = new ReturnPickupServiceImpl(mock(ReturnPickupRepository.class), routes,
                access, mock(DomainEventPublisher.class), mock(DispatchFeed.class), "", CLOCK);
        List<Actor> outsiders = List.of(
                TenantFixture.sellerOf(TenantFixture.ORG_A, "orders:read", "orders:update-status"),
                TenantFixture.providerOf(TenantFixture.ORG_B, "bookings:manage"),
                TenantFixture.customer(TenantFixture.CUSTOMER_1),
                TenantFixture.driver("drv-a", "delivery:operate"));
        for (Actor outsider : outsiders) {
            tenants.as(outsider);
            CrossTenant.assertDenied(() -> dispatch.list(null, null, "drv-b", 50));
            CrossTenant.assertDenied(() -> dispatch.get("route-a"));
            CrossTenant.assertDenied(() -> dispatch.openSos());
            CrossTenant.assertDenied(() -> dispatch.driverEvents(null, "drv-b", 50));
            CrossTenant.assertDenied(() -> dispatch.suspendDriver("drv-b", "x"));
            CrossTenant.assertDenied(() -> centres.list());
            CrossTenant.assertDenied(() -> tasks.list(null, null, 50));
            CrossTenant.assertDenied(() -> returns.get("rp-1"));
            CrossTenant.assertDenied(() -> returns.list(null, null, 50));
        }
        verify(routes, never()).find(any());
    }
}

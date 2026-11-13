package com.afrotransact.bookings.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.bookings.application.ProviderSetupService.SettingsCommand;
import com.afrotransact.bookings.application.ServiceAreaService.AreaCommand;
import com.afrotransact.bookings.application.ServiceCatalogService.ServiceCommand;
import com.afrotransact.bookings.application.TeamService.MemberCommand;
import com.afrotransact.bookings.domain.BookedService;
import com.afrotransact.bookings.domain.Booking;
import com.afrotransact.bookings.domain.BookingRules;
import com.afrotransact.bookings.domain.CancellationPolicy;
import com.afrotransact.bookings.domain.CustomerContact;
import com.afrotransact.bookings.domain.DayOverride;
import com.afrotransact.bookings.domain.PolicyKind;
import com.afrotransact.bookings.domain.Provider;
import com.afrotransact.bookings.domain.Review;
import com.afrotransact.bookings.domain.ReviewStatus;
import com.afrotransact.bookings.domain.ServiceMode;
import com.afrotransact.bookings.domain.ServiceOffering;
import com.afrotransact.bookings.domain.TakeHomeCalculator;
import com.afrotransact.bookings.domain.TeamMember;
import com.afrotransact.bookings.domain.TeamRole;
import com.afrotransact.bookings.domain.WeeklyHours;
import com.afrotransact.bookings.port.BookingRepository;
import com.afrotransact.bookings.port.GeoClient;
import com.afrotransact.bookings.port.IdempotencyStore;
import com.afrotransact.bookings.port.OfferingRepository;
import com.afrotransact.bookings.port.OrgVisibilityRepository;
import com.afrotransact.bookings.port.PaymentsClient;
import com.afrotransact.bookings.port.ProviderRepository;
import com.afrotransact.bookings.port.ReviewRepository;
import com.afrotransact.bookings.port.ServiceAreaConfigRepository;
import com.afrotransact.bookings.port.ServiceAreaRepository;
import com.afrotransact.bookings.port.TeamRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.tenant.CrossTenant;
import com.afrotransact.platform.test.tenant.TenantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cross-tenant tests for the bookings use cases, with the real application services, the real RBAC checkers
 * (production wiring: no OpenFGA) and mocked ports. Tenant A is a provider organisation, tenant B another one;
 * customer 1 and customer 2 are shoppers. Named by tenant-isolation-matrix.txt.
 */
class BookingsTenantIsolationTest {

    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    private static final Instant START = NOW.plus(Duration.ofDays(5));
    private static final String[] PROVIDER_PERMISSIONS = {BookingsPermissions.READ, BookingsPermissions.MANAGE,
        BookingsPermissions.SERVICES_WRITE, BookingsPermissions.AVAILABILITY_WRITE, BookingsPermissions.TEAM_MANAGE};

    private final TenantFixture fx = new TenantFixture();
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final OfferingRepository offerings = mock(OfferingRepository.class);
    private final ProviderRepository providers = mock(ProviderRepository.class);
    private final TeamRepository team = mock(TeamRepository.class);
    private final ReviewRepository reviews = mock(ReviewRepository.class);
    private final ServiceAreaRepository areas = mock(ServiceAreaRepository.class);
    private final ServiceAreaConfigRepository areaConfig = mock(ServiceAreaConfigRepository.class);
    private final IdempotencyStore idempotency = mock(IdempotencyStore.class);
    private final PaymentsClient payments = mock(PaymentsClient.class);
    private final OrgVisibilityRepository visibility = mock(OrgVisibilityRepository.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);

    private BookingService bookingService;
    private ProviderSetupService setup;
    private ServiceCatalogService catalog;
    private TeamService teamService;
    private AvailabilityService availability;
    private ReviewService reviewService;
    private ServiceAreaService areaService;

    private TeamMember memberA;
    private TeamMember memberB;
    private ServiceOffering serviceA;
    private ServiceOffering serviceB;
    private Booking bookingA;
    private Booking bookingB;

    @BeforeEach
    void wire() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        BookingsAccess access = new BookingsAccess(fx.permissions(), fx.resolver());
        BookingsSettings settings = mock(BookingsSettings.class);
        bookingService = new BookingServiceImpl(bookings, offerings, providers, team, idempotency, payments,
                mock(SlotFinder.class), mock(BookingMoney.class), mock(BookingEventPublisher.class),
                mock(PaymentOutcomes.class), mock(TakeHomeCalculator.class),
                mock(ProviderFees.class), visibility, access, settings, clock);
        setup = new ProviderSetupServiceImpl(providers, access, clock);
        catalog = new ServiceCatalogServiceImpl(offerings, providers, team,
                mock(TakeHomeCalculator.class), mock(ProviderFees.class), access,
                events, mock(ServiceAlertService.class), clock);
        teamService = new TeamServiceImpl(team, providers, access, clock);
        availability = new AvailabilityServiceImpl(team, providers, bookings, access, clock);
        reviewService = new ReviewServiceImpl(bookings, reviews, team, offerings, visibility, access,
                fx.permissions(), events, clock);
        areaService = new ServiceAreaServiceImpl(areas, areaConfig, offerings, providers, mock(GeoClient.class),
                access, mock(ServiceAlertService.class), clock);

        memberA = TeamMember.add(TenantFixture.ORG_A, "prov-org-a", "Ada", TeamRole.OWNER, true,
                WeeklyHours.closed(), NOW);
        memberB = TeamMember.add(TenantFixture.ORG_B, "prov-org-b", "Bee", TeamRole.OWNER, true,
                WeeklyHours.closed(), NOW);
        serviceA = offering(TenantFixture.ORG_A);
        serviceB = offering(TenantFixture.ORG_B);
        bookingA = booking(TenantFixture.ORG_A, memberA, TenantFixture.CUSTOMER_1, serviceA);
        bookingB = booking(TenantFixture.ORG_B, memberB, TenantFixture.CUSTOMER_2, serviceB);

        when(bookings.find(bookingA.id())).thenReturn(Optional.of(bookingA));
        when(bookings.lock(bookingA.id())).thenReturn(Optional.of(bookingA));
        when(bookings.find(bookingB.id())).thenReturn(Optional.of(bookingB));
        when(bookings.lock(bookingB.id())).thenReturn(Optional.of(bookingB));
        when(team.find(memberA.id())).thenReturn(Optional.of(memberA));
        when(team.lock(memberA.id())).thenReturn(Optional.of(memberA));
        when(team.find(memberB.id())).thenReturn(Optional.of(memberB));
        when(team.lock(memberB.id())).thenReturn(Optional.of(memberB));
        when(team.byOrg(TenantFixture.ORG_A)).thenReturn(List.of(memberA));
        when(team.byOrg(TenantFixture.ORG_B)).thenReturn(List.of(memberB));
        when(offerings.find(serviceA.id())).thenReturn(Optional.of(serviceA));
        when(offerings.find(serviceB.id())).thenReturn(Optional.of(serviceB));
        when(offerings.byOrg(TenantFixture.ORG_A, false)).thenReturn(List.of(serviceA));
        when(offerings.byOrg(TenantFixture.ORG_B, false)).thenReturn(List.of(serviceB));
        when(providers.find(TenantFixture.ORG_A)).thenReturn(Optional.of(provider(TenantFixture.ORG_A)));
        when(providers.find(TenantFixture.ORG_B)).thenReturn(Optional.of(provider(TenantFixture.ORG_B)));
        when(providers.lock(TenantFixture.ORG_A)).thenReturn(Optional.of(provider(TenantFixture.ORG_A)));
        when(providers.lock(TenantFixture.ORG_B)).thenReturn(Optional.of(provider(TenantFixture.ORG_B)));
        when(bookings.forCustomer(TenantFixture.CUSTOMER_1)).thenReturn(List.of(bookingA));
        when(bookings.forCustomer(TenantFixture.CUSTOMER_2)).thenReturn(List.of(bookingB));
        when(bookings.forProvider(eq(TenantFixture.ORG_A), any(), any(), any(), any()))
                .thenReturn(List.of(bookingA));
        when(bookings.forProvider(eq(TenantFixture.ORG_B), any(), any(), any(), any()))
                .thenReturn(List.of(bookingB));
        when(reviews.forBooking(bookingB.id())).thenReturn(Optional.of(reviewOf(bookingB)));
        when(reviews.byOrg(eq(TenantFixture.ORG_A), any(), anyInt(), anyInt()))
                .thenReturn(new ReviewRepository.ReviewPage(List.of(), 0, 0, 20));
    }

    private Actor providerA() {
        return TenantFixture.providerOf(TenantFixture.ORG_A, PROVIDER_PERMISSIONS);
    }

    private Actor providerB() {
        return TenantFixture.providerOf(TenantFixture.ORG_B, PROVIDER_PERMISSIONS);
    }

    // ------------------------------------------------------------------ booking by id: both sides

    @Test
    void positiveControlOwnersReachTheirBooking() {
        fx.as(TenantFixture.customer(TenantFixture.CUSTOMER_2));
        assertThat(bookingService.get(bookingB.id()).id()).isEqualTo(bookingB.id());
        fx.as(providerB());
        assertThat(bookingService.get(bookingB.id()).id()).isEqualTo(bookingB.id());
        assertThat(bookingService.forProvider(TenantFixture.ORG_B, START, START.plusSeconds(60), null, null))
                .containsExactly(bookingB);
    }

    @Test
    void foreignProviderAndForeignCustomerCannotReadOrActOnAnotherTenantsBooking() {
        UUID id = bookingB.id();
        for (Actor foreign : List.of(providerA(), TenantFixture.customer(TenantFixture.CUSTOMER_1,
                BookingsPermissions.READ, BookingsPermissions.MANAGE))) {
            fx.as(foreign);
            CrossTenant.assertDenied(() -> bookingService.get(id));
            CrossTenant.assertDenied(() -> bookingService.history(id));
            CrossTenant.assertDenied(() -> bookingService.syncPayment(id));
            CrossTenant.assertDenied(() -> bookingService.balancePayment(id));
            CrossTenant.assertDenied(() -> bookingService.cancellationQuote(id));
            CrossTenant.assertDenied(() -> bookingService.cancel(id, "x", "k1"));
            CrossTenant.assertDenied(() -> bookingService.reschedule(id, START, "k2"));
            CrossTenant.assertDenied(() -> bookingService.acceptNewTime(id, "k3"));
            CrossTenant.assertDenied(() -> bookingService.declineNewTime(id, "k4"));
            CrossTenant.assertDenied(() -> bookingService.confirm(id, "k5"));
            CrossTenant.assertDenied(() -> bookingService.decline(id, "x", "k6"));
            CrossTenant.assertDenied(() -> bookingService.proposeTime(id, START, "k7"));
            CrossTenant.assertDenied(() -> bookingService.complete(id, "k8"));
            CrossTenant.assertDenied(() -> bookingService.noShow(id, "k9"));
            CrossTenant.assertDenied(() -> reviewService.rate(id, 5, "great"));
        }
        verify(bookings, never()).update(any());
        verifyNoInteractions(payments);
    }

    @Test
    void customerCannotActAsProviderOnTheirOwnBooking() {
        fx.as(TenantFixture.customer(TenantFixture.CUSTOMER_1));
        CrossTenant.assertDenied(() -> bookingService.confirm(bookingA.id(), "k"));
        CrossTenant.assertDenied(() -> bookingService.complete(bookingA.id(), "k"));
        CrossTenant.assertDenied(() -> bookingService.noShow(bookingA.id(), "k"));
    }

    @Test
    void providerCannotActAsCustomerOnItsOwnOrgsBooking() {
        fx.as(providerA());
        CrossTenant.assertDenied(() -> bookingService.reschedule(bookingA.id(), START, "k"));
        CrossTenant.assertDenied(() -> reviewService.rate(bookingA.id(), 5, "self review"));
    }

    @Test
    void idempotencyReplayOfAnotherTenantsKeyDoesNotLeakTheirBooking() {
        when(idempotency.claim(any(), any(), any())).thenReturn(Optional.of(bookingB.id()));
        fx.as(providerA());
        CrossTenant.assertDenied(() -> bookingService.confirm(bookingB.id(), "shared-key"));
        fx.as(TenantFixture.customer(TenantFixture.CUSTOMER_1));
        CrossTenant.assertDenied(() -> bookingService.cancel(bookingB.id(), null, "shared-key"));
    }

    @Test
    void createBookingIsForCustomersOnlyAndTheCustomerIdComesFromTheToken() {
        fx.as(providerA());
        CrossTenant.assertDenied(() -> bookingService.create(new BookingService.CreateBooking(serviceB.id(), null,
                START, ServiceMode.IN_PERSON, List.of(), new CustomerContact("X", "x@example.com", null), null,
                null, "web"), "k"));
        fx.as(TenantFixture.staff());
        CrossTenant.assertDenied(() -> bookingService.create(new BookingService.CreateBooking(serviceB.id(), null,
                START, ServiceMode.IN_PERSON, List.of(), new CustomerContact("X", "x@example.com", null), null,
                null, "web"), "k"));
    }

    @Test
    void mineListsOnlyTheCallersBookingsAndRefusesProviders() {
        fx.as(TenantFixture.customer(TenantFixture.CUSTOMER_1));
        List<Booking> mine = bookingService.mine();
        CrossTenant.assertOnlyOwnedBy(mine, Booking::customerId, TenantFixture.CUSTOMER_1);
        assertThat(mine).containsExactly(bookingA);
        verify(bookings, never()).forCustomer(TenantFixture.CUSTOMER_2);
        fx.as(providerA());
        CrossTenant.assertDenied(() -> bookingService.mine());
    }

    @Test
    void practitionerSeesAndActsOnlyOnTheirOwnBookings() {
        TeamMember other = TeamMember.add(TenantFixture.ORG_A, "prac-2", "Zed", TeamRole.PRACTITIONER, true,
                WeeklyHours.closed(), NOW);
        TeamMember me = TeamMember.add(TenantFixture.ORG_A, "prac-1", "Pat", TeamRole.PRACTITIONER, true,
                WeeklyHours.closed(), NOW);
        Booking mine = booking(TenantFixture.ORG_A, me, TenantFixture.CUSTOMER_1, serviceA);
        Booking theirs = booking(TenantFixture.ORG_A, other, TenantFixture.CUSTOMER_2, serviceA);
        when(team.find(me.id())).thenReturn(Optional.of(me));
        when(team.find(other.id())).thenReturn(Optional.of(other));
        when(team.byUser(TenantFixture.ORG_A, "prac-1")).thenReturn(Optional.of(me));
        when(bookings.find(mine.id())).thenReturn(Optional.of(mine));
        when(bookings.find(theirs.id())).thenReturn(Optional.of(theirs));
        when(bookings.forProvider(eq(TenantFixture.ORG_A), any(), any(), any(), any()))
                .thenReturn(List.of(mine, theirs));
        fx.as(new Actor("prac-1", PrincipalType.PROVIDER_MEMBER, Optional.of(TenantFixture.ORG_A),
                Set.of(BookingsPermissions.ROLE_PRACTITIONER),
                Set.of(BookingsPermissions.READ, BookingsPermissions.MANAGE)));
        assertThat(bookingService.get(mine.id())).isSameAs(mine);
        CrossTenant.assertDenied(() -> bookingService.get(theirs.id()));
        assertThat(bookingService.forProvider(TenantFixture.ORG_A, START, START.plusSeconds(60), null, null))
                .containsExactly(mine);
    }

    // ------------------------------------------------------------------ provider org routes: orgId is a claim

    @Test
    void providerOrgRoutesRefuseAnotherOrganisationWithoutTouchingItsData() {
        String b = TenantFixture.ORG_B;
        fx.as(providerA());
        CrossTenant.assertDenied(() -> setup.get(b));
        CrossTenant.assertDenied(() -> setup.saveSettings(b, new SettingsCommand("Evil", ZoneOffset.UTC, "USD",
                null, null)));
        CrossTenant.assertDenied(() -> setup.savePolicy(b, PolicyKind.STRICT, null));
        CrossTenant.assertDenied(() -> catalog.list(b));
        CrossTenant.assertDenied(() -> catalog.get(b, serviceB.id()));
        CrossTenant.assertDenied(() -> catalog.create(b, serviceCommand()));
        CrossTenant.assertDenied(() -> catalog.update(b, serviceB.id(), serviceCommand()));
        CrossTenant.assertDenied(() -> catalog.setActive(b, serviceB.id(), false));
        CrossTenant.assertDenied(() -> catalog.assignMembers(b, serviceB.id(), Set.of(memberB.id())));
        CrossTenant.assertDenied(() -> teamService.list(b));
        CrossTenant.assertDenied(() -> teamService.add(b, memberCommand()));
        CrossTenant.assertDenied(() -> teamService.update(b, memberB.id(), memberCommand()));
        CrossTenant.assertDenied(() -> availability.setHours(b, memberB.id(), WeeklyHours.closed()));
        CrossTenant.assertDenied(() -> availability.overrides(b, memberB.id(), LocalDate.now(), LocalDate.now()));
        CrossTenant.assertDenied(() -> availability.putOverride(b, memberB.id(),
                DayOverride.timeOff(LocalDate.now(), null)));
        CrossTenant.assertDenied(() -> availability.removeOverride(b, memberB.id(), LocalDate.now()));
        CrossTenant.assertDenied(() -> availability.calendar(b, LocalDate.now(), LocalDate.now(), null));
        CrossTenant.assertDenied(() -> bookingService.forProvider(b, START, START.plusSeconds(60), null, null));
        CrossTenant.assertDenied(() -> bookingService.earnings(b, START, START.plusSeconds(60)));
        CrossTenant.assertDenied(() -> reviewService.providerReviews(b, ReviewStatus.PENDING, 0, 20));
        CrossTenant.assertDenied(() -> reviewService.reply(b, bookingB.id(), "thanks"));
        CrossTenant.assertDenied(() -> areaService.get(b));
        CrossTenant.assertDenied(() -> areaService.save(b, new AreaCommand("77001", 10, List.of())));
        CrossTenant.assertDenied(() -> areaService.preview(b, "77001", 10));
        verifyNoInteractions(areas, areaConfig);
        verify(providers, never()).update(any());
        verify(providers, never()).insert(any());
        verify(offerings, never()).update(any());
        verify(offerings, never()).insert(any());
        verify(team, never()).update(any());
        verify(team, never()).insert(any());
        verify(team, never()).putOverride(any(), any());
        verify(team, never()).deleteOverride(any(), any());
        verify(bookings, never()).forProvider(eq(b), any(), any(), any(), any());
    }

    @Test
    void customersAndBlankOrgActorsHaveNoProviderOrgAccess() {
        fx.as(TenantFixture.customer(TenantFixture.CUSTOMER_1, PROVIDER_PERMISSIONS));
        CrossTenant.assertDenied(() -> setup.get(TenantFixture.ORG_A));
        CrossTenant.assertDenied(() -> bookingService.forProvider(TenantFixture.ORG_A, START, START, null, null));
        fx.as(new Actor("prov-x", PrincipalType.PROVIDER_MEMBER, Optional.empty(), Set.of("provider.owner"),
                Set.of(PROVIDER_PERMISSIONS)));
        CrossTenant.assertDenied(() -> teamService.list(TenantFixture.ORG_A));
        CrossTenant.assertDenied(() -> catalog.list(TenantFixture.ORG_A));
    }

    @Test
    void foreignIdsInsideOwnOrgPathAreNotFound() {
        String a = TenantFixture.ORG_A;
        fx.as(providerA());
        CrossTenant.assertDenied(() -> catalog.get(a, serviceB.id()));
        CrossTenant.assertDenied(() -> catalog.update(a, serviceB.id(), serviceCommand()));
        CrossTenant.assertDenied(() -> catalog.setActive(a, serviceB.id(), true));
        CrossTenant.assertDenied(() -> catalog.assignMembers(a, serviceB.id(), Set.of()));
        CrossTenant.assertDenied(() -> catalog.assignMembers(a, serviceA.id(), Set.of(memberB.id())));
        CrossTenant.assertDenied(() -> teamService.update(a, memberB.id(), memberCommand()));
        CrossTenant.assertDenied(() -> availability.setHours(a, memberB.id(), WeeklyHours.closed()));
        CrossTenant.assertDenied(() -> availability.overrides(a, memberB.id(), LocalDate.now(), LocalDate.now()));
        CrossTenant.assertDenied(() -> availability.putOverride(a, memberB.id(),
                DayOverride.timeOff(LocalDate.now(), null)));
        CrossTenant.assertDenied(() -> availability.removeOverride(a, memberB.id(), LocalDate.now()));
        CrossTenant.assertDenied(() -> availability.calendar(a, LocalDate.now(), LocalDate.now(), memberB.id()));
        CrossTenant.assertDenied(() -> reviewService.reply(a, bookingB.id(), "thanks"));
        verify(offerings, never()).update(any());
        verify(team, never()).update(any());
        verify(team, never()).putOverride(any(), any());
        verify(reviews, never()).update(any());
    }

    @Test
    void providerListsAreFilteredByTheActorsOrganisation() {
        fx.as(providerA());
        List<Booking> rows = bookingService.forProvider(TenantFixture.ORG_A, START, START.plusSeconds(60), null,
                null);
        CrossTenant.assertOnlyOwnedBy(rows, Booking::orgId, TenantFixture.ORG_A);
        assertThat(bookingService.earnings(TenantFixture.ORG_A, START, START.plusSeconds(60)).orgId())
                .isEqualTo(TenantFixture.ORG_A);
        CrossTenant.assertOnlyOwnedBy(catalog.list(TenantFixture.ORG_A), v -> v.offering().orgId(),
                TenantFixture.ORG_A);
        CrossTenant.assertOnlyOwnedBy(teamService.list(TenantFixture.ORG_A), TeamMember::orgId,
                TenantFixture.ORG_A);
        verify(bookings, never()).forProvider(eq(TenantFixture.ORG_B), any(), any(), any(), any());
        verify(offerings, never()).byOrg(eq(TenantFixture.ORG_B), anyBoolean());
        verify(team, never()).byOrg(TenantFixture.ORG_B);
    }

    @Test
    void moderationAndStaffAreaRoutesRefuseProviders() {
        fx.as(providerA());
        CrossTenant.assertDenied(() -> reviewService.moderationQueue(ReviewStatus.PENDING, 0, 20));
        CrossTenant.assertDenied(() -> reviewService.forModeration(bookingB.id()));
        CrossTenant.assertDenied(() -> reviewService.approve(bookingB.id(), null));
    }

    // ------------------------------------------------------------------ data

    private static ServiceCommand serviceCommand() {
        return new ServiceCommand("Braids", "desc", "hair & beauty", 60, 5000, 1000, Set.of(ServiceMode.IN_PERSON),
                List.of(), null);
    }

    private static MemberCommand memberCommand() {
        return new MemberCommand("prov-x", "Mallory", TeamRole.OWNER, true, true, null);
    }

    private static Provider provider(String org) {
        return Provider.create(org, "Provider " + org, ZoneOffset.UTC, "USD", null, BookingRules.defaults(), NOW);
    }

    private static ServiceOffering offering(String org) {
        return new ServiceOffering(UUID.randomUUID(), org, "Knotless braids", "Hair included", "hair & beauty", 120,
                18_000, 4_000, "USD", Set.of(ServiceMode.IN_PERSON), List.of(), Set.of(), null, true, NOW, NOW, 0);
    }

    private static Booking booking(String org, TeamMember member, String customerId, ServiceOffering offering) {
        BookingRules rules = new BookingRules(720, 30, 30, 30, 3, false, false);
        Booking b = Booking.hold(UUID.randomUUID(), "BK-" + UUID.randomUUID().toString().substring(0, 6), org,
                member.id(), customerId, new CustomerContact("Customer " + customerId, customerId + "@example.com",
                        "+17135550142"),
                BookedService.of(offering, List.of(), ServiceMode.IN_PERSON, "Provider " + org, member.displayName()),
                null, null, "web", START, rules, CancellationPolicy.preset(PolicyKind.MODERATE),
                NOW.plus(Duration.ofMinutes(15)), NOW);
        b.attachPayment(UUID.randomUUID(), "pi_secret");
        return b;
    }

    private static Review reviewOf(Booking b) {
        return new Review(b.id(), b.orgId(), b.service().serviceId(), b.service().name(), b.customerId(), "Rev", 5,
                "great", NOW, ReviewStatus.APPROVED, null, null, 0);
    }
}

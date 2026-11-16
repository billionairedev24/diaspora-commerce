package com.afrotransact.deliveryruns.adapter.in.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.afrotransact.deliveryruns.api.DeliveryRunsApi;
import com.afrotransact.deliveryruns.application.AvailableRun;
import com.afrotransact.deliveryruns.application.DeliveryFeeService;
import com.afrotransact.deliveryruns.application.PreorderPublicService;
import com.afrotransact.deliveryruns.application.PublicRunService;
import com.afrotransact.deliveryruns.application.ReserveCommand;
import com.afrotransact.deliveryruns.application.RunSlotService;
import com.afrotransact.deliveryruns.domain.Capacity;
import com.afrotransact.deliveryruns.domain.CutoffPolicy;
import com.afrotransact.deliveryruns.domain.DeliveryWindow;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.FeeMethod;
import com.afrotransact.deliveryruns.domain.FeeSnapshot;
import com.afrotransact.deliveryruns.domain.FeeSource;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.LocationDetails;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunTerms;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.test.InProcessKit;
import com.afrotransact.platform.web.error.ConflictException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The published delivery-runs API: the use cases behind the endpoints orders and fulfilment call, entered under the caller's scopes. */
class DeliveryRunsApiAdapterTest {

    private static final Instant NOW = Instant.parse("2026-10-19T15:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-10-23T01:00:00Z");
    private static final CallerPrincipal ORDERS = InProcessKit.caller("orders", DeliveryRunsApi.RESERVE);

    private final PublicRunService publicRuns = mock(PublicRunService.class);
    private final RunSlotService slots = mock(RunSlotService.class);
    private final PreorderPublicService preorders = mock(PreorderPublicService.class);
    private final DeliveryFeeService fees = mock(DeliveryFeeService.class);
    private final DeliveryRunsApi api = new DeliveryRunsApiAdapter(publicRuns, slots, preorders, fees, InProcessKit.ACTORS);

    private <T> T asOrders(Supplier<T> call) {
        return InProcessCalls.as(ORDERS, "delivery-runs", call);
    }

    private static Run openRun() {
        RunTerms terms = new RunTerms(new DeliveryWindow(Instant.parse("2026-10-24T15:00:00Z"), Instant.parse("2026-10-24T19:00:00Z")),
                CUTOFF, null, CUTOFF.plus(Duration.ofHours(10)), 799, "USD", 5, Capacity.UNLIMITED, DropMode.DOORSTEP, null,
                CutoffPolicy.CANCEL, 7);
        return Run.draft(UUID.randomUUID(), UUID.randomUUID(), terms, null, NOW).open(NOW);
    }

    @Test
    void openRunsCarryWhatCheckoutReadsFromTheHttpAnswer() {
        Run run = openRun();
        Location location = mock(Location.class);
        LocationDetails details = mock(LocationDetails.class);
        when(location.details()).thenReturn(details);
        when(details.name()).thenReturn("Bastrop");
        when(details.timeZone()).thenReturn(ZoneId.of("America/Chicago"));
        FeeSnapshot rule = new FeeSnapshot(FeeMethod.AFROTRANSACT_DELIVERY, UUID.randomUUID(), 3, "USD", 799, FeeSource.RULE,
                "0-10 mi", null);
        when(publicRuns.available("78602", null, null, 0)).thenReturn(List.of(new AvailableRun(run, location, null, false, "u", rule)));
        List<DeliveryRunsApi.OpenRun> runs = asOrders(() -> api.openRuns("78602", 0));
        assertThat(runs).singleElement().satisfies(r -> {
            assertThat(r.runId()).isEqualTo(run.id().toString());
            assertThat(r.town()).isEqualTo("Bastrop");
            assertThat(r.cutoffAt()).isEqualTo(CUTOFF);
            assertThat(r.deliversOn()).hasToString("2026-10-24");
            assertThat(r.timeZone()).isEqualTo("America/Chicago");
            assertThat(r.feeMinor()).isEqualTo(799);
            assertThat(r.dropMode()).isEqualTo("DOORSTEP");
            assertThat(r.minOrders()).isEqualTo(5);
            assertThat(r.full()).isFalse();
            assertThat(r.feeRule().method()).isEqualTo("AFROTRANSACT_DELIVERY");
            assertThat(r.feeRule().ruleVersion()).isEqualTo(3);
        });
    }

    @Test
    void earlyAccessHoursAreClampedLikeTheEndpoint() {
        when(publicRuns.available("78602", null, null, 720)).thenReturn(List.of());
        assertThat(asOrders(() -> api.openRuns("78602", 5000))).isEmpty();
        verify(publicRuns).available("78602", null, null, 720);
    }

    @Test
    void aReservationAsksForExactlyThatRunWithOnePackageAndTheCharge() {
        UUID run = UUID.randomUUID();
        asOrders(() -> {
            api.reserve(run.toString(), "o1", "c1", 48, new DeliveryRunsApi.RunCharge(799L, 2500L, "DOOR"));
            return null;
        });
        ArgumentCaptor<ReserveCommand> sent = ArgumentCaptor.forClass(ReserveCommand.class);
        verify(slots).reserve(sent.capture());
        ReserveCommand c = sent.getValue();
        assertThat(c.runId()).isEqualTo(run);
        assertThat(c.orderId()).isEqualTo("o1");
        assertThat(c.customerId()).isEqualTo("c1");
        assertThat(c.packages()).isEqualTo(1);
        assertThat(c.allowNextRun()).isFalse();
        assertThat(c.earlyAccessHours()).isEqualTo(48);
        assertThat(c.charge().feeMinor()).isEqualTo(799L);
        assertThat(c.charge().totalMinor()).isEqualTo(2500L);
    }

    @Test
    void aFullOrClosedRunIsThe409OfTheEndpointAndAnUnknownRunIdIs400() {
        UUID run = UUID.randomUUID();
        when(slots.reserve(any())).thenThrow(new ConflictException("delivery-runs.run-full", "The run is full"));
        assertThatThrownBy(() -> asOrders(() -> {
            api.reserve(run.toString(), "o1", "c1", 0, null);
            return null;
        })).isInstanceOfSatisfying(ModuleApiException.class, e -> {
            assertThat(e.status()).isEqualTo(409);
            assertThat(e.code()).isEqualTo("delivery-runs.run-full");
        });
        assertThatThrownBy(() -> asOrders(() -> {
            api.release("not-a-uuid", "o1");
            return null;
        })).isInstanceOfSatisfying(ModuleApiException.class, e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    void noCarrierRuleIsEmptyAndARuleCarriesItsSnapshot() {
        when(fees.carrier("USD", null, 1000)).thenReturn(Optional.empty());
        assertThat(asOrders(() -> api.carrierFee("USD", null, 1000))).isEmpty();
        when(fees.carrier("USD", 900L, 1000)).thenReturn(Optional.of(new FeeSnapshot(FeeMethod.CARRIER, null, 0, "USD", 1200,
                FeeSource.CARRIER_QUOTE, null, null)));
        assertThat(asOrders(() -> api.carrierFee("USD", 900L, 1000))).get().satisfies(r -> {
            assertThat(r.method()).isEqualTo("CARRIER");
            assertThat(r.amountMinor()).isEqualTo(1200);
            assertThat(r.source()).isEqualTo("CARRIER_QUOTE");
        });
    }

    @Test
    void eligibilityNeedsCountryRegionAndAtinsAsTheEndpointDoes() {
        assertThatThrownBy(() -> asOrders(() -> api.eligibility("US", null, "Lubbock", List.of("A"), 0)))
                .isInstanceOfSatisfying(ModuleApiException.class, e -> assertThat(e.status()).isEqualTo(400));
        assertThatThrownBy(() -> asOrders(() -> api.eligibility("US", "TX", "Lubbock", List.of(), 0)))
                .isInstanceOfSatisfying(ModuleApiException.class, e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    void everyServiceEndpointRefusesACallerWithoutItsScope() {
        DeliveryRunsApi guarded = new DeliveryRunsApiAdapter(InProcessKit.guarded(PublicRunService.class, "x"),
                InProcessKit.guarded(RunSlotService.class, DeliveryRunsApi.RESERVE),
                InProcessKit.guarded(PreorderPublicService.class, DeliveryRunsApi.RESERVE),
                InProcessKit.guarded(DeliveryFeeService.class, DeliveryRunsApi.RESERVE), InProcessKit.ACTORS);
        String run = UUID.randomUUID().toString();
        InProcessKit.assertForbidden(InProcessKit.unscoped("orders"), () -> guarded.reserve(run, "o", "c", 0, null));
        InProcessKit.assertForbidden(InProcessKit.unscoped("orders"), () -> guarded.release(run, "o"));
        InProcessKit.assertForbidden(InProcessKit.unscoped("orders"), () -> guarded.eligibility("US", "TX", "L", List.of("A"), 0));
        InProcessKit.assertForbidden(InProcessKit.unscoped("orders"), () -> guarded.carrierFee("USD", null, 1));
        InProcessKit.assertForbidden(InProcessKit.unscoped("fulfilment"), () -> guarded.dispatch(run));
        assertThat(new CallerPrincipal("orders", "orders-service", Set.of(DeliveryRunsApi.RESERVE)).scopes())
                .contains(DeliveryRunsApi.RESERVE);
    }
}

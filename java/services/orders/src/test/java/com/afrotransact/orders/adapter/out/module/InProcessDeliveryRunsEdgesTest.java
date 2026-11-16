package com.afrotransact.orders.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.deliveryruns.api.DeliveryRunsApi;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.orders.domain.PreorderStage;
import com.afrotransact.orders.port.DeliveryFeeRules;
import com.afrotransact.orders.port.DeliveryRunSlots;
import com.afrotransact.orders.port.PreorderEligibility;
import com.afrotransact.orders.port.UpstreamFailure;
import org.junit.jupiter.api.Test;

/** Orders reaches delivery-runs through its published API as its own principal and reads the answers like the HTTP adapters do. */
class InProcessDeliveryRunsEdgesTest {

    private final List<String> calls = new ArrayList<>();
    private ModuleApiException failure;
    private List<DeliveryRunsApi.OpenRun> open = List.of();
    private List<DeliveryRunsApi.Eligibility> eligibility = List.of();
    private Optional<DeliveryRunsApi.FeeRule> carrier = Optional.empty();

    private final DeliveryRunsApi api = new DeliveryRunsApi() {
        @Override
        public List<OpenRun> openRuns(String zip, int earlyAccessHours) {
            calls.add("openRuns " + zip + " " + earlyAccessHours + " as " + InProcessCalls.require().clientId());
            fail();
            return open;
        }

        @Override
        public void reserve(String runId, String orderId, String customerId, int earlyAccessHours, RunCharge charge) {
            calls.add("reserve " + runId + " " + orderId + " " + earlyAccessHours + " " + charge);
            fail();
        }

        @Override
        public void release(String runId, String orderId) {
            calls.add("release " + runId + " " + orderId);
            fail();
        }

        @Override
        public List<Eligibility> eligibility(String country, String region, String city, List<String> atins, int hours) {
            calls.add("eligibility " + country + " " + region + " " + city + " " + atins + " " + hours);
            fail();
            return eligibility;
        }

        @Override
        public Optional<FeeRule> carrierFee(String currency, Long carrierQuoteMinor, long itemsMinor) {
            calls.add("carrierFee " + currency + " " + carrierQuoteMinor + " " + itemsMinor);
            fail();
            return carrier;
        }

        @Override
        public RunPlan dispatch(String runId) {
            throw new UnsupportedOperationException();
        }

        private void fail() {
            if (failure != null) {
                throw failure;
            }
        }
    };

    private final DeliveryRunSlots slots = InProcessWiring.runSlots(api);
    private final PreorderEligibility preorders = InProcessWiring.preorders(api);
    private final DeliveryFeeRules rules = InProcessWiring.feeRules(api);

    private static DeliveryRunsApi.FeeRule rule() {
        return new DeliveryRunsApi.FeeRule("AFROTRANSACT_DELIVERY", "r-1", 3, "USD", 799, "RULE", "0-10 mi", new BigDecimal("4.2"));
    }

    @Test
    void openRunsBecomeCheckoutRunsAndSkipThoseWithoutACutoffOrDay() {
        open = List.of(
                new DeliveryRunsApi.OpenRun("run-1", "Bastrop", Instant.parse("2026-10-23T01:00:00Z"), LocalDate.parse("2026-10-24"),
                        "America/Chicago", 799, null, "DOORSTEP", rule(), 5, 2, false),
                new DeliveryRunsApi.OpenRun("run-2", "Elgin", Instant.parse("2026-10-23T01:00:00Z"), LocalDate.parse("2026-10-24"),
                        "America/Chicago", 0, "USD", "PICKUP_POINT", null, 0, 9, true),
                new DeliveryRunsApi.OpenRun("run-3", "Nowhere", null, LocalDate.parse("2026-10-24"), null, 0, "USD", "DOORSTEP", null,
                        0, 0, false));
        List<DeliveryRunSlots.OpenRun> runs = slots.openRuns("78602", 24);
        assertThat(calls).containsExactly("openRuns 78602 24 as orders-service");
        assertThat(runs).extracting(DeliveryRunSlots.OpenRun::runId).containsExactly("run-1", "run-2");
        assertThat(runs.get(0).currency()).isEqualTo("USD");
        assertThat(runs.get(0).door()).isTrue();
        assertThat(runs.get(0).feeRule().ruleVersion()).isEqualTo(3);
        assertThat(runs.get(1).pickup()).isTrue();
        assertThat(runs.get(1).full()).isTrue();
    }

    @Test
    void aClosedOrFullRunIsRunUnavailableAndOtherRefusalsKeepTheirStatus() {
        failure = new ModuleApiException(409, "delivery-runs.run-full", "full");
        assertThatThrownBy(() -> slots.reserve("run-1", "o1", "c1")).isInstanceOfSatisfying(DeliveryRunSlots.RunUnavailable.class,
                e -> assertThat(e.code()).isEqualTo("orders.run-full"));
        failure = new ModuleApiException(409, "delivery-runs.run-closed", "closed");
        assertThatThrownBy(() -> slots.reserve("run-1", "o1", "c1")).isInstanceOfSatisfying(DeliveryRunSlots.RunUnavailable.class,
                e -> assertThat(e.code()).isEqualTo("orders.run-closed"));
        failure = new ModuleApiException(403, "auth.missing-permission", "no");
        assertThatThrownBy(() -> slots.reserve("run-1", "o1", "c1")).isInstanceOfSatisfying(UpstreamFailure.class,
                e -> assertThat(e.status()).isEqualTo(403));
    }

    @Test
    void releasingASlotTheOrderDoesNotHoldIsFine() {
        failure = new ModuleApiException(404, "delivery-runs.run-order-not-found", "none");
        slots.release("run-1", "o1");
        failure = new ModuleApiException(500, "x", "boom");
        assertThatThrownBy(() -> slots.release("run-1", "o1")).isInstanceOf(UpstreamFailure.class);
    }

    @Test
    void aReservationPassesTheHoursAndTheCharge() {
        slots.reserve("run-1", "o1", "c1", 24, new DeliveryRunSlots.RunCharge(799, 2500, "DOOR"));
        assertThat(calls).containsExactly("reserve run-1 o1 24 RunCharge[feeMinor=799, totalMinor=2500, kind=DOOR]");
    }

    @Test
    void eligibilityTakesTheHttpAdaptersDefaultsForWhatIsNotSent() {
        eligibility = List.of(
                new DeliveryRunsApi.Eligibility("A1", true, "w-1", "LUB-7", "Lubbock Pre-order", "OPEN", LocalDate.parse("2026-10-25"),
                        Instant.parse("2026-10-23T04:59:00Z"), true, true, 799L, 0L, "USD", Set.of("CARD", "ach"), "pp-1", rule(), null),
                new DeliveryRunsApi.Eligibility("A2", false, null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null),
                new DeliveryRunsApi.Eligibility("A3", true, "w-2", null, null, "OPEN", null, null, false, null, null, null, null,
                        null, null, null, null));
        Map<String, PreorderEligibility.Line> lines = preorders.eligibility("US", "TX", "Lubbock", List.of("A1", "A2", "A3"));
        assertThat(calls).containsExactly("eligibility US TX Lubbock [A1, A2, A3] 0");
        PreorderEligibility.Line a1 = lines.get("A1");
        assertThat(a1.preorder()).isTrue();
        assertThat(a1.stage()).isEqualTo(PreorderStage.OPEN);
        assertThat(a1.allowedMethods()).containsExactlyInAnyOrder("CARD", "ACH");
        assertThat(a1.doorFeeRule().ruleId()).isEqualTo("r-1");
        PreorderEligibility.Line a2 = lines.get("A2");
        assertThat(a2.preorder()).isFalse();
        assertThat(a2.door()).isTrue();
        assertThat(a2.pickup()).isFalse();
        assertThat(a2.doorFeeMinor()).isEqualTo(PreorderEligibility.DEFAULT_DOOR_FEE_MINOR);
        assertThat(a2.pickupFeeMinor()).isZero();
        assertThat(a2.currency()).isEqualTo("USD");
        assertThat(a2.stage()).isEqualTo(PreorderStage.UNAVAILABLE);
        // a window with no delivery day is never a pre-order
        assertThat(lines.get("A3").preorder()).isFalse();
        assertThat(preorders.eligibility("US", "TX", "Lubbock", List.of())).isEmpty();
    }

    @Test
    void theCarrierRuleIsASnapshotAndAnyFailureFallsBackToFlatRates() {
        carrier = Optional.of(new DeliveryRunsApi.FeeRule("CARRIER", null, 0, "USD", 1200, "CARRIER_QUOTE", null, null));
        assertThat(rules.carrier("USD", 900L, 5000)).get().satisfies(r -> {
            assertThat(r.method()).isEqualTo("CARRIER");
            assertThat(r.amountMinor()).isEqualTo(1200);
            assertThat(r.ruleId()).isNull();
        });
        failure = new ModuleApiException(502, "delivery-runs.geo-unavailable", "geo");
        assertThat(rules.carrier("USD", null, 5000)).isEmpty();
    }
}

package com.afrotransact.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import com.afrotransact.ai.application.SpendTodayService.SpendToday;
import com.afrotransact.ai.domain.SpendLimits;
import com.afrotransact.ai.port.UsageLedger;
import com.afrotransact.ai.port.UsageLedger.FeatureDayUsage;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.Test;

class SpendTodayServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-15T23:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);
    private static final Caller STAFF = new Caller("staff-1", "STAFF", null, Set.of("ai:manage"), null);

    private final UsageLedger ledger = mock(UsageLedger.class);
    private final SpendTodayServiceImpl service = new SpendTodayServiceImpl(ledger,
            new SpendLimits(2_000_000, 500_000, 20, 120), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void sumsTheLedgerByFeatureAgainstTheCapAndSaysWhenTheDayResets() {
        when(ledger.dayUsageByFeature(TODAY)).thenReturn(List.of(
                new FeatureDayUsage("lola", 12, 9_000, 1_250_000), new FeatureDayUsage("listing-writer", 3, 2_000, 150_000)));

        SpendToday today = service.today(STAFF);

        assertThat(today.day()).isEqualTo(TODAY);
        assertThat(today.capMicros()).isEqualTo(2_000_000);
        assertThat(today.callerCapMicros()).isEqualTo(500_000);
        assertThat(today.spentMicros()).isEqualTo(1_400_000);
        assertThat(today.calls()).isEqualTo(15);
        assertThat(today.tokens()).isEqualTo(11_000);
        assertThat(today.hasData()).isTrue();
        assertThat(today.byFeature()).extracting(SpendTodayService.FeatureSpend::feature).containsExactly("lola", "listing-writer");
        assertThat(today.resetsAt()).isEqualTo(Instant.parse("2026-11-16T00:00:00Z"));
    }

    @Test
    void aDayWithNoCallsIsZeroWithNoDataAndNothingIsInvented() {
        when(ledger.dayUsageByFeature(TODAY)).thenReturn(List.of());

        SpendToday today = service.today(STAFF);

        assertThat(today.spentMicros()).isZero();
        assertThat(today.calls()).isZero();
        assertThat(today.hasData()).isFalse();
        assertThat(today.byFeature()).isEmpty();
        assertThat(today.capMicros()).isEqualTo(2_000_000);
    }

    @Test
    void usesTheUtcDayNotTheServersLocalDay() {
        SpendTodayServiceImpl late = new SpendTodayServiceImpl(ledger, new SpendLimits(2_000_000, 0, 20, 120),
                Clock.fixed(Instant.parse("2026-11-15T23:59:59Z"), ZoneOffset.ofHours(9)));
        when(ledger.dayUsageByFeature(TODAY)).thenReturn(List.of());

        assertThat(late.today(STAFF).day()).isEqualTo(TODAY);
    }

    @Test
    void onlyStaffWithAiManageMayRead() {
        for (Caller caller : List.of(new Caller("u", "CUSTOMER", null, Set.of("ai:manage"), null),
                new Caller("s", "STAFF", null, Set.of("ai:use"), null),
                new Caller("svc", "SERVICE", null, Set.of("ai:usage.read"), null))) {
            assertThatThrownBy(() -> service.today(caller)).isInstanceOf(ForbiddenActionException.class);
        }
    }
}

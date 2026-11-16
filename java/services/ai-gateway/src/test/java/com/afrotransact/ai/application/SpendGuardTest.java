package com.afrotransact.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;

import com.afrotransact.ai.domain.AiFeature;
import com.afrotransact.ai.domain.BudgetExceededException;
import com.afrotransact.ai.domain.RateLimitedException;
import com.afrotransact.ai.domain.SpendLimits;
import com.afrotransact.ai.domain.UsageTotals;
import com.afrotransact.ai.port.AiMetrics;
import com.afrotransact.ai.port.CallRateLimiter;
import com.afrotransact.ai.port.UsageLedger;
import org.junit.jupiter.api.Test;

class SpendGuardTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);
    private static final SpendLimits OWNER_LIMITS = new SpendLimits(500_000, 20, 120);

    private final UsageLedger ledger = mock(UsageLedger.class);
    private final CallRateLimiter limiter = mock(CallRateLimiter.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final Caller shopper = new Caller("shopper-1", "CUSTOMER", null, Set.of(), null);
    private final Caller media = new Caller("media-service", "SERVICE", null, Set.of("ai:use"), null);

    private final AiSpendCapPolicy cap = mock(AiSpendCapPolicy.class);

    private SpendGuardImpl guard(SpendLimits limits) {
        return guard(limits, 2_000_000);
    }

    private SpendGuardImpl guard(SpendLimits limits, long dailyCapMicros) {
        when(cap.capMicros()).thenReturn(dailyCapMicros);
        when(ledger.globalTotals(TODAY)).thenReturn(UsageTotals.ZERO);
        when(ledger.callerTotals(anyString(), eq(TODAY))).thenReturn(UsageTotals.ZERO);
        return new SpendGuardImpl(ledger, limiter, limits, cap, mock(AiMetrics.class), clock);
    }

    @Test
    void allowsCallsBelowEveryLimit() {
        SpendGuardImpl guard = guard(OWNER_LIMITS);
        when(ledger.globalTotals(TODAY)).thenReturn(new UsageTotals(1, 1_999_999));
        when(ledger.callerTotals("shopper-1", TODAY)).thenReturn(new UsageTotals(1, 499_999));
        assertThatCode(() -> guard.check(shopper, AiFeature.LOLA)).doesNotThrowAnyException();
    }

    @Test
    void refusesEveryoneOnceTheTwoDollarCeilingIsReached() {
        SpendGuardImpl guard = guard(OWNER_LIMITS);
        when(ledger.globalTotals(TODAY)).thenReturn(new UsageTotals(5_000, 2_000_000));
        assertThatThrownBy(() -> guard.check(shopper, AiFeature.LOLA))
                .isInstanceOfSatisfying(BudgetExceededException.class, ex -> {
                    assertThat(ex.scope()).isEqualTo("global");
                    assertThat(ex.feature()).isEqualTo("lola");
                });
        assertThatThrownBy(() -> guard.check(media, AiFeature.IMAGE_MODERATION))
                .isInstanceOf(BudgetExceededException.class);
    }

    @Test
    void oneCallerCannotUseMoreThanItsShare() {
        SpendGuardImpl guard = guard(OWNER_LIMITS);
        when(ledger.callerTotals("shopper-1", TODAY)).thenReturn(new UsageTotals(9_000, 500_000));
        assertThatThrownBy(() -> guard.check(shopper, AiFeature.LOLA))
                .isInstanceOfSatisfying(BudgetExceededException.class, ex -> assertThat(ex.scope()).isEqualTo("caller"));
        // Another caller is unaffected.
        assertThatCode(() -> guard.check(media, AiFeature.IMAGE_MODERATION)).doesNotThrowAnyException();
    }

    @Test
    void callRateIsPerCallerAndHigherForServiceClients() {
        SpendGuardImpl guard = guard(OWNER_LIMITS);
        when(limiter.acquire("shopper-1", 20)).thenReturn(17L);
        when(limiter.acquire("media-service", 120)).thenReturn(0L);
        assertThatThrownBy(() -> guard.check(shopper, AiFeature.LOLA))
                .isInstanceOfSatisfying(RateLimitedException.class, ex -> assertThat(ex.retryAfterSeconds()).isEqualTo(17));
        assertThatCode(() -> guard.check(media, AiFeature.IMAGE_MODERATION)).doesNotThrowAnyException();
    }

    @Test
    void zeroCallerShareAndRatesMeanUnlimited() {
        SpendGuardImpl guard = guard(SpendLimits.unlimited());
        when(ledger.callerTotals("shopper-1", TODAY)).thenReturn(new UsageTotals(1, Long.MAX_VALUE / 2));
        assertThatCode(() -> guard.check(shopper, AiFeature.LOLA)).doesNotThrowAnyException();
    }

    @Test
    void aDailyCapOfZeroSwitchesAiOffEvenWithNothingSpent() {
        SpendGuardImpl guard = guard(OWNER_LIMITS, 0);
        assertThatThrownBy(() -> guard.check(shopper, AiFeature.LOLA))
                .isInstanceOfSatisfying(BudgetExceededException.class, ex -> {
                    assertThat(ex.scope()).isEqualTo("global");
                    assertThat(ex.getMessage()).contains("switched off");
                });
    }

    @Test
    void loweringTheCapBelowTodaysSpendBlocksTheNextCallAndRaisingItReopensTheSameDay() {
        SpendGuardImpl guard = guard(OWNER_LIMITS, 2_000_000);
        when(ledger.globalTotals(TODAY)).thenReturn(new UsageTotals(10, 1_500_000));
        assertThatCode(() -> guard.check(shopper, AiFeature.LOLA)).doesNotThrowAnyException();

        when(cap.capMicros()).thenReturn(1_000_000L);
        assertThatThrownBy(() -> guard.check(shopper, AiFeature.LOLA))
                .isInstanceOfSatisfying(BudgetExceededException.class, ex -> assertThat(ex.scope()).isEqualTo("global"));

        when(cap.capMicros()).thenReturn(5_000_000L);
        assertThatCode(() -> guard.check(shopper, AiFeature.LOLA)).doesNotThrowAnyException();
    }
}

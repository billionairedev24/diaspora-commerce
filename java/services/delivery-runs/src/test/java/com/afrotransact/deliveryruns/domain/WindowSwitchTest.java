package com.afrotransact.deliveryruns.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import com.afrotransact.deliveryruns.domain.PreorderResolver.ProductFacts;
import org.junit.jupiter.api.Test;

/** Each pre-order has its own on/off switch; the master switch is a global kill switch that never rewrites them. */
class WindowSwitchTest {

    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    private static final PaymentSettings PAYMENT = PaymentSettings.DEFAULT;

    private final PreorderResolver resolver = new PreorderResolver();

    private static Coverage coverage(boolean master, CoverageArea... areas) {
        return new Coverage(new PreorderSettings(master, Fallback.SHIP, EnumSet.allOf(Placement.class), PAYMENT, null,
                0), List.of(areas));
    }

    private static CoverageArea on(String key) {
        return new CoverageArea(AreaKey.parse(key), true);
    }

    private static PreorderWindow window(String target, Instant opens, Instant closes) {
        return Preorders.published(Preorders.withDates(Preorders.details(List.of(target)), opens, closes,
                LocalDate.ofInstant(closes.plus(Duration.ofDays(2)), Preorders.CHICAGO)));
    }

    private final PreorderWindow lubbock = window("US/TX/Lubbock", NOW.minus(Duration.ofDays(3)),
            NOW.plus(Duration.ofDays(9)));
    private final PreorderWindow elPaso = window("US/TX/El Paso", NOW.plus(Duration.ofDays(4)),
            NOW.plus(Duration.ofDays(20)));
    private final Coverage areas = coverage(true, on("US/TX/Lubbock"), on("US/TX/El Paso"));

    @Test
    void pausingOneWindowLeavesTheOthersOn() {
        PreorderWindow paused = lubbock.switchTo(false, PAYMENT, null, NOW);

        assertThat(paused.active()).isFalse();
        assertThat(WindowSwitch.of(paused, areas, NOW)).isEqualTo(WindowSwitch.PAUSED);
        assertThat(WindowSwitch.of(elPaso, areas, NOW)).isEqualTo(WindowSwitch.ON);
        assertThat(elPaso.active()).as("untouched").isTrue();
    }

    @Test
    void theMasterSwitchHoldsEveryWindowOffWithoutRewritingThem() {
        PreorderWindow paused = lubbock.switchTo(false, PAYMENT, null, NOW);
        Coverage masterOff = coverage(false, on("US/TX/Lubbock"), on("US/TX/El Paso"));

        assertThat(WindowSwitch.of(elPaso, masterOff, NOW)).isEqualTo(WindowSwitch.GLOBAL_OFF);
        assertThat(WindowSwitch.of(paused, masterOff, NOW)).as("its own switch wins the label")
                .isEqualTo(WindowSwitch.PAUSED);
        assertThat(elPaso.active()).as("the window keeps its own state").isTrue();

        // Master back on: each window is back to its own state.
        assertThat(WindowSwitch.of(elPaso, areas, NOW)).isEqualTo(WindowSwitch.ON);
        assertThat(WindowSwitch.of(paused, areas, NOW)).isEqualTo(WindowSwitch.PAUSED);
    }

    @Test
    void effectiveStateIsMasterAndWindowAndArea() {
        Coverage lubbockOff = coverage(true, new CoverageArea(AreaKey.parse("US/TX/Lubbock"), false),
                on("US/TX/El Paso"));
        assertThat(WindowSwitch.of(lubbock, lubbockOff, NOW)).isEqualTo(WindowSwitch.AREA_OFF);
        for (boolean master : new boolean[] {true, false}) {
            for (boolean window : new boolean[] {true, false}) {
                PreorderWindow w = window ? lubbock : lubbock.switchTo(false, PAYMENT, null, NOW);
                Coverage c = coverage(master, on("US/TX/Lubbock"));
                assertThat(WindowSwitch.of(w, c, NOW).live()).as("master %s, window %s", master, window)
                        .isEqualTo(master && window);
                assertThat(resolver.resolve("US", "TX", "Lubbock", List.of(w), c, Map.of(), false, NOW).on())
                        .isEqualTo(master && window);
            }
        }
    }

    @Test
    void aPausedWindowLeavesResolutionEligibilityAndTheCartWhileOthersStay() {
        PreorderWindow paused = lubbock.switchTo(false, PAYMENT, null, NOW);
        List<PreorderWindow> all = List.of(paused, elPaso);

        Resolution lbk = resolver.resolve("US", "TX", "Lubbock", all, areas, Map.of(), false, NOW);
        assertThat(lbk.on()).isFalse();
        assertThat(lbk.window()).as("no banner, ribbon or countdown").isNull();
        assertThat(resolver.eligibility(lbk, new ProductFacts("A1", "Pantry", false, true)).preorder())
                .as("no pre-order package in the cart").isFalse();
        assertThat(resolver.openList(all, areas, NOW)).containsExactly(elPaso);

        Resolution elp = resolver.resolve("US", "TX", "El Paso", all, areas, Map.of(), false, NOW);
        assertThat(elp.on()).isTrue();
        assertThat(elp.window()).isEqualTo(elPaso);

        // Resuming restores exposure.
        PreorderWindow resumed = paused.switchTo(true, PAYMENT, null, NOW);
        Resolution back = resolver.resolve("US", "TX", "Lubbock", List.of(resumed, elPaso), areas, Map.of(), false,
                NOW);
        assertThat(back.on()).isTrue();
        assertThat(back.stage()).isEqualTo(PreorderStatus.OPEN);
    }

    @Test
    void switchingIsIdempotentAndRespectsTheStateMachine() {
        assertThat(lubbock.switchTo(true, PAYMENT, null, NOW)).isSameAs(lubbock);
        PreorderWindow paused = lubbock.switchTo(false, PAYMENT, null, NOW);
        assertThat(paused.switchTo(false, PAYMENT, null, NOW)).isSameAs(paused);
        assertThat(paused.switchTo(true, PAYMENT, null, NOW).active()).isTrue();

        PreorderWindow draft = Preorders.draft(Preorders.lubbock());
        assertThat(draft.switchable(NOW)).isFalse();
        assertThat(WindowSwitch.of(draft, areas, NOW)).isEqualTo(WindowSwitch.NOT_LIVE);
        assertThatThrownBy(() -> draft.switchTo(false, PAYMENT, null, NOW))
                .isInstanceOf(InvalidTransitionException.class);

        Instant delivered = lubbock.deliveredAt();
        assertThat(lubbock.switchable(delivered)).isFalse();
        assertThatThrownBy(() -> lubbock.switchTo(false, PAYMENT, null, delivered))
                .isInstanceOf(InvalidTransitionException.class);

        PreorderWindow closed = lubbock;
        Instant afterClose = lubbock.details().closesAt().plusSeconds(60);
        assertThat(closed.switchTo(false, PAYMENT, null, afterClose).status(afterClose))
                .as("a closed (delivering) window can still be held off the storefront")
                .isEqualTo(PreorderStatus.PAUSED);
    }

    @Test
    void switchingOnReRunsThePublishChecks() {
        PreorderWindow toronto = Preorders.published(Preorders.details(List.of("CA/ON/Toronto")))
                .switchTo(false, PAYMENT, null, NOW);
        PaymentSettings usdOnly = new PaymentSettings(Map.of("USD", 799L), Map.of(), PAYMENT.allowedMethods(), null,
                null, null, null);
        assertThatThrownBy(() -> toronto.switchTo(true, usdOnly, null, NOW))
                .isInstanceOf(PublishBlockedException.class);
        assertThatThrownBy(() -> toronto.resume(usdOnly, null, NOW)).isInstanceOf(PublishBlockedException.class);
        assertThat(toronto.switchTo(true, PAYMENT, null, NOW).active()).isTrue();
    }
}

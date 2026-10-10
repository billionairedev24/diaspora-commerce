package com.afrotransact.deliveryruns.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PreorderWindowTest {

    private static final PaymentSettings PAYMENT = PaymentSettings.DEFAULT;

    @Test
    void statusIsDerivedFromDraftPauseAndDates() {
        PreorderWindow w = Preorders.published();
        assertThat(w.status(Preorders.OPENS.minusSeconds(1))).isEqualTo(PreorderStatus.UPCOMING);
        assertThat(w.status(Preorders.OPENS)).isEqualTo(PreorderStatus.OPEN);
        assertThat(w.status(Preorders.CLOSES)).as("closing instant is still open").isEqualTo(PreorderStatus.OPEN);
        assertThat(w.status(Preorders.CLOSES.plusSeconds(1))).isEqualTo(PreorderStatus.CLOSED);
        // Delivered from the day after delivery, at the delivery day's reference time (local noon).
        Instant deliveredAt = Instant.parse("2026-10-26T17:00:00Z");
        assertThat(w.deliveredAt()).isEqualTo(deliveredAt);
        assertThat(w.status(deliveredAt.minusSeconds(1))).isEqualTo(PreorderStatus.CLOSED);
        assertThat(w.status(deliveredAt)).isEqualTo(PreorderStatus.DELIVERED);

        assertThat(w.pause(Preorders.OPENS).status(Preorders.OPENS)).isEqualTo(PreorderStatus.PAUSED);
        assertThat(Preorders.draft(Preorders.lubbock()).status(Preorders.OPENS)).isEqualTo(PreorderStatus.DRAFT);
    }

    @Test
    void countdownTargetsFollowTheStage() {
        PreorderWindow w = Preorders.published();
        assertThat(w.countdownTarget(PreorderStatus.OPEN)).isEqualTo(Preorders.CLOSES);
        assertThat(w.countdownTarget(PreorderStatus.UPCOMING)).isEqualTo(Preorders.OPENS);
        assertThat(w.countdownTarget(PreorderStatus.CLOSED)).isEqualTo(Instant.parse("2026-10-25T17:00:00Z"));
        assertThat(w.countdownTarget(PreorderStatus.DELIVERED)).isNull();
    }

    @Test
    void aCompleteDraftPassesEveryCheckAndPublishes() {
        PreorderWindow draft = Preorders.draft(Preorders.lubbock())
                .withCreative(CreativeSlot.HERO, Preorders.HERO, Preorders.OPENS);
        assertThat(draft.publishChecks(PAYMENT)).isEmpty();
        PreorderWindow published = draft.publish(PAYMENT, Preorders.OPENS);
        assertThat(published.draft()).isFalse();
        assertThat(published.active()).isTrue();
        assertThatThrownBy(() -> published.publish(PAYMENT, Preorders.OPENS))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void anEmptyDraftFailsEverySevenChecks() {
        WindowDetails d = new WindowDetails("", List.of(), Preorders.CLOSES, Preorders.OPENS,
                LocalDate.parse("2026-10-01"), Preorders.CHICAGO, "", "", "", null, false, false, null, null, true,
                null, null, null, null, null);
        PreorderWindow draft = Preorders.draft(d);
        assertThat(draft.publishChecks()).extracting(RuleViolation::code).containsExactly(
                "delivery-runs.preorder-name-required", "delivery-runs.preorder-area-required",
                "delivery-runs.preorder-closes-before-opens", "delivery-runs.preorder-delivers-before-close",
                "delivery-runs.preorder-hero-required", "delivery-runs.preorder-ribbon-required",
                "delivery-runs.preorder-fulfilment-required");
        assertThat(draft.publishChecks()).extracting(RuleViolation::getMessage).contains("Name set",
                "Storefront hero uploaded (required)", "At least one fulfilment option");
        assertThatThrownBy(() -> draft.publish(PAYMENT, Preorders.OPENS))
                .isInstanceOfSatisfying(PublishBlockedException.class, e -> {
                    assertThat(e.violations()).hasSize(7);
                    assertThat(e.getMessage()).isEqualTo("7 checks to fix before publishing");
                });
    }

    @Test
    void deliveryMayBeUpToTwelveHoursBeforeTheClose() {
        // Delivery day reference is local noon: closes at 23:59 on the delivery day passes (noon ≥ close − 12 h).
        Instant sameDayLate = Instant.parse("2026-10-25T23:59:00Z").plus(Duration.ofHours(5));
        WindowDetails ok = Preorders.withDates(Preorders.lubbock(), Preorders.OPENS,
                Instant.parse("2026-10-26T04:59:00Z"), LocalDate.parse("2026-10-25"));
        assertThat(Preorders.published(ok).publishChecks()).isEmpty();
        WindowDetails late = Preorders.withDates(Preorders.lubbock(), Preorders.OPENS, sameDayLate.plusSeconds(3600),
                LocalDate.parse("2026-10-25"));
        assertThat(Preorders.published(late).publishChecks()).extracting(RuleViolation::code)
                .containsExactly("delivery-runs.preorder-delivers-before-close");
    }

    @Test
    void aDoorWindowNeedsADoorFeeInItsCurrency() {
        PreorderWindow toronto = Preorders.published(Preorders.details(List.of("CA/ON/Toronto")));
        PaymentSettings usdOnly = new PaymentSettings(Map.of("USD", 799L), Map.of(), PAYMENT.allowedMethods(), null,
                null, null, null);
        assertThat(toronto.details().currency()).isEqualTo("CAD");
        assertThat(toronto.publishChecks(usdOnly)).extracting(RuleViolation::code)
                .containsExactly("delivery-runs.preorder-fee-missing");
        assertThat(toronto.doorFee(PAYMENT)).contains(new Money(999, "CAD"));
        assertThat(toronto.pickupFee(PAYMENT)).isEqualTo(new Money(0, "CAD"));
    }

    @Test
    void windowFeeOverridesWinOverPlatformFees() {
        WindowDetails d = Preorders.lubbock();
        WindowDetails overridden = new WindowDetails(d.name(), d.targets(), d.opensAt(), d.closesAt(),
                d.deliversOn(), d.timeZone(), d.ribbon(), d.headline(), d.smsCopy(), d.categories(), true, true, null,
                null, true, null, d.comms(), new Money(499, "USD"), new Money(150, "USD"), null);
        PreorderWindow w = Preorders.published(overridden);
        assertThat(w.doorFee(PAYMENT)).contains(new Money(499, "USD"));
        assertThat(w.pickupFee(PAYMENT)).isEqualTo(new Money(150, "USD"));
        assertThatThrownBy(() -> new WindowDetails(d.name(), d.targets(), d.opensAt(), d.closesAt(), d.deliversOn(),
                d.timeZone(), d.ribbon(), d.headline(), d.smsCopy(), d.categories(), true, true, null, null, true,
                null, d.comms(), new Money(10_001, "USD"), null, null))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("between 0 and 100.00");
        assertThatThrownBy(() -> new WindowDetails(d.name(), d.targets(), d.opensAt(), d.closesAt(), d.deliversOn(),
                d.timeZone(), d.ribbon(), d.headline(), d.smsCopy(), d.categories(), true, true, null, null, true,
                null, d.comms(), new Money(500, "CAD"), null, null))
                .isInstanceOf(RuleViolation.class).hasMessageContaining("USD");
    }

    @Test
    void pauseResumeAndEdits() {
        PreorderWindow w = Preorders.published();
        PreorderWindow paused = w.pause(Preorders.OPENS);
        assertThat(paused.active()).isFalse();
        assertThatThrownBy(() -> paused.pause(Preorders.OPENS)).isInstanceOf(InvalidTransitionException.class);
        assertThat(paused.resume(Preorders.OPENS).active()).isTrue();
        assertThatThrownBy(() -> w.resume(Preorders.OPENS)).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> Preorders.draft(Preorders.lubbock()).pause(Preorders.OPENS))
                .isInstanceOf(InvalidTransitionException.class);

        // A published window must keep passing its checks; a delivered one is read-only.
        WindowDetails noRibbon = new WindowDetails("Lubbock", w.details().targets(), Preorders.OPENS, Preorders.CLOSES,
                Preorders.DELIVERS, Preorders.CHICAGO, "", "", "", null, true, false, null, null, true, null, null,
                null, null, null);
        assertThatThrownBy(() -> w.edit(noRibbon, PAYMENT, Preorders.OPENS))
                .isInstanceOf(PublishBlockedException.class);
        Instant delivered = w.deliveredAt();
        assertThatThrownBy(() -> w.edit(w.details(), PAYMENT, delivered)).isInstanceOf(RuleViolation.class)
                .hasMessageContaining("delivered");
        assertThatThrownBy(() -> w.withoutCreative(CreativeSlot.HERO, Preorders.OPENS))
                .isInstanceOf(RuleViolation.class);
        assertThat(Preorders.draft(Preorders.lubbock()).withCreative(CreativeSlot.HERO, Preorders.HERO,
                Preorders.OPENS).withoutCreative(CreativeSlot.HERO, Preorders.OPENS).creatives()).isEmpty();
    }

    @Test
    void detailsValidateCopyLengths() {
        WindowDetails d = Preorders.lubbock();
        assertThatThrownBy(() -> new WindowDetails(d.name(), d.targets(), d.opensAt(), d.closesAt(), d.deliversOn(),
                d.timeZone(), "x".repeat(81), d.headline(), d.smsCopy(), d.categories(), true, true, null, null, true,
                null, null, null, null, null)).hasMessageContaining("80");
        assertThatThrownBy(() -> new WindowDetails(d.name(), d.targets(), d.opensAt(), d.closesAt(), d.deliversOn(),
                d.timeZone(), d.ribbon(), "x".repeat(141), d.smsCopy(), d.categories(), true, true, null, null, true,
                null, null, null, null, null)).hasMessageContaining("140");
        assertThatThrownBy(() -> new WindowDetails(d.name(), d.targets(), d.opensAt(), d.closesAt(), d.deliversOn(),
                d.timeZone(), d.ribbon(), d.headline(), "€".repeat(81), d.categories(), true, true, null, null, true,
                null, null, null, null, null)).hasMessageContaining("160 GSM-7");
        WindowDetails dup = Preorders.details(List.of("US/TX/Lubbock", "us/tx/LUBBOCK", "US/TX"));
        assertThat(dup.targets()).extracting(AreaKey::key).containsExactly("US/TX/Lubbock", "US/TX");
    }

    @Test
    void codePrefixIsTheFirstThreeLettersOfTheFirstTargetCity() {
        assertThat(PreorderWindow.codePrefix(List.of(AreaKey.parse("US/TX/Lubbock")))).isEqualTo("LUB");
        assertThat(PreorderWindow.codePrefix(List.of(AreaKey.parse("US/TX/El Paso")))).isEqualTo("ELP");
        assertThat(PreorderWindow.codePrefix(List.of(AreaKey.parse("CA/ON")))).isEqualTo("ON");
        assertThat(PreorderWindow.codePrefix(List.of())).isEqualTo("PRE");
    }

    @Test
    void creativesAreFlaggedWhenMoreThanTwelvePercentOffTheirRatio() {
        assertThat(new CreativeRef(UUID.randomUUID(), null, 1920, 640).ratioOff(CreativeSlot.HERO)).isFalse();
        assertThat(new CreativeRef(UUID.randomUUID(), null, 1800, 640).ratioOff(CreativeSlot.HERO))
                .as("2.81 vs 3: 6 % off").isFalse();
        assertThat(new CreativeRef(UUID.randomUUID(), null, 1600, 640).ratioOff(CreativeSlot.HERO))
                .as("2.5 vs 3: 17 % off").isTrue();
        assertThat(new CreativeRef(UUID.randomUUID(), null, 1080, 1080).ratioOff(CreativeSlot.STORY)).isTrue();
        assertThat(new CreativeRef(UUID.randomUUID(), null, 1080, 1920).ratioOff(CreativeSlot.STORY)).isFalse();
        assertThat(CreativeSlot.PDP.spec()).isEqualTo("1200 × 300");
    }

    @Test
    void smsLengthIsCountedInGsm7Septets() {
        assertThat(Gsm7.septets("Hello")).isEqualTo(5);
        assertThat(Gsm7.septets("€5 [x]")).isEqualTo(9);
        assertThat(Gsm7.septets("🎉")).isEqualTo(-1);
        assertThat(Gsm7.fitsOneSegment("a".repeat(160))).isTrue();
        assertThat(Gsm7.fitsOneSegment("a".repeat(161))).isFalse();
        assertThat(Gsm7.fitsOneSegment("🎉" + "a".repeat(70))).isFalse();
        assertThat(Gsm7.fitsOneSegment("🎉" + "a".repeat(69))).isTrue();
    }

    @Test
    void areaKeysParseAndCover() {
        AreaKey lubbock = AreaKey.parse("us/tx/Lubbock");
        assertThat(lubbock.key()).isEqualTo("US/TX/Lubbock");
        assertThat(lubbock.covers("US", "TX", "LUBBOCK")).isTrue();
        assertThat(lubbock.covers("US", "TX", "Amarillo")).isFalse();
        assertThat(AreaKey.parse("US/TX").covers("US", "tx", "Amarillo")).isTrue();
        assertThat(AreaKey.parse("CA/ON/Toronto").label()).isEqualTo("Toronto, ON · CA");
        assertThat(AreaKey.parse("US/TX").label()).isEqualTo("All of TX");
        assertThatThrownBy(() -> AreaKey.parse("USA/TX")).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> AreaKey.parse("US")).isInstanceOf(RuleViolation.class);
    }
}

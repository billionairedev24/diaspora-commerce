package com.afrotransact.deliveryruns.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A pre-order window: shoppers in its targets order between {@code opensAt} and {@code closesAt}; everything is
 * delivered on {@code deliversOn}, however many orders came in. At close its paid orders are handed to a run.
 *
 * @param id        window id
 * @param code      short public code, e.g. {@code LBK-7} (prefix = first 3 letters of the first target city)
 * @param details   what staff edit
 * @param creatives uploaded advertorial images per slot
 * @param draft     not published yet
 * @param active    false while paused
 * @param runId     the run the window's orders were handed to at close
 * @param createdAt created
 * @param updatedAt last change
 * @param version   optimistic lock
 */
public record PreorderWindow(UUID id, String code, WindowDetails details, Map<CreativeSlot, CreativeRef> creatives,
                             boolean draft, boolean active, UUID runId, Instant createdAt, Instant updatedAt,
                             long version) {

    /** Placeholder in the SMS copy that notifications replaces with the tracked link. */
    public static final String SMS_LINK = "{link}";

    /** Delivery may be at most this long before the close (the design allows the same day). */
    public static final Duration DELIVERY_GRACE = Duration.ofHours(12);

    public PreorderWindow {
        if (id == null || code == null || code.isBlank() || details == null) {
            throw new IllegalArgumentException("id, code and details are required");
        }
        creatives = creatives == null || creatives.isEmpty() ? Map.of() : Map.copyOf(creatives);
    }

    /** A new draft. */
    public static PreorderWindow draft(UUID id, String code, WindowDetails details, Instant now) {
        return new PreorderWindow(id, code, details, Map.of(), true, true, null, now, now, 0);
    }

    /** {@code LBK} for {@code US/TX/Lubbock}: letters of the first target's city (or region), three, upper case. */
    public static String codePrefix(List<AreaKey> targets) {
        if (targets == null || targets.isEmpty()) {
            return "PRE";
        }
        AreaKey first = targets.get(0);
        String source = first.isCity() ? first.city() : first.region();
        String letters = source.replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT);
        return letters.isEmpty() ? "PRE" : letters.substring(0, Math.min(3, letters.length()));
    }

    // ---- status ----------------------------------------------------------------------------------------------

    /** {@code status(w, now)} of the design. */
    public PreorderStatus status(Instant now) {
        if (draft) {
            return PreorderStatus.DRAFT;
        }
        if (!active) {
            return PreorderStatus.PAUSED;
        }
        return timeStatus(now);
    }

    /** The status by dates alone (ignoring draft and pause): upcoming, open, closed or delivered. */
    public PreorderStatus timeStatus(Instant now) {
        if (now.isBefore(details.opensAt())) {
            return PreorderStatus.UPCOMING;
        }
        if (!now.isAfter(details.closesAt())) {
            return PreorderStatus.OPEN;
        }
        if (now.isBefore(deliveredAt())) {
            return PreorderStatus.CLOSED;
        }
        return PreorderStatus.DELIVERED;
    }

    /** One day after the delivery day's reference time: the window counts as delivered from then on. */
    public Instant deliveredAt() {
        return details.deliversAt().plus(Duration.ofDays(1));
    }

    /** Countdown target: closes when open, opens when upcoming, the delivery day when closed; else null. */
    public Instant countdownTarget(PreorderStatus status) {
        return switch (status) {
            case OPEN -> details.closesAt();
            case UPCOMING -> details.opensAt();
            case CLOSED -> details.deliversAt();
            default -> null;
        };
    }

    public boolean covers(String country, String region, String city) {
        return details.targets().stream().anyMatch(t -> t.covers(country, region, city));
    }

    // ---- publish checks ------------------------------------------------------------------------------------

    /** The seven publish checks of the design, each failed one as a {@link RuleViolation}. */
    public List<RuleViolation> publishChecks() {
        List<RuleViolation> failed = new ArrayList<>();
        WindowDetails d = details;
        if (d.name().isBlank()) {
            failed.add(new RuleViolation("delivery-runs.preorder-name-required", "Name set"));
        }
        if (d.targets().isEmpty()) {
            failed.add(new RuleViolation("delivery-runs.preorder-area-required", "At least one pre-order area chosen"));
        }
        if (!d.closesAt().isAfter(d.opensAt())) {
            failed.add(new RuleViolation("delivery-runs.preorder-closes-before-opens",
                    "Closing date is after opening date"));
        }
        if (d.deliversAt().isBefore(d.closesAt().minus(DELIVERY_GRACE))) {
            failed.add(new RuleViolation("delivery-runs.preorder-delivers-before-close",
                    "Delivery date is on or after closing date"));
        }
        if (!creatives.containsKey(CreativeSlot.HERO)) {
            failed.add(new RuleViolation("delivery-runs.preorder-hero-required", "Storefront hero uploaded (required)"));
        }
        if (d.ribbon().isBlank()) {
            failed.add(new RuleViolation("delivery-runs.preorder-ribbon-required", "Ribbon text set"));
        }
        if (!d.door() && !d.pickup()) {
            failed.add(new RuleViolation("delivery-runs.preorder-fulfilment-required",
                    "At least one fulfilment option"));
        }
        return failed;
    }

    /** The design's checks plus the payment setup: a door window needs a door fee in its currency. */
    public List<RuleViolation> publishChecks(PaymentSettings payment) {
        List<RuleViolation> failed = publishChecks();
        if (details.door() && doorFee(payment).isEmpty()) {
            failed.add(new RuleViolation("delivery-runs.preorder-fee-missing",
                    "Door delivery fee set for " + details.currency()));
        }
        return failed;
    }

    /**
     * The announcement SMS as notifications sends it: {@code {link}} becomes {@code <pageUrl>?src=sms&po=<CODE>&cr=announce}.
     */
    public String smsAsSent(String pageUrl) {
        return details.smsCopy().replace(SMS_LINK, pageUrl + "?src=sms&po=" + code + "&cr=announce");
    }

    /** As {@link #publishChecks(PaymentSettings)}, plus: the announcement SMS fits one message with its tracked link. */
    public List<RuleViolation> publishChecks(PaymentSettings payment, String pageUrl) {
        List<RuleViolation> failed = publishChecks(payment);
        if (pageUrl != null && !Gsm7.fitsOneSegment(smsAsSent(pageUrl))) {
            failed.add(new RuleViolation("delivery-runs.preorder-sms-too-long",
                    "Announcement SMS fits one message with its tracked link"));
        }
        return failed;
    }

    // ---- fees ------------------------------------------------------------------------------------------------

    /** Effective door fee: the window override, else the platform fee in the window's currency. */
    public Optional<Money> doorFee(PaymentSettings payment) {
        if (details.doorFeeOverride() != null) {
            return Optional.of(details.doorFeeOverride());
        }
        return payment.doorFee(details.currency());
    }

    /** Effective pickup fee: the window override, else the platform fee (free by default). */
    public Money pickupFee(PaymentSettings payment) {
        return details.pickupFeeOverride() != null ? details.pickupFeeOverride()
                : payment.pickupFee(details.currency());
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------

    /** DRAFT → published (active). Every check must pass. */
    public PreorderWindow publish(PaymentSettings payment, Instant now) {
        return publish(payment, null, now);
    }

    /** DRAFT → published, also checking the announcement SMS with its link under {@code pageUrl} (null skips it). */
    public PreorderWindow publish(PaymentSettings payment, String pageUrl, Instant now) {
        if (!draft) {
            throw new InvalidTransitionException(status(now).name(), "PUBLISHED");
        }
        List<RuleViolation> failed = publishChecks(payment, pageUrl);
        if (!failed.isEmpty()) {
            throw new PublishBlockedException(failed);
        }
        return new PreorderWindow(id, code, details, creatives, false, true, runId, createdAt, now, version);
    }

    public PreorderWindow pause(Instant now) {
        PreorderStatus s = status(now);
        if (draft || !active || s == PreorderStatus.DELIVERED) {
            throw new InvalidTransitionException(s.name(), PreorderStatus.PAUSED.name());
        }
        return new PreorderWindow(id, code, details, creatives, false, false, runId, createdAt, now, version);
    }

    public PreorderWindow resume(Instant now) {
        PreorderStatus s = status(now);
        if (draft || active) {
            throw new InvalidTransitionException(s.name(), "ACTIVE");
        }
        return new PreorderWindow(id, code, details, creatives, false, true, runId, createdAt, now, version);
    }

    /**
     * Resume, re-running the publish checks first: a window only goes back on the storefront when it would still be
     * allowed to publish (e.g. its currency still has a door fee).
     */
    public PreorderWindow resume(PaymentSettings payment, String pageUrl, Instant now) {
        PreorderStatus s = status(now);
        if (draft || active || timeStatus(now) == PreorderStatus.DELIVERED) {
            throw new InvalidTransitionException(s.name(), "ACTIVE");
        }
        List<RuleViolation> failed = publishChecks(payment, pageUrl);
        if (!failed.isEmpty()) {
            throw new PublishBlockedException(failed);
        }
        return new PreorderWindow(id, code, details, creatives, false, true, runId, createdAt, now, version);
    }

    /** A published window that is not delivered yet has its own on/off switch (drafts and delivered ones do not). */
    public boolean switchable(Instant now) {
        return !draft && timeStatus(now) != PreorderStatus.DELIVERED;
    }

    /**
     * Sets this window's own switch (idempotent: the same state returns this window unchanged). Switching on runs the
     * publish checks ({@link #resume(PaymentSettings, String, Instant)}); drafts and delivered windows refuse.
     */
    public PreorderWindow switchTo(boolean on, PaymentSettings payment, String pageUrl, Instant now) {
        if (!switchable(now)) {
            throw new InvalidTransitionException(status(now).name(), on ? "ACTIVE" : PreorderStatus.PAUSED.name());
        }
        if (on == active) {
            return this;
        }
        return on ? resume(payment, pageUrl, now) : pause(now);
    }

    /** Changes the details; a published window must still pass its checks, a delivered one is read-only. */
    public PreorderWindow edit(WindowDetails changed, PaymentSettings payment, Instant now) {
        return edit(changed, payment, null, now);
    }

    /** As {@link #edit(WindowDetails, PaymentSettings, Instant)}, checking the SMS link under {@code pageUrl}. */
    public PreorderWindow edit(WindowDetails changed, PaymentSettings payment, String pageUrl, Instant now) {
        requireEditable(now);
        PreorderWindow next = new PreorderWindow(id, code, changed, creatives, draft, active, runId, createdAt, now,
                version);
        if (!draft) {
            List<RuleViolation> failed = next.publishChecks(payment, pageUrl);
            if (!failed.isEmpty()) {
                throw new PublishBlockedException(failed);
            }
        }
        return next;
    }

    public PreorderWindow withCreative(CreativeSlot slot, CreativeRef ref, Instant now) {
        requireEditable(now);
        Map<CreativeSlot, CreativeRef> next = new EnumMap<>(CreativeSlot.class);
        next.putAll(creatives);
        next.put(slot, ref);
        return new PreorderWindow(id, code, details, next, draft, active, runId, createdAt, now, version);
    }

    public PreorderWindow withoutCreative(CreativeSlot slot, Instant now) {
        requireEditable(now);
        if (!draft && slot.required()) {
            throw new RuleViolation("delivery-runs.preorder-hero-required",
                    "A published pre-order keeps its storefront hero; replace it instead");
        }
        Map<CreativeSlot, CreativeRef> next = new EnumMap<>(CreativeSlot.class);
        next.putAll(creatives);
        next.remove(slot);
        return new PreorderWindow(id, code, details, next, draft, active, runId, createdAt, now, version);
    }

    /** The window's orders were handed to {@code run}. */
    public PreorderWindow handedTo(UUID run, Instant now) {
        return new PreorderWindow(id, code, details, creatives, draft, active, run, createdAt, now, version);
    }

    private void requireEditable(Instant now) {
        if (!draft && timeStatus(now) == PreorderStatus.DELIVERED) {
            throw new RuleViolation("delivery-runs.preorder-not-editable", "A delivered pre-order cannot be changed");
        }
    }
}

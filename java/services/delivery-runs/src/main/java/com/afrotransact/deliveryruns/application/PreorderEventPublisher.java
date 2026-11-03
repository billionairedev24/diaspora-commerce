package com.afrotransact.deliveryruns.application;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.afrotransact.deliveryruns.domain.AreaChanges;
import com.afrotransact.deliveryruns.domain.AreaKey;
import com.afrotransact.deliveryruns.domain.CommKind;
import com.afrotransact.deliveryruns.domain.CreativeRef;
import com.afrotransact.deliveryruns.domain.CreativeSlot;
import com.afrotransact.deliveryruns.domain.DeliveryFees;
import com.afrotransact.deliveryruns.domain.FeeSnapshot;
import com.afrotransact.deliveryruns.domain.PaymentSettings;
import com.afrotransact.deliveryruns.domain.PreorderOrder;
import com.afrotransact.deliveryruns.domain.PreorderSettings;
import com.afrotransact.deliveryruns.domain.PreorderUtm;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.WindowDecision;
import com.afrotransact.deliveryruns.domain.WindowDetails;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.springframework.stereotype.Component;

/** Builds and publishes pre-order events through the transactional outbox (topic at.delivery-runs.events). */
@Component
class PreorderEventPublisher {

    static final String SETTINGS_KEY = "preorder-settings";

    /** Market-local, GSM-7 only: "Tue, Oct 6 9:00 AM". */
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("EEE, MMM d h:mm a", Locale.US);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US);

    private final DomainEventPublisher events;
    private final DeliveryRunsSettings settings;
    private final PreorderOptions options;
    private final FeeBook book;
    private final AdvertorialLifecycle advertorials;
    private final RunLinks rl;

    PreorderEventPublisher(DomainEventPublisher events, DeliveryRunsSettings settings, PreorderOptions options,
                           FeeBook book, AdvertorialLifecycle advertorials, RunLinks rl) {
        this.rl = rl;
        this.book = book;
        this.advertorials = advertorials;
        this.events = events;
        this.settings = settings;
        this.options = options;
    }

    void window(String type, PreorderWindow w, PaymentSettings payment, Instant now) {
        window(type, w, payment, now, null);
    }

    /** A staff change of the window; {@code actorId} lands in the audit log. */
    void window(String type, PreorderWindow w, PaymentSettings payment, Instant now, String actorId) {
        events.publish(type, DeliveryRunsEvents.AGGREGATE, w.id().toString(),
                payload(w, payment, null, null, now, null, actorId, null));
        advertorials.onWindow(type, w);
    }

    /**
     * A decision about the window ({@code decision_needed}, {@code decided}, {@code cancelled}); {@code orders} are
     * attached to {@code cancelled} (every order orders-service must refund).
     */
    void decision(String type, PreorderWindow w, PaymentSettings payment, Instant now, WindowDecision d,
                  List<PreorderOrder> orders) {
        List<PreorderEventPayload.OrderRef> refs = orders == null ? null : orders.stream()
                .filter(PreorderOrder::counts)
                .map(o -> new PreorderEventPayload.OrderRef(o.orderId(), o.customerId(), o.fulfilment().name()))
                .toList();
        events.publish(type, DeliveryRunsEvents.AGGREGATE, w.id().toString(), payload(w, payment, null, null, now,
                refs, WindowDecision.SYSTEM.equals(d.actor()) ? null : d.actor(), d));
        advertorials.onWindow(type, w);
    }

    /** {@code orders} (paid) are attached to CLOSED. */
    void commsDue(PreorderWindow w, CommKind kind, Instant dueAt, PaymentSettings payment, Instant now,
                  List<PreorderOrder> orders) {
        List<PreorderEventPayload.OrderRef> refs = orders == null ? null : orders.stream().filter(PreorderOrder::counts)
                .map(o -> new PreorderEventPayload.OrderRef(o.orderId(), o.customerId(), o.fulfilment().name()))
                .toList();
        commsDueTo(w, kind, dueAt, payment, now, refs);
    }

    /** A message due to these orders' customers (OUT_FOR_DELIVERY: with the pickup-point handover codes). */
    void commsDueTo(PreorderWindow w, CommKind kind, Instant dueAt, PaymentSettings payment, Instant now,
                    List<PreorderEventPayload.OrderRef> refs) {
        events.publish(DeliveryRunsEvents.PREORDER_COMMS_DUE, DeliveryRunsEvents.AGGREGATE, w.id().toString(),
                payload(w, payment, kind.name(), dueAt, now, refs, null, null));
    }

    void settingsChanged(PreorderSettings s, String actor) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("enabled", s.enabled());
        p.put("fallback", s.fallback().name());
        p.put("placements", s.placements().stream().sorted().map(Enum::name).toList());
        p.put("payment", s.payment());
        p.put("actor", actor);
        p.put("actorId", actor);
        p.put("changedAt", s.updatedAt());
        events.publish(DeliveryRunsEvents.PREORDER_SETTINGS_CHANGED, DeliveryRunsEvents.AGGREGATE, SETTINGS_KEY, p);
    }

    /** One region's coverage was edited: what was added, removed and switched on or off. */
    void areasChanged(AreaKey region, AreaChanges c, String actor, Instant at) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("region", region.key());
        p.put("added", c.added().stream().map(a -> Map.of("key", a.key().key(), "on", a.on())).toList());
        p.put("removed", c.removed().stream().map(a -> a.key().key()).toList());
        p.put("switched", c.switched().stream().map(a -> Map.of("key", a.key().key(), "on", a.on())).toList());
        p.put("actorId", actor);
        p.put("changedAt", at);
        events.publish(DeliveryRunsEvents.PREORDER_AREAS_CHANGED, DeliveryRunsEvents.AGGREGATE, SETTINGS_KEY, p);
    }

    void lateOrderAdded(PreorderWindow w, PreorderOrder o, String paymentMethod, String paymentLinkUrl,
                        Instant linkExpiresAt, String reason, String actor) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("windowId", w.id());
        p.put("code", w.code());
        p.put("name", w.details().name());
        p.put("orderId", o.orderId());
        p.put("number", o.number());
        p.put("customerId", o.customerId());
        p.put("phone", o.phone());
        p.put("totalMinor", o.totalMinor());
        p.put("currency", o.currency());
        p.put("payment", paymentMethod);
        p.put("paymentUrl", paymentLinkUrl);
        p.put("expiresAt", linkExpiresAt);
        p.put("reason", reason);
        p.put("note", o.note());
        p.put("deliversOn", w.details().deliversOn());
        p.put("actor", actor);
        p.put("addedAt", o.placedAt());
        events.publish(DeliveryRunsEvents.PREORDER_LATE_ORDER_ADDED, DeliveryRunsEvents.AGGREGATE, w.id().toString(),
                p);
    }

    void exportCreated(PreorderWindow w, String kind, int rows, Map<String, String> filter, String actor,
                       Instant at) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("windowId", w.id());
        p.put("code", w.code());
        p.put("export", kind);
        p.put("rows", rows);
        p.put("filter", filter);
        p.put("containsPii", kind.startsWith("orders"));
        p.put("actor", actor);
        p.put("createdAt", at);
        events.publish(DeliveryRunsEvents.PREORDER_EXPORT_CREATED, DeliveryRunsEvents.AGGREGATE, w.id().toString(),
                p);
    }

    String pageUrl(PreorderWindow w) {
        return settings.publicWebUrl() + options.pagePath() + "/" + w.code();
    }

    /**
     * The pre-order page per channel: {@code ?src=&po=&cr=} (order attribution) plus {@code utm_source},
     * {@code utm_medium}, {@code utm_campaign} (preorder-<code>) and {@code utm_content} (the message), so every
     * announcement is attributed in analytics. {@code kind} is the message of a {@code preorder.comms.due}; other
     * window events carry the page links for staff to copy.
     */
    Map<String, String> links(PreorderWindow w, CommKind kind) {
        String page = settings.publicWebUrl() + options.pagePath();
        String slot = kind == null ? "window" : PreorderUtm.SLOTS.getOrDefault(kind, "window");
        Map<String, String> links = new LinkedHashMap<>();
        for (String channel : List.of("email", "sms", "push")) {
            links.put(channel, rl.windowAnnouncementUrl(page, w.code(), channel, slot));
        }
        links.put("whatsapp", rl.windowSocialUrl(page, w.code(), "whatsapp", "wa", "story"));
        links.put("instagram", rl.windowSocialUrl(page, w.code(), "instagram", "ig", "story"));
        links.put("facebook", rl.windowSocialUrl(page, w.code(), "facebook", "fb", "feed"));
        links.put("tiktok", rl.windowSocialUrl(page, w.code(), "tiktok", "tt", "story"));
        return links;
    }

    private PreorderEventPayload payload(PreorderWindow w, PaymentSettings payment, String kind, Instant dueAt,
                                         Instant now, List<PreorderEventPayload.OrderRef> orders,
                                         String actorId, WindowDecision decision) {
        WindowDetails d = w.details();
        DeliveryFees fees = book.current();
        Long door = d.door() ? w.doorFee(fees).map(FeeSnapshot::amountMinor).orElse(null) : null;
        Long pickup = d.pickup() ? w.pickupFee(fees).amountMinor() : null;
        CreativeRef hero = w.creatives().get(CreativeSlot.HERO);
        CreativeRef email = w.creatives().get(CreativeSlot.EMAIL);
        List<String> targets = d.targets().stream().map(AreaKey::key).toList();
        String where = d.targets().stream().map(AreaKey::label).collect(Collectors.joining(" · "));
        return new PreorderEventPayload(w.id(), w.code(), d.name(), w.status(now).name(), kind, dueAt, targets, where,
                d.opensAt(), d.closesAt(), d.deliversOn(), d.timeZone().getId(), d.ribbon(), d.headline(),
                d.smsCopy(), d.categories().stream().sorted().toList(), d.door(), d.pickup(), door, pickup,
                d.currency(), payment.pickupWindow(), d.pickupAddress(),
                d.comms().stream().sorted().map(Enum::name).toList(), hero == null ? null : hero.url(),
                email == null ? null : email.url(), w.runId(), pageUrl(w), links(w, kind == null ? null : CommKind.valueOf(kind)),
                options.supportPhone(),
                DATE_TIME.format(d.opensAt().atZone(d.timeZone())), DATE_TIME.format(d.closesAt().atZone(d.timeZone())),
                DATE.format(d.deliversOn()), orders, actorId, d.minOrders(), decision == null ? null
                : new PreorderEventPayload.Decision(decision.kind().name(), decision.reason(), decision.actor(),
                        decision.orders(), decision.minimum(), decision.at()));
    }
}

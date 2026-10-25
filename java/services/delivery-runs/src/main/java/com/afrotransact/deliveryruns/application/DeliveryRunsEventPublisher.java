package com.afrotransact.deliveryruns.application;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.afrotransact.deliveryruns.domain.Channel;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunOrder;
import com.afrotransact.deliveryruns.domain.RunStatus;
import com.afrotransact.deliveryruns.domain.Subscription;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.springframework.stereotype.Component;

/** Builds and publishes run and subscription events through the transactional outbox. */
@Component
class DeliveryRunsEventPublisher {

    private final DomainEventPublisher events;
    private final RunLinks links;

    DeliveryRunsEventPublisher(DomainEventPublisher events, RunLinks links) {
        this.events = events;
        this.links = links;
    }

    void run(String type, Location location, Run run, RunStatus previous, Instant previousCutoff, String reason,
             List<RunOrder> orders) {
        run(type, location, run, previous, previousCutoff, reason, orders, null);
    }

    void run(String type, Location location, Run run, RunStatus previous, Instant previousCutoff, String reason,
             List<RunOrder> orders, List<RunEventPayload.SellerRef> sellers) {
        String content = type.substring(type.lastIndexOf('.') + 1);
        List<RunEventPayload.OrderRef> refs = orders == null ? null : orders.stream()
                .map(o -> new RunEventPayload.OrderRef(o.orderId(), o.customerId(), o.packages(), o.status().name(),
                        o.handoverCode()))
                .toList();
        var t = run.terms();
        var d = location.details();
        RunEventPayload payload = new RunEventPayload(run.id(), run.status().name(),
                previous == null ? null : previous.name(), location.id(), d.slug(), d.name(), d.market(), d.state(),
                d.timeZone().getId(), t.window().start(), t.window().end(), t.cutoffAt(), previousCutoff,
                t.sellerPickupAt(), links.deliveryDay(location, run), links.windowDisplay(location, run),
                links.cutoffDisplay(location, run), t.feeMinor(), t.currency(),
                RunLinks.money(t.feeMinor(), t.currency()), t.dropMode().name(), t.pickupPoint(), t.minOrders(),
                run.reservedOrders(), t.capacity().maxOrders(), run.extensions(), reason, links.runUrl(location, run),
                links.channelLinks(location, run, content), links.locationUrl(location), d.whatsappInviteUrl(), refs,
                sellers);
        events.publish(type, DeliveryRunsEvents.AGGREGATE, run.id().toString(), payload);
    }

    void subscription(String type, Location location, Subscription s, Set<Channel> changed, String source,
                      String confirmToken) {
        SubscriptionEventPayload payload = new SubscriptionEventPayload(s.id(), location.id(), location.slug(),
                location.name(), s.userId(), s.email(), s.status().name(), names(s.consents().values().stream()
                        .filter(c -> c.granted()).map(c -> c.channel()).toList()),
                names(changed == null ? List.of() : List.copyOf(changed)), source,
                confirmToken == null ? null : links.confirmUrl(confirmToken),
                links.unsubscribeUrl(s.unsubscribeToken()), links.locationUrl(location),
                location.details().whatsappInviteUrl());
        events.publish(type, DeliveryRunsEvents.AGGREGATE, s.id().toString(), payload);
    }

    private static List<String> names(List<Channel> channels) {
        return channels.stream().sorted().map(Channel::name).toList();
    }
}

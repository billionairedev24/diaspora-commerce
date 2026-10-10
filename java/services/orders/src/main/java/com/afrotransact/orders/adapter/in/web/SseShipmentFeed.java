package com.afrotransact.orders.adapter.in.web;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import com.afrotransact.orders.port.ShipmentFeed;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live shipment status feed over server-sent events for the staff console (all shipments) and the seller center (own
 * organisation). In-process: each replica pushes the transitions it applied; behind several replicas the console
 * reconnects and re-reads (or a Kafka fan-out of orders.shipment.status_changed replaces this).
 */
@Component
class SseShipmentFeed implements ShipmentFeed {

    private static final Duration TIMEOUT = Duration.ofMinutes(30);

    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    private record Subscriber(SseEmitter emitter, Optional<String> orgId) {
    }

    SseEmitter subscribe(Optional<String> orgId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        Subscriber s = new Subscriber(emitter, orgId);
        subscribers.add(s);
        emitter.onCompletion(() -> subscribers.remove(s));
        emitter.onTimeout(() -> subscribers.remove(s));
        emitter.onError(e -> subscribers.remove(s));
        try {
            emitter.send(SseEmitter.event().name("ready").data("{}", MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            subscribers.remove(s);
        }
        return emitter;
    }

    @Override
    public void publish(Update update) {
        for (Subscriber s : subscribers) {
            if (s.orgId().isPresent() && !s.orgId().get().equals(update.sellerOrgId())) {
                continue;
            }
            try {
                s.emitter().send(SseEmitter.event().name("shipment.status_changed").id(update.shipmentId() + ":"
                        + update.to()).data(update, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                subscribers.remove(s);
            }
        }
    }

    int subscriberCount() {
        return subscribers.size();
    }
}

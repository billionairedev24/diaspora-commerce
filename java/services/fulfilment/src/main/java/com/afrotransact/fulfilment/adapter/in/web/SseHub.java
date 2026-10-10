package com.afrotransact.fulfilment.adapter.in.web;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;

import com.afrotransact.fulfilment.port.DispatchFeed;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live server-sent events. Dispatch consoles receive every update; a driver's stream receives updates addressed to
 * that driver (route changes, session.revoked). Updates are pushed right after the transaction commits. In-process:
 * each replica pushes what it applied; consoles reconnect and re-read (a Kafka fan-out of at.fulfilment.events can
 * replace this behind several replicas).
 */
@Component
class SseHub implements DispatchFeed {

    private static final Duration TIMEOUT = Duration.ofMinutes(30);

    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    /** {@code driverId} null = dispatch console. */
    private record Subscriber(SseEmitter emitter, String driverId) {
    }

    SseEmitter dispatch() {
        return subscribe(null);
    }

    SseEmitter driver(String driverId) {
        return subscribe(driverId);
    }

    private SseEmitter subscribe(String driverId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        Subscriber s = new Subscriber(emitter, driverId);
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
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            push(update);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                push(update);
            }
        });
    }

    private void push(Update update) {
        for (Subscriber s : subscribers) {
            if (s.driverId() != null && !s.driverId().equals(update.driverId())) {
                continue;
            }
            try {
                s.emitter().send(SseEmitter.event().name(update.name()).data(update, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                subscribers.remove(s);
            }
        }
    }

    int subscriberCount() {
        return subscribers.size();
    }
}

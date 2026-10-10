package com.afrotransact.orders.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * The actor of one transition.
 *
 * @param kind  actor type used by the transition rules
 * @param id    user / client / carrier id recorded in the history
 * @param orgId organisation the actor acts for (sellers), else null
 */
public record TransitionActor(ActorKind kind, String id, String orgId) {

    public TransitionActor {
        Objects.requireNonNull(kind, "kind");
        id = id == null || id.isBlank() ? kind.name().toLowerCase(Locale.ROOT) : id;
    }

    public static TransitionActor system() {
        return new TransitionActor(ActorKind.SYSTEM, "orders-checkout", null);
    }
}

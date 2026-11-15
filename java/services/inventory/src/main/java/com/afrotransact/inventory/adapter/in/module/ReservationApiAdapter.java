package com.afrotransact.inventory.adapter.in.module;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.inventory.api.ReservationApi;
import com.afrotransact.inventory.application.CommitResult;
import com.afrotransact.inventory.application.ReservationResult;
import com.afrotransact.inventory.application.ReservationService;
import com.afrotransact.inventory.application.ReserveCommand;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link ReservationApi}: the use cases behind {@code /v1/reservations...}, entered under the calling
 * module's principal. {@link ReservationService} applies {@code inventory:reserve} / {@code inventory:read} as for a token.
 */
@Component
class ReservationApiAdapter implements ReservationApi {

    private final ReservationService reservations;
    private final ModuleEntry entry;

    ReservationApiAdapter(ReservationService reservations, ActorResolver actors) {
        this.reservations = reservations;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public ReservationInfo reserve(String orderId, String idempotencyKey, List<Line> lines) {
        return entry.run(() -> {
            ReservationResult result = reservations.reserve(new ReserveCommand(orderId, idempotencyKey,
                    lines.stream().map(l -> new ReserveCommand.Line(l.sku(), l.nodeId(), l.qty(), l.group())).toList()));
            return ReservationViews.of(result.reservation(), result.replayed());
        }, InventoryFailures::map);
    }

    @Override
    public Optional<ReservationInfo> find(String orderId) {
        return entry.run(() -> {
            try {
                return Optional.of(ReservationViews.of(reservations.get(orderId), false));
            } catch (NotFoundException notFound) {
                return Optional.<ReservationInfo>empty();
            }
        }, InventoryFailures::map);
    }

    @Override
    public ReservationInfo confirm(String orderId) {
        return entry.run(() -> ReservationViews.of(reservations.confirm(orderId), false), InventoryFailures::map);
    }

    @Override
    public void commit(String orderId) {
        entry.runVoid(() -> reservations.commit(orderId), InventoryFailures::map);
    }

    @Override
    public ReservationInfo release(String orderId, String reason) {
        return entry.run(() -> ReservationViews.of(reservations.release(orderId, reason), false), InventoryFailures::map);
    }

    @Override
    public void commitGroups(String orderId, Set<String> groups) {
        entry.runVoid(() -> {
            CommitResult ignored = reservations.commitGroups(orderId, new LinkedHashSet<>(groups));
        }, InventoryFailures::map);
    }

    @Override
    public ReservationInfo releaseGroups(String orderId, Set<String> groups, String reason) {
        return entry.run(() -> ReservationViews.of(reservations.releaseGroups(orderId, new LinkedHashSet<>(groups), reason), false),
                InventoryFailures::map);
    }
}

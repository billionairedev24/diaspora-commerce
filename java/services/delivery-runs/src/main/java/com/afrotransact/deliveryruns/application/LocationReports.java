package com.afrotransact.deliveryruns.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.afrotransact.deliveryruns.domain.GeoPoint;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.PreorderOrder;
import com.afrotransact.deliveryruns.domain.PreorderStatus;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.RouteEstimate;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunOrder;
import com.afrotransact.deliveryruns.domain.RunOrderTally;
import com.afrotransact.deliveryruns.domain.RunPnl;
import com.afrotransact.deliveryruns.domain.RunPnlCalculator;
import com.afrotransact.deliveryruns.port.GeoGateway;
import com.afrotransact.deliveryruns.port.GeoUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The run and window rows of a location's history (S265). A run is counted and priced exactly as its P&amp;L is
 * ({@link RunLedger}, {@link RunPnlCalculator}), so the history and the P&amp;L never disagree.
 */
@Component
class LocationReports {

    private static final Logger LOG = LoggerFactory.getLogger(LocationReports.class);
    /** Windows looked at for a location's history, newest first. */
    private static final int WINDOW_LIMIT = 100;

    private final RunLedger ledger;
    private final RunPnlCalculator pnls;
    private final GeoGateway geo;
    private final DeliveryRunsSettings settings;
    private final PreorderHandover preorders;
    private final Clock clock;

    LocationReports(RunLedger ledger, RunPnlCalculator pnls, GeoGateway geo, DeliveryRunsSettings settings,
                    PreorderHandover preorders, Clock clock) {
        this.ledger = ledger;
        this.pnls = pnls;
        this.geo = geo;
        this.settings = settings;
        this.preorders = preorders;
        this.clock = clock;
    }

    /** The runs with their orders, cost, margin and break-even (a run whose route is unknown has no P&amp;L). */
    List<RunHistoryEntry> runs(Location location, List<Run> runs) {
        Map<UUID, List<RunOrder>> slots = ledger.slots(runs.stream().map(Run::id).toList());
        RouteEstimate located = null;
        boolean measured = false;
        List<RunHistoryEntry> out = new ArrayList<>();
        for (Run run : runs) {
            List<RunOrder> own = slots.getOrDefault(run.id(), List.of());
            RouteEstimate route = run.estimate();
            if (route == null) {
                if (!measured) {
                    located = locationRoute(location).orElse(null);
                    measured = true;
                }
                route = located;
            }
            RunPnl pnl = route == null ? null : pnls.pnl(run, route, own, null);
            out.add(new RunHistoryEntry(run, RunOrderTally.forRun(run, own), pnl));
        }
        return out;
    }

    /** The windows delivered by this location, newest first: orders, delivery fees, refunds, minimum met. */
    List<WindowHistoryEntry> windows(Location location, Set<UUID> runIds) {
        Instant now = Instant.now(clock);
        List<WindowHistoryEntry> out = new ArrayList<>();
        List<PreorderWindow> published = preorders.windows().findPublished();
        for (PreorderWindow w : published.subList(0, Math.min(published.size(), WINDOW_LIMIT))) {
            boolean handedToThisLocation = w.runId() != null && runIds.contains(w.runId());
            if (!handedToThisLocation && !servedBy(w, location)) {
                continue;
            }
            out.add(entry(w, w.status(now), preorders.orders().findByWindow(w.id())));
        }
        return out;
    }

    private boolean servedBy(PreorderWindow w, Location location) {
        return preorders.fees().distances().locate(w).map(l -> l.id().equals(location.id())).orElse(false);
    }

    private static WindowHistoryEntry entry(PreorderWindow w, PreorderStatus status, List<PreorderOrder> all) {
        int min = w.details().minOrders();
        List<PreorderOrder> counted = all.stream().filter(PreorderOrder::counts).toList();
        if (status == PreorderStatus.CANCELLED) {
            List<PreorderOrder> refunds = all.stream().filter(o -> o.refund() != null).toList();
            int placed = Math.max(all.size(), refunds.size());
            return new WindowHistoryEntry(w.id(), w.code(), w.details().name(), status, w.details().deliversOn(),
                    w.details().currency(), 0, 0, 0, refunds.size(),
                    refunds.stream().mapToLong(PreorderOrder::totalMinor).sum(), min, min < 1 ? null : placed >= min);
        }
        return new WindowHistoryEntry(w.id(), w.code(), w.details().name(), status, w.details().deliversOn(),
                w.details().currency(), counted.size(), counted.stream().mapToLong(PreorderOrder::deliveryFeeMinor).sum(),
                counted.stream().mapToLong(PreorderOrder::totalMinor).sum(), 0, 0, min,
                min < 1 ? null : counted.size() >= min);
    }

    /** The one-way route to the location, when geo-service can measure it. */
    private Optional<RouteEstimate> locationRoute(Location location) {
        GeoPoint origin = location.details().origin() == null ? settings.defaultOrigin() : location.details().origin();
        try {
            return Optional.of(geo.route(origin, location.details().area().center()));
        } catch (GeoUnavailableException e) {
            LOG.warn("No route for the history of location {}: {}", location.slug(), e.getMessage());
            return Optional.empty();
        }
    }

    /** The ids of these runs, for matching windows handed to them. */
    static Set<UUID> ids(List<Run> runs) {
        return runs.stream().map(Run::id).collect(Collectors.toSet());
    }
}

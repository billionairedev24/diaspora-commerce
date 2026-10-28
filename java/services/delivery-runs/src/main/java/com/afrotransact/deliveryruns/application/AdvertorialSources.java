package com.afrotransact.deliveryruns.application;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.AdvertorialCopy;
import com.afrotransact.deliveryruns.domain.AdvertorialSource;
import com.afrotransact.deliveryruns.domain.FeeSnapshot;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.PreorderStatus;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunStatus;
import com.afrotransact.deliveryruns.domain.WindowDetails;
import com.afrotransact.deliveryruns.port.LocationRepository;
import com.afrotransact.deliveryruns.port.PreorderWindowRepository;
import com.afrotransact.deliveryruns.port.RunRepository;
import org.springframework.stereotype.Component;

/**
 * The window or run an advertorial is about: its name, whether it takes orders now, when it closes and the words the
 * system drafts for it.
 */
@Component
class AdvertorialSources {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US);
    private static final DateTimeFormatter CLOSES = DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a", Locale.US);

    /**
     * @param kind         window or run
     * @param id           window id or run id
     * @param name         window name, or the run's town and delivery day
     * @param code         window code (null for runs)
     * @param status       window or run status now
     * @param open         takes orders now
     * @param cancelled    was cancelled
     * @param closes       when ordering closes (window close, run cutoff)
     * @param locationId   the location it serves (null when none serves a window)
     * @param locationName that location's name
     * @param place        the town or area shown to visitors
     * @param draft        the words the system drafts for it
     */
    record Info(AdvertorialSource kind, UUID id, String name, String code, String status, boolean open,
                boolean cancelled, Instant closes, UUID locationId, String locationName, String place,
                AdvertorialCopy draft) {
    }

    private final RunRepository runs;
    private final LocationRepository locations;
    private final PreorderWindowRepository windows;
    private final FeeBook fees;
    private final RunLinks links;

    AdvertorialSources(RunRepository runs, LocationRepository locations, PreorderWindowRepository windows,
                       FeeBook fees, RunLinks links) {
        this.runs = runs;
        this.locations = locations;
        this.windows = windows;
        this.fees = fees;
        this.links = links;
    }

    Optional<Info> find(AdvertorialSource kind, UUID id, Instant now) {
        return switch (kind) {
            case RUN -> runs.findById(id).flatMap(r -> locations.findById(r.locationId()).map(l -> run(r, l, now)));
            case WINDOW -> windows.findById(id).map(w -> window(w, now));
        };
    }

    Info run(Run run, Location location, Instant now) {
        String day = links.deliveryDay(location, run);
        String town = location.name();
        String fee = RunLinks.money(run.terms().feeMinor(), run.terms().currency());
        AdvertorialCopy draft = new AdvertorialCopy("AfroTransact delivery run to " + town + " · " + day,
                "Order by " + links.cutoffDisplay(location, run) + ". Delivery is " + fee + " per order.",
                "Order for the run");
        return new Info(AdvertorialSource.RUN, run.id(), town + " · " + day, null, run.status().name(),
                run.acceptsOrders(now), run.status() == RunStatus.CANCELLED,
                run.terms().cutoffAt(), location.id(), town, town, draft);
    }

    Info window(PreorderWindow w, Instant now) {
        WindowDetails d = w.details();
        PreorderStatus status = w.status(now);
        Optional<Location> served = fees.distances().locate(w);
        String where = d.targets().isEmpty() ? "your area" : d.targets().get(0).label();
        ZoneId zone = d.timeZone();
        String closes = CLOSES.format(d.closesAt().atZone(zone));
        String delivers = DAY.format(d.deliversOn());
        Optional<FeeSnapshot> door = d.door() ? w.doorFee(fees.current()) : Optional.empty();
        String fee = door.map(f -> RunLinks.money(f.amountMinor(), f.currency())).orElse(null);
        String headline = d.headline() != null && !d.headline().isBlank() ? d.headline()
                : where + " pre-order is open";
        String body = "Order by " + closes + ", delivered " + delivers
                + (fee == null ? "." : ". Door delivery is " + fee + ".");
        AdvertorialCopy draft = new AdvertorialCopy(clip(headline, AdvertorialCopy.MAX_HEADLINE),
                clip(body, AdvertorialCopy.MAX_BODY), "Pre-order now");
        return new Info(AdvertorialSource.WINDOW, w.id(), d.name(), w.code(), status.name(),
                status == PreorderStatus.OPEN, status == PreorderStatus.CANCELLED, d.closesAt(),
                served.map(Location::id).orElse(null), served.map(Location::name).orElse(null), where, draft);
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}

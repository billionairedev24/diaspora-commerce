package com.afrotransact.deliveryruns.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.application.AdvertorialSources.Info;
import com.afrotransact.deliveryruns.domain.AdvertorialEnd;
import com.afrotransact.deliveryruns.domain.AdvertorialSource;
import com.afrotransact.deliveryruns.domain.LocalAdvertorial;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.port.AdvertorialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class AdvertorialLifecycleImpl implements AdvertorialLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(AdvertorialLifecycleImpl.class);

    private final AdvertorialRepository advertorials;
    private final AdvertorialSources sources;
    private final Clock clock;

    AdvertorialLifecycleImpl(AdvertorialRepository advertorials, AdvertorialSources sources, Clock clock) {
        this.advertorials = advertorials;
        this.sources = sources;
        this.clock = clock;
    }

    @Override
    public void onRun(String type, Location location, Run run) {
        Instant now = Instant.now(clock);
        switch (type) {
            case DeliveryRunsEvents.RUN_ANNOUNCED, DeliveryRunsEvents.RUN_OPENED ->
                    draft(sources.run(run, location, now), now);
            case DeliveryRunsEvents.RUN_EXTENDED -> redraft(sources.run(run, location, now), now);
            case DeliveryRunsEvents.RUN_CANCELLED -> end(AdvertorialSource.RUN, run.id(), AdvertorialEnd.CANCELLED, now);
            case DeliveryRunsEvents.RUN_CONFIRMED, DeliveryRunsEvents.RUN_OUT_FOR_DELIVERY,
                 DeliveryRunsEvents.RUN_COMPLETED -> end(AdvertorialSource.RUN, run.id(),
                    AdvertorialEnd.SOURCE_CLOSED, now);
            default -> {
                // reminders and subscription events do not change what an advertorial says
            }
        }
    }

    @Override
    public void onWindow(String type, PreorderWindow window) {
        Instant now = Instant.now(clock);
        switch (type) {
            case DeliveryRunsEvents.PREORDER_WINDOW_PUBLISHED, DeliveryRunsEvents.PREORDER_WINDOW_OPENED,
                 DeliveryRunsEvents.PREORDER_WINDOW_RESUMED -> draft(sources.window(window, now), now);
            case DeliveryRunsEvents.PREORDER_WINDOW_UPDATED -> redraft(sources.window(window, now), now);
            case DeliveryRunsEvents.PREORDER_WINDOW_CLOSED, DeliveryRunsEvents.PREORDER_WINDOW_DELIVERED,
                 DeliveryRunsEvents.PREORDER_WINDOW_DECISION_NEEDED -> end(AdvertorialSource.WINDOW, window.id(),
                    AdvertorialEnd.SOURCE_CLOSED, now);
            case DeliveryRunsEvents.PREORDER_WINDOW_CANCELLED -> end(AdvertorialSource.WINDOW, window.id(),
                    AdvertorialEnd.CANCELLED, now);
            default -> {
                // pause and the audit event need nothing: a window that is not open serves no advertorial
            }
        }
    }

    /** Makes sure the source has its generated advertorial (re-drafting the words of an existing one). */
    private void draft(Info info, Instant now) {
        if (info.cancelled()) {
            return;
        }
        List<LocalAdvertorial> existing = advertorials.findBySource(info.kind(), info.id());
        LocalAdvertorial generated = existing.stream().filter(LocalAdvertorial::generated).findFirst().orElse(null);
        if (generated == null) {
            advertorials.insert(LocalAdvertorial.generated(UUID.randomUUID(), info.kind(), info.id(),
                    info.locationId(), info.draft(), now));
            LOG.info("Drafted the advertorial of {} {}", info.kind(), info.id());
            return;
        }
        LocalAdvertorial next = generated.redraft(info.draft(), now);
        if (next != generated) {
            advertorials.update(next);
        }
    }

    private void redraft(Info info, Instant now) {
        for (LocalAdvertorial a : advertorials.findBySource(info.kind(), info.id())) {
            LocalAdvertorial next = a.redraft(info.draft(), now);
            if (next != a) {
                advertorials.update(next);
            }
        }
    }

    private void end(AdvertorialSource kind, UUID id, AdvertorialEnd why, Instant now) {
        for (LocalAdvertorial a : advertorials.findBySource(kind, id)) {
            LocalAdvertorial ended = a.end(why, now);
            if (ended != a) {
                advertorials.update(ended);
            }
        }
    }
}

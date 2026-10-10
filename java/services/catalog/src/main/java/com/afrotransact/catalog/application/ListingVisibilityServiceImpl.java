package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.ListingEnforcementRun;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.port.ListingEnforcementRunRepository;
import com.afrotransact.catalog.port.ListingSuppressionRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.events.InboxDeduplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One short transaction records the decision (inbox, suppression, run); then one transaction per page republishes
 * up to {@code pageSize} products and advances the run's keyset cursor. Pages hold a row lock on the run, so two pods
 * resuming the same run never publish the same page.
 */
@Service
class ListingVisibilityServiceImpl implements ListingVisibilityService {

    static final String CONSUMER = "catalog.seller-compliance";
    private static final int STALLED_BATCH = 20;

    private static final Logger LOG = LoggerFactory.getLogger(ListingVisibilityServiceImpl.class);

    private final InboxDeduplicator inbox;
    private final ListingSuppressionRepository suppressions;
    private final ProductRepository products;
    private final ListingEnforcementRunRepository runs;
    private final ProductEvents events;
    private final Clock clock;
    private final ListingEnforcementSettings settings;
    private final TransactionTemplate tx;

    ListingVisibilityServiceImpl(InboxDeduplicator inbox, ListingSuppressionRepository suppressions,
                                 ProductRepository products, ListingEnforcementRunRepository runs,
                                 ProductEvents events, Clock clock, ListingEnforcementSettings settings,
                                 PlatformTransactionManager transactions) {
        this.inbox = inbox;
        this.suppressions = suppressions;
        this.products = products;
        this.runs = runs;
        this.events = events;
        this.clock = clock;
        this.settings = settings;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    public int hide(ListingEnforcement event) {
        return apply(event, ListingEnforcementRun.Action.HIDE);
    }

    @Override
    public int restore(ListingEnforcement event) {
        return apply(event, ListingEnforcementRun.Action.RESTORE);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hidden(String orgId) {
        return suppressions.isHidden(orgId);
    }

    @Override
    public int resumeStalled() {
        List<UUID> stalled = runs.stalled(Instant.now(clock).minus(settings.stallAfter()), STALLED_BATCH);
        int published = 0;
        for (UUID runId : stalled) {
            LOG.info("Resuming stalled listing enforcement run {}", runId);
            published += drive(runId);
        }
        return published;
    }

    private int apply(ListingEnforcement e, ListingEnforcementRun.Action action) {
        UUID runId = tx.execute(status -> start(e, action));
        return runId == null ? 0 : drive(runId);
    }

    /** Records the decision; returns the run to drive, or null when there is nothing to do. */
    private UUID start(ListingEnforcement e, ListingEnforcementRun.Action action) {
        if (!inbox.firstDelivery(CONSUMER, e.eventId())) {
            // Redelivery after a crash mid-run: pick the run up where its last committed page left it.
            return runs.findById(e.eventId()).filter(ListingEnforcementRun::running)
                    .map(ListingEnforcementRun::eventId).orElse(null);
        }
        if (e.orgId() == null || e.orgId().isBlank()) {
            LOG.warn("Ignoring listing enforcement event {} without orgId", e.eventId());
            return null;
        }
        boolean hide = action.hides();
        Instant now = Instant.now(clock);
        Instant at = e.occurredAt() == null ? now : e.occurredAt();
        String reason = e.reason() == null || e.reason().isBlank() ? (hide ? "MANUAL" : "CLEARED") : e.reason();
        if (!suppressions.apply(e.orgId(), hide, reason, at, now)) {
            return null;
        }
        int superseded = runs.supersedeRunning(e.orgId(), now);
        if (superseded > 0) {
            LOG.info("Enforcement {} of {} supersedes {} run(s) in progress", e.eventId(), e.orgId(), superseded);
        }
        runs.insert(ListingEnforcementRun.start(e.eventId(), e.orgId(), action, reason, now));
        return e.eventId();
    }

    private int drive(UUID runId) {
        int published = 0;
        while (true) {
            PageResult page = tx.execute(status -> nextPage(runId));
            if (page == null) {
                return published;
            }
            published += page.published();
            if (page.finished()) {
                return published;
            }
        }
    }

    private PageResult nextPage(UUID runId) {
        Optional<ListingEnforcementRun> locked = runs.lock(runId);
        if (locked.isEmpty() || !locked.get().running()) {
            return new PageResult(0, true);
        }
        ListingEnforcementRun run = locked.get();
        Instant now = Instant.now(clock);
        boolean hide = run.action().hides();
        if (suppressions.isHidden(run.orgId()) != hide) {
            // A newer decision flipped the organisation back; its own run republishes the products.
            runs.update(run.superseded(now));
            return new PageResult(0, true);
        }
        List<Product> page = products.findByOwnerOrgAfter(run.orgId(), run.cursor(), settings.pageSize());
        String type = hide ? CatalogEvents.PRODUCT_HIDDEN : CatalogEvents.PRODUCT_RESTORED;
        for (Product p : page) {
            events.publish(type, p, hide);
        }
        boolean last = page.size() < settings.pageSize();
        ListingEnforcementRun next = run.pageDone(page.isEmpty() ? null : page.getLast().id(), page.size(), last, now);
        runs.update(next);
        if (last) {
            LOG.info("Listings of {} {} ({}): {} product events in {} page(s)", run.orgId(),
                    hide ? "hidden" : "restored", run.reason(), next.processed(), next.pages());
        }
        return new PageResult(page.size(), last);
    }

    private record PageResult(int published, boolean finished) {
    }
}

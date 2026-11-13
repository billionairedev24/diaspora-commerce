package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.images.ImageCheckResult;
import com.afrotransact.catalog.domain.images.ImageFinding;
import com.afrotransact.catalog.domain.images.ImageQuality;
import com.afrotransact.catalog.domain.images.ImageQualityRecord;
import com.afrotransact.catalog.port.ImageImporter.ImageServiceUnavailable;
import com.afrotransact.catalog.port.ImageQualityRepository;
import com.afrotransact.catalog.port.ImageQualityRepository.Candidate;
import com.afrotransact.catalog.port.ImageVerifier;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class ImageQualityServiceImpl implements ImageQualityService {

    /** The reason of a listing that has no main picture at all. */
    static final String NO_MAIN_IMAGE = "NO_MAIN_IMAGE";
    private static final int REPORT_ROWS = 200;
    private static final int HIDE_PAGE = 200;

    private static final Logger LOG = LoggerFactory.getLogger(ImageQualityServiceImpl.class);

    private final ImageQualityRepository scores;
    private final ImageVerifier verifier;
    private final ImageSettings settings;
    private final ProductRepository products;
    private final ProductEvents productEvents;
    private final DomainEventPublisher events;
    private final PermissionChecker permissions;
    private final ResourceAccessChecker access;
    private final OwnerResolver owners;
    private final Clock clock;
    private final TransactionTemplate tx;

    ImageQualityServiceImpl(ImageQualityRepository scores, ImageVerifier verifier, ImageSettings settings,
                            ProductRepository products, ProductEvents productEvents, DomainEventPublisher events,
                            PermissionChecker permissions, ResourceAccessChecker access, OwnerResolver owners,
                            Clock clock, PlatformTransactionManager transactions) {
        this.scores = scores;
        this.verifier = verifier;
        this.settings = settings;
        this.products = products;
        this.productEvents = productEvents;
        this.events = events;
        this.permissions = permissions;
        this.access = access;
        this.owners = owners;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    public int rescoreBatch() {
        if (!settings.checking()) {
            return 0;
        }
        Instant now = Instant.now(clock);
        Instant stale = settings.recheckAfter().isZero() ? Instant.EPOCH : now.minus(settings.recheckAfter());
        List<Candidate> batch = scores.candidates(stale, settings.rescoreBatch());
        if (batch.isEmpty()) {
            return 0;
        }
        List<Candidate> withImage = batch.stream().filter(c -> c.mainImageUrl() != null && !c.mainImageUrl().isBlank()).toList();
        List<ImageCheckResult> results = List.of();
        if (!withImage.isEmpty()) {
            try {
                results = verifier.rescore(withImage.stream()
                        .map(c -> new ImageVerifier.Item(c.mainImageUrl(), ImageGateImpl.MAIN, c.productTypeCode())).toList());
            } catch (ImageServiceUnavailable ex) {
                LOG.warn("Image back-fill paused: media could not be asked: {}", ex.getMessage());
                return 0;
            }
        }
        int scored = 0;
        int next = 0;
        for (Candidate c : batch) {
            ImageCheckResult result = c.mainImageUrl() == null || c.mainImageUrl().isBlank() ? null : results.get(next++);
            tx.executeWithoutResult(s -> score(c, result, now));
            scored++;
        }
        LOG.info("Image back-fill scored {} listing(s)", scored);
        return scored;
    }

    /** Stores the state of one listing and tells the seller's side when it changed. */
    private void score(Candidate c, ImageCheckResult result, Instant now) {
        ImageQualityRecord previous = scores.find(c.productId()).orElse(null);
        ImageQuality state = result == null ? ImageQuality.FAIL : ImageQuality.of(result);
        List<ImageFinding> reasons = result == null
                ? List.of(new ImageFinding(NO_MAIN_IMAGE, ImageFinding.REJECT,
                        "This listing has no main photo. Add one so shoppers can see the product."))
                : result.checks().stream().filter(f -> !"INFO".equals(f.level())).toList();
        Instant failingSince = ImageQualityRecord.failingClock(state, previous, now);
        Instant hiddenAt = state == ImageQuality.FAIL && previous != null ? previous.hiddenAt() : null;
        ImageQualityRecord next = new ImageQualityRecord(c.productId(), c.ownerOrg(), c.atin(), c.title(), state, reasons,
                c.mainImageUrl(), now, failingSince, hiddenAt);
        scores.save(next);
        boolean wasHidden = previous != null && previous.hiddenAt() != null;
        if (wasHidden && hiddenAt == null) {
            products.findById(c.productId()).ifPresent(p -> productEvents.publish(CatalogEvents.PRODUCT_RESTORED, p));
        }
        boolean changed = previous == null ? next.needsAttention() : previous.state() != state;
        if (changed) {
            publishTask(next);
        }
    }

    private void publishTask(ImageQualityRecord r) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", r.productId().toString());
        payload.put("atin", r.atin());
        payload.put("ownerOrg", r.ownerOrg());
        payload.put("state", r.state().name());
        payload.put("reasonCodes", r.reasons().stream().map(ImageFinding::code).toList());
        payload.put("failingSince", r.failingSince());
        payload.put("hideAfter", r.hideAfter(settings.graceDays()));
        payload.put("occurredAt", Instant.now(clock));
        events.publish(CatalogEvents.LISTING_IMAGE_TASK, CatalogEvents.AGGREGATE_PRODUCT, r.productId().toString(), payload);
    }

    @Override
    public int rescoreNow() {
        permissions.require(CatalogPermissions.MODERATE);
        return rescoreBatch();
    }

    @Override
    public List<Task> myTasks() {
        ListingOwner owner = owner();
        Instant now = Instant.now(clock);
        return scores.tasks(owner.orgId()).stream().map(r -> new Task(r.productId(), r.atin(), r.title(), r.state().name(),
                r.reasons().stream().map(f -> new Reason(f.code(), f.level(), f.message())).toList(), r.failingSince(),
                r.hideAfter(settings.graceDays()), r.daysLeft(settings.graceDays(), now), r.hiddenAt() != null)).toList();
    }

    @Override
    public Report report() {
        permissions.require(CatalogPermissions.MODERATE);
        Instant now = Instant.now(clock);
        Map<String, Long> counts = new LinkedHashMap<>();
        scores.counts().forEach((state, n) -> counts.put(state.name(), n));
        List<ImageQualityRecord> failing = scores.failing(REPORT_ROWS);
        Map<String, Long> reasons = new LinkedHashMap<>();
        for (ImageQualityRecord r : failing) {
            r.reasons().forEach(f -> reasons.merge(f.code(), 1L, Long::sum));
        }
        Instant cutoff = now.minus(Duration.ofDays(settings.graceDays()));
        long wouldHide = scores.countDue(cutoff);
        List<ReportRow> rows = new ArrayList<>();
        for (ImageQualityRecord r : failing) {
            Instant end = r.hideAfter(settings.graceDays());
            rows.add(new ReportRow(r.productId(), r.atin(), r.ownerOrg(), r.title(),
                    r.reasons().stream().map(ImageFinding::code).toList(), r.failingSince(), end,
                    end != null && !end.isAfter(now), r.hiddenAt() != null));
        }
        return new Report(counts, scores.unscored(), reasons, settings.graceDays(), settings.hideFailing(), wouldHide, rows);
    }

    @Override
    public HideResult hideFailing(boolean dryRun) {
        permissions.require(CatalogPermissions.MODERATE);
        if (dryRun) {
            Instant cutoff = Instant.now(clock).minus(Duration.ofDays(settings.graceDays()));
            return new HideResult(true, settings.hideFailing(), (int) scores.countDue(cutoff));
        }
        return hideDue();
    }

    @Override
    public HideResult hideDue() {
        if (!settings.hideFailing()) {
            return new HideResult(false, false, 0);
        }
        Instant now = Instant.now(clock);
        Instant cutoff = now.minus(Duration.ofDays(settings.graceDays()));
        int hidden = 0;
        List<ImageQualityRecord> due = scores.failingSince(cutoff, HIDE_PAGE);
        for (ImageQualityRecord r : due) {
            Boolean done = tx.execute(s -> hide(r.productId(), now));
            if (Boolean.TRUE.equals(done)) {
                hidden++;
            }
        }
        if (hidden > 0) {
            LOG.info("Image hide step limited {} listing(s) that still fail after {} days", hidden, settings.graceDays());
        }
        return new HideResult(false, true, hidden);
    }

    /** Limits one listing (search and the buy box follow catalog.product.hidden); false when it is not live any more. */
    private boolean hide(UUID productId, Instant now) {
        Product p = products.findById(productId).orElse(null);
        if (p == null || p.status() != ProductStatus.LIVE) {
            return false;
        }
        scores.markHidden(productId, now);
        productEvents.publish(CatalogEvents.PRODUCT_HIDDEN, p);
        return true;
    }

    private ListingOwner owner() {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        ListingOwner owner = owners.ownerFor(actor);
        access.requireForOrg(CatalogPermissions.WRITE, CatalogRelations.ORG_VIEW_PRODUCTS, owner.orgId());
        return owner;
    }
}

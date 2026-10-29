package com.afrotransact.deliveryruns.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A location-targeted advertorial (S263): words for the visitors of the area a pre-order window or a delivery run
 * serves, shown with a live closing countdown while ordering is open and over when it closes or is cancelled. It is
 * drafted from the window or run (<em>generated</em>) or written by staff; a source may have several variants (A/B)
 * that share its visitors by weight.
 *
 * @param id         id
 * @param source     window or run
 * @param sourceId   window id or run id
 * @param locationId the delivery-runs location it is about (null when no location serves a window)
 * @param variant    label of the variant, e.g. {@code A}
 * @param weight     share of the source's visitors this variant gets, 1 to 100 (relative to its siblings)
 * @param priority   higher first when a visitor's area has several sources
 * @param generated  the words follow the source (refreshed when it changes) until staff edit them
 * @param copy       the words
 * @param startsAt   not shown before this (null = from the start)
 * @param endsAt     not shown from this on (null = while the source takes orders)
 * @param status     where it stands
 * @param ended      why it ended (set when {@link AdvertorialStatus#ENDED})
 * @param featured   up to four products staff chose to show on the card (empty for none)
 * @param createdBy  staff subject, or {@code system} for a generated one
 * @param createdAt  created
 * @param updatedAt  last change
 * @param version    optimistic lock
 */
public record LocalAdvertorial(UUID id, AdvertorialSource source, UUID sourceId, UUID locationId, String variant,
                               int weight, int priority, boolean generated, AdvertorialCopy copy, Instant startsAt,
                               Instant endsAt, AdvertorialStatus status, AdvertorialEnd ended,
                               List<FeaturedProduct> featured, String createdBy, Instant createdAt, Instant updatedAt,
                               long version) {

    public static final int MAX_WEIGHT = 100;
    public static final int MAX_PRIORITY = 1000;
    private static final Pattern VARIANT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]{0,7}$");

    /** An advertorial without featured products. */
    public LocalAdvertorial(UUID id, AdvertorialSource source, UUID sourceId, UUID locationId, String variant,
                            int weight, int priority, boolean generated, AdvertorialCopy copy, Instant startsAt,
                            Instant endsAt, AdvertorialStatus status, AdvertorialEnd ended, String createdBy,
                            Instant createdAt, Instant updatedAt, long version) {
        this(id, source, sourceId, locationId, variant, weight, priority, generated, copy, startsAt, endsAt, status,
                ended, List.of(), createdBy, createdAt, updatedAt, version);
    }

    public LocalAdvertorial {
        featured = featured == null ? List.of() : List.copyOf(featured);
        if (featured.size() > FeaturedProduct.MAX_PER_ADVERTORIAL) {
            throw new RuleViolation("delivery-runs.invalid-advertorial",
                    "Feature at most " + FeaturedProduct.MAX_PER_ADVERTORIAL + " products");
        }
        if (new HashSet<>(featured.stream().map(FeaturedProduct::atin).toList()).size() != featured.size()) {
            throw new RuleViolation("delivery-runs.invalid-advertorial", "A product can be featured once");
        }
        if (variant == null || !VARIANT.matcher(variant).matches()) {
            throw new RuleViolation("delivery-runs.invalid-advertorial",
                    "The variant is a short label such as A or B (letters, digits, up to 8)");
        }
        if (weight < 1 || weight > MAX_WEIGHT) {
            throw new RuleViolation("delivery-runs.invalid-advertorial", "The weight is 1 to " + MAX_WEIGHT);
        }
        if (priority < 0 || priority > MAX_PRIORITY) {
            throw new RuleViolation("delivery-runs.invalid-advertorial", "The priority is 0 to " + MAX_PRIORITY);
        }
        if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) {
            throw new RuleViolation("delivery-runs.invalid-advertorial", "It has to end after it starts");
        }
    }

    /** The token that tags links and orders: {@code adv-<32 hex>} (the order's {@code cr=}). */
    public String creative() {
        return creativeOf(id);
    }

    /** {@code adv-<32 hex>} for an advertorial id. */
    public static String creativeOf(UUID id) {
        return "adv-" + id.toString().replace("-", "");
    }

    /** A system-drafted advertorial that follows its source, live at once. */
    public static LocalAdvertorial generated(UUID id, AdvertorialSource source, UUID sourceId, UUID locationId,
                                             AdvertorialCopy copy, Instant now) {
        return new LocalAdvertorial(id, source, sourceId, locationId, "A", 1, 0, true, copy, null, null,
                AdvertorialStatus.LIVE, null, "system", now, now, 0);
    }

    /** A draft staff write. */
    public static LocalAdvertorial authored(UUID id, AdvertorialSource source, UUID sourceId, UUID locationId,
                                            String variant, int weight, int priority, AdvertorialCopy copy,
                                            Instant startsAt, Instant endsAt, String actor, Instant now) {
        return authored(id, source, sourceId, locationId, variant, weight, priority, copy, startsAt, endsAt, List.of(),
                actor, now);
    }

    /** A draft staff write, with the products they chose to feature. */
    public static LocalAdvertorial authored(UUID id, AdvertorialSource source, UUID sourceId, UUID locationId,
                                            String variant, int weight, int priority, AdvertorialCopy copy,
                                            Instant startsAt, Instant endsAt, List<FeaturedProduct> products,
                                            String actor, Instant now) {
        return new LocalAdvertorial(id, source, sourceId, locationId, variant, weight, priority, false, copy,
                startsAt, endsAt, AdvertorialStatus.DRAFT, null, products, actor, now, now, 0);
    }

    /** True while visitors may see it: live, inside its own schedule. (Its source must be taking orders too.) */
    public boolean servesAt(Instant now) {
        return status == AdvertorialStatus.LIVE && (startsAt == null || !now.isBefore(startsAt))
                && (endsAt == null || now.isBefore(endsAt));
    }

    /** Staff edit the words, variant, share and schedule; the words no longer follow the source. */
    public LocalAdvertorial edit(AdvertorialCopy words, String newVariant, int newWeight, int newPriority,
                                 Instant starts, Instant ends, Instant now) {
        return edit(words, newVariant, newWeight, newPriority, starts, ends, featured, now);
    }

    /** As above, with the featured products staff chose (an empty list clears them). */
    public LocalAdvertorial edit(AdvertorialCopy words, String newVariant, int newWeight, int newPriority,
                                 Instant starts, Instant ends, List<FeaturedProduct> products, Instant now) {
        requireNotEnded(AdvertorialStatus.DRAFT);
        return new LocalAdvertorial(id, source, sourceId, locationId, newVariant, newWeight, newPriority, false,
                words, starts, ends, status, ended, products, createdBy, createdAt, now, version);
    }

    /** The system re-drafts the words of a generated advertorial after its source changed. */
    public LocalAdvertorial redraft(AdvertorialCopy words, Instant now) {
        if (!generated || status == AdvertorialStatus.ENDED || copy.equals(words)) {
            return this;
        }
        return new LocalAdvertorial(id, source, sourceId, locationId, variant, weight, priority, true, words,
                startsAt, endsAt, status, ended, featured, createdBy, createdAt, now, version);
    }

    /** Back to following the source: new drafted words. */
    public LocalAdvertorial regenerated(AdvertorialCopy words, Instant now) {
        requireNotEnded(AdvertorialStatus.DRAFT);
        return new LocalAdvertorial(id, source, sourceId, locationId, variant, weight, priority, true, words,
                startsAt, endsAt, status, ended, featured, createdBy, createdAt, now, version);
    }

    public LocalAdvertorial publish(Instant now) {
        if (status != AdvertorialStatus.DRAFT && status != AdvertorialStatus.PAUSED) {
            throw new InvalidTransitionException(status.name(), AdvertorialStatus.LIVE.name());
        }
        return with(AdvertorialStatus.LIVE, null, now);
    }

    public LocalAdvertorial pause(Instant now) {
        if (status != AdvertorialStatus.LIVE) {
            throw new InvalidTransitionException(status.name(), AdvertorialStatus.PAUSED.name());
        }
        return with(AdvertorialStatus.PAUSED, null, now);
    }

    /** Ends it for good; ending an ended one keeps the first reason. */
    public LocalAdvertorial end(AdvertorialEnd why, Instant now) {
        if (status == AdvertorialStatus.ENDED) {
            return this;
        }
        return with(AdvertorialStatus.ENDED, why, now);
    }

    /**
     * Live again after the sweeper ended it because its source stopped taking orders and the source then took orders
     * again (a run extended past its cutoff, a paused window resumed). Anything else, including one staff ended, stays
     * as it is.
     */
    public LocalAdvertorial reopen(Instant now) {
        if (status != AdvertorialStatus.ENDED || ended != AdvertorialEnd.SOURCE_CLOSED) {
            return this;
        }
        return with(AdvertorialStatus.LIVE, null, now);
    }

    private void requireNotEnded(AdvertorialStatus target) {
        if (status == AdvertorialStatus.ENDED) {
            throw new InvalidTransitionException(status.name(), target.name());
        }
    }

    private LocalAdvertorial with(AdvertorialStatus next, AdvertorialEnd why, Instant now) {
        return new LocalAdvertorial(id, source, sourceId, locationId, variant, weight, priority, generated, copy,
                startsAt, endsAt, next, why, featured, createdBy, createdAt, now, version);
    }
}

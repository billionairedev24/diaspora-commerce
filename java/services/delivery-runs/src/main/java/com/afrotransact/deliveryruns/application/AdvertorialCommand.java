package com.afrotransact.deliveryruns.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.AdvertorialSource;
import com.afrotransact.deliveryruns.domain.FeaturedProduct;

/**
 * What staff write for an advertorial. Nulls take the defaults (variant B-style labels are staff's to choose; weight
 * 1, priority 0); on an edit {@code source} and {@code sourceId} are ignored.
 *
 * @param source    window or run
 * @param sourceId  window id or run id
 * @param variant   label of the variant, e.g. {@code A} or {@code B}
 * @param weight    share of the source's visitors among its variants (1 to 100)
 * @param priority  higher first when an area has several sources (0 to 1000)
 * @param headline  up to 140 characters
 * @param body      up to 400 characters
 * @param ctaLabel  the button, up to 40 characters
 * @param startsAt  not shown before (null = from the start)
 * @param endsAt    not shown from (null = while the source takes orders)
 * @param featured  up to four products to show on the card; null keeps the current ones (none on a new one)
 */
public record AdvertorialCommand(AdvertorialSource source, UUID sourceId, String variant, Integer weight,
                                 Integer priority, String headline, String body, String ctaLabel, Instant startsAt,
                                 Instant endsAt, List<FeaturedProduct> featured) {

    /** Without a say about featured products. */
    public AdvertorialCommand(AdvertorialSource source, UUID sourceId, String variant, Integer weight,
                              Integer priority, String headline, String body, String ctaLabel, Instant startsAt,
                              Instant endsAt) {
        this(source, sourceId, variant, weight, priority, headline, body, ctaLabel, startsAt, endsAt, null);
    }
}

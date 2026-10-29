package com.afrotransact.deliveryruns.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.AdvertorialSource;
import com.afrotransact.deliveryruns.domain.FeaturedProduct;

/**
 * Public (no sign-in): the advertorials a visitor sees for their deliver-to place, and the counting of views and
 * clicks (S263). Only advertorials whose window or run takes orders right now are served.
 */
public interface AdvertorialServing {

    /**
     * What a visitor at this place sees: the live advertorials of the open windows and runs that serve it, one variant
     * each (a visitor keeps the variant they were given), higher priority first, then the soonest to close.
     */
    List<ServedAdvertorial> serve(AdvertorialQuery query);

    /**
     * Counts one view or click (404 for an unknown advertorial). Best effort, never changes what is served. Only an
     * advertorial that is live, inside its schedule and whose window or run takes orders right now counts; a draft,
     * paused, ended or out-of-schedule one is ignored without an error. Each visitor counts once per advertorial and
     * kind in the event window (a visitor id, else the client address), and one client address only a few times, so a
     * direct POST cannot inflate the results.
     *
     * @param visitor the visitor id the storefront also sends for sticky variants (null when none)
     * @param client  the client address the request came from (null when unknown)
     */
    void record(UUID advertorialId, Event event, String visitor, String client);

    /** What happened. */
    enum Event {
        VIEW,
        CLICK
    }

    /**
     * Where the visitor is. A ZIP or coordinates match delivery runs by coverage; country and region (and city) match
     * pre-order windows.
     *
     * @param zip     5-digit ZIP
     * @param lat     latitude
     * @param lng     longitude
     * @param country ISO country code
     * @param region  state or region code
     * @param city    city
     * @param visitor an opaque, stable id of the visitor or session: the same visitor keeps their A/B variant
     * @param limit   at most this many (1 to 10)
     */
    record AdvertorialQuery(String zip, Double lat, Double lng, String country, String region, String city,
                            String visitor, int limit) {
    }

    /**
     * An advertorial ready to show.
     *
     * @param id              advertorial id (count views and clicks with it)
     * @param source          window or run
     * @param sourceId        window id or run id
     * @param variant         the variant the visitor got
     * @param code            window code (null for runs)
     * @param place           the town or area
     * @param headline        words
     * @param body            words
     * @param ctaLabel        the button
     * @param ctaUrl          where it goes, with UTM and (windows) order attribution
     * @param imageUrl        the window's hero, when it has one
     * @param featured        up to four products staff chose to show on the card
     * @param countdownTarget the instant the live countdown counts down to (the close or cutoff)
     * @param expiresAt       when it stops being served
     * @param deliveryDisplay the delivery day, e.g. "Sunday, October 25"
     * @param feeDisplay      the delivery fee, e.g. "$7.99" (null when none applies)
     */
    record ServedAdvertorial(UUID id, AdvertorialSource source, UUID sourceId, String variant, String code,
                             String place, String headline, String body, String ctaLabel, String ctaUrl,
                             String imageUrl, List<FeaturedProduct> featured, Instant countdownTarget,
                             Instant expiresAt, String deliveryDisplay, String feeDisplay) {
    }
}

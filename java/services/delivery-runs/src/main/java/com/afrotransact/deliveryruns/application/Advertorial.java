package com.afrotransact.deliveryruns.application;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.AreaKind;

/**
 * Data for an auto-generated run advertorial (S263): content-service renders it, targets it by area and expires it
 * at {@code expiresAt}.
 *
 * @param runId             run
 * @param locationId        location (targeting key)
 * @param locationSlug      location page key
 * @param town              town / area name
 * @param market            market code
 * @param state             state
 * @param status            run status
 * @param acceptingOrders   preorders are open now
 * @param deliveryDate      delivery day in the location's zone, ISO date
 * @param deliveryDay       e.g. "Saturday, October 24"
 * @param windowStart       window start
 * @param windowEnd         window end
 * @param windowDisplay     e.g. "10:00 AM – 2:00 PM"
 * @param cutoffAt          order cutoff
 * @param cutoffDisplay     e.g. "Thu, Oct 22 · 8:00 PM CDT"
 * @param countdownTarget   the instant a live countdown counts down to (the cutoff)
 * @param expiresAt         when the advertorial stops showing (the cutoff)
 * @param feeMinor          run fee
 * @param currency          currency
 * @param feeDisplay        e.g. "$7.99"
 * @param dropMode          DOORSTEP or PICKUP_POINT
 * @param pickupPointDisplay one line pickup-point address, for pickup-point runs
 * @param headline          default headline, e.g. "AfroTransact delivery run to Bastrop · Saturday, October 24"
 * @param ctaLabel          default call to action
 * @param ctaUrl            run link attributed to {@code utm_medium=advertorial}
 * @param whatsappInviteUrl the location's WhatsApp community
 * @param areaKind          POLYGON or RADIUS
 * @param geoZoneId         geo zone of a polygon area
 * @param centerLat         area centre
 * @param centerLng         area centre
 * @param radiusMiles       radius of a radius area
 */
public record Advertorial(UUID runId, UUID locationId, String locationSlug, String town, String market, String state,
                          String status, boolean acceptingOrders, String deliveryDate, String deliveryDay,
                          Instant windowStart, Instant windowEnd, String windowDisplay, Instant cutoffAt,
                          String cutoffDisplay, Instant countdownTarget, Instant expiresAt, long feeMinor,
                          String currency, String feeDisplay, String dropMode, String pickupPointDisplay,
                          String headline, String ctaLabel, String ctaUrl, String whatsappInviteUrl,
                          AreaKind areaKind, String geoZoneId, double centerLat, double centerLng,
                          Double radiusMiles) {
}

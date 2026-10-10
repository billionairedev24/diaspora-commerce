package com.afrotransact.deliveryruns.application;

import java.time.Duration;

import com.afrotransact.deliveryruns.domain.GeoPoint;

/**
 * Service settings ({@code at.delivery-runs.*}).
 *
 * @param publicWebUrl       storefront origin ({@code PUBLIC_WEB_URL}); every link in events and advertorials
 * @param locationPath       storefront path of location pages, e.g. {@code /delivery-runs}
 * @param geoZoneType        geo zone type of run-area polygons (and of coverage lookups)
 * @param defaultOrigin      where runs leave from (FC / hub) when a location sets no origin
 * @param reminderLead       cutoff reminder lead time (24 h)
 * @param confirmationTtl    how long an email confirmation link works
 * @param nextRunSearchLimit how many later runs a full or closed run's order may roll over to
 */
public record DeliveryRunsSettings(String publicWebUrl, String locationPath, String geoZoneType, GeoPoint defaultOrigin,
                                   Duration reminderLead, Duration confirmationTtl, int nextRunSearchLimit) {

    public DeliveryRunsSettings {
        publicWebUrl = publicWebUrl.endsWith("/") ? publicWebUrl.substring(0, publicWebUrl.length() - 1) : publicWebUrl;
        locationPath = locationPath.startsWith("/") ? locationPath : "/" + locationPath;
    }
}

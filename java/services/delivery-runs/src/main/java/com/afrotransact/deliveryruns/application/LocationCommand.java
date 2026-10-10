package com.afrotransact.deliveryruns.application;

import java.util.Map;

import com.afrotransact.deliveryruns.domain.AreaKind;
import com.afrotransact.deliveryruns.domain.CutoffPolicy;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.PickupPoint;

/**
 * Create or replace a location. A POLYGON area takes an existing {@code geoZoneId} or a {@code polygonGeoJson} that is
 * registered in geo-service; {@code centerLat/centerLng} is always required (radius centre / drive destination).
 */
public record LocationCommand(String slug, String name, String market, String state, String timeZone,
                              AreaKind areaKind, String geoZoneId, String polygonGeoJson, Double centerLat,
                              Double centerLng, Double radiusMiles, Double originLat, Double originLng,
                              Long defaultFeeMinor, String currency, Integer defaultMinOrders,
                              Integer defaultCapacityOrders, Integer defaultCapacityPackages, DropMode dropMode,
                              PickupPoint pickupPoint, String whatsappInviteUrl, Map<String, String> socialLinks,
                              CutoffPolicy defaultCutoffPolicy, Integer defaultExtendDays, Boolean active) {
}

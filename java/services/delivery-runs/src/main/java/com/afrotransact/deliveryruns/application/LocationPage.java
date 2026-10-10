package com.afrotransact.deliveryruns.application;

import java.util.List;
import java.util.Map;

import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.Run;

/**
 * Public location page data: the location, its upcoming runs, the page link and share links (UTM-attributed).
 *
 * @param location    active location
 * @param runs        announced / open / extended / confirmed / in-progress runs whose window has not ended
 * @param locationUrl storefront page
 * @param shareLinks  facebook, x, whatsapp share intents
 */
public record LocationPage(Location location, List<Run> runs, String locationUrl, Map<String, String> shareLinks) {
}

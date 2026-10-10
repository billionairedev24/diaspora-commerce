package com.afrotransact.content.domain.site;

/**
 * App store links; null until the app is published.
 *
 * @param ios     App Store URL
 * @param android Google Play URL
 */
public record AppLinks(String ios, String android) {
}

package com.afrotransact.content.domain.site;

/**
 * A social profile link.
 *
 * @param network network
 * @param url     profile URL (validated against the network)
 * @param visible shown in footers and the app
 * @param order   display order
 */
public record SocialLink(SocialNetwork network, String url, boolean visible, int order) {
}

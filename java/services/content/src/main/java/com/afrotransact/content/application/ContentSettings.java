package com.afrotransact.content.application;

import java.time.Duration;

/**
 * Runtime settings used by use cases and adapters.
 *
 * @param flagCacheTtl          flags reach every replica within this time (kill switch SLA: 30 s)
 * @param marketCacheTtl        published markets cache
 * @param placementCacheTtl     servable placements cache
 * @param resolveMaxAge         Cache-Control max-age of placement resolve responses
 * @param bundleMaxAge          browser max-age of market bundles
 * @param bundleSharedMaxAge    CDN s-maxage of market bundles (purged on publish)
 * @param siteMaxAge            max-age of site config and legal pages
 * @param legalBaseHref         public path prefix of legal pages
 * @param newsletterTokenKey    HMAC key for newsletter links (secret)
 * @param newsletterTokenTtl    confirm link lifetime
 * @param newsletterConfirmUrl  storefront page that confirms a subscription
 * @param newsletterUnsubscribeUrl storefront page that unsubscribes
 */
public record ContentSettings(Duration flagCacheTtl, Duration marketCacheTtl, Duration placementCacheTtl,
                              Duration resolveMaxAge, Duration bundleMaxAge, Duration bundleSharedMaxAge,
                              Duration siteMaxAge, String legalBaseHref, String newsletterTokenKey,
                              Duration newsletterTokenTtl, String newsletterConfirmUrl,
                              String newsletterUnsubscribeUrl) {

    @Override
    public String toString() {
        return "ContentSettings[flagCacheTtl=" + flagCacheTtl + ", marketCacheTtl=" + marketCacheTtl
                + ", placementCacheTtl=" + placementCacheTtl + ", newsletterTokenKey=***]";
    }
}

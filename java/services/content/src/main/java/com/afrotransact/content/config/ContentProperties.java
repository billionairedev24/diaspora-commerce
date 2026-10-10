package com.afrotransact.content.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code at.content.*} settings.
 *
 * @param publicPostPaths anonymous POST endpoints (visitor forms, flag evaluation)
 * @param flags           flag evaluation
 * @param markets         published markets cache
 * @param placements      placement caching
 * @param site            site config and legal pages
 * @param newsletter      double opt-in links
 * @param jobs            scheduled jobs
 * @param retailOrgId     AfroTransact Retail (1P): its ads campaigns are internal and never billed
 */
@ConfigurationProperties(prefix = "at.content")
public record ContentProperties(List<String> publicPostPaths, Flags flags, Markets markets, Placements placements,
                                Site site, Newsletter newsletter, Jobs jobs, String retailOrgId) {

    public ContentProperties {
        publicPostPaths = publicPostPaths == null ? List.of() : List.copyOf(publicPostPaths);
        flags = flags == null ? new Flags(null) : flags;
        markets = markets == null ? new Markets(null) : markets;
        placements = placements == null ? new Placements(null, null, null, null) : placements;
        site = site == null ? new Site(null, null) : site;
        newsletter = newsletter == null ? new Newsletter(null, null, null, null) : newsletter;
        jobs = jobs == null ? new Jobs(null) : jobs;
        retailOrgId = retailOrgId == null || retailOrgId.isBlank() ? "org_afrotransact_retail" : retailOrgId.trim();
    }

    /** @param cacheTtl changes reach every replica within this TTL (kill switch: 30 s) */
    public record Flags(Duration cacheTtl) {

        public Flags {
            cacheTtl = cacheTtl == null ? Duration.ofSeconds(30) : cacheTtl;
        }
    }

    /** @param cacheTtl published markets cache */
    public record Markets(Duration cacheTtl) {

        public Markets {
            cacheTtl = cacheTtl == null ? Duration.ofSeconds(30) : cacheTtl;
        }
    }

    /**
     * Placement caching.
     *
     * @param cacheTtl           servable placements cache
     * @param resolveMaxAge      resolve Cache-Control max-age
     * @param bundleMaxAge       bundle browser max-age
     * @param bundleSharedMaxAge bundle CDN s-maxage
     */
    public record Placements(Duration cacheTtl, Duration resolveMaxAge, Duration bundleMaxAge,
                             Duration bundleSharedMaxAge) {

        public Placements {
            cacheTtl = cacheTtl == null ? Duration.ofSeconds(10) : cacheTtl;
            resolveMaxAge = resolveMaxAge == null ? Duration.ofSeconds(30) : resolveMaxAge;
            bundleMaxAge = bundleMaxAge == null ? Duration.ofSeconds(60) : bundleMaxAge;
            bundleSharedMaxAge = bundleSharedMaxAge == null ? Duration.ofMinutes(5) : bundleSharedMaxAge;
        }
    }

    /**
     * Site config and legal pages.
     *
     * @param maxAge        Cache-Control max-age
     * @param legalBaseHref public path of legal pages
     */
    public record Site(Duration maxAge, String legalBaseHref) {

        public Site {
            maxAge = maxAge == null ? Duration.ofSeconds(60) : maxAge;
            legalBaseHref = legalBaseHref == null ? "/legal/" : legalBaseHref;
        }
    }

    /**
     * Newsletter links.
     *
     * @param tokenKey       HMAC key (secret, NEWSLETTER_TOKEN_KEY)
     * @param tokenTtl       confirm link lifetime
     * @param confirmUrl     storefront confirm page (absolute: it goes into emails; built from PUBLIC_WEB_URL)
     * @param unsubscribeUrl storefront unsubscribe page (absolute, as confirmUrl)
     */
    public record Newsletter(String tokenKey, Duration tokenTtl, String confirmUrl, String unsubscribeUrl) {

        /** Local storefront (infra/local/PORTS.md); deployed environments always set the URLs from PUBLIC_WEB_URL. */
        private static final String LOCAL_STOREFRONT = "http://localhost:3000";

        public Newsletter {
            tokenTtl = tokenTtl == null ? Duration.ofHours(72) : tokenTtl;
            confirmUrl = confirmUrl == null ? LOCAL_STOREFRONT + "/newsletter/confirm" : confirmUrl;
            unsubscribeUrl = unsubscribeUrl == null ? LOCAL_STOREFRONT + "/newsletter/unsubscribe" : unsubscribeUrl;
        }

        @Override
        public String toString() {
            return "Newsletter[tokenKey=***, tokenTtl=" + tokenTtl + ", confirmUrl=" + confirmUrl + "]";
        }
    }

    /** @param enabled run the placement lifecycle job */
    public record Jobs(Boolean enabled) {

        public Jobs {
            enabled = enabled == null ? Boolean.TRUE : enabled;
        }
    }
}

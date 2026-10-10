package com.afrotransact.content.domain.site;

/** Validates a site configuration before it is saved; a bad profile link blocks saving. */
public interface SiteConfigPolicy {

    /** Throws {@code ContentRuleViolation} with per-field errors when the configuration is invalid. */
    void validate(SiteConfig config);
}

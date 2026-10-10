package com.afrotransact.content.application;

/** Event types published by content-service (contracts/asyncapi/fragments/content.yaml). */
public final class ContentEvents {

    /**
     * Every content event goes to {@code at.content.events}. Keys keep per-entity order: market for placements,
     * {@code markets}, {@code site-config}, the legal document key, the flag key, the subscription id, the country
     * for launch lists and the CAR reference for applications.
     */
    public static final String AGGREGATE = "content";

    public static final String PLACEMENTS_PUBLISHED = "content.placements.published";
    public static final String MARKETS_PUBLISHED = "content.markets.published";
    public static final String SITE_CONFIG_CHANGED = "content.site-config.changed";
    public static final String LEGAL_VERSION_PUBLISHED = "content.legal.version_published";
    public static final String LEGAL_VERSION_AMENDED = "content.legal.version_amended";
    public static final String FLAG_CHANGED = "content.flag.changed";
    public static final String NEWSLETTER_CONFIRMATION_REQUESTED = "content.newsletter.confirmation_requested";
    public static final String NEWSLETTER_SUBSCRIBED = "content.newsletter.subscribed";
    public static final String NEWSLETTER_UNSUBSCRIBED = "content.newsletter.unsubscribed";
    public static final String LAUNCH_LIST_JOINED = "content.launch-list.joined";
    public static final String CAREER_APPLICATION_RECEIVED = "content.career-application.received";

    private ContentEvents() {
    }
}

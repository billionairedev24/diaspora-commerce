package com.afrotransact.seller.application;

/** Organisations: public profile for other services, details for members and staff, display-name changes. */
public interface OrgService {

    OrgPublicView publicProfile(String orgId);

    OrgView get(String orgId);

    /** Owner renames the store; publishes seller.org.updated (indexer and cart refresh seller names). */
    OrgView rename(String orgId, String displayName);
}

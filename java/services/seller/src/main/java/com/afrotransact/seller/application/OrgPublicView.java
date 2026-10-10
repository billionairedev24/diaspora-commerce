package com.afrotransact.seller.application;

/** What other services (indexer, cart, storefront) may know about an organisation. */
public record OrgPublicView(String id, String kind, String displayName, String status) {
}

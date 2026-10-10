package com.afrotransact.seller.application;

/** A document a reviewer opened (the view is logged) with a short-lived URL from media-service. */
public record DocumentView(String assetId, String kind, String label, String url) {
}

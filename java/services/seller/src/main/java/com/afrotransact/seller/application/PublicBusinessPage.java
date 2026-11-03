package com.afrotransact.seller.application;

/**
 * What the public page of a business without a website shows. The listings are added by the BFF from the catalogue.
 *
 * @param orgId       the organisation (the BFF reads its live listings with it)
 * @param kind        SELLER or PROVIDER
 * @param approved    the organisation is approved: until then the page is not offered to search engines
 * @param brandColor  {@code #rrggbb} or null
 * @param logoUrl     https URL or null
 */
public record PublicBusinessPage(String orgId, String slug, String kind, String name, String description,
                                 String brandColor, String logoUrl, String contactEmail, boolean approved) {
}

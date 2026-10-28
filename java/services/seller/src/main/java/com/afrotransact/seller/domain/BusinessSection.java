package com.afrotransact.seller.domain;

/**
 * Step "business": what they offer, legal form, market and how we reach them. Phone is stored in E.164.
 *
 * @param website  no longer asked for (every business gets a plain AfroTransact page, which Stripe is given as its
 *                 website); kept only so applications saved earlier still read
 * @param branding how that page looks (description, logo, one brand colour); null when none was given
 */
public record BusinessSection(Offering offering, EntityType entityType, String country, String storeName,
                              String contactEmail, String phone, String website, Branding branding) {

    /** A business section without branding. */
    public BusinessSection(Offering offering, EntityType entityType, String country, String storeName,
                           String contactEmail, String phone, String website) {
        this(offering, entityType, country, storeName, contactEmail, phone, website, null);
    }

    /**
     * How the plain public page looks.
     *
     * @param description what the business sells or does, in a few sentences
     * @param brandColor  one colour, {@code #rrggbb}
     * @param logoUrl     https URL of the logo (a public media asset)
     */
    public record Branding(String description, String brandColor, String logoUrl) {
    }
}

package com.afrotransact.seller.domain;

/** Step "business": what they offer, legal form, market and how we reach them. Phone is stored in E.164. */
public record BusinessSection(Offering offering, EntityType entityType, String country, String storeName,
                              String contactEmail, String phone, String website) {
}

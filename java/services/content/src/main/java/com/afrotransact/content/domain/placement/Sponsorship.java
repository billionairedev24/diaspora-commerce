package com.afrotransact.content.domain.placement;

/**
 * Whether a placement is a paid third-party creative. Paid creatives are labelled "Sponsored" and need approval
 * by someone other than their author (four-eyes).
 *
 * @param paid       paid by a third party
 * @param advertiser advertiser name shown with the creative (required when paid)
 */
public record Sponsorship(boolean paid, String advertiser) {

    public static Sponsorship none() {
        return new Sponsorship(false, null);
    }
}

package com.afrotransact.content.application;

import java.time.LocalDate;

import com.afrotransact.content.domain.legal.LegalVersion;

/**
 * Current version of a legal document, as listed in the site config and the legal center menu.
 *
 * @param key           document key
 * @param title         title
 * @param version       current version
 * @param effectiveDate effective date
 * @param href          public page path
 * @param draftBanner   counsel-review banner shown
 */
public record LegalSummary(String key, String title, String version, LocalDate effectiveDate, String href,
                           boolean draftBanner) {

    public static LegalSummary of(LegalVersion v, String baseHref) {
        return new LegalSummary(v.type().key(), v.type().title(), v.version().toString(), v.effectiveDate(),
                baseHref + v.type().key(), v.draftBanner());
    }
}

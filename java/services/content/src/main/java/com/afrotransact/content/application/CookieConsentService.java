package com.afrotransact.content.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.afrotransact.content.domain.legal.ConsentRecord;
import com.afrotransact.content.domain.legal.CookieCategory;
import com.afrotransact.content.domain.legal.CookieDefinition;

/** Cookie banner configuration and auditable consent records (with Global Privacy Control). */
public interface CookieConsentService {

    CookiePolicy policy();

    /**
     * Normalises and stores a decision: required categories on; GPC-locked categories off when the browser sends
     * Global Privacy Control or the visitor opts out of sale and sharing (GPC implies that opt-out). Returns the record.
     */
    ConsentRecord record(String subject, Map<String, Boolean> choices, boolean gpc, boolean saleOptOut, String source);

    CookieCategory saveCategory(CookieCategory category);

    CookieDefinition saveCookie(CookieDefinition cookie);

    void deleteCookie(String name);

    /**
     * What the consent banner renders.
     *
     * @param noticeVersion current Cookie Notice version (stored with each consent)
     * @param effectiveDate its effective date
     * @param noticeHref    Cookie Notice page
     * @param categories    categories in display order
     * @param cookies       cookie table
     * @param etag          fingerprint
     */
    record CookiePolicy(String noticeVersion, LocalDate effectiveDate, String noticeHref,
                        List<CookieCategory> categories, List<CookieDefinition> cookies, String etag) {
    }
}

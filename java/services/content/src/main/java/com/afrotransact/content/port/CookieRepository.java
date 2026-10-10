package com.afrotransact.content.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.content.domain.legal.CookieCategory;
import com.afrotransact.content.domain.legal.CookieDefinition;
import com.afrotransact.content.domain.legal.ConsentRecord;

/** Cookie categories, the cookie table and stored consent decisions. */
public interface CookieRepository {

    List<CookieCategory> categories();

    List<CookieDefinition> cookies();

    void saveCategory(CookieCategory category);

    void saveCookie(CookieDefinition cookie);

    boolean deleteCookie(String name);

    void insertConsent(ConsentRecord record);

    Optional<ConsentRecord> findConsent(UUID id);
}

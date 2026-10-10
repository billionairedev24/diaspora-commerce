package com.afrotransact.content.application;

import java.util.List;
import java.util.Map;

import com.afrotransact.content.domain.market.Capability;
import com.afrotransact.content.domain.market.Country;
import com.afrotransact.content.domain.market.MarketChanges;
import com.afrotransact.content.domain.market.MarketStatus;
import com.afrotransact.content.port.MarketRepository.Publication;

/**
 * Markets &amp; locations in the back office. Changes go to a draft; Publish makes them a new immutable version
 * that eligibility, checkout and onboarding read.
 */
public interface MarketService {

    List<Country> draftCountries();

    Country draftCountry(String code);

    Country updateCountry(String code, MarketStatus status, String launchNote);

    /** Changes switches of one region; marketplace off turns everything off there. */
    Country setCapabilities(String country, String region, Map<Capability, Boolean> changes);

    Country activateAll(String country);

    Country deactivateAll(String country);

    /** What Publish would change. */
    MarketChanges pendingChanges();

    PublishResult publish(String note);

    List<Publication> publications();

    /**
     * Outcome of a publish.
     *
     * @param version new version
     * @param changes what changed
     */
    record PublishResult(int version, MarketChanges changes) {
    }
}

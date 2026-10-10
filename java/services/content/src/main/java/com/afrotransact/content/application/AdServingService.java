package com.afrotransact.content.application;

import java.util.List;

/**
 * Campaigns eligible to serve, for search-service's auction (scope {@code ads:serve}): ACTIVE, inside their schedule
 * and with budget left today. Search adds the product-level rules (in stock, buy box, seller visible, relevance).
 */
public interface AdServingService {

    List<ServableCampaign> eligible(String market);
}

package com.afrotransact.content.application;

import com.afrotransact.content.domain.newsletter.LaunchAudience;
import com.afrotransact.content.domain.newsletter.LaunchListEntry;

/** Launch lists for countries that are not live yet (onboarding's "let me know when you launch"). */
public interface LaunchListService {

    LaunchListEntry join(String email, String country, LaunchAudience audience, boolean shoppingUpdates);
}

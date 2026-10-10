package com.afrotransact.identity.port;

import java.util.Set;

/** Which social providers have OAuth client credentials configured in this environment. */
public interface SocialProviderCatalog {

    Set<String> configuredProviders();
}

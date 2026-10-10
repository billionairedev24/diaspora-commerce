package com.afrotransact.content.domain.site;

/**
 * Display order of a sign-in provider button. Whether a provider is enabled is owned by identity-service.
 *
 * @param id    provider id (google, apple, facebook)
 * @param name  button label
 * @param order display order
 */
public record IdentityProviderEntry(String id, String name, int order) {
}

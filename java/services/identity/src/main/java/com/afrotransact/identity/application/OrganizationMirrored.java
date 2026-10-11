package com.afrotransact.identity.application;

/** In-process notice that an organisation from the seller service was created or updated in identity. */
public record OrganizationMirrored(String orgId) {
}

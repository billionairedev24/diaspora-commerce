package com.afrotransact.identity.application;

import com.afrotransact.identity.domain.IdentityProviderSetting;

/** Developers › Identity providers row: the switch plus whether client credentials are configured. */
public record ProviderAdminView(IdentityProviderSetting setting, boolean configured) {
}

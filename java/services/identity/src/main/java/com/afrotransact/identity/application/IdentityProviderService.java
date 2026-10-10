package com.afrotransact.identity.application;

import java.util.List;

import com.afrotransact.identity.domain.IdentityProviderSetting;

/** Which social providers appear on sign-in screens. */
public interface IdentityProviderService {

    /** Public: providers that are both enabled by the platform and configured in this environment. */
    List<IdentityProviderSetting> enabledProviders();

    boolean isEnabled(String providerId);

    /** {@code access:read}. */
    List<ProviderAdminView> all();

    /** {@code access:admin}. */
    ProviderAdminView setEnabled(String providerId, boolean enabled);
}

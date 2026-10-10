package com.afrotransact.identity.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.identity.domain.IdentityProviderSetting;

/** Platform switches for social sign-in providers. */
public interface IdentityProviderSettingsRepository {

    List<IdentityProviderSetting> all();

    Optional<IdentityProviderSetting> find(String id);

    void save(IdentityProviderSetting setting);
}

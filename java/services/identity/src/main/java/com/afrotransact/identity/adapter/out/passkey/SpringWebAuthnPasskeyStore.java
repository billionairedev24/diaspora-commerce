package com.afrotransact.identity.adapter.out.passkey;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.domain.Passkey;
import com.afrotransact.identity.port.PasskeyStore;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.stereotype.Component;

/**
 * Reads Spring Security's WebAuthn repositories. The WebAuthn user entity name is the account id (the session
 * principal name), so passkeys map to accounts without a join table.
 */
@Component
class SpringWebAuthnPasskeyStore implements PasskeyStore {

    private final PublicKeyCredentialUserEntityRepository userEntities;
    private final UserCredentialRepository credentials;

    SpringWebAuthnPasskeyStore(PublicKeyCredentialUserEntityRepository userEntities,
                               UserCredentialRepository credentials) {
        this.userEntities = userEntities;
        this.credentials = credentials;
    }

    @Override
    public List<Passkey> list(UUID userId) {
        PublicKeyCredentialUserEntity entity = userEntities.findByUsername(userId.toString());
        if (entity == null) {
            return List.of();
        }
        return credentials.findByUserId(entity.getId()).stream()
                .sorted(Comparator.comparing(CredentialRecord::getCreated))
                .map(c -> new Passkey(c.getCredentialId().toBase64UrlString(), c.getLabel(), c.getCreated(),
                        c.getLastUsed()))
                .toList();
    }

    @Override
    public boolean delete(UUID userId, String credentialId) {
        boolean owned = list(userId).stream().anyMatch(p -> p.credentialId().equals(credentialId));
        if (owned) {
            credentials.delete(Bytes.fromBase64(credentialId));
        }
        return owned;
    }
}

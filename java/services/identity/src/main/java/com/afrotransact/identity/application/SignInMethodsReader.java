package com.afrotransact.identity.application;

import java.util.UUID;
import java.util.stream.Collectors;

import com.afrotransact.identity.domain.FederatedIdentity;
import com.afrotransact.identity.domain.SignInMethods;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.FederatedIdentityRepository;
import com.afrotransact.identity.port.PasskeyStore;
import org.springframework.stereotype.Component;

/** Counts a user's sign-in methods (password, passkeys, social providers). */
@Component
class SignInMethodsReader {

    private final FederatedIdentityRepository federated;
    private final PasskeyStore passkeys;

    SignInMethodsReader(FederatedIdentityRepository federated, PasskeyStore passkeys) {
        this.federated = federated;
        this.passkeys = passkeys;
    }

    SignInMethods read(UserAccount user) {
        UUID id = user.id();
        return new SignInMethods(user.hasPassword(), passkeys.list(id).size(),
                federated.forUser(id).stream().map(FederatedIdentity::provider).collect(Collectors.toSet()));
    }
}

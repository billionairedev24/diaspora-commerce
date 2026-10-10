package com.afrotransact.identity.application;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.AccessPolicy;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TokenClaimsServiceImpl implements TokenClaimsService {

    private final UserAccountRepository users;
    private final AccessProfileLoader profiles;
    private final AccessPolicy policy;

    TokenClaimsServiceImpl(UserAccountRepository users, AccessProfileLoader profiles, AccessPolicy policy) {
        this.users = users;
        this.profiles = profiles;
        this.policy = policy;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TokenClaims> forSubject(String subject) {
        UUID id;
        try {
            id = UUID.fromString(subject);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        Optional<UserAccount> user = users.findById(id);
        return user.map(u -> new TokenClaims(u.id(), u, policy.resolve(profiles.load(u))));
    }
}

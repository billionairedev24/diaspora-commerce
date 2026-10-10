package com.afrotransact.identity.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.UserAccount;

/** Persistence of accounts. */
public interface UserAccountRepository {

    UserAccount insert(UserAccount account);

    /** Optimistic update; throws when the version changed concurrently. */
    UserAccount update(UserAccount account);

    Optional<UserAccount> findById(UUID id);

    Optional<UserAccount> findByEmail(String email);

    List<UserAccount> search(String query, int limit, int offset);

    long count(String query);
}

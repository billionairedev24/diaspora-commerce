package com.afrotransact.identity.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.LoginTicket;
import com.afrotransact.identity.domain.SignInChallenge;

/** Pending first-party challenges and one-time login tickets. */
public interface FirstPartySignInStore {

    void insertChallenge(SignInChallenge challenge);

    /** The challenge, row-locked until the end of the caller's transaction. */
    Optional<SignInChallenge> lockChallenge(UUID id);

    void updateChallenge(SignInChallenge challenge);

    void insertTicket(LoginTicket ticket);

    /**
     * Marks the ticket used (whatever the outcome, so a ticket can never be tried twice) and returns it as it was
     * before; empty when unknown or already used.
     */
    Optional<LoginTicket> burnTicket(String tokenHash, Instant now);

    /** Deletes expired challenges and tickets; returns the number of rows removed. */
    int purgeExpired(Instant before);
}

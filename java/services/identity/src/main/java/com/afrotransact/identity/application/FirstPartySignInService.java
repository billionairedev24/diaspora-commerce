package com.afrotransact.identity.application;

import java.util.Optional;
import java.util.UUID;

/**
 * First-party sign-in API for the BFF (the storefront keeps its own sign-in, MFA and passkey forms). Every method
 * except {@link #redeemLoginTicket} needs a client-credentials token of a configured first-party client with scope
 * {@code auth:first-party}. A completed sign-in yields a one-time login ticket that the BFF redeems on the normal
 * authorization-code flow ({@code /oauth2/authorize?…&login_ticket=…}), so tokens still come from the authorization
 * server with the usual claims, refresh tokens and session tracking.
 */
public interface FirstPartySignInService {

    /** Email + password; returns an MFA challenge when a second factor is required, else a login ticket. */
    SignInStep password(PasswordSignIn command);

    /** Texts a sign-in code for an MFA challenge (rate limited like the hosted page). */
    SmsCodeSent sendSmsCode(UUID challengeId, ClientContext context);

    /** Authenticator, text-message or recovery code for an MFA challenge; returns a login ticket. */
    SignInStep verifySecondFactor(SecondFactorSubmission submission);

    /** Starts a passkey sign-in (discoverable credential, user verification required). */
    PasskeyChallengeView passkeyOptions(ClientContext context);

    /** Verifies the passkey assertion; passkeys count as both factors. Returns a login ticket. */
    SignInStep verifyPasskey(UUID challengeId, String credentialJson, ClientContext context);

    /**
     * Burns a login ticket (single use, 60 seconds) for {@code clientId}; called by the authorization endpoint.
     *
     * @return the account, sign-in method and time, when the ticket is valid for this client
     */
    Optional<RedeemedLoginTicket> redeemLoginTicket(String ticket, String clientId);
}

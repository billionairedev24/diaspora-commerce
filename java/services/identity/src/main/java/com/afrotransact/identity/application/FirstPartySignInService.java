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

    /**
     * Email + password. A code is asked only of people who enrolled a factor (MFA_REQUIRED, an email code is sent
     * straight away when it is their only factor); where MFA is mandatory and nothing is set up the step is
     * MFA_ENROLMENT_REQUIRED (no code is sent, enrol first); everyone else gets a login ticket with the password only.
     */
    SignInStep password(PasswordSignIn command);

    /** Texts a sign-in code for an MFA challenge (rate limited like the hosted page). */
    SmsCodeSent sendSmsCode(UUID challengeId, ClientContext context);

    /** Emails a sign-in code for an MFA challenge (only for people who enrolled email codes; rate limited). */
    EmailCodeSent sendEmailCode(UUID challengeId, ClientContext context);

    /** Forced enrolment (MFA_ENROLMENT_REQUIRED): starts the authenticator app; no session exists yet. */
    TotpSetup enrolTotp(UUID challengeId, ClientContext context);

    /** Confirms the authenticator with a code and completes the sign-in. */
    EnrolmentCompleted confirmEnrolTotp(UUID challengeId, String code, ClientContext context);

    /** Forced enrolment: emails a first code to the verified account email. */
    EmailCodeSent enrolEmail(UUID challengeId, ClientContext context);

    /** Confirms the emailed code, turns email codes on and completes the sign-in. */
    EnrolmentCompleted confirmEnrolEmail(UUID challengeId, String code, ClientContext context);

    /** Authenticator, email, text-message or recovery code for an MFA challenge; returns a login ticket. */
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

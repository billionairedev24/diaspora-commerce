package com.afrotransact.identity.port;

/**
 * Delivers one-time codes. Adapters: outbox event {@code identity.verification.requested} consumed by the
 * notifications service (email via Resend/SES, SMS via Twilio), or a local adapter that logs the code.
 */
public interface VerificationCodeSender {

    void send(VerificationMessage message);
}

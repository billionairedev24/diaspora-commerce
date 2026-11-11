package com.afrotransact.identity.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.EmailOtpEnrolment;
import com.afrotransact.identity.domain.TotpEnrolment;
import com.afrotransact.identity.domain.TrustedBrowser;

/** TOTP enrolments, email codes, SMS factor, recovery codes, trusted browsers and the setup-prompt reminder. */
public interface MfaRepository {

    Optional<TotpEnrolment> totp(UUID userId);

    void saveTotp(TotpEnrolment enrolment);

    void deleteTotp(UUID userId);

    Optional<String> smsPhone(UUID userId);

    void enableSms(UUID userId, String phone, Instant at);

    void disableSms(UUID userId);

    Optional<EmailOtpEnrolment> emailOtp(UUID userId);

    /** Turns email codes on (idempotent: a second call keeps the first enrolment time). */
    void enableEmailOtp(UUID userId, Instant at);

    void disableEmailOtp(UUID userId);

    void touchEmailOtp(UUID userId, Instant at);

    /** When the person last dismissed the "Secure your account" prompt (skip or later), if ever. */
    Optional<Instant> setupPromptDismissedAt(UUID userId);

    void recordSetupPromptDismissed(UUID userId, Instant at);

    void replaceRecoveryCodes(UUID userId, List<String> hashes, Instant at);

    int unusedRecoveryCodes(UUID userId);

    /** Marks the matching unused code as used; returns false when none matched. */
    boolean useRecoveryCode(UUID userId, String hash, Instant at);

    void deleteRecoveryCodes(UUID userId);

    void insertTrustedBrowser(TrustedBrowser browser);

    Optional<TrustedBrowser> trustedBrowser(UUID userId, String tokenHash, Instant now);

    void deleteTrustedBrowsers(UUID userId);

    /** Unexpired trusted browsers, newest first. */
    List<TrustedBrowser> trustedBrowsers(UUID userId, Instant now);

    /** Deletes one trusted browser of the user; returns false when none matched. */
    boolean deleteTrustedBrowser(UUID userId, UUID browserId);
}

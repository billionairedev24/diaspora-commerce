package com.afrotransact.identity.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.TotpEnrolment;
import com.afrotransact.identity.domain.TrustedBrowser;

/** TOTP enrolments, SMS factor, recovery codes and trusted browsers. */
public interface MfaRepository {

    Optional<TotpEnrolment> totp(UUID userId);

    void saveTotp(TotpEnrolment enrolment);

    void deleteTotp(UUID userId);

    Optional<String> smsPhone(UUID userId);

    void enableSms(UUID userId, String phone, Instant at);

    void disableSms(UUID userId);

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

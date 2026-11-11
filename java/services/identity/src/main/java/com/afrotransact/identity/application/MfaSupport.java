package com.afrotransact.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.domain.EmailOtpEnrolment;
import com.afrotransact.identity.domain.MfaEnrolment;
import com.afrotransact.identity.domain.RecoveryCodes;
import com.afrotransact.identity.port.MfaRepository;
import com.afrotransact.identity.port.PasskeyStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Shared MFA reads/writes used by enrolment and sign-in. The one place that applies the SMS feature flag
 * ({@code at.identity.mfa.sms-enabled}, off by default): while it is off an existing SMS enrolment reads as "not
 * enrolled", so it is never used for sign-in and never shown.
 */
@Component
class MfaSupport {

    private final MfaRepository mfa;
    private final PasskeyStore passkeys;
    private final boolean smsEnabled;

    MfaSupport(MfaRepository mfa, PasskeyStore passkeys,
               @Value("${at.identity.mfa.sms-enabled:false}") boolean smsEnabled) {
        this.mfa = mfa;
        this.passkeys = passkeys;
        this.smsEnabled = smsEnabled;
    }

    boolean smsEnabled() {
        return smsEnabled;
    }

    MfaEnrolment enrolment(UUID userId) {
        boolean totp = mfa.totp(userId).map(t -> t.confirmed()).orElse(false);
        String sms = smsEnabled ? mfa.smsPhone(userId).orElse(null) : null;
        boolean email = mfa.emailOtp(userId).filter(EmailOtpEnrolment::enabled).isPresent();
        return new MfaEnrolment(totp, sms != null, sms, mfa.unusedRecoveryCodes(userId), passkeys.list(userId).size(),
                email);
    }

    List<String> newRecoveryCodes(UUID userId, Instant now) {
        List<String> codes = RecoveryCodes.generate();
        mfa.replaceRecoveryCodes(userId, codes.stream().map(RecoveryCodes::hash).toList(), now);
        return codes;
    }
}

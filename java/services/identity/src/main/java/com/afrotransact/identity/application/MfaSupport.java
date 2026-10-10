package com.afrotransact.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.domain.MfaEnrolment;
import com.afrotransact.identity.domain.RecoveryCodes;
import com.afrotransact.identity.port.MfaRepository;
import com.afrotransact.identity.port.PasskeyStore;
import org.springframework.stereotype.Component;

/** Shared MFA reads/writes used by enrolment and sign-in. */
@Component
class MfaSupport {

    private final MfaRepository mfa;
    private final PasskeyStore passkeys;

    MfaSupport(MfaRepository mfa, PasskeyStore passkeys) {
        this.mfa = mfa;
        this.passkeys = passkeys;
    }

    MfaEnrolment enrolment(UUID userId) {
        boolean totp = mfa.totp(userId).map(t -> t.confirmed()).orElse(false);
        String sms = mfa.smsPhone(userId).orElse(null);
        return new MfaEnrolment(totp, sms != null, sms, mfa.unusedRecoveryCodes(userId), passkeys.list(userId).size());
    }

    List<String> newRecoveryCodes(UUID userId, Instant now) {
        List<String> codes = RecoveryCodes.generate();
        mfa.replaceRecoveryCodes(userId, codes.stream().map(RecoveryCodes::hash).toList(), now);
        return codes;
    }
}

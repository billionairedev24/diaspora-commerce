package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import com.afrotransact.identity.domain.AccessPolicy;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.MfaEnrolment;
import com.afrotransact.identity.domain.MfaFactor;
import com.afrotransact.identity.domain.MfaPolicy;
import com.afrotransact.identity.domain.RecoveryCodes;
import com.afrotransact.identity.domain.SecretHashing;
import com.afrotransact.identity.domain.TotpAuthenticator;
import com.afrotransact.identity.domain.TotpEnrolment;
import com.afrotransact.identity.domain.TrustedBrowser;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.domain.VerificationPurpose;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.MfaRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SignInServiceImpl implements SignInService {

    private static final int USER_AGENT_MAX = 300;

    private final UserAccountRepository users;
    private final AccessProfileLoader profiles;
    private final AccessPolicy accessPolicy;
    private final MfaRepository mfa;
    private final MfaSupport support;
    private final MfaPolicy policy;
    private final TotpAuthenticator totp;
    private final CodeIssuer codes;
    private final AccessChangeLog changeLog;
    private final Clock clock;

    SignInServiceImpl(UserAccountRepository users, AccessProfileLoader profiles, AccessPolicy accessPolicy,
                      MfaRepository mfa, MfaSupport support, MfaPolicy policy, TotpAuthenticator totp,
                      CodeIssuer codes, AccessChangeLog changeLog, Clock clock) {
        this.users = users;
        this.profiles = profiles;
        this.accessPolicy = accessPolicy;
        this.mfa = mfa;
        this.support = support;
        this.policy = policy;
        this.totp = totp;
        this.codes = codes;
        this.changeLog = changeLog;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SignInAccount> findAccount(String emailOrId) {
        Optional<UserAccount> user;
        try {
            user = users.findById(UUID.fromString(emailOrId));
        } catch (IllegalArgumentException notAnId) {
            user = users.findByEmail(UserAccount.normaliseEmail(emailOrId));
        }
        return user.map(u -> new SignInAccount(u.id(), u.email(), u.passwordHash(), u.enabled(), u.emailVerified()));
    }

    @Override
    @Transactional(readOnly = true)
    public SecondFactorChallenge challenge(UUID userId, Optional<String> trustedBrowserToken, boolean passkey) {
        UserAccount u = user(userId);
        boolean staff = accessPolicy.resolve(profiles.load(u)).staff();
        MfaEnrolment e = support.enrolment(userId);
        Instant now = Instant.now(clock);
        boolean trusted = trustedBrowserToken
                .flatMap(t -> mfa.trustedBrowser(userId, SecretHashing.sha256Hex(t), now))
                .isPresent();
        if (!policy.secondFactorRequired(staff, e, trusted, passkey)) {
            return SecondFactorChallenge.none();
        }
        String smsPhone = smsDestination(u, e);
        return new SecondFactorChallenge(true, e.totp(), smsPhone != null, mask(smsPhone), e.recoveryCodesLeft() > 0);
    }

    @Override
    @Transactional
    public Instant sendSmsCode(UUID userId) {
        UserAccount u = user(userId);
        String phone = smsDestination(u, support.enrolment(userId));
        if (phone == null) {
            throw new IdentityRuleViolation("identity.sms-unavailable", "Text-message codes are not set up");
        }
        return codes.issue(u, VerificationPurpose.SIGN_IN_SMS, "SMS", phone);
    }

    @Override
    @Transactional(noRollbackFor = IdentityRuleViolation.class)
    public boolean verifySecondFactor(UUID userId, MfaFactor factor, String code) {
        Instant now = Instant.now(clock);
        boolean ok = switch (factor) {
            case TOTP -> verifyTotp(userId, code, now);
            case SMS -> verifySms(userId, code);
            case RECOVERY_CODE -> mfa.useRecoveryCode(userId, RecoveryCodes.hash(code), now);
        };
        if (ok && factor == MfaFactor.RECOVERY_CODE) {
            changeLog.append(userId.toString(), "mfa.recovery-code.used", userId, null, "recovery-code", null);
        }
        return ok;
    }

    @Override
    @Transactional
    public String trustBrowser(UUID userId, String userAgent) {
        String token = SecretHashing.randomToken(32);
        Instant now = Instant.now(clock);
        String ua = userAgent == null ? null : userAgent.substring(0, Math.min(userAgent.length(), USER_AGENT_MAX));
        mfa.insertTrustedBrowser(new TrustedBrowser(UUID.randomUUID(), userId, SecretHashing.sha256Hex(token), ua,
                now, now.plus(MfaPolicy.TRUSTED_BROWSER_TTL)));
        return token;
    }

    private boolean verifyTotp(UUID userId, String code, Instant now) {
        Optional<TotpEnrolment> enrolment = mfa.totp(userId).filter(TotpEnrolment::confirmed);
        if (enrolment.isEmpty()) {
            return false;
        }
        OptionalLong step = totp.verify(enrolment.get().secret(), code, now, enrolment.get().lastUsedStep());
        step.ifPresent(s -> mfa.saveTotp(enrolment.get().used(s)));
        return step.isPresent();
    }

    private boolean verifySms(UUID userId, String code) {
        try {
            codes.consume(userId, VerificationPurpose.SIGN_IN_SMS, code);
            return true;
        } catch (IdentityRuleViolation wrong) {
            return false;
        }
    }

    /** Enrolled SMS phone; staff without any factor fall back to the verified phone on file. */
    private String smsDestination(UserAccount u, MfaEnrolment e) {
        if (e.sms()) {
            return e.smsPhone();
        }
        boolean staff = accessPolicy.resolve(profiles.load(u)).staff();
        return staff && !e.totp() ? u.phone() : null;
    }

    private static String mask(String phone) {
        if (phone == null || phone.length() < 4) {
            return null;
        }
        return "•••• " + phone.substring(phone.length() - 4);
    }

    private UserAccount user(UUID id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("identity.account-not-found",
                "Account not found"));
    }
}

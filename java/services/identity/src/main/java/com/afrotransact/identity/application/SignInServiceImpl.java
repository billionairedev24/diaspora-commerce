package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import com.afrotransact.identity.domain.AccessPolicy;
import com.afrotransact.identity.domain.EffectiveAccess;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.MfaEnrolment;
import com.afrotransact.identity.domain.MfaFactor;
import com.afrotransact.identity.domain.MfaPolicy;
import com.afrotransact.identity.domain.MfaRequirement;
import com.afrotransact.identity.domain.RateLimitExceeded;
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
    private final AuthSettingsService authSettings;
    private final AccessChangeLog changeLog;
    private final Clock clock;

    SignInServiceImpl(UserAccountRepository users, AccessProfileLoader profiles, AccessPolicy accessPolicy,
                      MfaRepository mfa, MfaSupport support, MfaPolicy policy, TotpAuthenticator totp,
                      CodeIssuer codes, AuthSettingsService authSettings, AccessChangeLog changeLog, Clock clock) {
        this.users = users;
        this.profiles = profiles;
        this.accessPolicy = accessPolicy;
        this.mfa = mfa;
        this.support = support;
        this.policy = policy;
        this.totp = totp;
        this.codes = codes;
        this.authSettings = authSettings;
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
        MfaEnrolment e = support.enrolment(userId);
        Instant now = Instant.now(clock);
        boolean trusted = trustedBrowserToken
                .flatMap(t -> mfa.trustedBrowser(userId, SecretHashing.sha256Hex(t), now))
                .isPresent();
        if (policy.secondFactorRequired(e, trusted, passkey)) {
            return new SecondFactorChallenge(true, e.totp(), e.sms(), mask(e.smsPhone()), e.recoveryCodesLeft() > 0,
                    e.emailOtp(), maskEmail(u.email()), false);
        }
        if (policy.enrolmentRequired(secondFactorMandatory(u), e, passkey)) {
            return SecondFactorChallenge.enrol();
        }
        return SecondFactorChallenge.none();
    }

    @Override
    @Transactional
    public Instant sendEmailCode(UUID userId) {
        UserAccount u = user(userId);
        if (!support.enrolment(userId).emailOtp()) {
            throw new IdentityRuleViolation("identity.email-otp-unavailable", "Email codes are not set up");
        }
        return codes.issue(u, VerificationPurpose.SIGN_IN_EMAIL, "EMAIL", u.email());
    }

    @Override
    @Transactional
    public boolean sendEmailCodeIfDefault(UUID userId) {
        MfaEnrolment e = support.enrolment(userId);
        if (!e.emailOtp() || e.totp() || e.sms()) {
            return false;
        }
        try {
            sendEmailCode(userId);
            return true;
        } catch (RateLimitExceeded tooSoon) {
            return false;
        }
    }

    @Override
    @Transactional
    public Instant sendSmsCode(UUID userId) {
        UserAccount u = user(userId);
        String phone = support.enrolment(userId).smsPhone();
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
            case SMS -> support.smsEnabled() && verifySms(userId, code);
            case EMAIL_OTP -> verifyEmail(userId, code, now);
            case RECOVERY_CODE -> mfa.useRecoveryCode(userId, RecoveryCodes.hash(code), now);
        };
        if (ok && factor == MfaFactor.RECOVERY_CODE) {
            changeLog.append(userId.toString(), "mfa.recovery-code.used", userId, null, "recovery-code", null);
        }
        return ok;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean setupPromptDue(UUID userId) {
        UserAccount u = user(userId);
        return policy.reminderDue(support.enrolment(userId), secondFactorMandatory(u),
                mfa.setupPromptDismissedAt(userId).orElse(null), Instant.now(clock));
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

    private boolean verifyEmail(UUID userId, String code, Instant now) {
        if (!support.enrolment(userId).emailOtp()) {
            return false;
        }
        try {
            codes.consume(userId, VerificationPurpose.SIGN_IN_EMAIL, code);
        } catch (IdentityRuleViolation wrong) {
            return false;
        }
        mfa.touchEmailOtp(userId, now);
        changeLog.append(userId.toString(), "mfa.email-otp.used", userId, null, "email-otp", null);
        return true;
    }

    private boolean verifySms(UUID userId, String code) {
        try {
            codes.consume(userId, VerificationPurpose.SIGN_IN_SMS, code);
            return true;
        } catch (IdentityRuleViolation wrong) {
            return false;
        }
    }

    /** Staff always; others when their portal's MFA setting is on (S291). Mandatory means "must enrol", never "send a code". */
    private boolean secondFactorMandatory(UserAccount u) {
        EffectiveAccess access = accessPolicy.resolve(profiles.load(u));
        return MfaRequirement.mandatory(access.staff(), access.roles(), authSettings.effective());
    }

    private static String mask(String phone) {
        if (phone == null || phone.length() < 4) {
            return null;
        }
        return "•••• " + phone.substring(phone.length() - 4);
    }

    /** a••••••@example.com */
    private static String maskEmail(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        if (at <= 0) {
            return null;
        }
        return email.charAt(0) + "•".repeat(Math.max(1, Math.min(6, at - 1))) + email.substring(at);
    }

    private UserAccount user(UUID id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("identity.account-not-found",
                "Account not found"));
    }
}

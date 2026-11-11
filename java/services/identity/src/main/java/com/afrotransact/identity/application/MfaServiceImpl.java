package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.identity.domain.AccessPolicy;
import com.afrotransact.identity.domain.EffectiveAccess;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.MfaEnrolment;
import com.afrotransact.identity.domain.MfaFactor;
import com.afrotransact.identity.domain.MfaPolicy;
import com.afrotransact.identity.domain.MfaRequirement;
import com.afrotransact.identity.domain.RateLimitExceeded;
import com.afrotransact.identity.domain.TrustedBrowser;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.domain.VerificationPurpose;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.MfaRepository;
import com.afrotransact.identity.port.PasswordHasher;
import com.afrotransact.identity.port.PhoneVerificationRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MfaServiceImpl implements MfaService {

    private final CurrentUser current;
    private final UserAccountRepository users;
    private final AccessProfileLoader profiles;
    private final AccessPolicy accessPolicy;
    private final MfaRepository mfa;
    private final MfaSupport support;
    private final MfaPolicy policy;
    private final SecondFactorEnrolment enrolment;
    private final CodeIssuer codes;
    private final AuthSettingsService authSettings;
    private final PasswordHasher hasher;
    private final SignInProtectionService protection;
    private final AccessChangeLog changeLog;
    private final PhoneVerificationRepository phoneVerifications;
    private final Clock clock;

    MfaServiceImpl(CurrentUser current, UserAccountRepository users, AccessProfileLoader profiles,
                   AccessPolicy accessPolicy, MfaRepository mfa, MfaSupport support, MfaPolicy policy,
                   SecondFactorEnrolment enrolment, CodeIssuer codes, AccessChangeLog changeLog,
                   PhoneVerificationRepository phoneVerifications, Clock clock, AuthSettingsService authSettings,
                   PasswordHasher hasher, SignInProtectionService protection) {
        this.current = current;
        this.users = users;
        this.profiles = profiles;
        this.accessPolicy = accessPolicy;
        this.mfa = mfa;
        this.support = support;
        this.policy = policy;
        this.enrolment = enrolment;
        this.codes = codes;
        this.authSettings = authSettings;
        this.hasher = hasher;
        this.protection = protection;
        this.changeLog = changeLog;
        this.phoneVerifications = phoneVerifications;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public MfaStatus status() {
        UserAccount u = user();
        boolean mandatory = mandatory(u);
        MfaEnrolment e = support.enrolment(u.id());
        Instant now = Instant.now(clock);
        boolean suggested = policy.reminderDue(e, mandatory, mfa.setupPromptDismissedAt(u.id()).orElse(null), now);
        return new MfaStatus(e, mandatory, policy.mayDisable(mandatory),
                e.totp() && policy.mayRemove(mandatory, e, MfaFactor.TOTP),
                e.sms() && policy.mayRemove(mandatory, e, MfaFactor.SMS),
                e.emailOtp() && policy.mayRemove(mandatory, e, MfaFactor.EMAIL_OTP), suggested,
                support.smsEnabled());
    }

    @Override
    public TotpSetup startTotp() {
        return enrolment.startTotp(current.id());
    }

    @Override
    public List<String> confirmTotp(String code) {
        return enrolment.confirmTotp(current.id(), code);
    }

    @Override
    public Instant startEmailOtp() {
        return enrolment.startEmailOtp(current.id());
    }

    @Override
    public List<String> confirmEmailOtp(String code) {
        return enrolment.confirmEmailOtp(current.id(), code);
    }

    @Override
    @Transactional(noRollbackFor = {IdentityRuleViolation.class, RateLimitExceeded.class})
    public void disableTotp(String password) {
        UserAccount u = user();
        requireMayRemove(u, MfaFactor.TOTP);
        requirePassword(u, password);
        mfa.deleteTotp(u.id());
        afterFactorRemoved(u.id());
        changeLog.append(u.id().toString(), "mfa.totp.disabled", u.id(), null, "totp", null);
    }

    @Override
    @Transactional(noRollbackFor = {IdentityRuleViolation.class, RateLimitExceeded.class})
    public void disableEmailOtp(String password) {
        UserAccount u = user();
        requireMayRemove(u, MfaFactor.EMAIL_OTP);
        requirePassword(u, password);
        mfa.disableEmailOtp(u.id());
        afterFactorRemoved(u.id());
        changeLog.append(u.id().toString(), "mfa.email-otp.disabled", u.id(), null, "email-otp", null);
    }

    @Override
    public void dismissSetupPrompt() {
        enrolment.dismissSetupPrompt(current.id());
    }

    @Override
    @Transactional
    public Instant startSms() {
        requireSmsEnabled();
        UserAccount u = user();
        if (u.phone() == null) {
            throw new IdentityRuleViolation("identity.phone-missing", "Add a mobile number to your profile first",
                    Map.of("phone", "Add a mobile number first"));
        }
        return codes.issue(u, VerificationPurpose.SMS_ENROLMENT, "SMS", u.phone());
    }

    @Override
    @Transactional(noRollbackFor = IdentityRuleViolation.class)
    public List<String> confirmSms(String code) {
        requireSmsEnabled();
        UserAccount u = user();
        codes.consume(u.id(), VerificationPurpose.SMS_ENROLMENT, code);
        Instant now = Instant.now(clock);
        mfa.enableSms(u.id(), u.phone(), now);
        // The SMS enrolment code also proves the account's phone number (S234).
        phoneVerifications.markVerified(u.id(), u.phone(), now);
        changeLog.append(u.id().toString(), "mfa.sms.enabled", u.id(), null, "sms", null);
        return firstRecoveryCodes(u.id(), now);
    }

    @Override
    @Transactional(noRollbackFor = {IdentityRuleViolation.class, RateLimitExceeded.class})
    public void disableSms(String password) {
        UserAccount u = user();
        requireMayRemove(u, MfaFactor.SMS);
        requirePassword(u, password);
        mfa.disableSms(u.id());
        afterFactorRemoved(u.id());
        changeLog.append(u.id().toString(), "mfa.sms.disabled", u.id(), null, "sms", null);
    }

    @Override
    @Transactional
    public List<String> regenerateRecoveryCodes() {
        UserAccount u = user();
        MfaEnrolment e = support.enrolment(u.id());
        if (!e.anyFactor()) {
            throw new ConflictException("identity.mfa-not-enabled", "Turn on two-step verification first");
        }
        changeLog.append(u.id().toString(), "mfa.recovery-codes.regenerated", u.id(), null, "recovery-codes", null);
        return support.newRecoveryCodes(u.id(), Instant.now(clock));
    }

    private List<String> firstRecoveryCodes(UUID userId, Instant now) {
        if (mfa.unusedRecoveryCodes(userId) > 0) {
            return List.of();
        }
        return support.newRecoveryCodes(userId, now);
    }

    private void afterFactorRemoved(UUID userId) {
        MfaEnrolment left = support.enrolment(userId);
        if (!left.anyFactor()) {
            mfa.deleteRecoveryCodes(userId);
            mfa.deleteTrustedBrowsers(userId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<TrustedBrowser> trustedBrowsers() {
        return mfa.trustedBrowsers(current.id(), Instant.now(clock));
    }

    @Override
    @Transactional
    public void forgetTrustedBrowser(UUID browserId) {
        UUID user = current.id();
        if (!mfa.deleteTrustedBrowser(user, browserId)) {
            throw new NotFoundException("identity.trusted-browser-not-found", "That browser isn't trusted");
        }
        changeLog.append(user.toString(), "mfa.trusted-browser.forgotten", user, null, browserId.toString(), null);
    }

    @Override
    @Transactional
    public void forgetTrustedBrowsers() {
        UUID user = current.id();
        mfa.deleteTrustedBrowsers(user);
        changeLog.append(user.toString(), "mfa.trusted-browsers.forgotten", user, null, "all", null);
    }

    private void requireMayRemove(UserAccount u, MfaFactor factor) {
        if (!policy.mayRemove(mandatory(u), support.enrolment(u.id()), factor)) {
            throw new ForbiddenActionException("identity.mfa-required",
                    "Two-step verification is mandatory for your account: turn on another method first");
        }
    }

    /** Turning a factor off is a sensitive change: the password is asked again (accounts without one skip this). */
    private void requirePassword(UserAccount u, String password) {
        if (!u.hasPassword()) {
            return;
        }
        SignInAttempt attempt = new SignInAttempt(u.id(), u.email(), null, null, null, SignInAttempt.HOSTED,
                "MFA_DISABLE", null);
        protection.requireNotLocked(attempt);
        if (password == null || !hasher.matches(password, u.passwordHash())) {
            protection.recordFailure(new SignInAttempt(u.id(), u.email(), null, null, null, SignInAttempt.HOSTED,
                    "MFA_DISABLE", "bad-password"));
            throw new IdentityRuleViolation("identity.invalid-password", "That password isn't right",
                    Map.of("password", "Enter your current password"));
        }
    }

    private void requireSmsEnabled() {
        if (!support.smsEnabled()) {
            throw new ConflictException("identity.sms-unavailable", "Text-message codes are not available");
        }
    }

    private boolean mandatory(UserAccount u) {
        EffectiveAccess access = accessPolicy.resolve(profiles.load(u));
        return MfaRequirement.mandatory(access.staff(), access.roles(), authSettings.effective());
    }

    private UserAccount user() {
        return users.findById(current.id()).orElseThrow(() -> new NotFoundException("identity.account-not-found",
                "Account not found"));
    }
}

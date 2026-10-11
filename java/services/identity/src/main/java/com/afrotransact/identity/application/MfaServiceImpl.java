package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

import com.afrotransact.identity.domain.AccessPolicy;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.MfaEnrolment;
import com.afrotransact.identity.domain.MfaFactor;
import com.afrotransact.identity.domain.MfaPolicy;
import com.afrotransact.identity.domain.TotpAuthenticator;
import com.afrotransact.identity.domain.TotpEnrolment;
import com.afrotransact.identity.domain.TrustedBrowser;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.domain.VerificationPurpose;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.MfaRepository;
import com.afrotransact.identity.port.PhoneVerificationRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
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
    private final TotpAuthenticator totp;
    private final CodeIssuer codes;
    private final AccessChangeLog changeLog;
    private final PhoneVerificationRepository phoneVerifications;
    private final Clock clock;
    private final String issuerName;

    MfaServiceImpl(CurrentUser current, UserAccountRepository users, AccessProfileLoader profiles,
                   AccessPolicy accessPolicy, MfaRepository mfa, MfaSupport support, MfaPolicy policy,
                   TotpAuthenticator totp, CodeIssuer codes, AccessChangeLog changeLog,
                   PhoneVerificationRepository phoneVerifications, Clock clock,
                   @Value("${at.identity.mfa.issuer-name:AfroTransact}") String issuerName) {
        this.current = current;
        this.users = users;
        this.profiles = profiles;
        this.accessPolicy = accessPolicy;
        this.mfa = mfa;
        this.support = support;
        this.policy = policy;
        this.totp = totp;
        this.codes = codes;
        this.changeLog = changeLog;
        this.phoneVerifications = phoneVerifications;
        this.clock = clock;
        this.issuerName = issuerName;
    }

    @Override
    @Transactional(readOnly = true)
    public MfaStatus status() {
        UserAccount u = user();
        boolean staff = staff(u);
        MfaEnrolment e = support.enrolment(u.id());
        return new MfaStatus(e, staff || e.anyFactor(), policy.mayDisable(staff),
                e.totp() && policy.mayRemove(staff, e, MfaFactor.TOTP),
                e.sms() && policy.mayRemove(staff, e, MfaFactor.SMS));
    }

    @Override
    @Transactional
    public TotpSetup startTotp() {
        UserAccount u = user();
        String secret = totp.newSecret();
        mfa.saveTotp(new TotpEnrolment(u.id(), secret, Instant.now(clock), null, 0));
        return new TotpSetup(secret, totp.otpauthUri(issuerName, u.email(), secret));
    }

    @Override
    @Transactional
    public List<String> confirmTotp(String code) {
        UserAccount u = user();
        TotpEnrolment pending = mfa.totp(u.id()).orElseThrow(() -> new NotFoundException(
                "identity.totp-not-started", "Start authenticator setup first"));
        Instant now = Instant.now(clock);
        OptionalLong step = totp.verify(pending.secret(), code, now, pending.lastUsedStep());
        if (step.isEmpty()) {
            throw new IdentityRuleViolation("identity.invalid-code", "That code isn't right",
                    Map.of("code", "Enter the 6-digit code from your authenticator app"));
        }
        mfa.saveTotp(pending.confirm(now, step.getAsLong()));
        changeLog.append(u.id().toString(), "mfa.totp.enabled", u.id(), null, "totp", null);
        return firstRecoveryCodes(u.id(), now);
    }

    @Override
    @Transactional
    public void disableTotp() {
        UserAccount u = user();
        requireMayRemove(u, MfaFactor.TOTP);
        mfa.deleteTotp(u.id());
        afterFactorRemoved(u.id());
        changeLog.append(u.id().toString(), "mfa.totp.disabled", u.id(), null, "totp", null);
    }

    @Override
    @Transactional
    public Instant startSms() {
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
    @Transactional
    public void disableSms() {
        UserAccount u = user();
        requireMayRemove(u, MfaFactor.SMS);
        mfa.disableSms(u.id());
        afterFactorRemoved(u.id());
        changeLog.append(u.id().toString(), "mfa.sms.disabled", u.id(), null, "sms", null);
    }

    @Override
    @Transactional
    public List<String> regenerateRecoveryCodes() {
        UserAccount u = user();
        MfaEnrolment e = support.enrolment(u.id());
        if (!e.anyFactor() && !staff(u)) {
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
        if (!support.enrolment(userId).anyFactor()) {
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
        if (!policy.mayRemove(staff(u), support.enrolment(u.id()), factor)) {
            throw new ForbiddenActionException("identity.staff-mfa-required",
                    "Two-step verification is mandatory for staff accounts: turn on another method first");
        }
    }

    private boolean staff(UserAccount u) {
        return accessPolicy.resolve(profiles.load(u)).staff();
    }

    private UserAccount user() {
        return users.findById(current.id()).orElseThrow(() -> new NotFoundException("identity.account-not-found",
                "Account not found"));
    }
}

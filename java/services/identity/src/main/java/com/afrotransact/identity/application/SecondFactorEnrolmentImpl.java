package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.TotpAuthenticator;
import com.afrotransact.identity.domain.TotpEnrolment;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.domain.VerificationPurpose;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.MfaRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SecondFactorEnrolmentImpl implements SecondFactorEnrolment {

    private final UserAccountRepository users;
    private final MfaRepository mfa;
    private final MfaSupport support;
    private final TotpAuthenticator totp;
    private final CodeIssuer codes;
    private final AccessChangeLog changeLog;
    private final Clock clock;
    private final String issuerName;

    SecondFactorEnrolmentImpl(UserAccountRepository users, MfaRepository mfa, MfaSupport support,
                              TotpAuthenticator totp, CodeIssuer codes, AccessChangeLog changeLog, Clock clock,
                              @Value("${at.identity.mfa.issuer-name:AfroTransact}") String issuerName) {
        this.users = users;
        this.mfa = mfa;
        this.support = support;
        this.totp = totp;
        this.codes = codes;
        this.changeLog = changeLog;
        this.clock = clock;
        this.issuerName = issuerName;
    }

    @Override
    @Transactional
    public TotpSetup startTotp(UUID userId) {
        UserAccount u = user(userId);
        String secret = totp.newSecret();
        mfa.saveTotp(new TotpEnrolment(u.id(), secret, Instant.now(clock), null, 0));
        return new TotpSetup(secret, totp.otpauthUri(issuerName, u.email(), secret));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TotpSetup> pendingTotp(UUID userId) {
        UserAccount u = user(userId);
        return mfa.totp(userId).filter(t -> !t.confirmed())
                .map(t -> new TotpSetup(t.secret(), totp.otpauthUri(issuerName, u.email(), t.secret())));
    }

    @Override
    @Transactional(noRollbackFor = IdentityRuleViolation.class)
    public List<String> confirmTotp(UUID userId, String code) {
        UserAccount u = user(userId);
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
    public Instant startEmailOtp(UUID userId) {
        UserAccount u = user(userId);
        if (!u.emailVerified()) {
            throw new IdentityRuleViolation("identity.email-not-verified",
                    "Confirm your email address before using it for sign-in codes");
        }
        return codes.issue(u, VerificationPurpose.EMAIL_OTP_ENROLMENT, "EMAIL", u.email());
    }

    @Override
    @Transactional(noRollbackFor = IdentityRuleViolation.class)
    public List<String> confirmEmailOtp(UUID userId, String code) {
        UserAccount u = user(userId);
        if (!u.emailVerified()) {
            throw new IdentityRuleViolation("identity.email-not-verified",
                    "Confirm your email address before using it for sign-in codes");
        }
        codes.consume(u.id(), VerificationPurpose.EMAIL_OTP_ENROLMENT, code);
        Instant now = Instant.now(clock);
        mfa.enableEmailOtp(u.id(), now);
        changeLog.append(u.id().toString(), "mfa.email-otp.enabled", u.id(), null, "email-otp", null);
        return firstRecoveryCodes(u.id(), now);
    }

    @Override
    @Transactional
    public void dismissSetupPrompt(UUID userId) {
        mfa.recordSetupPromptDismissed(userId, Instant.now(clock));
    }

    private List<String> firstRecoveryCodes(UUID userId, Instant now) {
        if (mfa.unusedRecoveryCodes(userId) > 0) {
            return List.of();
        }
        return support.newRecoveryCodes(userId, now);
    }

    private UserAccount user(UUID id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("identity.account-not-found",
                "Account not found"));
    }
}

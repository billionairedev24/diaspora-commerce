package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import com.afrotransact.identity.application.IdentityEventPayloads.UserPhoneVerified;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.domain.VerificationCode;
import com.afrotransact.identity.domain.VerificationPurpose;
import com.afrotransact.identity.port.PhoneVerificationRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PhoneVerificationServiceImpl implements PhoneVerificationService {

    private final CurrentUser current;
    private final UserAccountRepository users;
    private final PhoneVerificationRepository verifications;
    private final CodeIssuer codes;
    private final DomainEventPublisher events;
    private final Clock clock;

    PhoneVerificationServiceImpl(CurrentUser current, UserAccountRepository users,
                                 PhoneVerificationRepository verifications, CodeIssuer codes,
                                 DomainEventPublisher events, Clock clock) {
        this.current = current;
        this.users = users;
        this.verifications = verifications;
        this.codes = codes;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SmsCodeSent start() {
        UserAccount u = userWithPhone();
        if (verifications.verifiedPhone(u.id()).map(u.phone()::equals).orElse(false)) {
            throw new ConflictException("identity.phone-already-verified", "This phone number is already verified");
        }
        Instant expires = codes.issue(u, VerificationPurpose.PHONE_VERIFICATION, "SMS", u.phone());
        return new SmsCodeSent(mask(u.phone()), expires);
    }

    @Override
    @Transactional(noRollbackFor = IdentityRuleViolation.class)
    public PhoneVerificationView confirm(String code) {
        UserAccount u = userWithPhone();
        VerificationCode used = codes.consume(u.id(), VerificationPurpose.PHONE_VERIFICATION, code);
        if (!u.phone().equals(used.destination())) {
            throw new IdentityRuleViolation("identity.phone-changed",
                    "Your phone number changed after the code was sent. Ask for a new code",
                    Map.of("code", "Ask for a new code"));
        }
        Instant now = Instant.now(clock);
        verifications.markVerified(u.id(), u.phone(), now);
        events.publish(IdentityEvents.USER_PHONE_VERIFIED, IdentityEvents.AGGREGATE, u.id().toString(),
                new UserPhoneVerified(u.id().toString(), u.phone(), now));
        return new PhoneVerificationView(u.phone(), true, now);
    }

    private UserAccount userWithPhone() {
        UserAccount u = users.findById(current.id()).orElseThrow(() -> new NotFoundException(
                "identity.account-not-found", "Account not found"));
        if (u.phone() == null) {
            throw new IdentityRuleViolation("identity.phone-missing", "Add a mobile number to your profile first",
                    Map.of("phone", "Add a mobile number first"));
        }
        return u;
    }

    private static String mask(String phone) {
        return phone.length() < 4 ? phone : "•••• " + phone.substring(phone.length() - 4);
    }
}

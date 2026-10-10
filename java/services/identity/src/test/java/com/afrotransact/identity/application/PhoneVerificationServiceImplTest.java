package com.afrotransact.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.domain.VerificationCode;
import com.afrotransact.identity.domain.VerificationPurpose;
import com.afrotransact.identity.port.PhoneVerificationRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ConflictException;
import org.junit.jupiter.api.Test;

class PhoneVerificationServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final String PHONE = "+17132490142";

    private final UUID id = UUID.randomUUID();
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final PhoneVerificationRepository verifications = mock(PhoneVerificationRepository.class);
    private final CodeIssuer codes = mock(CodeIssuer.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final PhoneVerificationServiceImpl service = new PhoneVerificationServiceImpl(
            new CurrentUser(() -> new Actor(id.toString(), PrincipalType.CUSTOMER, Optional.empty(), Set.of(),
                    Set.of())), users, verifications, codes, events, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void startTextsACodeToTheAccountPhone() {
        account(PHONE);
        when(codes.issue(any(), eq(VerificationPurpose.PHONE_VERIFICATION), eq("SMS"), eq(PHONE)))
                .thenReturn(NOW.plusSeconds(600));
        SmsCodeSent sent = service.start();
        assertThat(sent.sentTo()).isEqualTo("•••• 0142");
        assertThat(sent.expiresAt()).isEqualTo(NOW.plusSeconds(600));
    }

    @Test
    void startNeedsAPhoneAndAnUnverifiedNumber() {
        account(null);
        assertThatThrownBy(service::start).isInstanceOf(IdentityRuleViolation.class).hasMessageContaining("mobile");
        account(PHONE);
        when(verifications.verifiedPhone(id)).thenReturn(Optional.of(PHONE));
        assertThatThrownBy(service::start).isInstanceOf(ConflictException.class);
        when(verifications.verifiedPhone(id)).thenReturn(Optional.of("+447911123456"));
        service.start();
        verify(codes).issue(any(), eq(VerificationPurpose.PHONE_VERIFICATION), eq("SMS"), eq(PHONE));
    }

    @Test
    void confirmMarksTheCurrentNumberVerifiedAndPublishes() {
        account(PHONE);
        when(codes.consume(id, VerificationPurpose.PHONE_VERIFICATION, "123456")).thenReturn(code(PHONE));
        PhoneVerificationView v = service.confirm("123456");
        assertThat(v.phoneVerified()).isTrue();
        assertThat(v.phone()).isEqualTo(PHONE);
        verify(verifications).markVerified(id, PHONE, NOW);
        verify(events).publish(eq("identity.user.phone-verified"), eq("identity"), eq(id.toString()), any());
    }

    @Test
    void aCodeSentToAnEarlierNumberDoesNotVerifyTheNewOne() {
        account(PHONE);
        when(codes.consume(id, VerificationPurpose.PHONE_VERIFICATION, "123456")).thenReturn(code("+447911123456"));
        assertThatThrownBy(() -> service.confirm("123456")).isInstanceOf(IdentityRuleViolation.class)
                .satisfies(e -> assertThat(((IdentityRuleViolation) e).code()).isEqualTo("identity.phone-changed"));
        verify(verifications, never()).markVerified(any(), anyString(), any());
    }

    @Test
    void wrongCodesPropagateTheCodeIssuerError() {
        account(PHONE);
        when(codes.consume(id, VerificationPurpose.PHONE_VERIFICATION, "000000"))
                .thenThrow(new IdentityRuleViolation("identity.invalid-code", "That code isn't right"));
        assertThatThrownBy(() -> service.confirm("000000")).isInstanceOf(IdentityRuleViolation.class);
        verify(verifications, never()).markVerified(any(), anyString(), any());
    }

    private void account(String phone) {
        when(users.findById(id)).thenReturn(Optional.of(new UserAccount(id, "Ada", "Okafor", "ada@example.com", true,
                phone, "h", true, false, NOW, null, NOW, NOW, 0)));
    }

    private VerificationCode code(String destination) {
        return new VerificationCode(UUID.randomUUID(), id, VerificationPurpose.PHONE_VERIFICATION, destination, "h",
                NOW.minusSeconds(30), NOW.plusSeconds(570), NOW, 0);
    }
}

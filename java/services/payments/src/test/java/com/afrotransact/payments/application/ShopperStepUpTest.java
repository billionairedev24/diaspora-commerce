package com.afrotransact.payments.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.afrotransact.payments.port.OrgMembershipDirectory;
import com.afrotransact.payments.port.StripeGateway;
import com.afrotransact.payments.port.WalletRepository;
import com.afrotransact.platform.test.tenant.TenantFixture;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** F17: saved payment method management asks for a recent sign-in when switched on; the 92-day session stays. */
class ShopperStepUpTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final TenantFixture tenants = new TenantFixture();
    private final PaymentsAccess access = new PaymentsAccess(tenants.permissions(), tenants.access(),
            tenants.resolver(), mock(OrgMembershipDirectory.class));
    private final StripeGateway stripe = mock(StripeGateway.class);
    private final WalletRepository wallet = mock(WalletRepository.class);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private PaymentMethodService service(boolean enabled) {
        return new PaymentMethodServiceImpl(wallet, mock(WalletSync.class), stripe, mock(MoneyTransactions.class),
                access, new ShopperStepUp(enabled, Duration.ofMinutes(30), CLOCK), CLOCK);
    }

    private void signedInDaysAgo(long days) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(TenantFixture.CUSTOMER_1)
                .issuedAt(NOW.minusSeconds(60)).expiresAt(NOW.plusSeconds(240))
                .claim("auth_time", NOW.minus(Duration.ofDays(days))).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_1));
    }

    @Test
    void anOldSessionCannotAddRemoveDefaultOrOpenTheWalletWhenOn() {
        signedInDaysAgo(60);
        PaymentMethodService on = service(true);
        List<ThrowingCallable> calls = List.of(() -> on.createSetupIntent(null, "k"),
                () -> on.createEphemeralKey(null, "k"), () -> on.setDefault("pm_1"), () -> on.remove("pm_1"));
        for (ThrowingCallable call : calls) {
            assertThatThrownBy(call).isInstanceOf(ForbiddenActionException.class)
                    .hasMessageContaining("sign in again");
        }
        verifyNoInteractions(stripe);
    }

    @Test
    void offMeansTheOldBehaviour() {
        signedInDaysAgo(60);
        assertThatCode(() -> new ShopperStepUp(false, Duration.ofMinutes(30), CLOCK).require("x"))
                .doesNotThrowAnyException();
    }
}

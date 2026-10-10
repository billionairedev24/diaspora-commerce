package com.afrotransact.identity.adapter.in.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.identity.application.FirstPartySignInService;
import com.afrotransact.identity.application.RedeemedLoginTicket;
import com.afrotransact.identity.domain.SignInMethod;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;

class LoginTicketAuthenticationFilterTest {

    private final UUID account = UUID.randomUUID();
    private final FirstPartySignInService signIn = mock(FirstPartySignInService.class);
    private final UserDetailsService users = name -> User.withUsername(name).password("{bcrypt}x")
            .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER"))).build();
    private final LoginTicketAuthenticationFilter filter = new LoginTicketAuthenticationFilter(
            new ProviderManager(new LoginTicketAuthenticationProvider(signIn, users)), "/oauth2/authorize");

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTicketAuthenticatesTheAccountForThisRequestOnly() throws Exception {
        when(signIn.redeemLoginTicket("t1", "gateway-bff")).thenReturn(Optional.of(new RedeemedLoginTicket(account,
                SignInMethod.PASSWORD_MFA, Instant.parse("2026-10-10T12:00:00Z"))));
        MockHttpServletRequest request = authorize("t1");
        AtomicReference<Authentication> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(SecurityContextHolder.getContext().getAuthentication());

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seen.get().isAuthenticated()).isTrue();
        assertThat(seen.get().getName()).isEqualTo(account.toString());
        assertThat(seen.get().getCredentials()).isNull();
        assertThat(seen.get().getAuthorities()).anySatisfy(a -> assertThat(a)
                .isInstanceOfSatisfying(FactorGrantedAuthority.class, f -> assertThat(f.getIssuedAt())
                        .isEqualTo(Instant.parse("2026-10-10T12:00:00Z"))));
        assertThat(request.getSession(false)).as("nothing stored in a session").isNull();
    }

    @Test
    void invalidTicketGetsInvalidGrantInsteadOfTheLoginPage() throws Exception {
        when(signIn.redeemLoginTicket("used", "gateway-bff")).thenReturn(Optional.empty());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> called = new AtomicReference<>(false);

        filter.doFilter(authorize("used"), response, (req, res) -> called.set(true));

        assertThat(called.get()).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("\"error\":\"invalid_grant\"");
    }

    @Test
    void requestsWithoutATicketPassThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorize");
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> called.set(true));
        assertThat(called.get()).isTrue();

        MockHttpServletRequest other = new MockHttpServletRequest("GET", "/userinfo");
        other.setParameter(LoginTicketAuthenticationFilter.PARAMETER, "t1");
        filter.doFilter(other, new MockHttpServletResponse(), (req, res) -> called.set(true));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static MockHttpServletRequest authorize(String ticket) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorize");
        request.setParameter("client_id", "gateway-bff");
        request.setParameter("response_type", "code");
        request.setParameter(LoginTicketAuthenticationFilter.PARAMETER, ticket);
        return request;
    }
}

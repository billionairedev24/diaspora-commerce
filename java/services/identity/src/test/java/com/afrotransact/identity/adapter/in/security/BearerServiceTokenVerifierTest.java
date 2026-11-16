package com.afrotransact.identity.adapter.in.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PlatformSecurityProperties;
import com.afrotransact.platform.security.PrincipalType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class BearerServiceTokenVerifierTest {

    private final JwtDecoder decoder = mock(JwtDecoder.class);

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void request(String authorization) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (authorization != null) {
            req.addHeader("Authorization", authorization);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    private static Jwt jwt(String pt) {
        return new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "RS256"),
                Map.of("sub", "gateway-bff", "pt", pt, "scope", "auth:first-party openid", "perms", List.of("x:y")));
    }

    private BearerServiceTokenVerifier verifier(boolean securityOn) {
        return new BearerServiceTokenVerifier(new PlatformSecurityProperties(securityOn, null, null, null), decoder);
    }

    @Test
    void aVerifiedClientCredentialsTokenIsAServiceActorWhenSecurityIsOff() {
        request("Bearer abc");
        when(decoder.decode("abc")).thenReturn(jwt("SERVICE"));
        Actor actor = verifier(false).verifiedService().orElseThrow();
        assertThat(actor.subject()).isEqualTo("gateway-bff");
        assertThat(actor.type()).isEqualTo(PrincipalType.SERVICE);
        assertThat(actor.can("auth:first-party")).isTrue();
    }

    @Test
    void aUserTokenABadTokenNoTokenAndSecurityOnGiveNothing() {
        request("Bearer abc");
        when(decoder.decode("abc")).thenReturn(jwt("CUSTOMER"));
        assertThat(verifier(false).verifiedService()).isEmpty();

        when(decoder.decode("bad")).thenThrow(new BadJwtException("signature"));
        request("Bearer bad");
        assertThat(verifier(false).verifiedService()).isEmpty();

        request(null);
        assertThat(verifier(false).verifiedService()).isEmpty();

        request("Bearer abc");
        when(decoder.decode("abc")).thenReturn(jwt("SERVICE"));
        assertThat(verifier(true).verifiedService()).isEmpty();
    }
}

package com.afrotransact.mcp.adapter.in.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.mcp.application.McpCaller;
import com.afrotransact.module.EndUserActor;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.EndUsers;
import com.afrotransact.platform.security.PrincipalType;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.function.ServerRequest;

/** The signed-in person is captured on the request thread, where the SecurityContext is, and survives the hop to the tool's thread. */
class McpCallerExtractorTest {

    private final EndUsers endUsers = new EndUsers(true);
    private final McpCallerExtractor extractor = new McpCallerExtractor(() -> new Actor("seller-1", PrincipalType.SELLER_MEMBER,
            Optional.of("org-1"), Set.of(), Set.of("mcp:seller")), endUsers, true);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static ServerRequest request(String authorization) {
        ServerRequest request = mock(ServerRequest.class);
        ServerRequest.Headers headers = mock(ServerRequest.Headers.class);
        when(request.headers()).thenReturn(headers);
        when(headers.firstHeader("Authorization")).thenReturn(authorization);
        return request;
    }

    private static void signIn(String type) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("seller-1").claim("pt", type).claim("org", "org-1")
                .claim("perms", List.of("mcp:seller")).claim("client_id", "agent").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES));
    }

    @Test
    void thePersonIsCapturedFromTheRequestsSecurityContextAndSurvivesItsEnd() {
        signIn("SELLER_MEMBER");
        McpCaller caller = McpCallerExtractor.caller(extractor.extract(request("Bearer abc")));
        SecurityContextHolder.clearContext(); // the tool runs later, maybe on another thread: no SecurityContext there

        EndUserActor person = caller.person().get();
        assertThat(person.userId()).isEqualTo("seller-1");
        assertThat(person.principalType()).isEqualTo("SELLER_MEMBER");
        assertThat(person.organisationId()).contains("org-1");
        assertThat(person.scopes()).containsExactly("mcp:seller");
        assertThat(caller.bearerToken()).isEqualTo("abc");
        assertThat(caller.credentials().person().get()).isSameAs(person);
    }

    @Test
    void aServiceTokenIsCapturedAsTheRefusalAndNeverAsAPerson() {
        signIn("SERVICE");
        McpCaller caller = McpCallerExtractor.caller(extractor.extract(request("Bearer abc")));
        assertThatThrownBy(() -> caller.person().get()).isInstanceOfSatisfying(ModuleApiException.class, e -> {
            assertThat(e.status()).isEqualTo(403);
            assertThat(e.code()).isEqualTo("auth.service-token");
        });
    }

    @Test
    void noAuthenticationIsCapturedAsA401() {
        McpTransportContext context = extractor.extract(request(null));
        McpCaller caller = McpCallerExtractor.caller(context);
        assertThatThrownBy(() -> caller.person().get()).isInstanceOfSatisfying(ModuleApiException.class,
                e -> assertThat(e.status()).isEqualTo(401));
        assertThat(Map.of("k", caller.bearerToken() == null)).containsEntry("k", true);
    }
}

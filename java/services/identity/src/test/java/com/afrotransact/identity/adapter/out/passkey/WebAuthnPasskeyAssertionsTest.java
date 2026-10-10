package com.afrotransact.identity.adapter.out.passkey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.port.PasskeyAssertions.PasskeyRequest;
import com.afrotransact.identity.testsupport.TestPasskeyAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.management.MapPublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.MapUserCredentialRepository;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WebAuthnPasskeyAssertionsTest {

    private static final String ORIGIN = "http://localhost:3000";

    private final MapPublicKeyCredentialUserEntityRepository users = new MapPublicKeyCredentialUserEntityRepository();
    private final MapUserCredentialRepository credentials = new MapUserCredentialRepository();
    private final WebAuthnPasskeyAssertions assertions = new WebAuthnPasskeyAssertions(
            new Webauthn4JRelyingPartyOperations(users, credentials,
                    PublicKeyCredentialRpEntity.builder().id("localhost").name("AfroTransact").build(),
                    Set.of(ORIGIN)), "localhost");

    @Test
    void optionsAskForADiscoverablePasskeyWithUserVerification() {
        PasskeyRequest request = assertions.newRequest();
        JsonNode options = JsonMapper.builder().build().readTree(request.optionsJson());
        assertThat(options.get("challenge").asString()).isEqualTo(request.challenge());
        assertThat(options.get("rpId").asString()).isEqualTo("localhost");
        assertThat(options.get("userVerification").asString()).isEqualTo("required");
        assertThat(options.has("allowCredentials") && options.get("allowCredentials").size() > 0).isFalse();
    }

    @Test
    void verifiesTheOwnersSignatureAndRejectsWrongChallengeOrOrigin() {
        UUID account = UUID.randomUUID();
        TestPasskeyAuthenticator passkey = new TestPasskeyAuthenticator("localhost");
        passkey.register(users, credentials, account.toString());
        PasskeyRequest request = assertions.newRequest();

        assertThat(assertions.verify(request, passkey.assertion(request.challenge(), ORIGIN))).contains(account);
        assertThat(assertions.verify(request, passkey.assertion(assertions.newRequest().challenge(), ORIGIN)))
                .isEmpty();
        assertThat(assertions.verify(request, passkey.assertion(request.challenge(), "https://evil.example")))
                .isEmpty();
        assertThat(assertions.verify(request, "{\"not\":\"a credential\"}")).isEmpty();
    }
}

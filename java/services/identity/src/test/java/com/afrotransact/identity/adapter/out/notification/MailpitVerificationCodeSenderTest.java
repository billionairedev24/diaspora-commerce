package com.afrotransact.identity.adapter.out.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.domain.VerificationPurpose;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.identity.port.VerificationMessage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** S276: local one-time codes land in Mailpit (send API); texted codes are findable by phone and by email. */
class MailpitVerificationCodeSenderTest {

    private static final UUID USER = UUID.randomUUID();
    private static final Instant EXPIRES = Instant.parse("2026-10-11T08:10:00.123456Z");

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private HttpServer mailpit;

    @AfterEach
    void stop() {
        if (mailpit != null) {
            mailpit.stop(0);
        }
    }

    private String startMailpit(AtomicReference<String> received) throws IOException {
        mailpit = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mailpit.createContext("/api/v1/send", ex -> {
            received.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] ok = "{\"ID\":\"x\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, ok.length);
            ex.getResponseBody().write(ok);
            ex.close();
        });
        mailpit.start();
        return "http://127.0.0.1:" + mailpit.getAddress().getPort() + "/";
    }

    @Test
    void textedSignInCodeGoesToTheAccountsEmailNamedAfterThePhone() throws IOException {
        when(users.findById(any())).thenReturn(Optional.of(new UserAccount(USER, "Amara", "Okonkwo",
                "staff.support@afrotransact.test", true, "+17135550105", "h", true, false, null, null, EXPIRES,
                EXPIRES, 0)));
        AtomicReference<String> received = new AtomicReference<>();
        new MailpitVerificationCodeSender(startMailpit(received), users).send(new VerificationMessage(USER,
                VerificationPurpose.SIGN_IN_SMS, "SMS", "+17135550105", "Amara", "482913", EXPIRES));

        JsonNode body = JsonMapper.builder().build().readTree(received.get());
        assertThat(body.get("To").get(0).get("Email").asString()).isEqualTo("staff.support@afrotransact.test");
        assertThat(body.get("To").get(0).get("Name").asString()).isEqualTo("SMS +17135550105");
        assertThat(body.get("Subject").asString()).contains("482913").contains("+17135550105");
        assertThat(body.get("Text").asString()).contains("code is 482913").contains("2026-10-11T08:10:00Z")
                .doesNotContain("123456");
        assertThat(body.get("Tags").toString()).contains("sms");
    }

    @Test
    void emailCodeGoesToTheAddressItWasSentTo() throws IOException {
        AtomicReference<String> received = new AtomicReference<>();
        new MailpitVerificationCodeSender(startMailpit(received), users).send(new VerificationMessage(USER,
                VerificationPurpose.EMAIL_VERIFICATION, "EMAIL", "ama@example.com", "Ama", "120934", EXPIRES));

        JsonNode body = JsonMapper.builder().build().readTree(received.get());
        assertThat(body.get("To").get(0).get("Email").asString()).isEqualTo("ama@example.com");
        assertThat(body.get("Subject").asString()).isEqualTo("AfroTransact code 120934 (email verification)");
    }

    @Test
    void mailpitDownNeverBreaksSignIn() {
        MailpitVerificationCodeSender sender = new MailpitVerificationCodeSender("http://127.0.0.1:1", users);
        assertThatCode(() -> sender.send(new VerificationMessage(USER, VerificationPurpose.EMAIL_VERIFICATION, "EMAIL",
                "ama@example.com", "Ama", "120934", EXPIRES))).doesNotThrowAnyException();
    }
}

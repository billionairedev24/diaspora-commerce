package com.afrotransact.identity.adapter.out.notification;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.identity.port.VerificationCodeSender;
import com.afrotransact.identity.port.VerificationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Local development only (S276): writes the code to the log, as {@link LoggingVerificationCodeSender} does, and drops it
 * in the local Mailpit inbox (http://localhost:8025) through Mailpit's send API. Email codes go to the address they
 * were sent to; text-message codes go to the account's email with the phone number as the recipient name and an
 * {@code sms} tag, so "search to:+17135550105" or the account's email finds them. The configuration refuses this
 * sender on any profile but {@code local}. When Mailpit is not running the log line is all there is.
 */
public class MailpitVerificationCodeSender implements VerificationCodeSender {

    private static final Logger LOG = LoggerFactory.getLogger(MailpitVerificationCodeSender.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final VerificationCodeSender log;
    private final UserAccountRepository users;
    private final URI sendApi;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    public MailpitVerificationCodeSender(String mailpitUrl, UserAccountRepository users) {
        this(mailpitUrl, users, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    MailpitVerificationCodeSender(String mailpitUrl, UserAccountRepository users, HttpClient http) {
        this.log = new LoggingVerificationCodeSender();
        this.users = users;
        String base = mailpitUrl.endsWith("/") ? mailpitUrl.substring(0, mailpitUrl.length() - 1) : mailpitUrl;
        this.sendApi = URI.create(base + "/api/v1/send");
        this.http = http;
    }

    @Override
    public void send(VerificationMessage m) {
        log.send(m);
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(sendApi).timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(message(m)))).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                LOG.warn("[local] Mailpit did not take the {} code ({}): {}", m.purpose(), res.statusCode(),
                        res.body());
            }
        } catch (IOException e) {
            LOG.warn("[local] Mailpit unreachable at {} ({}); the code is in this log only", sendApi, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The Mailpit send API body. */
    Map<String, Object> message(VerificationMessage m) {
        boolean sms = "SMS".equals(m.channel().toUpperCase(Locale.ROOT));
        String purpose = m.purpose().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        String to = sms ? users.findById(m.userId()).map(UserAccount::email).orElse("sms@afrotransact.local")
                : m.destination();
        String name = sms ? "SMS " + m.destination() : m.firstName();
        String subject = (sms ? "[SMS to " + m.destination() + "] " : "") + "AfroTransact code " + m.code()
                + " (" + purpose + ")";
        String text = "Hi " + (m.firstName() == null ? "there" : m.firstName()) + ",\n\n"
                + "Your AfroTransact " + purpose + " code is " + m.code() + ". It expires at " + m.expiresAt().truncatedTo(ChronoUnit.SECONDS)
                + ".\n\n" + (sms ? "Sent as a text message to " + m.destination() + ". " : "")
                + "Local development: identity delivered this straight to Mailpit "
                + "(at.identity.verification.sender=mailpit); other environments use the notifications service.\n";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("From", Map.of("Email", "no-reply@afrotransact.local", "Name", "AfroTransact identity (local)"));
        Map<String, String> recipient = new LinkedHashMap<>();
        recipient.put("Email", to);
        recipient.put("Name", name == null ? "" : name);
        body.put("To", List.of(recipient));
        body.put("Subject", subject);
        body.put("Text", text);
        body.put("Tags", List.of(sms ? "sms" : "email", "verification-code"));
        return body;
    }
}

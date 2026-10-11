package com.afrotransact.content.domain.privacy;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.content.domain.ContentRuleViolation;
import com.afrotransact.content.domain.ContentStateConflict;
import com.afrotransact.content.domain.EmailAddress;

/**
 * A privacy request from Your Privacy Choices: access, delete, correct, or opt out of sale, sharing and targeted
 * advertising. It is answered within {@link #RESPONSE_WINDOW} (US state privacy laws allow 45 days); staff complete
 * or deny it, and a denial must say why so the requester can appeal.
 *
 * @param id             request id
 * @param reference      {@code PR-######}, shown to the requester
 * @param type           what was asked for
 * @param email          email on the requester's account (identity is verified through it)
 * @param stateCode      US state of residence (two letters, DC included)
 * @param details        optional free text from the requester
 * @param accountSubject account id when the requester was signed in, else empty
 * @param locale         language of the form (replies use it)
 * @param status         received, completed or denied
 * @param resolutionNote what staff did, or why the request was denied
 * @param submittedAt    when it was received
 * @param dueAt          response deadline
 * @param resolvedAt     when it was completed or denied
 * @param resolvedBy     staff member who resolved it
 * @param version        optimistic-locking version
 */
public record PrivacyRequest(UUID id, String reference, PrivacyRequestType type, EmailAddress email, String stateCode,
                             String details, Optional<String> accountSubject, String locale, PrivacyRequestStatus status,
                             String resolutionNote, Instant submittedAt, Instant dueAt, Instant resolvedAt,
                             String resolvedBy, long version) {

    /** Response deadline counted from receipt. */
    public static final Duration RESPONSE_WINDOW = Duration.ofDays(45);
    /** Longest free text accepted from the requester or staff. */
    public static final int MAX_TEXT = 2000;

    private static final Set<String> LOCALES = Set.of("en", "fr", "es", "ar", "yo");

    /** What the form submits (raw values; validated by {@link #submit}). */
    public record Submission(String type, String email, String state, String details, String locale) {
    }

    /** Validates a submission (every problem reported at once, per field) and opens the request. */
    public static PrivacyRequest submit(UUID id, long sequence, Submission s, String accountSubject, Instant now) {
        Map<String, String> errors = new LinkedHashMap<>();
        Optional<PrivacyRequestType> type = PrivacyRequestType.parse(s.type());
        if (type.isEmpty()) {
            errors.put("type", "Choose what you would like to do");
        }
        String rawEmail = s.email() == null ? "" : s.email().trim().toLowerCase(Locale.ROOT);
        if (!EmailAddress.valid(rawEmail)) {
            errors.put("email", "Enter the email address on your account");
        }
        String state = s.state() == null ? "" : s.state().trim().toUpperCase(Locale.ROOT);
        if (!UsStates.isState(state)) {
            errors.put("state", "Choose your state");
        }
        String details = s.details() == null ? null : s.details().strip();
        if (details != null && details.length() > MAX_TEXT) {
            errors.put("details", "Use " + MAX_TEXT + " characters or fewer");
        }
        ContentRuleViolation.throwIfAny("content.invalid-privacy-request", "There are problems with your request", errors);
        String loc = s.locale() == null ? "en" : s.locale().trim().toLowerCase(Locale.ROOT);
        Optional<String> subject = accountSubject == null || accountSubject.isBlank()
                ? Optional.empty() : Optional.of(accountSubject);
        return new PrivacyRequest(id, reference(sequence), type.orElseThrow(), new EmailAddress(rawEmail), state,
                details == null || details.isEmpty() ? null : details, subject, LOCALES.contains(loc) ? loc : "en",
                PrivacyRequestStatus.RECEIVED, null, now, now.plus(RESPONSE_WINDOW), null, null, 0);
    }

    /** {@code PR-100001}: short enough to read out on the phone. */
    public static String reference(long sequence) {
        return String.format(Locale.ROOT, "PR-%06d", sequence);
    }

    public boolean overdue(Instant now) {
        return status == PrivacyRequestStatus.RECEIVED && now.isAfter(dueAt);
    }

    /** Completes or denies a received request; a denial needs the reason the requester will be given. */
    public PrivacyRequest resolve(PrivacyRequestStatus outcome, String note, String actor, Instant now) {
        if (status != PrivacyRequestStatus.RECEIVED) {
            throw new ContentStateConflict("content.privacy-request-resolved",
                    "Request " + reference + " is already " + status.name().toLowerCase(Locale.ROOT));
        }
        if (outcome == null || outcome == PrivacyRequestStatus.RECEIVED) {
            throw new ContentRuleViolation("content.invalid-privacy-resolution", "Choose completed or denied",
                    Map.of("outcome", "Choose completed or denied"));
        }
        String n = note == null ? "" : note.strip();
        if (outcome == PrivacyRequestStatus.DENIED && n.isEmpty()) {
            throw new ContentRuleViolation("content.invalid-privacy-resolution", "Say why the request is denied",
                    Map.of("note", "Say why the request is denied (the requester can appeal)"));
        }
        if (n.length() > MAX_TEXT) {
            throw new ContentRuleViolation("content.invalid-privacy-resolution", "The note is too long",
                    Map.of("note", "Use " + MAX_TEXT + " characters or fewer"));
        }
        return new PrivacyRequest(id, reference, type, email, stateCode, details, accountSubject, locale, outcome,
                n.isEmpty() ? null : n, submittedAt, dueAt, now, actor, version);
    }
}

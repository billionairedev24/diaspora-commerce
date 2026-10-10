package com.afrotransact.content.domain.careers;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.afrotransact.content.domain.ContentRuleViolation;
import com.afrotransact.content.domain.ContentStateConflict;
import com.afrotransact.content.domain.EmailAddress;

/**
 * Form rules from the careers page: name, valid email, LinkedIn link (empty or a linkedin.com profile), work
 * authorisation, an uploaded résumé (PDF or Word) and consent.
 */
public class DefaultApplicationPolicy implements ApplicationPolicy {

    private static final Pattern LINKEDIN =
            Pattern.compile("^https://([a-z]{2,3}\\.|www\\.)?linkedin\\.com/in/[A-Za-z0-9_%-]{3,100}/?$");
    private static final Pattern ASSET = Pattern.compile("^[A-Za-z0-9_-]{6,80}$");
    private static final Pattern RESUME = Pattern.compile("^[^/\\\\]{1,190}\\.(pdf|docx?)$", Pattern.CASE_INSENSITIVE);

    @Override
    public JobApplication validate(Job job, ApplicationDraft d, Instant now) {
        if (!job.open()) {
            throw new ContentStateConflict("content.job-closed", "This role is no longer accepting applications");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        String name = d.name() == null ? "" : d.name().trim().replaceAll("\\s+", " ");
        if (name.length() < 3 || name.length() > 120) {
            errors.put("name", "Enter your full name");
        }
        String email = d.email() == null ? "" : d.email().trim().toLowerCase(Locale.ROOT);
        if (!EmailAddress.valid(email)) {
            errors.put("email", "Enter a valid email address");
        }
        String linkedin = d.linkedin() == null || d.linkedin().isBlank() ? null : d.linkedin().trim();
        if (linkedin != null && !LINKEDIN.matcher(linkedin).matches()) {
            errors.put("linkedin", "Use your full LinkedIn profile link, like https://www.linkedin.com/in/your-name");
        }
        WorkAuthorization auth = WorkAuthorization.parse(d.workAuthorization());
        if (auth == null) {
            errors.put("workAuthorization", "Tell us whether you are authorised to work in the US");
        }
        if (d.resumeAssetId() == null || !ASSET.matcher(d.resumeAssetId()).matches()) {
            errors.put("resumeAssetId", "Upload your résumé");
        }
        if (d.resumeFileName() == null || !RESUME.matcher(d.resumeFileName().trim()).matches()) {
            errors.put("resumeFileName", "Upload your résumé as a PDF or Word document");
        }
        if (d.coverNote() != null && d.coverNote().length() > 4000) {
            errors.put("coverNote", "At most 4,000 characters");
        }
        if (!Boolean.TRUE.equals(d.consent())) {
            errors.put("consent", "Confirm that we may process your application");
        }
        ContentRuleViolation.throwIfAny("content.invalid-application", "Check the highlighted fields", errors);
        return new JobApplication(null, null, job.id(), name, new EmailAddress(email), linkedin, auth,
                d.resumeAssetId(), d.resumeFileName().trim(),
                d.coverNote() == null || d.coverNote().isBlank() ? null : d.coverNote().trim(), now);
    }
}

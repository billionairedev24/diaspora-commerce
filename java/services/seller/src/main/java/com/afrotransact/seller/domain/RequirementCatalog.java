package com.afrotransact.seller.domain;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maps Stripe requirement keys to typed forms and validates the seller's answers. */
public final class RequirementCatalog {

    private static final Pattern SUBJECT = Pattern.compile("^(company|individual|representative|person_[A-Za-z0-9]+)\\..*");
    private static final List<String> DOCUMENT_TYPES = List.of("IRS letter 147C", "IRS SS-4 confirmation (CP 575)",
            "Articles of organization", "State business licence", "Passport", "Driver's licence", "State ID card",
            "Utility bill", "Bank statement", "Lease agreement", "Government letter");

    private RequirementCatalog() {
    }

    public static RequirementKind classify(String key) {
        if (key == null) {
            return RequirementKind.UNSUPPORTED;
        }
        if (key.equals("company.tax_id")) {
            return RequirementKind.TAX_ID;
        }
        if (key.endsWith(".id_number") || key.endsWith(".ssn_last_4")) {
            return RequirementKind.ID_NUMBER;
        }
        if (key.contains(".verification.document") || key.contains(".verification.additional_document")) {
            return RequirementKind.VERIFICATION_DOCUMENT;
        }
        if (key.startsWith("company.ownership_declaration") || key.equals("company.owners_provided")) {
            return RequirementKind.OWNERSHIP_DECLARATION;
        }
        if (key.matches("^(individual|representative|person_[A-Za-z0-9]+)\\.dob(\\..*)?$")) {
            return RequirementKind.REPRESENTATIVE_DOB;
        }
        if (key.endsWith("relationship.title")) {
            return RequirementKind.REPRESENTATIVE_TITLE;
        }
        if (key.startsWith("tos_acceptance")) {
            return RequirementKind.TOS_ACCEPTANCE;
        }
        if (key.equals("external_account")) {
            return RequirementKind.BANK_ACCOUNT;
        }
        if (key.equals("business_profile.url") || key.equals("business_profile.product_description")) {
            return RequirementKind.BUSINESS_URL;
        }
        return RequirementKind.UNSUPPORTED;
    }

    public static String subject(String key) {
        Matcher m = SUBJECT.matcher(key == null ? "" : key);
        return m.matches() ? m.group(1) : "account";
    }

    public static RequirementForm form(String key) {
        RequirementKind kind = classify(key);
        String subject = subject(key);
        return switch (kind) {
            case TAX_ID -> new RequirementForm(key, kind, "Company tax ID (EIN)", subject,
                    "Stripe must match the business name to the IRS record before payouts.",
                    List.of(new FormField("ein", "EIN", "TEXT", true, null, "^\\d{2}-?\\d{7}$")));
            case ID_NUMBER -> new RequirementForm(key, kind, "Full Social Security number", subject,
                    "The last 4 digits couldn't be matched; Stripe needs the full number. It is tokenised by "
                            + "Stripe.js in your browser and never stored by AfroTransact.",
                    List.of(new FormField("idNumberToken", "SSN token (Stripe.js, pii_…)", "TOKEN", true, null,
                            "^pii_[A-Za-z0-9]+$")));
            case VERIFICATION_DOCUMENT -> new RequirementForm(key, kind, key.contains("additional_document")
                    ? "Proof of address" : subject.equals("company") ? "Business verification document"
                    : "Photo ID", subject, "Stripe couldn't verify this automatically; upload a clear document.",
                    List.of(new FormField("documentType", "Document type", "SELECT", true, DOCUMENT_TYPES, null),
                            new FormField("frontAssetId", "Upload document (front)", "ASSET", true, null, null),
                            new FormField("backAssetId", "Back (ID cards only)", "ASSET", false, null, null)));
            case OWNERSHIP_DECLARATION -> new RequirementForm(key, kind, "Ownership declaration", subject,
                    "Confirm the owners listed are complete and correct (FinCEN beneficial ownership).",
                    List.of(new FormField("declare", "I confirm the owners and directors provided are up to date "
                            + "and correct", "CHECKBOX", true, null, null),
                            new FormField("declarerName", "Your full name", "TEXT", true, null, null)));
            case REPRESENTATIVE_DOB -> new RequirementForm(key, kind, "Date of birth", subject,
                    "Needed to complete identity verification.",
                    List.of(new FormField("dateOfBirth", "Date of birth", "DATE", true, null, null)));
            case REPRESENTATIVE_TITLE -> new RequirementForm(key, kind, "Representative job title", subject,
                    "Stripe needs the title of the person who controls the account.",
                    List.of(new FormField("title", "Title", "TEXT", true, null, null)));
            case TOS_ACCEPTANCE -> new RequirementForm(key, kind, "Stripe Connected Account Agreement", subject,
                    "Updated Stripe terms must be accepted to keep accepting payments.",
                    List.of(new FormField("accept", "I accept the Stripe Connected Account Agreement", "CHECKBOX",
                            true, null, null)));
            case BANK_ACCOUNT -> new RequirementForm(key, kind, "Payout bank account", subject,
                    "Your bank rejected the last payout or no payout account is on file.",
                    List.of(new FormField("routingNumber", "Routing number", "TEXT", true, null, "^\\d{9}$"),
                            new FormField("bankAccountToken", "Bank account token (Stripe.js, btok_…)", "TOKEN",
                                    true, null, "^btok_[A-Za-z0-9]+$")));
            case BUSINESS_URL -> new RequirementForm(key, kind, "Business website or product description", subject,
                    "Card networks require a public description of what you sell.",
                    List.of(new FormField("url", "Website or social profile URL", "URL", true, null,
                            "^https?://\\S+\\.\\S+$")));
            case UNSUPPORTED -> new RequirementForm(key, kind, "Contact support", subject,
                    "This item can't be answered online yet; our team will help you.", List.of());
        };
    }

    /** Validates an answer against the requirement's form. */
    public static Validated<RequirementAnswer> answer(String key, Map<String, String> values, LocalDate today) {
        RequirementForm form = form(key);
        Map<String, String> errors = new LinkedHashMap<>();
        if (form.kind() == RequirementKind.UNSUPPORTED) {
            errors.put("requirement", "This requirement can't be answered online; contact support");
            return new Validated<>(null, errors);
        }
        for (FormField f : form.fields()) {
            String v = values.get(f.name());
            boolean empty = v == null || v.isBlank();
            if (empty) {
                if (f.required()) {
                    errors.put(f.name(), "Required");
                }
                continue;
            }
            String t = v.trim();
            if (f.pattern() != null && !t.matches(f.pattern())) {
                errors.put(f.name(), "Invalid format");
            } else if ("SELECT".equals(f.type()) && !f.options().contains(t)) {
                errors.put(f.name(), "Choose one of the listed options");
            } else if ("CHECKBOX".equals(f.type()) && !"true".equalsIgnoreCase(t)) {
                errors.put(f.name(), "Required");
            } else if ("DATE".equals(f.type())) {
                try {
                    LocalDate d = LocalDate.parse(t);
                    if (d.isAfter(today.minusYears(18))) {
                        errors.put(f.name(), "Must be 18 or older");
                    }
                } catch (DateTimeParseException ex) {
                    errors.put(f.name(), "Use YYYY-MM-DD");
                }
            }
        }
        String ein = values.get("ein");
        if (form.kind() == RequirementKind.TAX_ID && ein != null && !TaxIds.isValidEin(ein)) {
            errors.putIfAbsent("ein", "Enter the 9-digit EIN, e.g. 12-3456789");
        }
        String routing = values.get("routingNumber");
        if (form.kind() == RequirementKind.BANK_ACCOUNT && routing != null && !RoutingNumbers.isValid(routing.trim())) {
            errors.putIfAbsent("routingNumber", "This routing number isn't valid");
        }
        if (!errors.isEmpty()) {
            return new Validated<>(null, errors);
        }
        String dob = values.get("dateOfBirth");
        return new Validated<>(new RequirementAnswer(key, form.kind(), form.subject(),
                ein == null ? null : TaxIds.digits(ein), trimmed(values, "idNumberToken"),
                trimmed(values, "documentType"), trimmed(values, "frontAssetId"), trimmed(values, "backAssetId"),
                trimmed(values, "declarerName"), dob == null ? null : LocalDate.parse(dob.trim()),
                trimmed(values, "title"), trimmed(values, "bankAccountToken"),
                routing == null ? null : TaxIds.last4(routing), trimmed(values, "url")), errors);
    }

    private static String trimmed(Map<String, String> values, String key) {
        String v = values.get(key);
        return v == null || v.isBlank() ? null : v.trim();
    }
}

package com.afrotransact.seller.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maps Stripe requirement keys to what Account health shows and the embedded component that resolves them. */
public final class RequirementCatalog {

    private static final Pattern SUBJECT = Pattern.compile("^(company|individual|representative|person_[A-Za-z0-9]+)\\..*");

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

    /**
     * What Account health shows for {@code key}. Nothing is typed into AfroTransact: Stripe collects it in the
     * embedded component the form names (the payout bank account in account management, everything else in account
     * onboarding, which asks for exactly what is due).
     */
    public static RequirementForm form(String key) {
        RequirementKind kind = classify(key);
        String subject = subject(key);
        return switch (kind) {
            case TAX_ID -> onboarding(key, kind, "Company tax ID (EIN)", subject,
                    "Stripe must match the business name to the IRS record before payouts.");
            case ID_NUMBER -> onboarding(key, kind, "Full Social Security number", subject,
                    "The last 4 digits couldn't be matched; Stripe needs the full number. You enter it in Stripe's "
                            + "secure form; AfroTransact never sees it.");
            case VERIFICATION_DOCUMENT -> onboarding(key, kind, key.contains("additional_document")
                    ? "Proof of address" : subject.equals("company") ? "Business verification document"
                    : "Photo ID", subject, "Stripe couldn't verify this automatically; upload a clear document.");
            case OWNERSHIP_DECLARATION -> onboarding(key, kind, "Ownership declaration", subject,
                    "Confirm the owners listed are complete and correct (FinCEN beneficial ownership).");
            case REPRESENTATIVE_DOB -> onboarding(key, kind, "Date of birth", subject,
                    "Needed to complete identity verification.");
            case REPRESENTATIVE_TITLE -> onboarding(key, kind, "Representative job title", subject,
                    "Stripe needs the title of the person who controls the account.");
            case TOS_ACCEPTANCE -> onboarding(key, kind, "Stripe Connected Account Agreement", subject,
                    "Updated Stripe terms must be accepted to keep accepting payments.");
            case BANK_ACCOUNT -> new RequirementForm(key, kind, "Payout bank account", subject,
                    "Your bank rejected the last payout or no payout account is on file. Change it in Stripe's "
                            + "account settings.", RequirementForm.ACCOUNT_MANAGEMENT);
            case BUSINESS_URL -> onboarding(key, kind, "Business website or product description", subject,
                    "Card networks require a public description of what you sell.");
            case UNSUPPORTED -> onboarding(key, kind, "More information for Stripe", subject,
                    "Stripe needs more information about your business.");
        };
    }

    private static RequirementForm onboarding(String key, RequirementKind kind, String label, String subject,
                                              String why) {
        return new RequirementForm(key, kind, label, subject, why, RequirementForm.ACCOUNT_ONBOARDING);
    }
}

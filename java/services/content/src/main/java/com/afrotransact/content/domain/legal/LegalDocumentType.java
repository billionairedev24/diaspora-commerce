package com.afrotransact.content.domain.legal;

import com.afrotransact.content.domain.ContentRuleViolation;

/** Documents of the Legal & privacy center. */
public enum LegalDocumentType {
    CONDITIONS_OF_USE("conditions-of-use", "Conditions of Use"),
    PRIVACY("privacy", "Privacy Notice"),
    COOKIES("cookies", "Cookie Notice"),
    PRIVACY_CHOICES("privacy-choices", "Your Privacy Choices"),
    RETURNS("returns", "Returns & Refunds"),
    SELLER_TERMS("seller-terms", "Seller & Provider Terms"),
    PROHIBITED_ITEMS("prohibited-items", "Prohibited & Restricted Items"),
    ACCESSIBILITY("accessibility", "Accessibility Statement"),
    SMS_TERMS("sms-terms", "SMS & Notification Terms");

    private final String key;
    private final String title;

    LegalDocumentType(String key, String title) {
        this.key = key;
        this.title = title;
    }

    public String key() {
        return key;
    }

    public String title() {
        return title;
    }

    public static LegalDocumentType fromKey(String key) {
        for (LegalDocumentType t : values()) {
            if (t.key.equalsIgnoreCase(key) || t.name().equalsIgnoreCase(key)) {
                return t;
            }
        }
        throw new ContentRuleViolation("content.unknown-legal-document", "Unknown legal document " + key);
    }
}

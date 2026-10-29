package com.afrotransact.content.domain.legal;

import java.util.Optional;

import com.afrotransact.content.domain.ContentRuleViolation;

/** Documents of the Legal & privacy center. */
public enum LegalDocumentType {
    CONDITIONS_OF_USE("conditions-of-use", "Conditions of Use"),
    PRIVACY("privacy", "Privacy Notice"),
    COOKIES("cookies", "Cookie Notice"),
    PRIVACY_CHOICES("privacy-choices", "Your Privacy Choices"),
    RETURNS("returns", "Returns & Refunds"),
    SELLER_TERMS("seller-terms", "Seller Terms"),
    PROHIBITED_ITEMS("prohibited-items", "Prohibited & Restricted Items"),
    ACCESSIBILITY("accessibility", "Accessibility Statement"),
    SMS_TERMS("sms-terms", "SMS & Notification Terms"),
    GIFT_CARD_TERMS("gift-card-terms", "Gift Card Terms"),
    STORE_CREDIT_TERMS("store-credit-terms", "Store Credit Terms"),
    MEMBERSHIP_TERMS("membership-terms", "Plus Membership Terms"),
    REFERRAL_TERMS("referral-terms", "Referral Program Terms"),
    PREORDER_DELIVERY_TERMS("preorder-delivery-terms", "Pre-order and Delivery Run Terms"),
    COMMUNITY_GUIDELINES("community-guidelines", "Community Guidelines"),
    IP_POLICY("ip-policy", "Intellectual Property and Copyright Policy"),
    PROVIDER_TERMS("provider-terms", "Service Provider Terms"),
    PAYMENTS_TERMS("payments-terms", "Payment Terms for Sellers and Providers"),
    SUBPROCESSORS("subprocessors", "Sub-processors and Service Providers"),
    DATA_PROCESSING("data-processing", "Data Processing Terms"),
    SELLER_FEES("seller-fees", "Seller Fees Summary");

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

    /** The document with this key (or enum name), empty when no such document exists. */
    public static Optional<LegalDocumentType> find(String key) {
        for (LegalDocumentType t : values()) {
            if (t.key.equalsIgnoreCase(key) || t.name().equalsIgnoreCase(key)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
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

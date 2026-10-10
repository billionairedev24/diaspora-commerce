package com.afrotransact.ai.application;

/** Permission keys checked by ai-gateway use cases (granted by identity-service roles and client scopes). */
public final class AiPermissions {

    /** Seller and staff AI utilities (listing writer, translation, taglines) and service embeddings. */
    public static final String USE = "ai:use";
    /** Trust-team triage summaries of applications and listings. */
    public static final String TRIAGE = "ai:triage";
    /** Model routes, budgets and Lola settings (existing identity permission). */
    public static final String MANAGE = "ai:manage";
    /** Service scope of seller-service: read AI usage (credits) per organisation and day for metered billing. */
    public static final String USAGE_READ = "ai:usage.read";

    private AiPermissions() {
    }
}

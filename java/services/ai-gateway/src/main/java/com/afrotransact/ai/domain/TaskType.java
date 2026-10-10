package com.afrotransact.ai.domain;

/**
 * What a model call is for. Each task has its own ordered list of models in the routing table, so a fast, cheap model
 * can classify intent while a strong model plans tool use.
 */
public enum TaskType {
    /** Short classification and summaries (fast, cheap model). */
    INTENT,
    /** First Lola round: decide which tools to call (strong model). */
    PLANNING,
    /** Lola rounds after tool results: write the answer (strong model). */
    CHAT,
    /** Listing titles, descriptions, bullets and storefront taglines. */
    LISTING_WRITER,
    /** Listing and message translation. */
    TRANSLATION,
    /** Trust-team summaries of applications and listings. */
    TRIAGE,
    /** Text embeddings for semantic search. */
    EMBEDDING,
    /** Vision check of uploaded product and storefront images before they are published (media-service). */
    IMAGE_MODERATION
}

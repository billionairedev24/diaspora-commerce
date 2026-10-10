package com.afrotransact.ai.application;

import java.util.List;
import java.util.Map;

/**
 * Seller and staff writing aids. Everything returned is a draft for a human to edit: nothing is published, saved to a
 * listing or sent anywhere by this service.
 */
public interface ContentAssistService {

    ListingDraft writeListing(Caller caller, ListingRequest request);

    Translation translate(Caller caller, TranslationRequest request);

    Taglines taglines(Caller caller, TaglineRequest request);

    /**
     * Listing writer input.
     *
     * @param productType product type code, for example {@code SPICE_BLEND}
     * @param title       current title (optional)
     * @param attributes  product attributes (brand, weight, origin, ingredients …)
     * @param language    output language (BCP 47, default en)
     * @param tone        optional tone, for example "warm" or "premium"
     */
    record ListingRequest(String productType, String title, Map<String, String> attributes, String language,
                          String tone) {
    }

    /**
     * Listing draft.
     *
     * @param title          suggested title (≤ 200 characters)
     * @param description    suggested description
     * @param bullets        up to five key-feature bullets
     * @param status         always {@code DRAFT}
     * @param autoPublished  always false — the seller reviews and publishes
     * @param model          model that wrote it
     */
    record ListingDraft(String title, String description, List<String> bullets, String status,
                        boolean autoPublished, String model) {
    }

    /**
     * Translation input.
     *
     * @param text           text to translate
     * @param targetLanguage BCP 47 target, for example {@code fr} or {@code yo}
     * @param sourceLanguage optional source language
     * @param context        optional context, for example "product title"
     */
    record TranslationRequest(String text, String targetLanguage, String sourceLanguage, String context) {
    }

    /** Translated text (a draft; never applied to a listing automatically). */
    record Translation(String translation, String targetLanguage, String model) {
    }

    /**
     * Storefront tagline input.
     *
     * @param storeName   store name
     * @param description what the store sells
     * @param categories  main categories
     * @param tone        optional tone
     * @param count       suggestions wanted (1–5)
     */
    record TaglineRequest(String storeName, String description, List<String> categories, String tone, int count) {
    }

    /** Tagline suggestions to choose from. */
    record Taglines(List<String> suggestions, String model) {
    }
}

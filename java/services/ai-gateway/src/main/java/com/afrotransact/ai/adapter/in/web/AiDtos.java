package com.afrotransact.ai.adapter.in.web;

import java.util.List;
import java.util.Map;

import com.afrotransact.ai.application.LolaRequest;
import com.afrotransact.ai.domain.lola.LolaEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the ai-gateway API. */
final class AiDtos {

    private AiDtos() {
    }

    @Schema(description = "A shopper message to Lola")
    record ChatRequest(
            @Schema(description = "Conversation id returned by a previous turn; omit to start a new conversation",
                    example = "lola_3f2a9c0d1e7b4a51") @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String sessionId,
            @Schema(description = "What the shopper typed", example = "Jollof for 20")
            @NotBlank @Size(max = 4000) String message,
            @Schema(description = "Deliver-to ZIP from the header picker", example = "77002")
            @Pattern(regexp = "\\d{5}") String zip,
            @Schema(description = "Preferred reply language (BCP 47)", example = "en") @Size(max = 16) String language) {

        LolaRequest toRequest() {
            return new LolaRequest(sessionId, message, zip, language);
        }
    }

    @Schema(description = "One streamed event, as in the SSE stream (event name + JSON data)")
    record EventView(@Schema(example = "tool_call") String type, Object data) {

        static EventView of(LolaEvent event) {
            return new EventView(event.type(), event);
        }
    }

    @Schema(description = "Non-streaming Lola turn: every event in order, then the final turn summary")
    record ChatReply(List<EventView> events, LolaEvent.Done done) {
    }

    @Schema(description = "A stored (redacted) conversation turn")
    record TurnView(String role, String content, List<?> products, String at) {
    }

    @Schema(description = "Embedding request (the Go search service sends exactly {input, purpose})")
    record EmbeddingRequest(
            @Schema(description = "Text to embed", example = "spicy pepper soup for a cold night")
            @NotBlank @Size(max = 16000) String input,
            @Schema(description = "search-query, search-document, listing or similarity", example = "search-query")
            String purpose) {
    }

    @Schema(description = "Embedding vector")
    record EmbeddingResponse(@Schema(description = "The vector") float[] embedding,
                             @Schema(example = "openai/text-embedding-3-small") String model,
                             @Schema(example = "1536") int dimensions) {
    }

    @Schema(description = "Listing writer input; the result is a draft and is never published automatically")
    record ListingWriterRequest(@Schema(example = "SPICE_BLEND") String productType,
                                @Schema(example = "Jollof seasoning 100g") String title,
                                @Schema(description = "Product attributes") Map<String, String> attributes,
                                @Schema(example = "en") String language,
                                @Schema(example = "warm") String tone) {
    }

    @Schema(description = "Translation input")
    record TranslateRequest(@NotBlank @Size(max = 16000) String text,
                            @NotBlank @Schema(example = "fr") String targetLanguage,
                            @Schema(example = "en") String sourceLanguage,
                            @Schema(example = "product description") String context) {
    }

    @Schema(description = "Storefront tagline input")
    record TaglineRequest(@NotBlank @Size(max = 120) String storeName,
                          @Size(max = 2000) String description,
                          List<String> categories,
                          @Schema(example = "joyful") String tone,
                          @Schema(example = "3") Integer count) {
    }

    @Schema(description = "Image moderation input (media-service sends a JPEG preview of at most 768 px)")
    record ModerateImageRequest(
            @Schema(description = "Base64-encoded image (at most 2 MB decoded)")
            @NotBlank @Size(max = 2_900_000) String image,
            @Schema(example = "image/jpeg") @NotBlank String contentType,
            @Schema(description = "product-image or storefront", example = "product-image") String purpose) {
    }

    @Schema(description = "A moderation finding")
    record ModerationLabelView(@Schema(example = "WATERMARK") String code,
                               @Schema(example = "Another shop's logo in the corner") String description) {
    }

    @Schema(description = "Image moderation verdict; advisory, media decides how to apply it")
    record ModerateImageResponse(
            @Schema(description = "ALLOW (publish), REVIEW (hold for a person) or BLOCK (prohibited)",
                    allowableValues = {"ALLOW", "REVIEW", "BLOCK"}) String decision,
            List<ModerationLabelView> labels,
            @Schema(example = "google/gemini-2.5-flash") String model) {
    }

    @Schema(description = "Trust-team triage input; field values as the reviewer sees them (masked where required)")
    record TriageRequest(@NotBlank @Schema(example = "APPLICATION") String subjectType,
                         @NotBlank String subjectId,
                         @NotEmpty Map<String, String> fields) {
    }
}

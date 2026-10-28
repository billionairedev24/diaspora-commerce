package com.afrotransact.ai.adapter.in.web;

import java.util.Base64;
import java.util.List;

import com.afrotransact.ai.adapter.in.web.AiDtos.AssistantReply;
import com.afrotransact.ai.adapter.in.web.AiDtos.AssistantRequest;
import com.afrotransact.ai.adapter.in.web.AiDtos.AssistantToolCall;
import com.afrotransact.ai.adapter.in.web.AiDtos.EmbeddingRequest;
import com.afrotransact.ai.adapter.in.web.AiDtos.EmbeddingResponse;
import com.afrotransact.ai.adapter.in.web.AiDtos.ListingWriterRequest;
import com.afrotransact.ai.adapter.in.web.AiDtos.ModerateImageRequest;
import com.afrotransact.ai.adapter.in.web.AiDtos.ModerateImageResponse;
import com.afrotransact.ai.adapter.in.web.AiDtos.ModerationLabelView;
import com.afrotransact.ai.adapter.in.web.AiDtos.TaglineRequest;
import com.afrotransact.ai.adapter.in.web.AiDtos.TranslateRequest;
import com.afrotransact.ai.adapter.in.web.AiDtos.TriageRequest;
import com.afrotransact.ai.application.ContentAssistService;
import com.afrotransact.ai.application.EmbeddingService;
import com.afrotransact.ai.application.ImageModerationService;
import com.afrotransact.ai.application.SellerAssistantService;
import com.afrotransact.ai.application.TriageService;
import com.afrotransact.ai.domain.AiRuleViolation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "AI utilities", description = "Embeddings for search, image moderation for media and drafting aids for "
        + "sellers and staff")
@RestController
class AiController {

    private final EmbeddingService embeddings;
    private final ContentAssistService content;
    private final TriageService triage;
    private final ImageModerationService images;
    private final SellerAssistantService assistant;
    private final CallerResolver callers;

    AiController(EmbeddingService embeddings, ContentAssistService content, TriageService triage,
                 ImageModerationService images, SellerAssistantService assistant, CallerResolver callers) {
        this.embeddings = embeddings;
        this.content = content;
        this.triage = triage;
        this.images = images;
        this.assistant = assistant;
        this.callers = callers;
    }

    @Operation(summary = "Embed text (ai:use)", description = """
            Used by the search service for semantic retrieval: request `{"input","purpose"}`, response \
            `{"embedding":[…]}` (plus model and dimensions). Personal data is redacted before embedding.""")
    @PostMapping("/v1/embeddings")
    EmbeddingResponse embed(@Valid @RequestBody EmbeddingRequest body, HttpServletRequest request) {
        EmbeddingService.Result r = embeddings.embed(callers.current(request), body.input(), body.purpose());
        return new EmbeddingResponse(r.embedding(), r.model(), r.dimensions());
    }

    @Operation(summary = "Draft a listing title, description and bullets (ai:use)",
            description = "Returns a DRAFT for the seller to edit; nothing is saved or published.")
    @PostMapping("/v1/ai/listing-writer")
    ContentAssistService.ListingDraft listingWriter(@RequestBody ListingWriterRequest body,
                                                    HttpServletRequest request) {
        return content.writeListing(callers.current(request), new ContentAssistService.ListingRequest(
                body.productType(), body.title(), body.attributes(), body.language(), body.tone()));
    }

    @Operation(summary = "Translate text (ai:use)", description = "Draft translation; never applied automatically.")
    @PostMapping("/v1/ai/translate")
    ContentAssistService.Translation translate(@Valid @RequestBody TranslateRequest body, HttpServletRequest request) {
        return content.translate(callers.current(request), new ContentAssistService.TranslationRequest(body.text(),
                body.targetLanguage(), body.sourceLanguage(), body.context()));
    }

    @Operation(summary = "Suggest storefront taglines (ai:use)")
    @PostMapping("/v1/ai/tagline")
    ContentAssistService.Taglines tagline(@Valid @RequestBody TaglineRequest body, HttpServletRequest request) {
        return content.taglines(callers.current(request), new ContentAssistService.TaglineRequest(body.storeName(),
                body.description(), body.categories(), body.tone(), body.count() == null ? 3 : body.count()));
    }

    @Operation(summary = "Moderate an uploaded image before it is published (ai:use)", description = """
            The AI moderation hook of media-service (client media-service): a vision model checks a product or \
            storefront image for prohibited content (BLOCK) and marketplace rules such as watermarks, contact \
            details or text overlays (REVIEW). Labels imply a minimum decision and an unreadable answer is REVIEW, \
            never ALLOW. Advisory: media holds or publishes the image and staff review held ones.""")
    @PostMapping("/v1/ai/moderate-image")
    ModerateImageResponse moderateImage(@Valid @RequestBody ModerateImageRequest body, HttpServletRequest request) {
        byte[] image;
        try {
            image = Base64.getMimeDecoder().decode(body.image());
        } catch (IllegalArgumentException e) {
            throw new AiRuleViolation("ai.unsupported-image", "image must be base64-encoded");
        }
        ImageModerationService.Result r = images.moderate(callers.current(request),
                new ImageModerationService.Request(image, body.contentType(), body.purpose()));
        return new ModerateImageResponse(r.decision().name(),
                r.labels().stream().map(l -> new ModerationLabelView(l.code(), l.description())).toList(), r.model());
    }

    @Operation(summary = "One model turn for Lola in Seller Center (ai:use, seller and provider members)", description = """
            Called by the gateway-bff, which runs the conversation, the tools (it holds the seller's session and the \
            platform APIs) and the plan tier limits. Send the conversation and the tools offered for this turn; the \
            answer is text, or tool calls for the caller to run before calling again. Nothing is executed here. The \
            usage is billed to the member's organisation as AI credits and counted against the `seller-assistant` \
            budget (429 `ai.budget-exceeded` when it is spent).""")
    @PostMapping("/v1/ai/seller-assistant/complete")
    AssistantReply sellerAssistant(@Valid @RequestBody AssistantRequest body, HttpServletRequest request) {
        SellerAssistantService.Reply r = assistant.complete(callers.current(request), new SellerAssistantService.Request(
                body.messages().stream().map(m -> new SellerAssistantService.Message(m.role(), m.content(),
                        m.toolCalls() == null ? List.of() : m.toolCalls().stream()
                                .map(c -> new SellerAssistantService.ToolCall(c.id(), c.name(), c.arguments())).toList(),
                        m.toolCallId(), m.toolName())).toList(),
                body.tools() == null ? List.of() : body.tools().stream().map(t -> new SellerAssistantService.Tool(
                        t.name(), t.description(), t.inputSchema())).toList(),
                body.maxTokens()));
        return new AssistantReply(r.text(), r.toolCalls().stream()
                .map(c -> new AssistantToolCall(c.id(), c.name(), c.arguments())).toList(), r.promptTokens(),
                r.completionTokens(), r.model());
    }

    @Operation(summary = "One model turn for Lola in the staff console (ai:use, staff accounts)", description = """
            Called by the gateway-bff, which runs the conversation and the tools with the staff member's own session \
            and permissions. Same shape as the seller assistant turn. Nothing is executed here; counted against the \
            `staff-assistant` budget (429 `ai.budget-exceeded` when it is spent).""")
    @PostMapping("/v1/ai/staff-assistant/complete")
    AssistantReply staffAssistant(@Valid @RequestBody AssistantRequest body, HttpServletRequest request) {
        SellerAssistantService.Reply r = assistant.completeForStaff(callers.current(request),
                new SellerAssistantService.Request(
                        body.messages().stream().map(m -> new SellerAssistantService.Message(m.role(), m.content(),
                                m.toolCalls() == null ? List.of() : m.toolCalls().stream()
                                        .map(c -> new SellerAssistantService.ToolCall(c.id(), c.name(), c.arguments()))
                                        .toList(), m.toolCallId(), m.toolName())).toList(),
                        body.tools() == null ? List.of() : body.tools().stream().map(t -> new SellerAssistantService.Tool(
                                t.name(), t.description(), t.inputSchema())).toList(),
                        body.maxTokens()));
        return new AssistantReply(r.text(), r.toolCalls().stream()
                .map(c -> new AssistantToolCall(c.id(), c.name(), c.arguments())).toList(), r.promptTokens(),
                r.completionTokens(), r.model());
    }

    @Operation(summary = "Trust-team triage summary with cited fields (ai:triage)",
            description = "Advisory only: decision is always HUMAN_REVIEW_REQUIRED; flags without real citations are "
                    + "dropped.")
    @PostMapping("/v1/ai/triage")
    TriageService.Report triage(@Valid @RequestBody TriageRequest body, HttpServletRequest request) {
        return triage.triage(callers.current(request), new TriageService.Request(body.subjectType(), body.subjectId(),
                body.fields()));
    }
}

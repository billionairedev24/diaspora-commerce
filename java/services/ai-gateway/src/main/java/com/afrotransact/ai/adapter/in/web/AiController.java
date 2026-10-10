package com.afrotransact.ai.adapter.in.web;

import com.afrotransact.ai.adapter.in.web.AiDtos.EmbeddingRequest;
import com.afrotransact.ai.adapter.in.web.AiDtos.EmbeddingResponse;
import com.afrotransact.ai.adapter.in.web.AiDtos.ListingWriterRequest;
import com.afrotransact.ai.adapter.in.web.AiDtos.TaglineRequest;
import com.afrotransact.ai.adapter.in.web.AiDtos.TranslateRequest;
import com.afrotransact.ai.adapter.in.web.AiDtos.TriageRequest;
import com.afrotransact.ai.application.ContentAssistService;
import com.afrotransact.ai.application.EmbeddingService;
import com.afrotransact.ai.application.TriageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "AI utilities", description = "Embeddings for search and drafting aids for sellers and staff")
@RestController
class AiController {

    private final EmbeddingService embeddings;
    private final ContentAssistService content;
    private final TriageService triage;
    private final CallerResolver callers;

    AiController(EmbeddingService embeddings, ContentAssistService content, TriageService triage,
                 CallerResolver callers) {
        this.embeddings = embeddings;
        this.content = content;
        this.triage = triage;
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

    @Operation(summary = "Trust-team triage summary with cited fields (ai:triage)",
            description = "Advisory only: decision is always HUMAN_REVIEW_REQUIRED; flags without real citations are "
                    + "dropped.")
    @PostMapping("/v1/ai/triage")
    TriageService.Report triage(@Valid @RequestBody TriageRequest body, HttpServletRequest request) {
        return triage.triage(callers.current(request), new TriageService.Request(body.subjectType(), body.subjectId(),
                body.fields()));
    }
}

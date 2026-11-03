package com.afrotransact.catalog.adapter.in.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.adapter.in.web.DraftDtos.CreateDraftRequest;
import com.afrotransact.catalog.adapter.in.web.DraftDtos.DraftResponse;
import com.afrotransact.catalog.adapter.in.web.DraftDtos.DraftSummary;
import com.afrotransact.catalog.adapter.in.web.DraftDtos.SaveDraftRequest;
import com.afrotransact.catalog.application.ProductDraftService;
import com.afrotransact.catalog.domain.SavedDraft;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@Tag(name = "Product drafts", description = "Autosaved drafts of the one-step product form (S307)")
@RestController
@RequestMapping("/v1/product-drafts")
class DraftController {

    private final ProductDraftService drafts;
    private final JsonMapper json;

    DraftController(ProductDraftService drafts, JsonMapper json) {
        this.drafts = drafts;
        this.json = json;
    }

    @Operation(operationId = "listDrafts", summary = "The organisation's drafts, most recently saved first")
    @GetMapping
    List<DraftSummary> list() {
        return drafts.list().stream().map(DraftSummary::of).toList();
    }

    @Operation(operationId = "createDraft", summary = "Save a new draft (Save draft, or the first autosave)")
    @PostMapping
    ResponseEntity<DraftResponse> create(@Valid @RequestBody CreateDraftRequest body) {
        SavedDraft d = drafts.create(body.productId(), body.title(), body.categoryPath(),
                json.writeValueAsString(body.payload()));
        return ResponseEntity.created(URI.create("/v1/product-drafts/" + d.id())).body(DraftResponse.of(d, json));
    }

    @Operation(operationId = "getDraft", summary = "Get a draft with its payload")
    @GetMapping("/{id}")
    DraftResponse get(@PathVariable UUID id) {
        return DraftResponse.of(drafts.get(id), json);
    }

    @Operation(operationId = "saveDraft", summary = "Autosave a draft",
            description = "409 catalog.draft-changed when the draft was saved "
            + "elsewhere since version")
    @PutMapping("/{id}")
    DraftResponse save(@PathVariable UUID id, @Valid @RequestBody SaveDraftRequest body) {
        return DraftResponse.of(drafts.save(id, body.version(), body.title(), body.categoryPath(),
                json.writeValueAsString(body.payload())), json);
    }

    @Operation(operationId = "deleteDraft", summary = "Discard a draft")
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        drafts.delete(id);
        return ResponseEntity.noContent().build();
    }
}

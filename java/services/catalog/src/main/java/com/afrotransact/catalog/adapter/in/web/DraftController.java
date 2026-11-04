package com.afrotransact.catalog.adapter.in.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.adapter.in.web.DraftDtos.CreateDraftRequest;
import com.afrotransact.catalog.adapter.in.web.DraftDtos.DraftResponse;
import com.afrotransact.catalog.adapter.in.web.DraftDtos.DraftSummary;
import com.afrotransact.catalog.adapter.in.web.DraftDtos.SaveDraftRequest;
import com.afrotransact.catalog.application.PageView;
import com.afrotransact.catalog.application.ProductDraftService;
import com.afrotransact.catalog.domain.DraftListItem;
import com.afrotransact.catalog.domain.DraftSortField;
import com.afrotransact.catalog.domain.SavedDraft;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.RequestParam;
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

    @Operation(operationId = "listDrafts", summary = "The organisation's drafts, most recently saved first",
            description = "Server-driven: q searches title and category path, kind=new leaves out the unsaved "
            + "edits of existing products, sort is updatedAt|title|categoryPath[,asc|desc] (default updatedAt,desc), "
            + "page is 0-based, size is at most 100. The body stays a plain array; X-Total-Count is the number of "
            + "drafts matching q and kind.")
    @GetMapping
    ResponseEntity<List<DraftSummary>> list(
            @Parameter(description = "Search over title and category path") @RequestParam(required = false) String q,
            @Parameter(description = "new = drafts of products not created yet; all (default) = every draft")
            @RequestParam(defaultValue = "all") String kind,
            @Parameter(description = "updatedAt | title | categoryPath, optionally ,asc or ,desc")
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        String[] parts = sort == null ? new String[0] : sort.split(",", 2);
        boolean descending = parts.length < 2 ? parts.length == 0 || DraftSortField.parse(parts[0]) == DraftSortField.UPDATED_AT
                : !"asc".equalsIgnoreCase(parts[1].strip());
        PageView<DraftListItem> result = drafts.search(q, "new".equalsIgnoreCase(kind),
                DraftSortField.parse(parts.length == 0 ? null : parts[0]), descending, page, size);
        return ResponseEntity.ok().header("X-Total-Count", Long.toString(result.total()))
                .body(result.items().stream().map(DraftSummary::of).toList());
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

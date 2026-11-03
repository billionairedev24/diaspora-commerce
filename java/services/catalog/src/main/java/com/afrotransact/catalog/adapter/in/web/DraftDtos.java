package com.afrotransact.catalog.adapter.in.web;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.catalog.domain.SavedDraft;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Request and response bodies for /v1/product-drafts. */
final class DraftDtos {

    private DraftDtos() {
    }

    @Schema(name = "CreateDraftRequest", description = "A new draft; with productId it is the unsaved edit of that "
            + "product (one per product: saving another replaces it)")
    record CreateDraftRequest(UUID productId, @Size(max = 200) String title, @Size(max = 400) String categoryPath,
                              @NotNull @Schema(description = "The form's product input as entered (need not be valid)")
                              JsonNode payload) {
    }

    @Schema(name = "SaveDraftRequest", description = "Autosave: replaces the payload when version still matches")
    record SaveDraftRequest(@PositiveOrZero long version, @Size(max = 200) String title,
                            @Size(max = 400) String categoryPath, @NotNull JsonNode payload) {
    }

    @Schema(name = "ProductDraftSummary")
    record DraftSummary(UUID id, UUID productId, String title, String categoryPath, Instant updatedAt, long version) {

        static DraftSummary of(SavedDraft d) {
            return new DraftSummary(d.id(), d.productId(), d.title(), d.categoryPath(), d.updatedAt(), d.version());
        }
    }

    @Schema(name = "ProductDraft")
    record DraftResponse(UUID id, UUID productId, String title, String categoryPath, JsonNode payload,
                         Instant createdAt, Instant updatedAt, long version) {

        static DraftResponse of(SavedDraft d, JsonMapper json) {
            return new DraftResponse(d.id(), d.productId(), d.title(), d.categoryPath(), json.readTree(d.payload()),
                    d.createdAt(), d.updatedAt(), d.version());
        }
    }
}

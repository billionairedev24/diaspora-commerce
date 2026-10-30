package com.afrotransact.catalog.adapter.in.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.application.BrandAdminService.BrandPage;
import com.afrotransact.catalog.application.BrandAdminService.BrandView;
import com.afrotransact.catalog.application.BrandAdminService.BulkOutcome;
import com.afrotransact.catalog.application.BrandAdminService.BulkResult;
import com.afrotransact.catalog.application.BrandAdminService.ScopeNode;
import com.afrotransact.catalog.domain.brand.Brand;
import com.afrotransact.catalog.domain.brand.BrandStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the staff brand registry API. */
final class BrandAdminDtos {

    private BrandAdminDtos() {
    }

    @Schema(name = "BrandScopeNode")
    record ScopeResponse(UUID nodeId, String path, @Schema(allowableValues = {"DEPARTMENT", "CATEGORY", "PRODUCT_TYPE"})
                         String level) {

        static ScopeResponse of(ScopeNode n) {
            return new ScopeResponse(n.nodeId(), n.path(), n.level());
        }
    }

    @Schema(name = "BrandAdmin")
    record BrandResponse(UUID id, String name, BrandStatus status, UUID mergedIntoId, String mergedIntoName,
                         String createdByOrg, Instant createdAt, Instant updatedAt, long version, String decidedBy,
                         Instant decidedAt, String decisionReason, long products, long sellers,
                         List<ScopeResponse> scope) {

        static BrandResponse of(BrandView v) {
            Brand b = v.usage().brand();
            return new BrandResponse(b.id(), b.name(), b.status(), b.mergedIntoId(), v.mergedIntoName(),
                    b.createdByOrg(), b.createdAt(), b.updatedAt(), b.version(), b.decidedBy(), b.decidedAt(),
                    b.decisionReason(), v.usage().products(), v.usage().sellers(),
                    v.scope().stream().map(ScopeResponse::of).toList());
        }
    }

    @Schema(name = "BrandAdminList")
    record BrandListResponse(List<BrandResponse> brands, long total, Map<String, Long> counts) {

        static BrandListResponse of(BrandPage p) {
            Map<String, Long> counts = new LinkedHashMap<>();
            p.counts().forEach((k, v) -> counts.put(k.name(), v));
            return new BrandListResponse(p.brands().stream().map(BrandResponse::of).toList(), p.total(), counts);
        }
    }

    @Schema(name = "CreateBrandAdmin")
    record CreateBody(@NotBlank @Size(max = 120) String name, @NotEmpty @Size(max = 50) List<UUID> scope,
                      @NotBlank @Size(max = 1000) String reason) {
    }

    @Schema(name = "CreateBrandsBulk", description = "One brand per entry (the console splits a pasted list on new "
            + "lines and commas); duplicates and invalid names are reported per entry")
    record BulkBody(@NotEmpty @Size(max = 500) List<@Size(max = 200) String> names,
                    @NotEmpty @Size(max = 50) List<UUID> scope, @NotBlank @Size(max = 1000) String reason) {
    }

    @Schema(name = "UpdateBrandAdmin", description = "Only the fields sent change; at least one must differ from the "
            + "stored value")
    record UpdateBody(@Size(max = 120) String name, @Size(max = 50) List<UUID> scope,
                      @NotBlank @Size(max = 1000) String reason) {
    }

    @Schema(name = "MergeBrand")
    record MergeBody(UUID targetId, @NotBlank @Size(max = 1000) String reason) {
    }

    @Schema(name = "BulkBrandOutcome")
    record OutcomeResponse(String name, @Schema(allowableValues = {"CREATED", "EXISTS", "INVALID"}) String outcome,
                           UUID brandId, String message) {

        static OutcomeResponse of(BulkOutcome o) {
            return new OutcomeResponse(o.name(), o.outcome(), o.brandId(), o.message());
        }
    }

    @Schema(name = "BulkBrandResult")
    record BulkResponse(List<OutcomeResponse> results, int created, int existing, int invalid) {

        static BulkResponse of(BulkResult r) {
            return new BulkResponse(r.results().stream().map(OutcomeResponse::of).toList(), r.created(), r.existing(),
                    r.invalid());
        }
    }
}

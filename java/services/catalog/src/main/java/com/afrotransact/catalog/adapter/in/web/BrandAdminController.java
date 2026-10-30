package com.afrotransact.catalog.adapter.in.web;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.adapter.in.web.BrandAdminDtos.BrandListResponse;
import com.afrotransact.catalog.adapter.in.web.BrandAdminDtos.BrandResponse;
import com.afrotransact.catalog.adapter.in.web.BrandAdminDtos.BulkBody;
import com.afrotransact.catalog.adapter.in.web.BrandAdminDtos.BulkResponse;
import com.afrotransact.catalog.adapter.in.web.BrandAdminDtos.CreateBody;
import com.afrotransact.catalog.adapter.in.web.BrandAdminDtos.MergeBody;
import com.afrotransact.catalog.adapter.in.web.BrandAdminDtos.UpdateBody;
import com.afrotransact.catalog.adapter.in.web.TaxonomyDtos.ReasonBody;
import com.afrotransact.catalog.application.BrandAdminService;
import com.afrotransact.catalog.application.BrandAdminService.BrandFilter;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.brand.BrandStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Brands (back office)", description = "Staff review the brands sellers typed in and keep the brand "
        + "registry: approve, reject, merge, edit name and scope, add and retire (catalog:taxonomy)")
@RestController
class BrandAdminController {

    private static final String IF_MATCH_NOTE = "If-Match: the version read with the brand (412 "
            + "catalog.version-mismatch when it changed since, 428 when missing). Every change carries a reason and is "
            + "audited with the taxonomy changes.";

    private final BrandAdminService admin;

    BrandAdminController(BrandAdminService admin) {
        this.admin = admin;
    }

    @Operation(operationId = "listBrandRegistry", summary = "Brands by status, name and scope with their usage",
            description = "status is a comma-separated list (PENDING is the review queue); q ignores case, accents and "
                    + "punctuation; scope narrows to brands applying to a taxonomy node. counts holds brands per status.")
    @GetMapping("/v1/taxonomy/brands")
    BrandListResponse list(@RequestParam(required = false) String status, @RequestParam(required = false) String q,
                           @RequestParam(required = false) UUID scope,
                           @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
        return BrandListResponse.of(admin.list(new BrandFilter(statuses(status), q, scope, limit, offset)));
    }

    @Operation(operationId = "getBrandRegistryEntry", summary = "One brand with usage and scope")
    @GetMapping("/v1/taxonomy/brands/{id}")
    BrandResponse get(@PathVariable UUID id) {
        return BrandResponse.of(admin.get(id));
    }

    @Operation(operationId = "createRegistryBrand", summary = "Add an approved brand",
            description = "409 catalog.brand-exists when the name exists in an overlapping scope.")
    @PostMapping("/v1/taxonomy/brands")
    @ResponseStatus(HttpStatus.CREATED)
    BrandResponse create(@Valid @RequestBody CreateBody body) {
        return BrandResponse.of(admin.create(body.name(), body.scope(), body.reason()));
    }

    @Operation(operationId = "createRegistryBrands", summary = "Add many approved brands at once",
            description = "Entries that exist or are invalid are reported per entry; the rest are created.")
    @PostMapping("/v1/taxonomy/brands/bulk")
    BulkResponse createMany(@Valid @RequestBody BulkBody body) {
        return BulkResponse.of(admin.createMany(body.names(), body.scope(), body.reason()));
    }

    @Operation(operationId = "approveRegistryBrand", summary = "Approve a pending brand", description = IF_MATCH_NOTE)
    @PostMapping("/v1/taxonomy/brands/{id}/approve")
    BrandResponse approve(@PathVariable UUID id, @Valid @RequestBody ReasonBody body,
                          @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return BrandResponse.of(admin.approve(id, IfMatch.expected(ifMatch), body.reason()));
    }

    @Operation(operationId = "rejectRegistryBrand", summary = "Reject a pending brand", description = IF_MATCH_NOTE)
    @PostMapping("/v1/taxonomy/brands/{id}/reject")
    BrandResponse reject(@PathVariable UUID id, @Valid @RequestBody ReasonBody body,
                         @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return BrandResponse.of(admin.reject(id, IfMatch.expected(ifMatch), body.reason()));
    }

    @Operation(operationId = "retireRegistryBrand", summary = "Retire an approved brand", description = IF_MATCH_NOTE)
    @PostMapping("/v1/taxonomy/brands/{id}/retire")
    BrandResponse retire(@PathVariable UUID id, @Valid @RequestBody ReasonBody body,
                         @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return BrandResponse.of(admin.retire(id, IfMatch.expected(ifMatch), body.reason()));
    }

    @Operation(operationId = "mergeRegistryBrand", summary = "Merge a brand into an existing approved brand",
            description = "Listings that hold the brand are re-pointed and show the target's name; the target's scope "
                    + "grows by the scope of the merged brand. " + IF_MATCH_NOTE)
    @PostMapping("/v1/taxonomy/brands/{id}/merge")
    BrandResponse merge(@PathVariable UUID id, @Valid @RequestBody MergeBody body,
                        @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return BrandResponse.of(admin.merge(id, IfMatch.expected(ifMatch), body.targetId(), body.reason()));
    }

    @Operation(operationId = "updateRegistryBrand", summary = "Edit a brand's name and scope",
            description = "A rename follows to the listings. 422 Nothing to change when nothing differs. "
                    + IF_MATCH_NOTE)
    @PatchMapping("/v1/taxonomy/brands/{id}")
    BrandResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateBody body,
                         @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return BrandResponse.of(admin.update(id, IfMatch.expected(ifMatch), body.name(), body.scope(),
                body.reason()));
    }

    private static Set<BrandStatus> statuses(String csv) {
        if (csv == null || csv.isBlank()) {
            return EnumSet.noneOf(BrandStatus.class);
        }
        Set<BrandStatus> out = EnumSet.noneOf(BrandStatus.class);
        for (String s : Arrays.asList(csv.split(","))) {
            try {
                out.add(BrandStatus.valueOf(s.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new CatalogRuleViolation("Unknown brand status " + s.trim(), Map.of("status", "Unknown status"));
            }
        }
        return out;
    }
}

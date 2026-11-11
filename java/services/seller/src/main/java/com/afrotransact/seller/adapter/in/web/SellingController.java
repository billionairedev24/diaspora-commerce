package com.afrotransact.seller.adapter.in.web;

import java.time.LocalDate;
import java.util.List;

import com.afrotransact.seller.application.SellingApplicationService;
import com.afrotransact.seller.application.SellingApplicationService.ApplyCommand;
import com.afrotransact.seller.application.SellingApplicationService.DocumentInput;
import com.afrotransact.seller.application.SellingApplicationService.ResubmitCommand;
import com.afrotransact.seller.application.SellingStatusService;
import com.afrotransact.seller.application.SellingViews.Capabilities;
import com.afrotransact.seller.application.SellingViews.MyApplication;
import com.afrotransact.seller.application.SellingViews.SellingApprovals;
import com.afrotransact.seller.domain.SellingFacts;
import com.afrotransact.seller.domain.SellingStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Selling approvals", description = "Amazon-style approvals to sell: SELL (live after validation), APPLY "
        + "(apply once, documents reviewed by staff) or NOT_AVAILABLE. docs/engineering/listing-rules.md section 2.")
@RestController
class SellingController {

    private final SellingStatusService status;
    private final SellingApplicationService applications;

    SellingController(SellingStatusService status, SellingApplicationService applications) {
        this.status = status;
        this.applications = applications;
    }

    @Operation(summary = "Can this organisation sell this product? SELL, APPLY or NOT_AVAILABLE, with the reason and what is missing",
            description = "Readable by the organisation's members, staff, and catalog-service (service scope "
                    + "sellers:catalog-scope.read). All parameters are optional while the product form is being "
                    + "filled; pendingFacts lists what the answer still depends on (jewelry: material). material "
                    + "gold, silver or a gemstone, or a watch, makes the jewelry rule apply. AfroTransact Retail "
                    + "always gets SELL.")
    @GetMapping("/v1/orgs/{orgId}/selling-status")
    SellingStatus sellingStatus(@PathVariable String orgId,
                                @RequestParam(required = false) String categoryCode,
                                @RequestParam(required = false) String brand,
                                @RequestParam(required = false) String productTypeCode,
                                @RequestParam(required = false) String material,
                                @Parameter(description = "Barcode, for a single product rule")
                                @RequestParam(required = false) String gtin,
                                @RequestParam(defaultValue = "false") boolean watch) {
        return status.status(orgId, new SellingFacts(categoryCode, brand, productTypeCode, material, gtin, watch));
    }

    @Operation(summary = "Selling approvals page: every scope that needs approval, with this organisation's state",
            description = "state is NOT_APPLIED, APPLIED, NEEDS_ATTENTION, APPROVED, EXPIRED or NOT_AVAILABLE; "
                    + "statusWords and message are plain words for the seller. notices lists expiry warnings "
                    + "(30, 14, 7 days), paused listings and applications that need an answer.")
    @GetMapping("/v1/orgs/{orgId}/selling-approvals")
    SellingApprovals approvals(@PathVariable String orgId) {
        return status.approvals(orgId);
    }

    @Operation(summary = "Seller capabilities (food service): the product form shows food service fields only when approved")
    @GetMapping("/v1/orgs/{orgId}/selling-capabilities")
    Capabilities capabilities(@PathVariable String orgId) {
        return status.capabilities(orgId);
    }

    @Operation(summary = "Apply to sell in a scope (owner, admin or catalog role)",
            description = "Upload each required file to media-service first and send its asset id with the dates "
                    + "read off it. 422 seller.selling-documents-invalid names the documents to fix (field errors "
                    + "documents.<KIND>): a purchase invoice must be dated within 180 days, a permit or insurance "
                    + "policy needs a future expiry date, insurance must name AfroTransact as additional insured. "
                    + "409 seller.selling-not-available when the seller was disqualified and the reapply date has "
                    + "not come. Repeating the call while an application is in progress returns it.")
    @PostMapping("/v1/orgs/{orgId}/selling-applications")
    @ResponseStatus(HttpStatus.CREATED)
    MyApplication apply(@PathVariable String orgId, @Valid @RequestBody ApplyRequest body) {
        return applications.apply(orgId, new ApplyCommand(body.scopeType(), body.scopeKey(), body.note(),
                body.documents() == null ? List.of() : body.documents().stream().map(DocumentBody::toInput).toList()));
    }

    @Operation(summary = "The organisation's selling applications, newest first")
    @GetMapping("/v1/orgs/{orgId}/selling-applications")
    List<MyApplication> list(@PathVariable String orgId) {
        return applications.list(orgId);
    }

    @Operation(summary = "One selling application")
    @GetMapping("/v1/orgs/{orgId}/selling-applications/{applicationId}")
    MyApplication one(@PathVariable String orgId, @PathVariable String applicationId) {
        return applications.get(orgId, applicationId);
    }

    @Operation(summary = "Answer a request for information with the documents asked for",
            description = "Only while the application is INFO_REQUESTED. The review starts again at step 1.")
    @PostMapping("/v1/orgs/{orgId}/selling-applications/{applicationId}/resubmit")
    MyApplication resubmit(@PathVariable String orgId, @PathVariable String applicationId,
                           @Valid @RequestBody ResubmitRequest body) {
        return applications.resubmit(orgId, applicationId, new ResubmitCommand(body.note(),
                body.documents() == null ? List.of() : body.documents().stream().map(DocumentBody::toInput).toList()));
    }

    @Schema(name = "SellingDocument")
    record DocumentBody(@NotBlank @Schema(allowableValues = {"PURCHASE_INVOICE", "PHOTOS_ALL_SIDES",
            "BRAND_AUTHORIZATION", "SAFETY_CERTIFICATE", "PERMIT_LICENCE", "LIABILITY_INSURANCE"}) String kind,
                        @NotBlank @Size(max = 80) String assetId, @Size(max = 200) String fileName,
                        @Schema(description = "The date on an invoice") LocalDate issuedOn,
                        @Schema(description = "Expiry of a permit, licence or policy") LocalDate expiresOn,
                        @Schema(description = "Insurance: the policy names AfroTransact as additional insured")
                        boolean namesAfroTransact, @Size(max = 500) String note) {

        DocumentInput toInput() {
            return new DocumentInput(kind, assetId, fileName, issuedOn, expiresOn, namesAfroTransact, note);
        }
    }

    @Schema(name = "ApplyToSell")
    record ApplyRequest(@NotBlank String scopeType, @NotBlank @Size(max = 200) String scopeKey,
                        @Size(max = 1000) String note, @Valid List<DocumentBody> documents) {
    }

    @Schema(name = "ResubmitSellingApplication")
    record ResubmitRequest(@Size(max = 1000) String note, @Valid List<DocumentBody> documents) {
    }
}

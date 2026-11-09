package com.afrotransact.catalog.adapter.in.web;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.application.ProductTaxService;
import com.afrotransact.catalog.application.ProductTaxService.EvidenceFile;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.tax.MissingTaxFact;
import com.afrotransact.catalog.domain.tax.TaxEvidence;
import com.afrotransact.catalog.domain.tax.TaxReadiness;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Products", description = "Product builder, listing moderation and lookup")
@RestController
class TaxController {

    private final ProductTaxService tax;

    TaxController(ProductTaxService tax) {
        this.tax = tax;
    }

    @Schema(name = "TaxReadiness", description = "Publish gate for a product: it can go live only when status is READY")
    record TaxReadinessResponse(UUID productId, String status, String categoryCode, String categoryStatus,
                                String categoryMessage, String ruleVersion, List<VariantReadinessResponse> variants) {

        static TaxReadinessResponse of(TaxReadiness r) {
            return new TaxReadinessResponse(r.productId(), r.status().name(), r.categoryCode(), r.categoryStatus(),
                    r.categoryMessage(), r.ruleVersion(),
                    r.variants().stream().map(v -> new VariantReadinessResponse(v.atin(), v.sellerSku(),
                            v.status().name(), v.missingTaxFacts(), v.reason())).toList());
        }
    }

    record VariantReadinessResponse(String atin, String sellerSku, String status, List<MissingTaxFact> missingTaxFacts,
                                    String reason) {
    }

    @Schema(name = "TaxEvidence")
    record TaxEvidenceResponse(UUID id, String atin, String factId, int version, String fileName, String contentType,
                               long bytes, String url, String uploadedBy, Instant uploadedAt) {

        static TaxEvidenceResponse of(TaxEvidence e) {
            return new TaxEvidenceResponse(e.id(), e.variantAtin(), e.factId(), e.version(), e.fileName(),
                    e.contentType(), e.bytes(), "/v1/tax-evidence/" + e.id() + "/file", e.uploadedBy(), e.uploadedAt());
        }
    }

    @Operation(summary = "Tax publish gate of a product, per variant (owner or staff)",
            description = "Asks tax-returns to resolve every variant with its facts and the seller's behaviour facts. "
                    + "A product goes live only when status is READY. Missing facts come with plain messages.")
    @GetMapping("/v1/products/{id}/tax-readiness")
    TaxReadinessResponse readiness(@PathVariable UUID id) {
        return TaxReadinessResponse.of(tax.readiness(id));
    }

    @Operation(summary = "Upload the proof for an exemption fact (label photo, Drug Facts, Supplement Facts)",
            description = "PDF, JPEG or PNG up to 10 MB. Re-uploading a fact adds a version; earlier files are kept for audit.")
    @PostMapping(value = "/v1/variants/{atin}/tax-evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    TaxEvidenceResponse upload(@PathVariable String atin, @RequestParam String factId,
                               @RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new CatalogRuleViolation("The file is empty", Map.of("file", "Choose a file"));
        }
        return TaxEvidenceResponse.of(tax.uploadEvidence(atin, factId, file.getOriginalFilename(), file.getContentType(),
                file.getBytes()));
    }

    @Operation(summary = "The evidence files of a variant (owner or staff)")
    @GetMapping("/v1/variants/{atin}/tax-evidence")
    List<TaxEvidenceResponse> evidence(@PathVariable String atin) {
        return tax.evidence(atin).stream().map(TaxEvidenceResponse::of).toList();
    }

    @Operation(summary = "The file of an evidence record (owner or staff)")
    @GetMapping("/v1/tax-evidence/{id}/file")
    ResponseEntity<byte[]> file(@PathVariable UUID id) {
        EvidenceFile f = tax.evidenceFile(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.meta().contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(f.meta().fileName()).build().toString())
                .body(f.content());
    }
}

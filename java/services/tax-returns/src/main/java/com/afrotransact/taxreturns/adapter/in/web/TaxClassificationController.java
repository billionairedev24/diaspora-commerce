package com.afrotransact.taxreturns.adapter.in.web;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import com.afrotransact.taxreturns.application.TaxClassificationService;
import com.afrotransact.taxreturns.domain.tax.ClassificationRule;
import com.afrotransact.taxreturns.domain.tax.TaxCategory;
import com.afrotransact.taxreturns.domain.tax.TaxClassification;
import com.afrotransact.taxreturns.domain.tax.TaxCode;
import com.afrotransact.taxreturns.port.TaxClassificationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** What an item is for tax: codes, rules, the review queue and tax by code (staff, tax:read / tax:configure). */
@RestController
@Tag(name = "Tax classification")
class TaxClassificationController {

    private final TaxClassificationService classification;

    TaxClassificationController(TaxClassificationService classification) {
        this.classification = classification;
    }

    @Schema(name = "TaxCode")
    record CodeDto(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 120) String label,
                   @NotBlank String category, @Size(max = 60) String providerCode, Boolean active) {

        static CodeDto of(TaxCode c) {
            return new CodeDto(c.code(), c.label(), c.category().name(), c.providerCode(), c.active());
        }

        TaxCode toDomain() {
            return new TaxCode(code, label, TaxCategory.parse(category), providerCode == null || providerCode.isBlank()
                    ? null : providerCode.trim(), active == null || active);
        }
    }

    @Schema(name = "TaxClassificationRule")
    record RuleDto(Long id, @NotBlank @Size(max = 80) String productType, @Size(max = 80) String attrKey,
                   @Size(max = 120) String attrValue, @NotBlank @Size(max = 40) String taxCode,
                   @Size(max = 300) String note) {

        static RuleDto of(ClassificationRule r) {
            return new RuleDto(r.id(), r.productType(), r.attrKey(), r.attrValue(), r.taxCode(), r.note());
        }

        ClassificationRule toDomain() {
            return new ClassificationRule(id, productType, blank(attrKey), blank(attrValue), taxCode, note);
        }

        private static String blank(String v) {
            return v == null || v.isBlank() ? null : v.trim();
        }
    }

    @Schema(name = "TaxClassificationTest")
    record TestRequest(@NotBlank String productType, Map<String, String> attributes) {
    }

    @Schema(name = "TaxClassificationResult")
    record ResultDto(String taxCode, String category, String providerCode, String basis, boolean needsReview) {

        static ResultDto of(TaxClassification c) {
            return new ResultDto(c.taxCode(), c.category().name(), c.providerCode(), c.basis().name(),
                    c.needsReview());
        }
    }

    @Schema(name = "TaxReviewItem")
    record SeenDto(String productType, Map<String, String> attributes, String attrHash, String taxCode, String basis,
                   String sampleAtin, long count, String firstSeen, String lastSeen, String reviewedAt,
                   String reviewedBy, String reviewNote) {

        static SeenDto of(TaxClassificationRepository.Seen s) {
            return new SeenDto(s.productType(), s.attributes(), s.attrHash(), s.taxCode(), s.basis(), s.sampleAtin(),
                    s.count(), s.firstSeen().toString(), s.lastSeen().toString(),
                    s.reviewedAt() == null ? null : s.reviewedAt().toString(), s.reviewedBy(), s.reviewNote());
        }
    }

    @Schema(name = "TaxReviewRequest")
    record ReviewRequest(@NotBlank String productType, @NotBlank String attrHash, @Size(max = 300) String note) {
    }

    @Schema(name = "TaxByCodeRow")
    record CodeRowDto(String taxCode, String jurisdiction, String currency, long taxableMinor, long taxMinor) {
    }

    @Operation(summary = "Tax codes (tax:read)")
    @GetMapping("/v1/tax/codes")
    List<CodeDto> codes() {
        return classification.codes().stream().map(CodeDto::of).toList();
    }

    @Operation(summary = "Create or change a tax code, e.g. set its provider code (tax:configure)")
    @PutMapping("/v1/tax/codes/{code}")
    CodeDto saveCode(@PathVariable String code, @Valid @RequestBody CodeDto body) {
        return CodeDto.of(classification.saveCode(new CodeDto(code, body.label(), body.category(),
                body.providerCode(), body.active()).toDomain()));
    }

    @Operation(summary = "Classification rules: product type (and a seller's answer) to tax code (tax:read)")
    @GetMapping("/v1/tax/classification-rules")
    List<RuleDto> rules() {
        return classification.rules().stream().map(RuleDto::of).toList();
    }

    @Operation(summary = "Create or change a rule; the same type and answer replaces the code (tax:configure)")
    @PutMapping("/v1/tax/classification-rules")
    RuleDto saveRule(@Valid @RequestBody RuleDto body) {
        return RuleDto.of(classification.saveRule(body.toDomain()));
    }

    @Operation(summary = "Delete a rule (tax:configure)")
    @DeleteMapping("/v1/tax/classification-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteRule(@PathVariable long id) {
        classification.deleteRule(id);
    }

    @Operation(summary = "What would this product type with these answers be classified as? Nothing is recorded (tax:read)")
    @PostMapping("/v1/tax/classification-test")
    ResultDto test(@Valid @RequestBody TestRequest body) {
        return ResultDto.of(classification.test(body.productType(), body.attributes()));
    }

    @Operation(summary = "Items classified by default (general taxable) that nobody has reviewed yet (tax:read)")
    @GetMapping("/v1/tax/review-queue")
    List<SeenDto> reviewQueue(@RequestParam(defaultValue = "false") boolean includeReviewed) {
        return classification.reviewQueue(includeReviewed).stream().map(SeenDto::of).toList();
    }

    @Operation(summary = "Mark a queue item reviewed, after adding a rule or deciding the default is right (tax:configure)")
    @PostMapping("/v1/tax/review-queue/reviewed")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reviewed(@Valid @RequestBody ReviewRequest body) {
        classification.markReviewed(body.productType(), body.attrHash(), body.note());
    }

    @Operation(summary = "Tax collected per tax code and jurisdiction for a month, sales minus refunds (tax:read)")
    @GetMapping("/v1/tax/liability/by-code")
    List<CodeRowDto> byCode(@RequestParam String month) {
        YearMonth m;
        try {
            m = YearMonth.parse(month);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("month must be YYYY-MM");
        }
        return classification.liabilityByCode(m).stream().map(r -> new CodeRowDto(r.taxCode(), r.jurisdiction(),
                r.currency(), r.taxableMinor(), r.taxMinor())).toList();
    }
}

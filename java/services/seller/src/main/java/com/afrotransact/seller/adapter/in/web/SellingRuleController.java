package com.afrotransact.seller.adapter.in.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.afrotransact.seller.application.SellingApprovalService;
import com.afrotransact.seller.application.SellingRuleAdminService;
import com.afrotransact.seller.application.SellingRuleAdminService.Doc;
import com.afrotransact.seller.application.SellingRuleAdminService.RuleInput;
import com.afrotransact.seller.application.SellingViews.ApprovalView;
import com.afrotransact.seller.application.SellingViews.RuleView;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellingApprovalStatus;
import com.afrotransact.seller.domain.SellingScopeType;
import com.afrotransact.seller.port.Page;
import com.afrotransact.seller.port.SellingRuleRepository.RuleAudit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Selling rules", description = "Staff: scope rules, gated brands and the documents each asks for "
        + "(selling-rules:manage to change; every change needs a reason and is audited), and the approvals sellers hold.")
@RestController
class SellingRuleController {

    private final SellingRuleAdminService rules;
    private final SellingApprovalService approvals;

    SellingRuleController(SellingRuleAdminService rules, SellingApprovalService approvals) {
        this.rules = rules;
        this.approvals = approvals;
    }

    @Operation(summary = "Selling rules (scope rules, gated brands, capabilities)",
            description = "scopeType: CATEGORY, PRODUCT_TYPE, BRAND, BRAND_IN_CATEGORY, PRODUCT or CAPABILITY. level: "
                    + "SELL, APPLY or NOT_AVAILABLE. text matches the label or key.")
    @GetMapping("/v1/selling-rules")
    RulePage list(@RequestParam(required = false) String scopeType, @RequestParam(required = false) String level,
                  @RequestParam(required = false) String text, @RequestParam(defaultValue = "false") boolean activeOnly,
                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        Page<RuleView> p = rules.search(scopeType, level, text, activeOnly, page, size);
        return new RulePage(p.items(), p.page(), p.size(), p.total());
    }

    @Operation(summary = "One rule")
    @GetMapping("/v1/selling-rules/{id}")
    RuleView one(@PathVariable UUID id) {
        return rules.get(id);
    }

    @Operation(summary = "The rule's audit trail, newest first: who changed it, why, what it was and what it became")
    @GetMapping("/v1/selling-rules/{id}/audit")
    List<RuleAudit> audit(@PathVariable UUID id) {
        return rules.audit(id);
    }

    @Operation(summary = "Add a rule, typically a gated brand (selling-rules:manage, reason required)")
    @PostMapping("/v1/selling-rules")
    @ResponseStatus(HttpStatus.CREATED)
    RuleView create(@Valid @RequestBody RuleBody body) {
        return rules.create(body.toInput(), body.reason(), body.actorName());
    }

    @Operation(summary = "Edit a rule (selling-rules:manage, reason required; the scope never changes)",
            description = "A change publishes seller.selling.rule.changed so catalog re-checks the scope's listings.")
    @PutMapping("/v1/selling-rules/{id}")
    RuleView update(@PathVariable UUID id, @Valid @RequestBody RuleBody body) {
        return rules.update(id, body.toInput(), body.reason(), body.actorName());
    }

    @Operation(summary = "Staff: approvals sellers hold (applications:review, :approve or :assign)")
    @GetMapping("/v1/selling-approvals")
    ApprovalPage approvals(@RequestParam(required = false) String orgId, @RequestParam(required = false) String status,
                           @RequestParam(required = false) String scopeType,
                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        Page<ApprovalView> p = approvals.search(orgId == null || orgId.isBlank() ? null : orgId.trim(),
                status == null || status.isBlank() ? null : parse(SellingApprovalStatus.class, status),
                scopeType == null || scopeType.isBlank() ? null : parse(SellingScopeType.class, scopeType), page, size);
        return new ApprovalPage(p.items(), p.page(), p.size(), p.total());
    }

    @Operation(summary = "Staff: one approval")
    @GetMapping("/v1/selling-approvals/{id}")
    ApprovalView approval(@PathVariable UUID id) {
        return approvals.get(id);
    }

    @Operation(summary = "Pause the scope's listings (performance problem or a new rule); the approval is kept (applications:approve)")
    @PostMapping("/v1/selling-approvals/{id}/pause")
    ApprovalView pause(@PathVariable UUID id, @Valid @RequestBody ApprovalAction body) {
        return approvals.pause(id, body.reason(), body.actorName());
    }

    @Operation(summary = "Lift a staff pause: listings come back (applications:approve)")
    @PostMapping("/v1/selling-approvals/{id}/restore")
    ApprovalView restore(@PathVariable UUID id, @Valid @RequestBody ApprovalAction body) {
        return approvals.restore(id, body.reason(), body.actorName());
    }

    @Operation(summary = "Withdraw the approval: \"cannot apply\", not appealable, reapplyAfter required (applications:approve)")
    @PostMapping("/v1/selling-approvals/{id}/revoke")
    ApprovalView revoke(@PathVariable UUID id, @Valid @RequestBody ApprovalAction body) {
        return approvals.revoke(id, body.reason(), body.reapplyAfter(), body.actorName());
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String s) {
        try {
            return Enum.valueOf(type, s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SellerRuleViolation("seller.invalid-input", "Unknown value " + s);
        }
    }

    @Schema(name = "SellingRuleInput")
    record RuleBody(@NotBlank String scopeType, @NotBlank @Size(max = 200) String key, String categoryCode,
                    @Size(max = 200) String label, @NotBlank String level, List<DocBody> documents,
                    @Size(max = 500) @Schema(description = "NOT_AVAILABLE: the reason sellers read") String sellerReason,
                    @Size(max = 500) String note, String capability, List<String> materials, boolean watch,
                    Integer reapplyAfterDays, Boolean active, Long version,
                    @NotBlank @Size(min = 10, max = 500) @Schema(description = "Why this change (audited)") String reason,
                    @Size(max = 200) String actorName) {

        RuleInput toInput() {
            return new RuleInput(scopeType, key, categoryCode, label, level,
                    documents == null ? List.of() : documents.stream().map(d -> new Doc(d.kind(), d.optional()))
                            .toList(), sellerReason, note, capability, materials, watch, reapplyAfterDays, active,
                    version);
        }
    }

    @Schema(name = "SellingRuleDocument")
    record DocBody(@NotBlank String kind, boolean optional) {
    }

    @Schema(name = "SellingApprovalAction")
    record ApprovalAction(@NotBlank @Size(min = 10, max = 500) String reason, LocalDate reapplyAfter,
                          @Size(max = 200) String actorName) {
    }

    @Schema(name = "SellingRulePage")
    record RulePage(List<RuleView> items, int page, int size, long total) {
    }

    @Schema(name = "SellingApprovalPage")
    record ApprovalPage(List<ApprovalView> items, int page, int size, long total) {
    }
}

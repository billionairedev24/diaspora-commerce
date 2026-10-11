package com.afrotransact.content.adapter.in.web;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.content.application.CookieConsentService;
import com.afrotransact.content.application.CookieConsentService.CookiePolicy;
import com.afrotransact.content.domain.legal.ConsentRecord;
import com.afrotransact.content.domain.legal.CookieCategory;
import com.afrotransact.content.domain.legal.CookieDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Cookie consent", description = "Consent banner configuration, cookie table and auditable consent records (GPC aware)")
@RestController
@RequestMapping("/v1/consent")
class ConsentController {

    private final CookieConsentService consent;

    ConsentController(CookieConsentService consent) {
        this.consent = consent;
    }

    @Schema(name = "CookiePolicy")
    record CookiePolicyResponse(String noticeVersion, LocalDate effectiveDate, String noticeHref,
                                List<CookieCategory> categories, List<CookieDefinition> cookies) {
    }

    @Schema(name = "ConsentRequest")
    record ConsentRequest(@Schema(description = "Anonymous device id or user id") String subject,
                          @Schema(description = "Choice per category key") Map<String, Boolean> categories,
                          @Schema(description = "Global Privacy Control (also read from the Sec-GPC header)") Boolean gpc,
                          @Schema(description = "Do not sell or share my personal information (implied by GPC); "
                                  + "turns GPC-locked categories off") Boolean saleOptOut,
                          @Schema(description = "banner, preferences, privacy-choices, app or gpc") String source) {
    }

    @Schema(name = "ConsentRecord")
    record ConsentResponse(UUID id, Map<String, Boolean> categories, boolean gpc, boolean saleOptOut,
                           String policyVersion, Instant recordedAt) {
    }

    @Schema(name = "CookieCategoryRequest")
    record CategoryRequest(@NotBlank String name, @NotBlank String description, Boolean required, Boolean gpcOptOut,
                           Integer order) {
    }

    @Schema(name = "CookieRequest")
    record CookieRequest(@NotBlank String provider, @NotBlank String purpose, @NotBlank String duration,
                         @NotBlank String category) {
    }

    @Operation(summary = "Categories and cookie table for the consent banner")
    @SecurityRequirements
    @GetMapping("/cookie-policy")
    ResponseEntity<CookiePolicyResponse> policy() {
        CookiePolicy p = consent.policy();
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .eTag(p.etag())
                .body(new CookiePolicyResponse(p.noticeVersion(), p.effectiveDate(), p.noticeHref(), p.categories(),
                        p.cookies()));
    }

    @Operation(summary = "Store a consent decision (required categories forced on; GPC or a sale/sharing opt-out forces advertising off)")
    @SecurityRequirements
    @PostMapping("/records")
    @ResponseStatus(HttpStatus.CREATED)
    ConsentResponse record(@RequestBody ConsentRequest body,
                           @RequestHeader(name = "Sec-GPC", required = false) String secGpc) {
        boolean gpc = Boolean.TRUE.equals(body.gpc()) || "1".equals(secGpc);
        ConsentRecord r = consent.record(body.subject(), body.categories(), gpc, Boolean.TRUE.equals(body.saleOptOut()),
                body.source());
        return new ConsentResponse(r.id(), r.categories(), r.gpc(), r.saleOptOut(), r.policyVersion(), r.recordedAt());
    }

    @Operation(summary = "Create or change a cookie category (content:legal.edit)")
    @PutMapping("/categories/{key}")
    CookieCategory saveCategory(@PathVariable String key, @Valid @RequestBody CategoryRequest body) {
        return consent.saveCategory(new CookieCategory(key, body.name(), body.description(), Boolean.TRUE.equals(body.required()),
                Boolean.TRUE.equals(body.gpcOptOut()), body.order() == null ? 0 : body.order()));
    }

    @Operation(summary = "Create or change a cookie in the table (content:legal.edit)")
    @PutMapping("/cookies/{name}")
    CookieDefinition saveCookie(@PathVariable String name, @Valid @RequestBody CookieRequest body) {
        return consent.saveCookie(new CookieDefinition(name, body.provider(), body.purpose(), body.duration(),
                body.category()));
    }

    @Operation(summary = "Remove a cookie from the table (content:legal.edit)")
    @DeleteMapping("/cookies/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteCookie(@PathVariable String name) {
        consent.deleteCookie(name);
    }
}

package com.afrotransact.catalog.adapter.in.web;

import java.time.Duration;
import java.util.Map;

import com.afrotransact.catalog.adapter.in.web.PictureRulesDtos.OverridesDto;
import com.afrotransact.catalog.adapter.in.web.PictureRulesDtos.RulesDto;
import com.afrotransact.catalog.adapter.in.web.PictureRulesDtos.UpdateRequest;
import com.afrotransact.catalog.adapter.in.web.PictureRulesDtos.ViewDto;
import com.afrotransact.catalog.application.PictureRulesService;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.pictures.PictureProfile;
import com.afrotransact.catalog.domain.pictures.PictureRules;
import com.afrotransact.platform.security.StaffOrServiceOnly;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Picture rules", description = "The ONE picture-rules setting: a named profile (strict, startup, testing) plus "
        + "per-knob overrides, staff-controlled and audited")
@RestController
class PictureRulesController {

    private final PictureRulesService rules;

    PictureRulesController(PictureRulesService rules) {
        this.rules = rules;
    }

    @Operation(operationId = "getPublicPictureRules", summary = "The picture rules in force (public)",
            description = "Read by media, search, the indexer and the gateway (Go) with a short cache, and by Seller "
                    + "Center to word its messages. Nothing here is secret: it is the rule sellers are held to.")
    @GetMapping("/v1/public/picture-rules")
    ResponseEntity<RulesDto> current() {
        PictureRules r = rules.current();
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(10)).cachePublic())
                .eTag("\"" + r.version() + "\"").body(RulesDto.of(r));
    }

    @Operation(operationId = "getPictureRulesSetting",
            summary = "The picture rules, who changed them last and the audit trail (catalog:moderate or catalog:picture-rules.manage)")
    @StaffOrServiceOnly
    @GetMapping("/v1/catalog/picture-rules")
    ResponseEntity<ViewDto> view() {
        ViewDto v = ViewDto.of(rules.view());
        return ResponseEntity.ok().eTag("\"" + v.rules().version() + "\"").body(v);
    }

    @Operation(operationId = "updatePictureRulesSetting",
            summary = "Change the profile and overrides (catalog:picture-rules.manage; reason and the version loaded as If-Match)",
            description = "409 catalog.concurrent-update when the version is stale; 422 for an invalid value or a missing reason. "
                    + "An empty overrides object returns every knob to the profile's value.")
    @StaffOrServiceOnly
    @PutMapping("/v1/catalog/picture-rules")
    ResponseEntity<ViewDto> update(@RequestHeader(value = "If-Match", required = false) String ifMatch,
                                   @Valid @RequestBody UpdateRequest body) {
        Long expected = IfMatch.expected(ifMatch);
        if (expected == null) {
            throw new CatalogRuleViolation("Load the picture rules first", Map.of("If-Match", "Required"));
        }
        PictureProfile profile = PictureProfile.parse(body.profile()).orElseThrow(
                () -> new CatalogRuleViolation("Unknown profile", Map.of("profile", "Use strict, startup or testing")));
        OverridesDto overrides = body.overrides() == null ? new OverridesDto(null, null, null, null, null, null, null,
                null, null, null) : body.overrides();
        rules.update(profile, overrides.toDomain(), body.reason(), expected);
        ViewDto v = ViewDto.of(rules.view());
        return ResponseEntity.ok().eTag("\"" + v.rules().version() + "\"").body(v);
    }
}

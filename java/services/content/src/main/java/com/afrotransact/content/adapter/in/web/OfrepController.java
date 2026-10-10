package com.afrotransact.content.adapter.in.web;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.afrotransact.content.application.FlagEvaluationService;
import com.afrotransact.content.application.FlagEvaluationService.FlagResults;
import com.afrotransact.content.domain.flag.FlagEvaluation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OpenFeature Remote Evaluation Protocol (OFREP v1), so any OpenFeature SDK with the OFREP provider (web,
 * React Native, Go, Java) evaluates flags here. Context keys: {@code targetingKey}, {@code market}, {@code roles}.
 */
@Tag(name = "OpenFeature (OFREP)", description = "OpenFeature Remote Evaluation Protocol v1")
@SecurityRequirements
@RestController
@RequestMapping("/ofrep/v1/evaluate/flags")
class OfrepController {

    private final FlagEvaluationService evaluation;

    OfrepController(FlagEvaluationService evaluation) {
        this.evaluation = evaluation;
    }

    @Schema(name = "OfrepEvaluationRequest")
    record OfrepRequest(@Schema(description = "Evaluation context: targetingKey, market, roles") Map<String, Object> context) {
    }

    @Schema(name = "OfrepEvaluationSuccess")
    record OfrepSuccess(String key, Object value, String reason, String variant, Map<String, Object> metadata) {

        static OfrepSuccess of(FlagEvaluation e) {
            return new OfrepSuccess(e.key(), e.value(), e.reason().name(), e.variant(), Map.of("version", e.version()));
        }
    }

    @Schema(name = "OfrepEvaluationFailure")
    record OfrepFailure(String key, String errorCode, String errorDetails) {
    }

    @Schema(name = "OfrepBulkResponse")
    record OfrepBulk(List<OfrepSuccess> flags, Map<String, Object> metadata) {
    }

    @Operation(summary = "Evaluate one flag (OFREP)")
    @PostMapping("/{key}")
    ResponseEntity<?> evaluate(@PathVariable String key, @RequestBody(required = false) OfrepRequest body) {
        Ctx ctx = Ctx.of(body);
        return evaluation.evaluate(key, ctx.targetingKey(), ctx.market(), ctx.roles())
                .<ResponseEntity<?>>map(e -> ResponseEntity.ok(OfrepSuccess.of(e)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new OfrepFailure(key, "FLAG_NOT_FOUND", "Flag " + key + " does not exist")));
    }

    @Operation(summary = "Evaluate every flag (OFREP bulk; ETag / If-None-Match → 304)")
    @PostMapping
    ResponseEntity<OfrepBulk> evaluateAll(@RequestBody(required = false) OfrepRequest body,
                                          @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        Ctx ctx = Ctx.of(body);
        FlagResults r = evaluation.evaluateAll(ctx.targetingKey(), ctx.market(), ctx.roles(), List.of());
        String etag = "\"" + r.etag() + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag)
                .body(new OfrepBulk(r.evaluations().stream().map(OfrepSuccess::of).toList(), Map.of()));
    }

    private record Ctx(String targetingKey, String market, Set<String> roles) {

        static Ctx of(OfrepRequest body) {
            Map<String, Object> c = body == null || body.context() == null ? Map.of() : body.context();
            Object roles = c.get("roles");
            Set<String> parsed;
            if (roles instanceof Collection<?> list) {
                parsed = list.stream().map(String::valueOf).collect(Collectors.toSet());
            } else if (roles instanceof String s && !s.isBlank()) {
                parsed = Set.of(s.split(","));
            } else {
                parsed = Set.of();
            }
            return new Ctx(str(c.get("targetingKey")), str(c.get("market")), parsed);
        }

        private static String str(Object o) {
            return o == null ? null : String.valueOf(o);
        }
    }
}

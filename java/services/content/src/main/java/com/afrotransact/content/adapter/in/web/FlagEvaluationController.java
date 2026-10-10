package com.afrotransact.content.adapter.in.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.content.adapter.in.web.FlagDtos.EvaluateRequest;
import com.afrotransact.content.adapter.in.web.FlagDtos.EvaluateResponse;
import com.afrotransact.content.application.FlagEvaluationService;
import com.afrotransact.content.application.FlagEvaluationService.FlagResults;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Feature flag evaluation", description = "Server-side evaluation with stable bucketing")
@SecurityRequirements
@RestController
@RequestMapping("/v1/flags/evaluate")
class FlagEvaluationController {

    private final FlagEvaluationService evaluation;

    FlagEvaluationController(FlagEvaluationService evaluation) {
        this.evaluation = evaluation;
    }

    @Operation(summary = "Evaluate flags for a subject: map of flag → on/off")
    @PostMapping
    ResponseEntity<EvaluateResponse> evaluate(@RequestBody EvaluateRequest body) {
        String market = body.context() == null ? null : body.context().market();
        List<String> roles = body.context() == null || body.context().roles() == null ? List.of() : body.context().roles();
        FlagResults r = evaluation.evaluateAll(body.subject(), market, Set.copyOf(roles),
                body.flags() == null ? List.of() : body.flags());
        Map<String, Boolean> flags = new LinkedHashMap<>();
        r.evaluations().forEach(e -> flags.put(e.key(), e.value()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(r.etag())
                .body(new EvaluateResponse(flags, r.etag()));
    }
}

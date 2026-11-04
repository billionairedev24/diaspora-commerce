package com.afrotransact.identity.adapter.in.web;

import java.util.List;
import java.util.Map;

import com.afrotransact.identity.adapter.in.web.SessionPolicyDtos.AudiencePolicy;
import com.afrotransact.identity.adapter.in.web.SessionPolicyDtos.Effective;
import com.afrotransact.identity.adapter.in.web.SessionPolicyDtos.Entry;
import com.afrotransact.identity.adapter.in.web.SessionPolicyDtos.Update;
import com.afrotransact.identity.application.SessionPolicyService;
import com.afrotransact.identity.application.StepUpService;
import com.afrotransact.identity.domain.SessionAudience;
import com.afrotransact.identity.domain.SessionPolicy;
import com.afrotransact.platform.web.error.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Session policy", description = "When each audience (customer-web, customer-app, seller, staff, driver) is "
        + "signed out for inactivity and for session length. Staff manage it with identity:admin; the gateway-bff "
        + "reads it with a service token holding sessions:policy.read.")
@RestController
class SessionPolicyController {

    private static final String STEP_UP = "X-Step-Up";
    private static final String STEP_UP_HELP = "Step-up proof from POST /v1/admin/identity/step-up; not needed within "
            + "five minutes of signing in";

    private final SessionPolicyService policies;
    private final StepUpService stepUp;

    SessionPolicyController(SessionPolicyService policies, StepUpService stepUp) {
        this.policies = policies;
        this.stepUp = stepUp;
    }

    @Operation(summary = "The policy per audience: in force, stored overrides and built-in defaults (identity:admin)")
    @GetMapping("/v1/admin/identity/session-policy")
    List<Entry> adminSessionPolicy() {
        return policies.view().stream().map(Entry::of).toList();
    }

    @Operation(summary = "Replace the stored overrides of one audience",
            description = "Validated (idle 1 minute to 180 days, session length 5 minutes to 365 days, idle not longer "
                    + "than the session length, warning 0 to 300 seconds and shorter than idle), needs a reason, "
                    + "written to the access change log with before and after. Values equal to the default are "
                    + "stored as 'use the default'.")
    @PutMapping("/v1/admin/identity/session-policy/{audience}")
    Entry updateAdminSessionPolicy(@PathVariable String audience, @RequestBody Update body,
                                   @Parameter(description = STEP_UP_HELP)
                                   @RequestHeader(value = STEP_UP, required = false) String proof,
                                   @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        SessionAudience a = SessionAudience.parse(audience).orElseThrow(
                () -> new NotFoundException("identity.unknown-audience", "Unknown audience"));
        stepUp.require(proof, jwt == null ? null : jwt.getClaimAsInstant("auth_time"));
        return Entry.of(policies.update(a, body.toDomain(), body.reason()));
    }

    @Operation(summary = "The effective policy of every audience (service clients)",
            description = "For the gateway-bff, which enforces idle and absolute timeouts on its cookie sessions. "
                    + "Needs a SERVICE token with sessions:policy.read.")
    @GetMapping("/v1/internal/session-policy")
    Effective effective() {
        Map<SessionAudience, SessionPolicy> all = policies.effective();
        return new Effective(all.entrySet().stream().map(e -> AudiencePolicy.of(e.getKey(), e.getValue())).toList());
    }
}

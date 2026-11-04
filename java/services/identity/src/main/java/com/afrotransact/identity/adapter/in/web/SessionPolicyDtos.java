package com.afrotransact.identity.adapter.in.web;

import java.util.List;

import com.afrotransact.identity.application.SessionPolicyService.SessionPolicyEntry;
import com.afrotransact.identity.domain.SessionAudience;
import com.afrotransact.identity.domain.SessionPolicy;
import com.afrotransact.identity.domain.SessionPolicyOverrides;
import io.swagger.v3.oas.annotations.media.Schema;

/** Sign-out policy bodies: the staff console's per-audience table and the gateway-bff's read. */
final class SessionPolicyDtos {

    private static final String AUDIENCES = "customer-web, customer-app, seller, staff or driver";

    private SessionPolicyDtos() {
    }

    @Schema(name = "SessionPolicyValues", description = "When an audience is signed out")
    record Values(@Schema(description = "Minutes without real user activity before sign-out (1 minute to 180 days)")
                  int idleTimeoutMinutes,
                  @Schema(description = "Minutes after sign-in before sign-out, however active (5 minutes to 365 days)")
                  int absoluteTimeoutMinutes,
                  @Schema(description = "Seconds before the idle timeout at which clients warn (0 to 300; 0 = no warning)")
                  int warnSeconds) {

        static Values of(SessionPolicy p) {
            return new Values(p.idleTimeoutMinutes(), p.absoluteTimeoutMinutes(), p.warnSeconds());
        }
    }

    @Schema(name = "SessionPolicyOverrides", description = "What is stored for one audience; a null field uses the "
            + "built-in default. Values equal to the default are stored as null.")
    record Overrides(Integer idleTimeoutMinutes, Integer absoluteTimeoutMinutes, Integer warnSeconds) {

        static Overrides of(SessionPolicyOverrides o) {
            return new Overrides(o.idleTimeoutMinutes(), o.absoluteTimeoutMinutes(), o.warnSeconds());
        }

        SessionPolicyOverrides toDomain() {
            return new SessionPolicyOverrides(idleTimeoutMinutes, absoluteTimeoutMinutes, warnSeconds);
        }
    }

    @Schema(name = "SessionPolicyEntry")
    record Entry(@Schema(description = AUDIENCES) String audience,
                 @Schema(description = "What staff see: 'Staff console'") String label, Values effective,
                 Overrides overrides, Values defaults) {

        static Entry of(SessionPolicyEntry e) {
            return new Entry(e.audience().key(), e.audience().label(), Values.of(e.effective()),
                    Overrides.of(e.overrides()), Values.of(e.defaults()));
        }
    }

    @Schema(name = "SessionPolicyUpdate", description = "Replaces the stored overrides of one audience: send null "
            + "fields to go back to the default")
    record Update(Overrides overrides, String reason) {

        SessionPolicyOverrides toDomain() {
            return overrides == null ? SessionPolicyOverrides.none() : overrides.toDomain();
        }
    }

    @Schema(name = "AudienceSessionPolicy", description = "The policy in force for one audience")
    record AudiencePolicy(@Schema(description = AUDIENCES) String audience, int idleTimeoutMinutes,
                          int absoluteTimeoutMinutes, int warnSeconds) {

        static AudiencePolicy of(SessionAudience a, SessionPolicy p) {
            return new AudiencePolicy(a.key(), p.idleTimeoutMinutes(), p.absoluteTimeoutMinutes(), p.warnSeconds());
        }
    }

    @Schema(name = "EffectiveSessionPolicies")
    record Effective(List<AudiencePolicy> audiences) {
    }
}

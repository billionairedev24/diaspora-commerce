package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.TokenInspection;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.DiscoveryReader;
import com.afrotransact.identity.port.JwtInspector;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import org.springframework.stereotype.Service;

@Service
class TokenInspectorServiceImpl implements TokenInspectorService {

    private final JwtInspector inspector;
    private final DiscoveryReader discovery;
    private final String issuer;
    private final PermissionChecker permissions;
    private final AccessChangeLog changeLog;
    private final Clock clock;

    TokenInspectorServiceImpl(JwtInspector inspector, DiscoveryReader discovery, TokenIssuer issuer,
                              PermissionChecker permissions, AccessChangeLog changeLog, Clock clock) {
        this.inspector = inspector;
        this.discovery = discovery;
        this.issuer = issuer.value();
        this.permissions = permissions;
        this.changeLog = changeLog;
        this.clock = clock;
    }

    @Override
    public TokenInspection inspect(String token) {
        Actor actor = permissions.require(IdentityPermissions.IDENTITY_ADMIN);
        String text = token == null ? "" : token.trim();
        if (text.isEmpty() || text.length() > MAX_TOKEN_LENGTH) {
            throw new IdentityRuleViolation("identity.invalid-token-input", "Paste a token of up to "
                    + MAX_TOKEN_LENGTH + " characters", Map.of("token", "Paste a JWT"));
        }
        TokenInspection result = inspector.inspect(text, issuer, Instant.now(clock));
        // The audit trail says who looked at whose token, never the token: subject and key id only.
        Object sub = result.claims().get("sub");
        changeLog.append(actor.subject(), "token.inspected", null, null, "jwt",
                "valid=" + result.valid() + (sub == null ? "" : "; sub=" + sub)
                        + (result.keyId() == null ? "" : "; kid=" + result.keyId()));
        return result;
    }

    @Override
    public Documents documents() {
        permissions.require(IdentityPermissions.IDENTITY_ADMIN);
        return new Documents(discovery.discovery(), discovery.jwks());
    }
}

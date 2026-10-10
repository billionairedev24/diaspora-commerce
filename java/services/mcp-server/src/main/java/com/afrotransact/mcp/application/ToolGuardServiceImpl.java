package com.afrotransact.mcp.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.mcp.domain.AccessDecision;
import com.afrotransact.mcp.domain.ArgumentDigest;
import com.afrotransact.mcp.domain.CallerProfile;
import com.afrotransact.mcp.domain.RateLimiter;
import com.afrotransact.mcp.domain.ToolAccessPolicy;
import com.afrotransact.mcp.domain.ToolDescriptor;
import com.afrotransact.mcp.port.AuditTrail;
import com.afrotransact.mcp.port.McpMetrics;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class ToolGuardServiceImpl implements ToolGuardService {

    /** Dedicated logger so audit lines can be routed to the audit log pipeline even when Kafka is down. */
    private static final Logger AUDIT = LoggerFactory.getLogger("com.afrotransact.mcp.audit");

    private final ToolAccessPolicy policy;
    private final RateLimiter limiter;
    private final AuditTrail audit;
    private final McpMetrics metrics;
    private final Clock clock;

    ToolGuardServiceImpl(ToolAccessPolicy policy, RateLimiter limiter, AuditTrail audit, McpMetrics metrics,
                         Clock clock) {
        this.policy = policy;
        this.limiter = limiter;
        this.audit = audit;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public List<ToolDescriptor> visibleTools(CallerProfile caller) {
        return ToolCatalog.all().stream().filter(t -> policy.decide(caller, t).allowed()).toList();
    }

    @Override
    public Admission admit(CallerProfile caller, String toolName) {
        Optional<ToolDescriptor> tool = ToolCatalog.find(toolName);
        if (tool.isEmpty()) {
            return new Admission(Verdict.UNKNOWN_TOOL, "Unknown tool " + toolName, Duration.ZERO);
        }
        AccessDecision access = policy.decide(caller, tool.get());
        if (!access.allowed()) {
            return new Admission(Verdict.FORBIDDEN, "Not allowed to call " + toolName + ": " + access.reason(),
                    Duration.ZERO);
        }
        RateLimiter.Decision rate = limiter.tryAcquire(caller.bucketKey());
        if (!rate.allowed()) {
            return new Admission(Verdict.RATE_LIMITED, "Too many tool calls; retry in "
                    + Math.max(1, rate.retryAfter().toMillis()) + " ms", rate.retryAfter());
        }
        return new Admission(Verdict.ALLOWED, "", Duration.ZERO);
    }

    @Override
    public void require(CallerProfile caller, String toolName) {
        ToolDescriptor tool = ToolCatalog.find(toolName).orElseThrow(() -> new ForbiddenActionException(
                "mcp.unknown-tool", "Unknown tool " + toolName));
        AccessDecision access = policy.decide(caller, tool);
        if (!access.allowed()) {
            throw new ForbiddenActionException("mcp.tool-forbidden", access.reason());
        }
    }

    @Override
    public void audit(CallerProfile caller, String toolName, String canonicalArguments, String outcome,
                      Duration duration) {
        String toolset = ToolCatalog.find(toolName).map(t -> t.toolset().name()).orElse("");
        AuditTrail.ToolInvoked event = new AuditTrail.ToolInvoked(UUID.randomUUID().toString(), toolName, toolset,
                caller.subject(), caller.clientId(), caller.orgId(), ArgumentDigest.sha256(canonicalArguments),
                outcome, duration.toMillis(), Instant.now(clock));
        AUDIT.info("mcp.tool.invoked tool={} toolset={} subject={} client={} org={} args={} outcome={} ms={}",
                event.tool(), event.toolset(), event.subject(), event.clientId(), event.orgId(), event.argumentsHash(),
                event.outcome(), event.durationMs());
        // unknown names are client input: never use them as metric tags (unbounded cardinality)
        metrics.toolCall(toolset.isEmpty() ? "unknown" : toolName, toolset, outcome, duration);
        audit.record(event);
    }
}

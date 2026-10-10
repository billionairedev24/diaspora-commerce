package com.afrotransact.mcp.domain;

/**
 * Toolset scope first ({@code mcp:shopper}, {@code mcp:seller}, {@code mcp:staff}), then the toolset's rule (seller
 * tools need an organisation, staff tools a staff principal), then the tool's own platform permission.
 */
public class DefaultToolAccessPolicy implements ToolAccessPolicy {

    @Override
    public AccessDecision decide(CallerProfile caller, ToolDescriptor tool) {
        if (!caller.can(tool.toolset().permission())) {
            return AccessDecision.deny("requires scope " + tool.toolset().permission());
        }
        if (tool.toolset() == Toolset.SELLER && (caller.orgId() == null || caller.orgId().isBlank())
                && !caller.can("*")) {
            return AccessDecision.deny("seller tools need a seller or provider organisation");
        }
        if (tool.toolset() == Toolset.STAFF && !caller.staff()) {
            return AccessDecision.deny("staff tools need a staff account");
        }
        if (tool.requiredPermission() != null && !caller.can(tool.requiredPermission())) {
            return AccessDecision.deny("requires permission " + tool.requiredPermission());
        }
        return AccessDecision.ALLOW;
    }
}

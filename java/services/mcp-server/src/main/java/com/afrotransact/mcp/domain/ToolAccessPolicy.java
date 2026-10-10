package com.afrotransact.mcp.domain;

/** Decides which tools a caller may list and call, from the scopes and permissions in their token. */
public interface ToolAccessPolicy {

    AccessDecision decide(CallerProfile caller, ToolDescriptor tool);
}

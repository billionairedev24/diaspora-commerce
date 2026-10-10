package com.afrotransact.mcp.domain;

/**
 * Access rules of one MCP tool.
 *
 * @param name               tool name as listed by {@code tools/list}
 * @param toolset            toolset (and therefore the scope) it belongs to
 * @param requiredPermission additional platform permission (null when the toolset scope is enough)
 * @param readOnly           true when the tool changes nothing
 */
public record ToolDescriptor(String name, Toolset toolset, String requiredPermission, boolean readOnly) {

    public ToolDescriptor {
        if (name == null || name.isBlank() || toolset == null) {
            throw new IllegalArgumentException("name and toolset are required");
        }
    }
}

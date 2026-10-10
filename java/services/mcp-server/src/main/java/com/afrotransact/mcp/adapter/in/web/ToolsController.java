package com.afrotransact.mcp.adapter.in.web;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.afrotransact.mcp.application.ToolCatalog;
import com.afrotransact.mcp.application.ToolGuardService;
import com.afrotransact.mcp.domain.CallerProfile;
import com.afrotransact.mcp.domain.ToolDescriptor;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST listing of the MCP tools for the developer portal (the MCP endpoint itself is JSON-RPC, not REST). */
@Tag(name = "Tools", description = "MCP tool catalogue: names, descriptions, JSON schemas and required scopes")
@RestController
class ToolsController {

    private final ObjectProvider<List<McpStatelessServerFeatures.SyncToolSpecification>> specs;
    private final ToolGuardService guard;
    private final ActorResolver actors;

    ToolsController(ObjectProvider<List<McpStatelessServerFeatures.SyncToolSpecification>> specs,
                    ToolGuardService guard, ActorResolver actors) {
        this.specs = specs;
        this.guard = guard;
        this.actors = actors;
    }

    @Operation(summary = "List MCP tools", description = """
            Every tool of the shopper, seller and staff toolsets with its JSON input schema and the scope / \
            permission needed to call it. With `mine=true` (authenticated) only the tools the caller's token \
            allows — the same filter `tools/list` applies on the MCP endpoint.""")
    @SecurityRequirements
    @GetMapping("/v1/tools")
    List<ToolView> tools(@Parameter(description = "Only tools callable with my token")
                         @RequestParam(defaultValue = "false") boolean mine) {
        Set<String> visible = mine ? guard.visibleTools(caller()).stream().map(ToolDescriptor::name)
                .collect(Collectors.toSet()) : null;
        Map<String, McpStatelessServerFeatures.SyncToolSpecification> byName = specs.stream()
                .flatMap(List::stream)
                .collect(Collectors.toMap(s -> s.tool().name(), s -> s, (a, b) -> a));
        return ToolCatalog.all().stream()
                .filter(t -> visible == null || visible.contains(t.name()))
                .map(t -> view(t, Optional.ofNullable(byName.get(t.name()))))
                .toList();
    }

    private CallerProfile caller() {
        Actor actor = actors.current();
        return new CallerProfile(actor.subject(), "rest", actor.type().name(), actor.orgId().orElse(null),
                actor.permissions());
    }

    private static ToolView view(ToolDescriptor t, Optional<McpStatelessServerFeatures.SyncToolSpecification> spec) {
        return new ToolView(t.name(),
                spec.map(s -> s.tool().title()).orElse(t.name()),
                spec.map(s -> s.tool().description()).orElse(""),
                t.toolset().name(), t.toolset().permission(), t.requiredPermission(), t.readOnly(),
                spec.map(s -> s.tool().inputSchema()).orElse(Map.of()));
    }

    @Schema(description = "One MCP tool")
    record ToolView(@Schema(example = "search_products") String name,
                    @Schema(example = "Search products") String title,
                    String description,
                    @Schema(example = "SHOPPER") String toolset,
                    @Schema(example = "mcp:shopper") String requiredScope,
                    @Schema(example = "orders:update-status") String requiredPermission,
                    boolean readOnly,
                    @Schema(description = "JSON schema of the arguments") Map<String, Object> inputSchema) {
    }
}

package com.afrotransact.mcp.adapter.in.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.afrotransact.mcp.application.McpCaller;
import com.afrotransact.mcp.application.ToolGuardService;
import com.afrotransact.mcp.domain.CallerProfile;
import com.afrotransact.mcp.domain.ToolDescriptor;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decorates the Streamable HTTP (stateless) transport of the Spring AI MCP server with the platform's guard:
 * <ul>
 *   <li>{@code tools/list} returns only the tools the caller's scopes and permissions allow;</li>
 *   <li>{@code tools/call} is refused for tools outside those scopes (JSON-RPC error -32003) and when the caller's
 *       token bucket is empty (-32029 with {@code retryAfterMs});</li>
 *   <li>every call — allowed or not — is audited ({@code mcp.tool.invoked}) with an argument hash and outcome.</li>
 * </ul>
 */
public class GuardedMcpTransport implements McpStatelessServerTransport {

    static final int FORBIDDEN = -32003;
    static final int RATE_LIMITED = -32029;

    private final McpStatelessServerTransport delegate;
    private final ToolGuardService guard;
    private final JsonMapper json;

    public GuardedMcpTransport(McpStatelessServerTransport delegate, ToolGuardService guard, JsonMapper json) {
        this.delegate = delegate;
        this.guard = guard;
        this.json = json;
    }

    @Override
    public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
        delegate.setMcpHandler(new GuardedHandler(mcpHandler));
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public List<String> protocolVersions() {
        return delegate.protocolVersions();
    }

    private final class GuardedHandler implements McpStatelessServerHandler {

        private final McpStatelessServerHandler handler;

        GuardedHandler(McpStatelessServerHandler handler) {
            this.handler = handler;
        }

        @Override
        public Mono<McpSchema.JSONRPCResponse> handleRequest(McpTransportContext context,
                                                            McpSchema.JSONRPCRequest request) {
            return switch (request.method()) {
                case McpSchema.METHOD_TOOLS_LIST -> handler.handleRequest(context, request)
                        .map(response -> filterTools(context, response));
                case McpSchema.METHOD_TOOLS_CALL -> guardCall(context, request);
                default -> handler.handleRequest(context, request);
            };
        }

        @Override
        public Mono<Void> handleNotification(McpTransportContext context, McpSchema.JSONRPCNotification notification) {
            return handler.handleNotification(context, notification);
        }

        private McpSchema.JSONRPCResponse filterTools(McpTransportContext context, McpSchema.JSONRPCResponse response) {
            if (!(response.result() instanceof McpSchema.ListToolsResult tools)) {
                return response;
            }
            CallerProfile caller = McpCallerExtractor.caller(context).profile();
            Set<String> visible = guard.visibleTools(caller).stream().map(ToolDescriptor::name)
                    .collect(Collectors.toSet());
            List<McpSchema.Tool> allowed = tools.tools().stream().filter(t -> visible.contains(t.name())).toList();
            return McpSchema.JSONRPCResponse.result(response.id(),
                    new McpSchema.ListToolsResult(allowed, tools.nextCursor(), tools.meta()));
        }

        private Mono<McpSchema.JSONRPCResponse> guardCall(McpTransportContext context,
                                                         McpSchema.JSONRPCRequest request) {
            McpCaller caller = McpCallerExtractor.caller(context);
            Map<?, ?> params = request.params() instanceof Map<?, ?> m ? m : Map.of();
            String tool = String.valueOf(params.get("name"));
            String arguments = canonical(params.get("arguments"));
            ToolGuardService.Admission admission = guard.admit(caller.profile(), tool);
            if (!admission.allowed()) {
                guard.audit(caller.profile(), tool, arguments, outcome(admission.verdict()), Duration.ZERO);
                return Mono.just(McpSchema.JSONRPCResponse.error(request.id(), error(admission)));
            }
            long start = System.nanoTime();
            return handler.handleRequest(context, request)
                    .doOnNext(response -> guard.audit(caller.profile(), tool, arguments, outcome(response),
                            Duration.ofNanos(System.nanoTime() - start)))
                    .doOnError(e -> guard.audit(caller.profile(), tool, arguments, "error",
                            Duration.ofNanos(System.nanoTime() - start)));
        }

        private McpSchema.JSONRPCResponse.JSONRPCError error(ToolGuardService.Admission admission) {
            return switch (admission.verdict()) {
                case UNKNOWN_TOOL -> new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INVALID_PARAMS,
                        admission.message());
                case RATE_LIMITED -> new McpSchema.JSONRPCResponse.JSONRPCError(RATE_LIMITED, admission.message(),
                        Map.of("retryAfterMs", Math.max(1, admission.retryAfter().toMillis())));
                default -> new McpSchema.JSONRPCResponse.JSONRPCError(FORBIDDEN, admission.message());
            };
        }
    }

    private static String outcome(ToolGuardService.Verdict verdict) {
        return switch (verdict) {
            case UNKNOWN_TOOL -> "unknown_tool";
            case RATE_LIMITED -> "rate_limited";
            case FORBIDDEN -> "denied";
            case ALLOWED -> "ok";
        };
    }

    private static String outcome(McpSchema.JSONRPCResponse response) {
        if (response.error() != null) {
            return "error";
        }
        if (response.result() instanceof McpSchema.CallToolResult r && Boolean.TRUE.equals(r.isError())) {
            return "error";
        }
        return "ok";
    }

    /** Key-sorted JSON of the arguments, so equal arguments always hash the same. */
    private String canonical(Object arguments) {
        return json.writeValueAsString(sorted(arguments == null ? Map.of() : arguments));
    }

    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new TreeMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), sorted(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(v -> out.add(sorted(v)));
            return out;
        }
        return value;
    }
}

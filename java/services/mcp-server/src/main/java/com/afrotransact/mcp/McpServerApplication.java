package com.afrotransact.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** mcp-server: shopper, seller and staff MCP toolsets over the platform's public APIs (Spring AI MCP). */
@SpringBootApplication
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}

package com.afrotransact.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import com.afrotransact.mcp.domain.ToolDescriptor;
import org.junit.jupiter.api.Test;

/**
 * Owner rule: the daily AI spend cap is changed by people in the staff console (ai-gateway, permission ai:cap.manage) and
 * never through MCP; no MCP tool is built until approved. A set_ai_spend_cap PROPOSE tool is only a proposal in
 * docs/engineering/mcp-tool-proposals.md.
 */
class NoSpendCapToolTest {

    @Test
    void noRegisteredToolReadsOrChangesTheSpendCap() {
        assertThat(ToolCatalog.all()).extracting(ToolDescriptor::name).noneMatch(name -> {
            String n = name.toLowerCase(Locale.ROOT);
            return n.contains("spend") || n.contains("cap_") || n.endsWith("_cap") || n.contains("budget");
        });
    }

    @Test
    void noToolIsGatedOnTheCapPermission() {
        assertThat(ToolCatalog.all()).extracting(ToolDescriptor::requiredPermission).doesNotContain("ai:cap.manage");
    }
}

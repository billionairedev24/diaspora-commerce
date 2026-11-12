package com.afrotransact.mcp;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.afrotransact.platform.test.HexagonalArchitectureRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private static final String BASE = "com.afrotransact.mcp";

    @Test
    void followsHexagonalLayering() {
        HexagonalArchitectureRules.verify(classes(), BASE);
    }

    /**
     * The server is public and has no AI budget of its own: a tool that needs a model must call the ai-gateway with the
     * caller's relayed token, so the gateway's USD 2 daily ceiling and per-caller limits apply. Direct model clients
     * would bypass them.
     */
    @Test
    void toolsNeverCallModelProvidersDirectly() {
        noClasses().that().resideInAPackage(BASE + "..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.ai.chat..",
                        "org.springframework.ai.openai..", "org.springframework.ai.embedding..", "com.openai..",
                        "com.afrotransact.ai..")
                .because("AI calls go through the ai-gateway, which enforces the daily spend ceiling")
                .check(classes());
    }

    private static JavaClasses classes() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }
}

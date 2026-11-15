package com.afrotransact.platform.test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/** The Release 1 module-boundary rules must fail on a violation, not only pass on clean code. */
class ModuleBoundaryRulesTest {

    private static final JavaClasses FIXTURES = new ClassFileImporter().importPackages("com.afrotransact.fakea",
            "com.afrotransact.fakeb");

    @Test
    void anotherModulesInternalsAreRefused() {
        assertThatThrownBy(() -> HexagonalArchitectureRules.onlyPublishedApiOfOtherModules("com.afrotransact.fakea")
                .check(FIXTURES)).isInstanceOf(AssertionError.class).hasMessageContaining("ReachesIntoOtherModule");
    }

    @Test
    void anotherModulesApiIsRefusedOutsideModuleAdapters() {
        assertThatThrownBy(() -> HexagonalArchitectureRules.otherModulesApiOnlyInModuleAdapters("com.afrotransact.fakea")
                .check(FIXTURES)).isInstanceOf(AssertionError.class).hasMessageContaining("UsesApiInUseCase")
                .hasMessageNotContaining("GoodAdapter");
    }

    @Test
    void aGlobalControllerAdviceIsRefused() {
        assertThatThrownBy(() -> HexagonalArchitectureRules.controllerAdvicesAreScoped("com.afrotransact.fakea")
                .check(FIXTURES)).isInstanceOf(AssertionError.class).hasMessageContaining("GlobalAdvice");
    }

    @Test
    void theModuleThatOnlyUsesTheApiInAnAdapterPasses() {
        assertThatCode(() -> HexagonalArchitectureRules.onlyPublishedApiOfOtherModules("com.afrotransact.fakeb")
                .check(FIXTURES)).doesNotThrowAnyException();
    }
}

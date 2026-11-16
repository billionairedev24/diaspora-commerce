package com.afrotransact.platform.web.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.afrotransact.module.ModuleApiException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

class ModuleFailuresValidateTest {

    record Body(@NotBlank String name, @Size(max = 3) String note) {
    }

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void aValidRequestPasses() {
        assertThatCode(() -> ModuleFailures.validate(validator, new Body("x", "abc"))).doesNotThrowAnyException();
    }

    @Test
    void aViolationIsTheHttp400WithOneLinePerField() {
        assertThatThrownBy(() -> ModuleFailures.validate(validator, new Body(" ", "abcd")))
                .isInstanceOfSatisfying(ModuleApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.code()).isEqualTo("validation");
                    assertThat(e.details()).hasSize(2).anyMatch(l -> l.startsWith("name: ")).anyMatch(l -> l.startsWith("note: "));
                });
    }
}

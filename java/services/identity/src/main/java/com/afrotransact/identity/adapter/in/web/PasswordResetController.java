package com.afrotransact.identity.adapter.in.web;

import com.afrotransact.identity.adapter.in.web.PasswordResetDtos.ResetCodeRequest;
import com.afrotransact.identity.adapter.in.web.PasswordResetDtos.ResetCodeRequested;
import com.afrotransact.identity.adapter.in.web.PasswordResetDtos.ResetConfirmRequest;
import com.afrotransact.identity.application.PasswordResetCompletion;
import com.afrotransact.identity.application.PasswordResetRequest;
import com.afrotransact.identity.application.PasswordResetService;
import com.afrotransact.identity.application.SignInAttempt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Forgotten password over the API (the customer and driver apps); the hosted pages use the same service. */
@Tag(name = "Password reset", description = "Forgotten password (public, enumeration-safe)")
@SecurityRequirements
@RestController
class PasswordResetController {

    private static final String CLIENT = "api";

    private final PasswordResetService resets;

    PasswordResetController(PasswordResetService resets) {
        this.resets = resets;
    }

    @Operation(summary = "Ask for a password reset code (always 202, whether or not the email has an account)")
    @PostMapping("/v1/password-reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ResetCodeRequested request(@Valid @RequestBody ResetCodeRequest body, HttpServletRequest request) {
        resets.request(new PasswordResetRequest(body.email(),
                request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT), CLIENT,
                SignInAttempt.FIRST_PARTY));
        return new ResetCodeRequested((int) resets.codeTimeToLive().toMinutes());
    }

    @Operation(summary = "Set a new password with the emailed or texted code")
    @PostMapping("/v1/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void confirm(@Valid @RequestBody ResetConfirmRequest body, HttpServletRequest request) {
        resets.complete(new PasswordResetCompletion(body.email(), body.code(), body.newPassword(),
                body.confirmPassword(), request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT), CLIENT,
                SignInAttempt.FIRST_PARTY));
    }
}

package com.afrotransact.identity.adapter.in.web;

import com.afrotransact.identity.adapter.in.web.AccountDtos.CodeSentResponse;
import com.afrotransact.identity.adapter.in.web.AccountDtos.ResendCodeRequest;
import com.afrotransact.identity.adapter.in.web.AccountDtos.SignUpRequest;
import com.afrotransact.identity.adapter.in.web.AccountDtos.SignUpResponse;
import com.afrotransact.identity.adapter.in.web.AccountDtos.VerifyEmailRequest;
import com.afrotransact.identity.application.AccountService;
import com.afrotransact.identity.application.SignUpCommand;
import com.afrotransact.identity.application.SignUpResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Accounts", description = "Unified sign-up and email verification (public)")
@SecurityRequirements
@RestController
class AccountController {

    private final AccountService accounts;

    AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @Operation(summary = "Create an account (customers, sellers and providers use the same form)",
            description = "Adds account.customer, plus the seller/provider applicant role when intent is sell/provide. "
                    + "A 6-digit code is emailed (10-minute expiry). With invitationToken (organisation invite link, "
                    + "same email) the account starts verified, joins the organisation and no code is sent.")
    @PostMapping("/v1/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    SignUpResponse signUp(@Valid @RequestBody SignUpRequest body, HttpServletRequest request) {
        SignUpResult r = accounts.signUp(new SignUpCommand(body.firstName(), body.lastName(), body.email(),
                body.phone(), body.phoneRegion(), body.password(), body.confirmPassword(),
                Boolean.TRUE.equals(body.termsAccepted()), Boolean.TRUE.equals(body.smsConsent()), body.intent(),
                body.invitationToken(), body.referralCode(), null, request.getRemoteAddr()));
        return new SignUpResponse(r.userId(), r.email(), r.verificationRequired(), r.codeExpiresAt());
    }

    @Operation(summary = "Confirm the email address with the 6-digit code")
    @PostMapping("/v1/accounts/verification")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void verify(@Valid @RequestBody VerifyEmailRequest body) {
        accounts.verifyEmail(body.email(), body.code());
    }

    @Operation(summary = "Send a new code (at most 1 per 30 seconds and 5 per hour; 429 with Retry-After otherwise)")
    @PostMapping("/v1/accounts/verification/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    CodeSentResponse resend(@Valid @RequestBody ResendCodeRequest body) {
        return new CodeSentResponse(accounts.resendVerification(body.email()));
    }
}

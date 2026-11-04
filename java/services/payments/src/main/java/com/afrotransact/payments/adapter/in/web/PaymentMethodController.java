package com.afrotransact.payments.adapter.in.web;

import java.util.List;

import com.afrotransact.payments.adapter.in.web.PaymentsDtos.EphemeralKeyRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.EphemeralKeyResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.PaymentMethodResponse;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.SetupIntentRequest;
import com.afrotransact.payments.adapter.in.web.PaymentsDtos.SetupIntentResponse;
import com.afrotransact.payments.application.EphemeralKeyResult;
import com.afrotransact.payments.application.PaymentMethodService;
import com.afrotransact.payments.application.SetupIntentResult;
import com.afrotransact.payments.application.Wallet;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Saved payment methods", description = "The signed-in shopper's wallet (called by gateway-bff with the "
        + "customer's token)")
@RestController
class PaymentMethodController {

    private final PaymentMethodService methods;

    PaymentMethodController(PaymentMethodService methods) {
        this.methods = methods;
    }

    @Operation(summary = "List saved payment methods (display data only, never full numbers)")
    @GetMapping("/v1/customers/me/payment-methods")
    PaymentsDtos.WalletResponse list() {
        Wallet w = methods.list();
        return new PaymentsDtos.WalletResponse(w.stripeCustomerId(),
                w.items().stream().map(PaymentMethodResponse::of).toList());
    }

    @Operation(summary = "Start saving a method: SetupIntent client secret for Stripe Elements / PaymentSheet",
            description = "Bank accounts verify instantly with Financial Connections or by microdeposits (listed as "
                    + "verified=false until then). The Stripe Customer is created on first use.")
    @PostMapping("/v1/customers/me/setup-intents")
    @ResponseStatus(HttpStatus.CREATED)
    SetupIntentResponse setupIntent(@RequestHeader(value = PaymentController.IDEMPOTENCY_KEY, required = false)
                                    String idempotencyKey,
                                    @RequestBody(required = false) SetupIntentRequest body) {
        SetupIntentResult r = methods.createSetupIntent(body == null ? List.of() : body.types(), idempotencyKey);
        return new SetupIntentResponse(r.setupIntentId(), r.clientSecret(), r.status());
    }

    @Operation(summary = "PaymentSheet: ephemeral key for the shopper's Stripe Customer (shopper token)",
            description = "Returns {customerId, ephemeralKeySecret, expiresAt} for the Stripe Customer linked to the "
                    + "signed-in shopper, creating the customer on first use. gateway-bff's POST "
                    + "/v1/account/payment-sheet calls it with the shopper's token. Optional Idempotency-Key.")
    @PostMapping("/v1/customers/me/ephemeral-keys")
    @ResponseStatus(HttpStatus.CREATED)
    EphemeralKeyResponse ephemeralKey(@RequestHeader(value = PaymentController.IDEMPOTENCY_KEY, required = false)
                                      String idempotencyKey,
                                      @RequestBody(required = false) EphemeralKeyRequest body) {
        EphemeralKeyResult r = methods.createEphemeralKey(body == null ? null : body.stripeVersion(), idempotencyKey);
        return new EphemeralKeyResponse(r.customerId(), r.ephemeralKeySecret(), r.expiresAt());
    }

    @Operation(summary = "Make a saved method the default")
    @PostMapping("/v1/customers/me/payment-methods/{id}/default")
    PaymentMethodResponse makeDefault(@PathVariable String id) {
        return PaymentMethodResponse.of(methods.setDefault(id));
    }

    @Operation(summary = "Remove (detach) a saved method",
            description = "The default method cannot be removed: choose another default first "
                    + "(409 payments.default-method-required).")
    @DeleteMapping("/v1/customers/me/payment-methods/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable String id) {
        methods.remove(id);
    }
}

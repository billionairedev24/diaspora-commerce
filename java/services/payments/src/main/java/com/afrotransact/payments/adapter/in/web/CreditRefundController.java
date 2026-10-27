package com.afrotransact.payments.adapter.in.web;

import java.time.Instant;

import com.afrotransact.payments.application.CreditRefundService;
import com.afrotransact.payments.application.CreditRefundService.Choice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Credit", description = "AfroTransact credit: a per-customer append-only ledger (grant, spend, refund, "
        + "adjustment). Credit only buys products and services; there is no cash-out, payout, card refund or "
        + "transfer of credit anywhere in this API (ADR 0006).")
@RestController
class CreditRefundController {

    private final CreditRefundService choices;

    CreditRefundController(CreditRefundService choices) {
        this.choices = choices;
    }

    @Schema(name = "CreditRefundChoice")
    record ChoiceResponse(String orderId,
                          @Schema(description = "From now on the card part of every refund of this order comes back "
                                  + "as AfroTransact credit") boolean cardRefundsAsCredit,
                          @Schema(description = "What the card paid for the order") long cardPartMinor,
                          String currency, String chosenBy, Instant chosenAt) {

        static ChoiceResponse of(Choice c) {
            return new ChoiceResponse(c.orderId(), c.cardRefundsAsCredit(), c.cardPart().minor(),
                    c.cardPart().currency(), c.chosenBy(), c.chosenAt());
        }
    }

    @Schema(name = "CreditRefundChoiceRequest")
    record StaffChoiceRequest(@NotBlank @Size(max = 500) @Schema(description = "At least 15 characters; kept in the "
            + "audit trail") String reason) {
    }

    @Operation(operationId = "chooseCreditRefundsAsShopper", summary = "Take the card part of this order's refunds "
            + "as credit (the shopper's own order)",
            description = "One way: credit never goes back to a card or to cash. Refunds already sent to the card "
                    + "stay there. 409 payments.no-card-portion when the order was paid with credit only (its "
                    + "refunds already come back as credit); 403 payments.not-your-order for another shopper's order.")
    @PostMapping("/v1/customers/me/orders/{orderId}/credit-refunds")
    ChoiceResponse shopper(@PathVariable String orderId) {
        return ChoiceResponse.of(choices.chooseAsShopper(orderId));
    }

    @Operation(operationId = "chooseCreditRefundsAsStaff", summary = "Take the card part of an order's refunds as "
            + "credit, for the customer, with a reason (ledger:adjust)",
            description = "The same one-way choice made by staff; the reason and the staff member are kept.")
    @PostMapping("/v1/orders/{orderId}/credit-refunds")
    ChoiceResponse staff(@PathVariable String orderId, @Valid @RequestBody StaffChoiceRequest body) {
        return ChoiceResponse.of(choices.chooseAsStaff(orderId, body.reason()));
    }
}

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
                          @Schema(description = "Staff chose, with a reason, that the card part of every refund of this "
                                  + "order comes back as AfroTransact credit") boolean cardRefundsAsCredit,
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

    @Operation(operationId = "chooseCreditRefundsAsStaff", summary = "Take the card part of an order's refunds as "
            + "credit, for the customer, with a reason (ledger:adjust)",
            description = "A support resolution may send the card part of an order's refunds back as credit instead of to the "
                    + "card. Staff only, with a reason (at least 15 characters); customers cannot choose. One way: credit never "
                    + "goes back to a card or to cash. The reason and the staff member are kept. 409 payments.no-card-portion "
                    + "when the order was paid with credit only.")
    @PostMapping("/v1/orders/{orderId}/credit-refunds")
    ChoiceResponse staff(@PathVariable String orderId, @Valid @RequestBody StaffChoiceRequest body) {
        return ChoiceResponse.of(choices.chooseAsStaff(orderId, body.reason()));
    }
}

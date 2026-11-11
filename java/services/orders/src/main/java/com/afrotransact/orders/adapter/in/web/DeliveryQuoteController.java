package com.afrotransact.orders.adapter.in.web;

import java.util.List;

import com.afrotransact.orders.application.CheckoutCommand;
import com.afrotransact.orders.application.DeliveryQuoteService;
import com.afrotransact.orders.application.DeliveryQuoteView;
import com.afrotransact.orders.domain.QuoteContact;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Delivery quotes (shopper)", description = "Ask for a delivery quote when the address is outside the "
        + "delivery zones (listing rules D20). Signed-in shoppers only; each shopper sees only their own requests.")
@RestController
class DeliveryQuoteController {

    private final DeliveryQuoteService quotes;

    DeliveryQuoteController(DeliveryQuoteService quotes) {
        this.quotes = quotes;
    }

    @Operation(summary = "Ask for a delivery quote for my cart and a saved address",
            description = "Snapshots the cart lines, packages and the address (city, state, ZIP kept; the street is "
                    + "only hashed) and opens a case for customer care. Idempotent: the same cart and address returns "
                    + "the same request while it is open or inside the duplicate window. "
                    + "Limited per shopper per hour (429 orders.quote-rate-limited).")
    @PostMapping("/v1/delivery-quotes")
    DeliveryQuoteView request(@Parameter(hidden = true) @RequestHeader(value = HttpHeaders.AUTHORIZATION,
            required = false) String authorization, @Valid @RequestBody QuoteRequest body) {
        List<CheckoutCommand.SelectedLine> lines = body.lines() == null ? List.of() : body.lines().stream()
                .map(l -> new CheckoutCommand.SelectedLine(l.variantAtin(), l.quantity())).toList();
        return quotes.request(Bearer.of(authorization), body.addressId(), lines, body.contact(), body.note());
    }

    @Operation(summary = "My delivery quote requests, newest first")
    @GetMapping("/v1/delivery-quotes")
    List<DeliveryQuoteView> mine() {
        return quotes.mine();
    }

    @Operation(summary = "One of my delivery quote requests")
    @GetMapping("/v1/delivery-quotes/{id}")
    DeliveryQuoteView get(@PathVariable String id) {
        return quotes.get(id);
    }

    @Schema(name = "DeliveryQuoteRequest")
    record QuoteRequest(@NotBlank @Size(max = 100) String addressId, @Valid @Size(max = 100) List<Line> lines,
                        QuoteContact contact, @Size(max = 500) String note) {
    }

    @Schema(name = "DeliveryQuoteLine")
    record Line(@NotBlank String variantAtin, int quantity) {
    }
}

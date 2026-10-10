package com.afrotransact.offers.adapter.in.web;

import java.net.URI;
import java.util.UUID;

import com.afrotransact.offers.adapter.in.web.OfferDtos.CreateOfferRequest;
import com.afrotransact.offers.adapter.in.web.OfferDtos.OfferPageResponse;
import com.afrotransact.offers.adapter.in.web.OfferDtos.OfferResponse;
import com.afrotransact.offers.adapter.in.web.OfferDtos.StockUpdateRequest;
import com.afrotransact.offers.adapter.in.web.OfferDtos.StockUpdateResponse;
import com.afrotransact.offers.adapter.in.web.OfferDtos.UpdateOfferRequest;
import com.afrotransact.offers.application.OfferService;
import com.afrotransact.offers.application.OfferView;
import com.afrotransact.offers.domain.OfferStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Offers", description = "Seller offers on existing catalog products (offers:read / offers:write)")
@RestController
@RequestMapping("/v1/offers")
class OfferController {

    private final OfferService offers;

    OfferController(OfferService offers) {
        this.offers = offers;
    }

    @Operation(summary = "Create an offer on an existing live variant (by ATIN or GTIN)",
            description = "The seller organisation is the token's org claim. 404 offers.product-not-found when the "
                    + "ATIN/GTIN has no live product page; 409 offers.offer-exists when you already offer the same "
                    + "ATIN in that condition. Triggers a buy box recomputation.")
    @PostMapping
    ResponseEntity<OfferResponse> create(@Valid @RequestBody CreateOfferRequest body) {
        OfferView created = offers.create(body.toCommand());
        return ResponseEntity.created(URI.create("/v1/offers/" + created.offer().id()))
                .body(OfferResponse.of(created));
    }

    @Operation(summary = "List offers (own organisation; staff may filter by org)")
    @GetMapping
    OfferPageResponse list(@RequestParam(required = false) String sellerOrgId,
                           @RequestParam(required = false) String atin,
                           @RequestParam(required = false) OfferStatus status,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "25") int size) {
        return OfferPageResponse.of(offers.list(sellerOrgId, atin, status, page, size));
    }

    @Operation(summary = "Get an offer")
    @GetMapping("/{id}")
    OfferResponse get(@PathVariable UUID id) {
        return OfferResponse.of(offers.get(id));
    }

    @Operation(summary = "Change price, shipping, quantity, handling time, ship-from, condition or fulfilment",
            description = "Optimistic: 409 offers.concurrent-update when the offer changed meanwhile.")
    @PatchMapping("/{id}")
    OfferResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateOfferRequest body) {
        return OfferResponse.of(offers.update(id, body.toCommand()));
    }

    @Operation(summary = "Pause an active offer (it leaves the buy box and the listing)")
    @PostMapping("/{id}/pause")
    OfferResponse pause(@PathVariable UUID id) {
        return OfferResponse.of(offers.pause(id));
    }

    @Operation(summary = "Resume a paused offer")
    @PostMapping("/{id}/resume")
    OfferResponse resume(@PathVariable UUID id) {
        return OfferResponse.of(offers.resume(id));
    }

    @Operation(summary = "Withdraw an offer for good (frees the SKU and condition slot)")
    @PostMapping("/{id}/withdraw")
    OfferResponse withdraw(@PathVariable UUID id) {
        return OfferResponse.of(offers.withdraw(id));
    }

    @Operation(summary = "Set the stock of a seller-fulfilled SKU (seller center inventory adjustments)",
            description = "Sets the declared quantity of every open offer of the caller's organisation with this "
                    + "seller SKU and recomputes their buy boxes. 404 offers.sku-not-found when none of your offers "
                    + "uses the SKU (other organisations' SKUs are never visible); 409 "
                    + "offers.stock-managed-by-inventory for Fulfilled-by-AfroTransact offers. An absolute "
                    + "quantity is idempotent, so Idempotency-Key is accepted and needs no replay store.")
    @PutMapping("/stock/{sku}")
    StockUpdateResponse setStock(@PathVariable String sku, @Valid @RequestBody StockUpdateRequest body,
                                 @RequestParam(required = false) String sellerOrgId,
                                 @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return StockUpdateResponse.of(offers.setSellerStock(sku, body.quantity(), body.reason(), sellerOrgId));
    }
}

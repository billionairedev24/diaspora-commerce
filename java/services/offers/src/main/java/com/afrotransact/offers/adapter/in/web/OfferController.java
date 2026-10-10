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

    @Operation(summary = "Get an offer", description = "ETag carries the version to send back in If-Match.")
    @GetMapping("/{id}")
    ResponseEntity<OfferResponse> get(@PathVariable UUID id) {
        return tagged(offers.get(id));
    }

    @Operation(summary = "Change price, shipping, quantity, handling time, ship-from, condition or fulfilment",
            description = "Optimistic locking (S259): send If-Match with the ETag (or version in the body) of the "
                    + "offer you edited; a newer stored version answers 409 offers.version-mismatch, and a write "
                    + "racing another one answers 409 offers.concurrent-update. A seller may keep one open offer "
                    + "per variant and condition in each channel (Fulfilled by AfroTransact and seller-fulfilled).")
    @PatchMapping("/{id}")
    ResponseEntity<OfferResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateOfferRequest body,
                                         @RequestHeader(name = Versions.IF_MATCH, required = false) String ifMatch) {
        return tagged(offers.update(id, body.toCommand(), Versions.expected(ifMatch, body.version())));
    }

    @Operation(summary = "Pause an active offer (it leaves the buy box and the listing)",
            description = "If-Match as for PATCH.")
    @PostMapping("/{id}/pause")
    ResponseEntity<OfferResponse> pause(@PathVariable UUID id,
                                        @RequestHeader(name = Versions.IF_MATCH, required = false) String ifMatch) {
        return tagged(offers.pause(id, Versions.expected(ifMatch)));
    }

    @Operation(summary = "Resume a paused offer", description = "If-Match as for PATCH.")
    @PostMapping("/{id}/resume")
    ResponseEntity<OfferResponse> resume(@PathVariable UUID id,
                                         @RequestHeader(name = Versions.IF_MATCH, required = false) String ifMatch) {
        return tagged(offers.resume(id, Versions.expected(ifMatch)));
    }

    @Operation(summary = "Withdraw an offer for good (frees the SKU and condition slot)",
            description = "If-Match as for PATCH.")
    @PostMapping("/{id}/withdraw")
    ResponseEntity<OfferResponse> withdraw(@PathVariable UUID id,
                                           @RequestHeader(name = Versions.IF_MATCH, required = false) String ifMatch) {
        return tagged(offers.withdraw(id, Versions.expected(ifMatch)));
    }

    private static ResponseEntity<OfferResponse> tagged(OfferView view) {
        return Versions.tagged(OfferResponse.of(view), view.offer().version());
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

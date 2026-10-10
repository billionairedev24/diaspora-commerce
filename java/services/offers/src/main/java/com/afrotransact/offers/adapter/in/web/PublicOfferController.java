package com.afrotransact.offers.adapter.in.web;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.afrotransact.offers.adapter.in.web.PublicDtos.BuyBoxListResponse;
import com.afrotransact.offers.adapter.in.web.PublicDtos.BuyBoxResponse;
import com.afrotransact.offers.adapter.in.web.PublicDtos.OfferDetailResponse;
import com.afrotransact.offers.application.BuyBoxView;
import com.afrotransact.offers.application.OfferListingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Public offers", description = "Anonymous storefront reads: buy box, other sellers, offers for the cart")
@SecurityRequirements
@RestController
@RequestMapping("/v1/public")
class PublicOfferController {

    private static final CacheControl SHORT = CacheControl.maxAge(Duration.ofSeconds(15)).cachePublic();

    private final OfferListingService listing;

    PublicOfferController(OfferListingService listing) {
        this.listing = listing;
    }

    @Operation(summary = "Buy box and \"Other sellers on AfroTransact\" for a variant ATIN",
            description = "buyBox = winner with reasons; otherOffers = every other listed offer sorted by landed "
                    + "price (price + shipping) with badges. Maps to the BFF ProductDetail.buyBox / otherOffers.")
    @GetMapping("/atins/{atin}/offers")
    ResponseEntity<BuyBoxResponse> offers(@PathVariable String atin,
                                          @RequestParam(defaultValue = "USD") String currency) {
        return ResponseEntity.ok().cacheControl(SHORT).body(BuyBoxResponse.of(listing.offers(atin, currency)));
    }

    @Operation(summary = "Buy boxes of up to 50 variant ATINs (search cards, variant picker, cart)",
            description = "by=variant (default): one buy box per variant ATIN. by=product: the ATINs are product "
                    + "ATINs (search hits) and each item summarises the product page: the best variant winner, its "
                    + "reasons, offerCount and lowest prices across every variant; otherOffers is empty.")
    @GetMapping("/buy-boxes")
    ResponseEntity<BuyBoxListResponse> buyBoxes(
            @Parameter(description = "Comma-separated ATINs (max 50)") @RequestParam List<String> atins,
            @RequestParam(defaultValue = "USD") String currency,
            @Parameter(description = "variant | product") @RequestParam(defaultValue = "variant") String by) {
        List<BuyBoxView> views = "product".equalsIgnoreCase(by) ? listing.productSummaries(atins, currency)
                : listing.buyBoxes(atins, currency);
        return ResponseEntity.ok().cacheControl(SHORT).body(new BuyBoxListResponse(
                views.stream().map(BuyBoxResponse::of).toList()));
    }

    @Operation(summary = "Buy boxes of every variant of a product page that has offers")
    @GetMapping("/products/{productAtin}/buy-boxes")
    ResponseEntity<BuyBoxListResponse> productBuyBoxes(@PathVariable String productAtin,
                                                       @RequestParam(defaultValue = "USD") String currency) {
        return ResponseEntity.ok().cacheControl(SHORT).body(new BuyBoxListResponse(
                listing.productBuyBoxes(productAtin, currency).stream().map(BuyBoxResponse::of).toList()));
    }

    @Operation(summary = "One offer by id — cart uses it to add a non-buy-box offer",
            description = "buyable is false when the offer cannot be bought now; 404 when withdrawn or unknown.")
    @GetMapping("/offers/{offerId}")
    OfferDetailResponse offer(@PathVariable UUID offerId) {
        return OfferDetailResponse.of(listing.offer(offerId));
    }
}

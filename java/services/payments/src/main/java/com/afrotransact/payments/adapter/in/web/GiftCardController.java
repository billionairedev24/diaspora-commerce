package com.afrotransact.payments.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.payments.application.GiftCardService;
import com.afrotransact.payments.application.GiftCardService.Config;
import com.afrotransact.payments.application.GiftCardService.IssueCommand;
import com.afrotransact.payments.application.GiftCardService.Purchase;
import com.afrotransact.payments.application.GiftCardService.PurchaseCommand;
import com.afrotransact.payments.application.GiftCardService.Redemption;
import com.afrotransact.payments.domain.GiftCard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Gift cards", description = "Buy a gift card with the normal card payment, have it emailed on a chosen "
        + "date and redeem the code once into AfroTransact credit (S321). Codes are stored hashed; wrong codes are "
        + "rate limited. A redeemed card is credit and is never refunded to a card.")
@RestController
class GiftCardController {

    static final String CLIENT_ADDRESS = "X-Client-Address";

    private final GiftCardService giftCards;

    GiftCardController(GiftCardService giftCards) {
        this.giftCards = giftCards;
    }

    // ------------------------------------------------------------------ DTOs

    @Schema(name = "GiftCardConfig", description = "Limits, preset amounts and terms for the purchase page")
    record ConfigResponse(long minMinor, long maxMinor, String currency, List<Long> presetsMinor,
                          @Schema(description = "Terms by language, as set by staff; empty = the page's built-in terms")
                          Map<String, String> terms) {

        static ConfigResponse of(Config c) {
            return new ConfigResponse(c.min().minor(), c.max().minor(), c.min().currency(), c.presetsMinor(),
                    c.terms());
        }
    }

    @Schema(name = "GiftCardPurchaseRequest")
    record PurchaseRequest(
            @NotNull @Schema(description = "Within the limits of Settings, Money (default 10.00 to 500.00)")
            Long amountMinor,
            @NotBlank @Size(max = 254) String recipientEmail,
            @Size(max = 120) String recipientName,
            @Size(max = 120) String senderName,
            @Size(max = 500) String message,
            @Schema(description = "When to email the card; omit to send it now. Up to a year ahead") Instant sendAt,
            @Size(max = 10) @Schema(description = "Language of the email: en, fr, es, ar or yo") String locale) {
    }

    @Schema(name = "GiftCardRedeemRequest")
    record RedeemRequest(@NotBlank @Size(max = 64) String code) {
    }

    @Schema(name = "GiftCardIssueRequest")
    record IssueRequest(
            @NotNull Long amountMinor,
            @NotBlank @Size(max = 254) String recipientEmail,
            @Size(max = 120) String recipientName,
            @Size(max = 500) String message,
            Instant sendAt,
            @Size(max = 10) String locale,
            @NotBlank @Size(max = 500) @Schema(description = "Why staff issue it; at least 15 characters")
            String reason) {
    }

    @Schema(name = "GiftCardReasonRequest")
    record ReasonRequest(@NotBlank @Size(max = 500) @Schema(description = "At least 15 characters") String reason) {
    }

    /** What the buyer sees of a card: never the code, never who redeemed it. */
    @Schema(name = "GiftCardPurchase", description = "A gift card as its buyer sees it")
    record MyGiftCardResponse(UUID id,
                              @Schema(allowableValues = {"PENDING_PAYMENT", "ACTIVE", "REDEEMED", "VOID",
                                      "PAYMENT_FAILED"}) String status,
                              long amountMinor, String currency, String recipientEmail, String recipientName,
                              String senderName, String message, Instant sendAt,
                              @Schema(allowableValues = {"SCHEDULED", "SENT"}) String deliveryStatus,
                              Instant sentAt, boolean redeemed, Instant createdAt,
                              @Schema(description = "Stripe client secret to confirm the payment with Stripe "
                                      + "Elements; only while the payment is pending") String clientSecret) {

        static MyGiftCardResponse of(GiftCard c, String clientSecret) {
            return new MyGiftCardResponse(c.id(), c.status().name(), c.amount().minor(), c.amount().currency(),
                    c.recipientEmail(), c.recipientName(), c.senderName(), c.message(), c.sendAt(),
                    c.delivery().name(), c.sentAt(), c.status() == GiftCard.Status.REDEEMED, c.createdAt(),
                    clientSecret);
        }
    }

    @Schema(name = "GiftCardRedemption")
    record RedemptionResponse(long redeemedMinor, long balanceMinor, String currency) {

        static RedemptionResponse of(Redemption r) {
            return new RedemptionResponse(r.redeemed().minor(), r.balance().minor(), r.balance().currency());
        }
    }

    @Schema(name = "GiftCard", description = "A gift card as staff see it (the code is never shown, only its last "
            + "four characters)")
    record GiftCardResponse(UUID id,
                            @Schema(allowableValues = {"PENDING_PAYMENT", "ACTIVE", "REDEEMED", "VOID",
                                    "PAYMENT_FAILED"}) String status,
                            @Schema(allowableValues = {"PURCHASE", "STAFF"}) String origin, long amountMinor,
                            String currency, String codeLast4, String purchaserId, String senderName,
                            String recipientEmail, String recipientName, String message, String locale,
                            Instant sendAt, @Schema(allowableValues = {"SCHEDULED", "SENT"}) String deliveryStatus,
                            int deliveryCount, Instant sentAt, String issueReason, String issuedBy,
                            String redeemedBy, Instant redeemedAt, String voidReason, String voidedBy,
                            Instant voidedAt, boolean refundedToCard, Instant createdAt) {

        static GiftCardResponse of(GiftCard c) {
            return new GiftCardResponse(c.id(), c.status().name(), c.origin().name(), c.amount().minor(),
                    c.amount().currency(), c.codeLast4(), c.purchaserId(), c.senderName(), c.recipientEmail(),
                    c.recipientName(), c.message(), c.locale(), c.sendAt(), c.delivery().name(), c.deliveryCount(),
                    c.sentAt(), c.issueReason(), c.issuedBy(), c.redeemedBy(), c.redeemedAt(), c.voidReason(),
                    c.voidedBy(), c.voidedAt(), c.refundedToCard(), c.createdAt());
        }
    }

    // ------------------------------------------------------------------ the shopper

    @Operation(summary = "Gift card limits, preset amounts and terms")
    @GetMapping("/v1/gift-cards/config")
    ConfigResponse config() {
        return ConfigResponse.of(giftCards.config());
    }

    @Operation(summary = "Start buying a gift card; returns the Stripe client secret to pay with Elements",
            description = "Idempotent per Idempotency-Key. The card is not active until the payment is captured "
                    + "(POST …/confirm, or the Stripe webhook). Only a card payment buys a gift card: credit never "
                    + "does.")
    @PostMapping("/v1/gift-cards/purchases")
    @ResponseStatus(HttpStatus.CREATED)
    MyGiftCardResponse purchase(@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 160) String idempotencyKey,
                                @Valid @RequestBody PurchaseRequest body) {
        Purchase p = giftCards.startPurchase(new PurchaseCommand(body.amountMinor(), body.recipientEmail(),
                body.recipientName(), body.senderName(), body.message(), body.sendAt(), body.locale()),
                idempotencyKey);
        return MyGiftCardResponse.of(p.card(), p.clientSecret());
    }

    @Operation(summary = "After the payment was confirmed: capture it and issue the card")
    @PostMapping("/v1/gift-cards/purchases/{id}/confirm")
    MyGiftCardResponse confirm(@PathVariable UUID id) {
        Purchase p = giftCards.confirmPurchase(id);
        return MyGiftCardResponse.of(p.card(), null);
    }

    @Operation(summary = "The gift cards the signed-in shopper bought, newest first")
    @GetMapping("/v1/gift-cards/mine")
    List<MyGiftCardResponse> mine() {
        return giftCards.myPurchases().stream().map(c -> MyGiftCardResponse.of(c, null)).toList();
    }

    @Operation(summary = "Redeem a gift card code into the signed-in shopper's credit",
            description = "Once per card. Wrong codes are counted per shopper and per network address (X-Client-Address, "
                    + "set by the gateway); over the limit the answer is 429 with Retry-After.")
    @PostMapping("/v1/gift-cards/redeem")
    RedemptionResponse redeem(@Valid @RequestBody RedeemRequest body, HttpServletRequest request,
                              @RequestHeader(value = CLIENT_ADDRESS, required = false) String clientAddress) {
        String address = clientAddress == null || clientAddress.isBlank() ? request.getRemoteAddr() : clientAddress;
        return RedemptionResponse.of(giftCards.redeem(body.code(), address));
    }

    // ------------------------------------------------------------------ staff

    @Operation(summary = "Look up gift cards (payments:read)",
            description = "q matches a card id, the last four characters of the code, the recipient's email or name, "
                    + "the sender's name or the purchaser's customer id.")
    @GetMapping("/v1/gift-cards")
    List<GiftCardResponse> search(@RequestParam(required = false) String q,
                                  @RequestParam(required = false) GiftCard.Status status,
                                  @RequestParam(defaultValue = "50") int limit) {
        return giftCards.search(q, status, limit).stream().map(GiftCardResponse::of).toList();
    }

    @Operation(summary = "One gift card (payments:read)")
    @GetMapping("/v1/gift-cards/{id}")
    GiftCardResponse get(@PathVariable UUID id) {
        return GiftCardResponse.of(giftCards.get(id));
    }

    @Operation(summary = "Issue a gift card (ledger:adjust)",
            description = "Goodwill or a promotion: a reason is required, the amount follows the limits of Settings, "
                    + "Money, and the card is a liability from the start. It is emailed on sendAt (now by default).")
    @PostMapping("/v1/gift-cards")
    @ResponseStatus(HttpStatus.CREATED)
    GiftCardResponse issue(@Valid @RequestBody IssueRequest body) {
        return GiftCardResponse.of(giftCards.issue(new IssueCommand(body.amountMinor(), body.recipientEmail(),
                body.recipientName(), body.message(), body.sendAt(), body.locale(), body.reason())));
    }

    @Operation(summary = "Void an unredeemed gift card (ledger:adjust)",
            description = "A purchased card is refunded to the buyer's card; one staff issued just ends. A redeemed "
                    + "card is credit and cannot be voided (409 payments.gift-card-redeemed).")
    @PostMapping("/v1/gift-cards/{id}/void")
    GiftCardResponse voidCard(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return GiftCardResponse.of(giftCards.voidCard(id, body.reason()));
    }

    @Operation(summary = "Email the gift card again with a new code (ledger:adjust)",
            description = "The old code stops working.")
    @PostMapping("/v1/gift-cards/{id}/resend")
    GiftCardResponse resend(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return GiftCardResponse.of(giftCards.resend(id, body.reason()));
    }
}

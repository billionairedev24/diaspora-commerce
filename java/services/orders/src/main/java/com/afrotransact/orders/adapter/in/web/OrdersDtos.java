package com.afrotransact.orders.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.afrotransact.orders.application.CheckoutCommand;
import com.afrotransact.orders.application.CheckoutInput;
import com.afrotransact.orders.application.CourierEventCommand;
import com.afrotransact.orders.domain.Attribution;
import com.afrotransact.orders.domain.ContactPreference;
import com.afrotransact.orders.domain.CourierProof;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.HandoverMethod;
import com.afrotransact.orders.domain.HandoverPreference;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request bodies of the orders API. */
final class OrdersDtos {

    private OrdersDtos() {
    }

    @Schema(name = "CheckoutRequest", description = "Place the order for the selected cart lines")
    record CheckoutRequest(
            @Schema(description = "Saved address id from the identity address book") @NotBlank String addressId,
            @Schema(description = "Delivery choice per cart package (<sellerOrgId>:<fulfilment>)")
            List<@Valid DeliverySelection> deliverySelections,
            @Schema(description = "Cart lines to buy; omit to buy every available line") List<@Valid Line> lines,
            @Schema(description = "Saved Stripe payment method (confirmed client-side)") String paymentMethodId,
            @Schema(description = "apple_pay | google_pay | link") String wallet,
            Boolean gift,
            @Schema(description = "Coupon code: validated and redeemed with offers-service, released when the order "
                    + "fails or is cancelled") @Size(max = 20) String couponCode,
            @Schema(description = "S272 marketing attribution (last touch): stored on the order and sent in "
                    + "orders.order.placed") @Valid AttributionDto attribution,
            @Schema(description = "Paid with a new method from Stripe's Payment Element: save it on the shopper's "
                    + "Stripe Customer for future purchases (the PaymentIntent gets setup_future_usage=off_session)")
            Boolean savePaymentMethod,
            @Schema(description = "S320: pay with the shopper's AfroTransact credit first. Credit is spent, up to the "
                    + "order total, in the step that creates the payment (once per order, never more than the "
                    + "balance); the card pays only the rest, and an order that credit pays in full needs no card "
                    + "(the answer has no clientSecret). Credit can only buy products and services") Boolean useCredit,
            @Schema(description = "The total and tax the shopper was shown (gateway-bff checks the signed lock): the "
                    + "checkout is refused with 409 orders.total-changed, before any payment is created, when the order "
                    + "would cost more. Omit for no guard") @Valid TaxGuardDto taxGuard) {

        /** A request without a shown-total guard. */
        CheckoutRequest(String addressId, List<DeliverySelection> deliverySelections, List<Line> lines,
                        String paymentMethodId, String wallet, Boolean gift, String couponCode,
                        AttributionDto attribution, Boolean savePaymentMethod, Boolean useCredit) {
            this(addressId, deliverySelections, lines, paymentMethodId, wallet, gift, couponCode, attribution,
                    savePaymentMethod, useCredit, null);
        }

        CheckoutCommand toCommand(String idempotencyKey, String bearer) {
            List<CheckoutInput.Selection> selections = deliverySelections == null ? List.of()
                    : deliverySelections.stream().map(DeliverySelection::toSelection).toList();
            List<CheckoutCommand.SelectedLine> selected = lines == null ? List.of()
                    : lines.stream().map(l -> new CheckoutCommand.SelectedLine(l.variantAtin(),
                    l.quantity() == null ? 0 : l.quantity())).toList();
            return new CheckoutCommand(idempotencyKey, bearer, addressId, selections, selected, couponCode,
                    attribution == null ? null : Attribution.ofNullable(attribution.src(), attribution.cr(),
                            attribution.po()), Boolean.TRUE.equals(savePaymentMethod),
                    Boolean.TRUE.equals(useCredit), taxGuard == null ? null : taxGuard.toGuard());
        }
    }

    @Schema(name = "TaxGuard", description = "What the shopper was shown at checkout")
    record TaxGuardDto(@Min(0) long expectedTotalMinor, @Min(0) long expectedTaxMinor, boolean inputsMatch) {

        CheckoutInput.TaxGuard toGuard() {
            return new CheckoutInput.TaxGuard(expectedTotalMinor, expectedTaxMinor, inputsMatch);
        }
    }

    @Schema(name = "Attribution", description = "Where the shopper came from: ?src=ig|fb|tt|wa|email|sms|app or the "
            + "on-site placement (hero, ribbon, pdp, page), the creative slot and the pre-order window code")
    record AttributionDto(@Size(max = 40) String src, @Size(max = 40) String cr, @Size(max = 40) String po) {
    }

    @Schema(name = "DeliverySelection")
    record DeliverySelection(
            @Schema(description = "<sellerOrgId>:<fulfilment>, or preorder:<windowId> for a pre-order package")
            @NotBlank String packageId,
            @Schema(description = "carrier | express | afrotransact_delivery | pickup; door | pickup for a pre-order "
                    + "package") String option,
            @Schema(description = "standard | express | local | pickup; derived from option when omitted")
            String serviceLevel,
            @Schema(description = "Pickup point (pickup only): a carrier-gateway pickup location that takes orders. "
                    + "Platform-fulfilled packages (AfroTransact Retail, Fulfilled by AfroTransact) use an AfroTransact "
                    + "counter or locker; a seller's packages (including AfroTransact Delivery from the store) one of "
                    + "that seller's own pickup locations. Anything else is refused with 422 orders.invalid-selection. "
                    + "A pickup package has no shipping charge.") String pickupPointId,
            @Valid Preferences preferences,
            @Schema(description = "An agreed delivery quote (POST /v1/delivery-quotes) to use as this package's "
                    + "shipping charge. It must be the shopper's own, for this exact cart and address, ready and not "
                    + "expired, and is used once. Not for pickup or pre-order packages. 422 orders.quote-changed, "
                    + "orders.quote-expired, orders.quote-used, orders.quote-not-ready or orders.quote-not-found "
                    + "otherwise.") @Size(max = 64) String quoteId) {

        CheckoutInput.Selection toSelection() {
            return new CheckoutInput.Selection(packageId, option, serviceLevel, pickupPointId,
                    preferences == null ? null : preferences.toDomain(), quoteId == null || quoteId.isBlank() ? null
                    : new CheckoutInput.QuoteRef(quoteId.trim(), null, 0));
        }
    }

    @Schema(name = "DeliveryPreferences", description = "Customer delivery preferences shown in the driver app")
    record Preferences(
            @Schema(description = "hand | door | desk (hand needs the 4-digit handover code)") String handover,
            @Size(max = 120) String safePlace,
            @Size(max = 240) String instructions,
            @Schema(description = "text | call | none") String contact) {

        DeliveryPreferences toDomain() {
            return new DeliveryPreferences(handover == null ? null : parse(HandoverPreference.class, handover),
                    safePlace, instructions, contact == null ? null : parse(ContactPreference.class, contact));
        }
    }

    @Schema(name = "CheckoutLine")
    record Line(@NotBlank String variantAtin, Integer quantity, String packageId) {
    }

    @Schema(name = "TransitionRequest", description = "Move a shipment to the next status")
    record TransitionRequest(
            @Schema(description = "confirmed | packed | ready | picked_up | in_transit | out_for_delivery | "
                    + "delivered | delivery_failed | returned | cancelled") @NotBlank String to,
            @Schema(description = "Required for cancelled and delivery_failed; for delivered by staff on a pickup or "
                    + "AfroTransact Delivery order, say why it is confirmed without the code or the driver's proof")
            @Size(max = 500) String reason,
            @Schema(description = "The customer's 4-digit code: required to hand a pickup order over (delivered); "
                    + "staff may give a reason instead") @Pattern(regexp = "\\d{4}") String handoverCode) {
    }

    @Schema(name = "CancelShipmentRequest")
    record CancelRequest(@Size(max = 500) String reason) {
    }

    @Schema(name = "TrackingRequest", description = "Carrier label of a seller-fulfilled carrier shipment")
    record TrackingRequest(@Schema(description = "usps | ups | fedex | dhl") @NotBlank String carrier,
                           @NotBlank @Size(max = 80) String trackingNumber) {
    }

    @Schema(name = "CourierEventRequest", description = "Driver-app event with proof (queued offline, own id)")
    record CourierEventRequest(
            @Schema(description = "Unique id from the driver app; replays are ignored") @NotBlank String eventId,
            @Schema(description = "picked_up | in_transit | out_for_delivery | delivered | delivery_failed | returned")
            @NotBlank String status,
            Instant occurredAt,
            @Valid Proof proof,
            @Size(max = 500) String note,
            @Schema(description = "fulfilment-service relays (delivery:relay): the driver the event is from")
            @Size(max = 120) String driverId) {

        CourierEventCommand toCommand() {
            return new CourierEventCommand(eventId, status, occurredAt, proof == null ? null : proof.toDomain(), note,
                    driverId);
        }
    }

    @Schema(name = "CourierProof")
    record Proof(
            @Schema(description = "Media asset of the proof photo") String photoAssetId,
            @Schema(description = "customer | someone_else | safe_place") String handoverMethod,
            @Schema(description = "Customer's 4-digit code (handed to the customer)") String handoverCode,
            String recipientName, String relationship, String signatureAssetId, String safePlace,
            String failureReason, Double latitude, Double longitude) {

        CourierProof toDomain() {
            return new CourierProof(photoAssetId, handoverMethod == null ? null
                    : parse(HandoverMethod.class, handoverMethod), handoverCode, recipientName, relationship,
                    signatureAssetId, safePlace, failureReason, latitude, longitude);
        }
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new OrdersRuleViolation("orders.invalid-value", "Unknown " + type.getSimpleName() + " " + value);
        }
    }
}

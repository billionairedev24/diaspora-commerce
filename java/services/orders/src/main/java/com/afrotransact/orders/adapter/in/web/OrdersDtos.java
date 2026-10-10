package com.afrotransact.orders.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.afrotransact.orders.application.CheckoutCommand;
import com.afrotransact.orders.application.CheckoutInput;
import com.afrotransact.orders.application.CourierEventCommand;
import com.afrotransact.orders.domain.ContactPreference;
import com.afrotransact.orders.domain.CourierProof;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.HandoverMethod;
import com.afrotransact.orders.domain.HandoverPreference;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
            Boolean gift) {

        CheckoutCommand toCommand(String idempotencyKey, String bearer) {
            List<CheckoutInput.Selection> selections = deliverySelections == null ? List.of()
                    : deliverySelections.stream().map(DeliverySelection::toSelection).toList();
            List<CheckoutCommand.SelectedLine> selected = lines == null ? List.of()
                    : lines.stream().map(l -> new CheckoutCommand.SelectedLine(l.variantAtin(),
                    l.quantity() == null ? 0 : l.quantity())).toList();
            return new CheckoutCommand(idempotencyKey, bearer, addressId, selections, selected);
        }
    }

    @Schema(name = "DeliverySelection")
    record DeliverySelection(
            @NotBlank String packageId,
            @Schema(description = "carrier | express | afrotransact_delivery | pickup") String option,
            @Schema(description = "standard | express | local | pickup; derived from option when omitted")
            String serviceLevel,
            @Schema(description = "Pickup point (pickup only)") String pickupPointId,
            @Valid Preferences preferences) {

        CheckoutInput.Selection toSelection() {
            return new CheckoutInput.Selection(packageId, option, serviceLevel, pickupPointId,
                    preferences == null ? null : preferences.toDomain());
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
            @Schema(description = "Required for cancelled and delivery_failed") @Size(max = 500) String reason) {
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
            @Size(max = 500) String note) {

        CourierEventCommand toCommand() {
            return new CourierEventCommand(eventId, status, occurredAt, proof == null ? null : proof.toDomain(), note);
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
